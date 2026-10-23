package com.hospital.rag.store;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.model.AccessControl;
import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.ScoredChunk;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 基于 PostgreSQL + pgvector 的向量存储。
 *
 * <p>使用余弦距离算子 {@code <=>} 做近邻检索，相似度 = 1 - 距离。
 * 仅在 {@code rag.vector.backend=pgvector} 时生效。</p>
 *
 * <p><b>权限过滤</b>：与 BM25 侧保持一致，ACL 字段随片段一并落库，
 * 但过滤动作仍由 {@link com.hospital.rag.retrieve.VectorRetriever} 在 Java 侧完成。
 * 数据量大时可把权限条件下推到 SQL 的 {@code WHERE} 中。</p>
 */
@Component
@ConditionalOnProperty(name = "rag.vector.backend", havingValue = "pgvector")
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    /** 表名合法性校验，防止 SQL 注入（表名无法参数化）。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final JdbcTemplate jdbcTemplate;
    private final String table;
    private final int dimension;
    private final String indexType;

    public PgVectorStore(JdbcTemplate jdbcTemplate, RagProperties properties) {
        RagProperties.Vector config = properties.getVector();
        this.jdbcTemplate = jdbcTemplate;
        this.table = config.getTable();
        this.dimension = config.getDimension();
        this.indexType = config.getIndexType();
        if (!SAFE_IDENTIFIER.matcher(this.table).matches()) {
            throw new IllegalArgumentException("非法的 pgvector 表名: " + this.table);
        }
    }

    /** 启动时确保扩展、表与索引存在（数据库不可用时不阻断应用启动）。 */
    @PostConstruct
    void initialize() {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbcTemplate.execute(createTableSql());
            createIndexIfNeeded();
            log.info("pgvector 已就绪: table={}, dimension={}, index={}", table, dimension, indexType);
        } catch (Exception e) {
            log.error("初始化 pgvector 失败（请确认 PostgreSQL 可用且已安装 vector 扩展）: {}", e.getMessage());
        }
    }

    private String createTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "chunk_id          text PRIMARY KEY,"
                + "document_id       text,"
                + "document_title    text,"
                + "content           text,"
                + "chunk_index       integer,"
                + "departments       text[],"
                + "roles             text[],"
                + "sensitivity_level integer,"
                + "public_access     boolean,"
                + "embedding         vector(" + dimension + ")"
                + ")";
    }

    private void createIndexIfNeeded() {
        if (indexType == null || "none".equalsIgnoreCase(indexType)) {
            return;
        }
        String indexName = table + "_embedding_idx";
        String sql;
        if ("ivfflat".equalsIgnoreCase(indexType)) {
            sql = "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table
                    + " USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)";
        } else {
            sql = "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table
                    + " USING hnsw (embedding vector_cosine_ops)";
        }
        jdbcTemplate.execute(sql);
    }

    @Override
    public void add(Chunk chunk, float[] embedding) {
        addAll(Collections.singletonList(chunk), Collections.singletonList(embedding));
    }

    @Override
    public void addAll(List<Chunk> chunks, List<float[]> embeddings) {
        if (chunks == null || embeddings == null) {
            return;
        }
        int n = Math.min(chunks.size(), embeddings.size());
        if (n == 0) {
            return;
        }
        List<Chunk> batchChunks = new ArrayList<>(n);
        List<float[]> batchVectors = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batchChunks.add(chunks.get(i));
            batchVectors.add(embeddings.get(i));
        }
        upsert(batchChunks, batchVectors);
    }

    private void upsert(List<Chunk> chunks, List<float[]> embeddings) {
        String sql = "INSERT INTO " + table + " ("
                + "chunk_id, document_id, document_title, content, chunk_index,"
                + " departments, roles, sensitivity_level, public_access, embedding"
                + ") VALUES (?,?,?,?,?,?,?,?,?, CAST(? AS vector))"
                + " ON CONFLICT (chunk_id) DO UPDATE SET"
                + " document_id = EXCLUDED.document_id,"
                + " document_title = EXCLUDED.document_title,"
                + " content = EXCLUDED.content,"
                + " chunk_index = EXCLUDED.chunk_index,"
                + " departments = EXCLUDED.departments,"
                + " roles = EXCLUDED.roles,"
                + " sensitivity_level = EXCLUDED.sensitivity_level,"
                + " public_access = EXCLUDED.public_access,"
                + " embedding = EXCLUDED.embedding";

        try {
            jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    for (int i = 0; i < chunks.size(); i++) {
                        Chunk chunk = chunks.get(i);
                        AccessControl acl = chunk.getAccessControl();
                        ps.setString(1, chunk.getId());
                        ps.setString(2, chunk.getDocumentId());
                        ps.setString(3, chunk.getDocumentTitle());
                        ps.setString(4, chunk.getContent());
                        ps.setInt(5, chunk.getIndex());
                        ps.setArray(6, toTextArray(connection, acl == null ? null : acl.getDepartments()));
                        ps.setArray(7, toTextArray(connection, acl == null ? null : acl.getRoles()));
                        ps.setInt(8, acl == null ? 0 : acl.getSensitivityLevel());
                        ps.setBoolean(9, acl != null && acl.isPublicAccess());
                        ps.setString(10, toVectorLiteral(embeddings.get(i)));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            });
        } catch (Exception e) {
            log.error("pgvector 写入失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public List<ScoredChunk> search(float[] queryVector, int topK) {
        List<ScoredChunk> results = new ArrayList<>();
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return results;
        }
        String sql = "SELECT chunk_id, document_id, document_title, content, chunk_index,"
                + " departments, roles, sensitivity_level, public_access,"
                + " (embedding <=> CAST(? AS vector)) AS distance"
                + " FROM " + table
                + " ORDER BY distance"
                + " LIMIT ?";
        try {
            results = jdbcTemplate.query(sql, (rs, rowNum) -> {
                double distance = rs.getDouble("distance");
                double similarity = 1.0 - distance;
                return ScoredChunk.builder()
                        .chunk(toChunk(rs))
                        .score(similarity)
                        .vectorScore(similarity)
                        .source("vector-pg")
                        .build();
            }, toVectorLiteral(queryVector), topK);
        } catch (Exception e) {
            log.error("pgvector 检索失败: {}", e.getMessage(), e);
        }
        return results;
    }

    @Override
    public int size() {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("pgvector count 失败: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public void clear() {
        try {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
            log.info("已清空 pgvector 表: {}", table);
        } catch (Exception e) {
            log.error("清空 pgvector 表失败: {}", e.getMessage(), e);
        }
    }

    // ---------- 辅助方法 ----------

    private Array toTextArray(java.sql.Connection connection, Collection<String> values) throws SQLException {
        String[] array = values == null ? new String[0] : values.toArray(new String[0]);
        return connection.createArrayOf("text", array);
    }

    /** 将 float[] 序列化为 pgvector 字面量，如 {@code [0.1,0.2,0.3]}。 */
    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private Chunk toChunk(ResultSet rs) throws SQLException {
        AccessControl acl = AccessControl.builder()
                .departments(toSet(rs.getArray("departments")))
                .roles(toSet(rs.getArray("roles")))
                .sensitivityLevel(rs.getInt("sensitivity_level"))
                .publicAccess(rs.getBoolean("public_access"))
                .build();
        return Chunk.builder()
                .id(rs.getString("chunk_id"))
                .documentId(rs.getString("document_id"))
                .documentTitle(rs.getString("document_title"))
                .content(rs.getString("content"))
                .index(rs.getInt("chunk_index"))
                .accessControl(acl)
                .build();
    }

    private java.util.Set<String> toSet(Array array) throws SQLException {
        if (array == null) {
            return new HashSet<>();
        }
        Object raw = array.getArray();
        java.util.Set<String> result = new HashSet<>();
        if (raw instanceof String[]) {
            Collections.addAll(result, (String[]) raw);
        }
        return result;
    }
}

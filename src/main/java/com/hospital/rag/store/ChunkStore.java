package com.hospital.rag.store;

import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.UserContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 片段仓库（内存实现）。
 *
 * <p>负责保存全部切分后的片段，并提供基于用户上下文的权限过滤视图。
 * 生产环境可替换为关系库 / ES 实现。</p>
 */
@Component
public class ChunkStore {

    private final Map<String, Chunk> chunks = new ConcurrentHashMap<>();

    /** documentId -> (片段下标 -> 片段)，供邻接扩展按文档顺序取相邻片段。 */
    private final Map<String, Map<Integer, Chunk>> byDocument = new ConcurrentHashMap<>();

    /** 保存单个片段。 */
    public void save(Chunk chunk) {
        if (chunk != null && chunk.getId() != null) {
            chunks.put(chunk.getId(), chunk);
            String documentId = chunk.getDocumentId();
            if (documentId != null) {
                byDocument.computeIfAbsent(documentId, k -> new ConcurrentHashMap<>())
                        .put(chunk.getIndex(), chunk);
            }
        }
    }

    /** 批量保存片段。 */
    public void saveAll(Collection<Chunk> toSave) {
        if (toSave != null) {
            toSave.forEach(this::save);
        }
    }

    /** 按 id 查询片段。 */
    public Optional<Chunk> findById(String id) {
        return Optional.ofNullable(chunks.get(id));
    }

    /** 返回全部片段。 */
    public List<Chunk> findAll() {
        return new ArrayList<>(chunks.values());
    }

    /**
     * 取某文档下标区间内的片段，按下标升序返回。
     *
     * <p>用于检索后的邻接扩展：切片按固定长度硬切，表格 / 参数列表天然跨片段，
     * 命中片段时把它前后的片段一并取回，答案才不会缺一段。区间越界自动裁剪。</p>
     *
     * @param documentId 文档 id，为空时返回空列表
     * @param fromIndex  起始下标（含）
     * @param toIndex    结束下标（含）
     */
    public List<Chunk> findByDocumentBetween(String documentId, int fromIndex, int toIndex) {
        if (documentId == null) {
            return new ArrayList<>();
        }
        Map<Integer, Chunk> doc = byDocument.get(documentId);
        if (doc == null || doc.isEmpty()) {
            return new ArrayList<>();
        }
        List<Chunk> result = new ArrayList<>();
        for (int i = Math.max(0, fromIndex); i <= toIndex; i++) {
            Chunk chunk = doc.get(i);
            if (chunk != null) {
                result.add(chunk);
            }
        }
        return result;
    }

    /** 返回某用户有权访问的全部片段。 */
    public List<Chunk> findAccessible(UserContext user) {
        List<Chunk> result = new ArrayList<>();
        for (Chunk chunk : chunks.values()) {
            if (chunk.isAccessible(user)) {
                result.add(chunk);
            }
        }
        return result;
    }

    /** 已存储片段总数。 */
    public int size() {
        return chunks.size();
    }

    /** 清空仓库。 */
    public void clear() {
        chunks.clear();
        byDocument.clear();
    }
}

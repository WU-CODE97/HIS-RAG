package com.hospital.rag.ingest;

import com.hospital.rag.embedding.EmbeddingModel;
import com.hospital.rag.model.AccessControl;
import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.Document;
import com.hospital.rag.retrieve.Bm25Index;
import com.hospital.rag.retrieve.HybridSearchIndex;
import com.hospital.rag.store.ChunkStore;
import com.hospital.rag.store.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档入库服务：解析 → 切分 → 向量化 → 写入检索后端。
 *
 * <p>检索后端有两种形态：</p>
 * <ul>
 *   <li>存在 {@link HybridSearchIndex}（即 {@code rag.hybrid.backend=elasticsearch}）时，
 *       片段与向量一次性写入 ES 混合索引，不再写本地向量库与 BM25 索引；</li>
 *   <li>否则按 {@code rag.vector.backend} / {@code rag.bm25.backend} 分别写入。</li>
 * </ul>
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final TextSplitter textSplitter;
    private final EmbeddingModel embeddingModel;
    private final ChunkStore chunkStore;
    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final ObjectProvider<HybridSearchIndex> hybridSearchIndex;
    private final List<DocumentParser> parsers;

    public DocumentIngestionService(TextSplitter textSplitter,
                                    EmbeddingModel embeddingModel,
                                    ChunkStore chunkStore,
                                    VectorStore vectorStore,
                                    Bm25Index bm25Index,
                                    ObjectProvider<HybridSearchIndex> hybridSearchIndex,
                                    List<DocumentParser> parsers) {
        this.textSplitter = textSplitter;
        this.embeddingModel = embeddingModel;
        this.chunkStore = chunkStore;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.hybridSearchIndex = hybridSearchIndex;
        this.parsers = parsers;
    }

    /**
     * 从文件字节流入库（自动识别 PDF / 纯文本）。
     *
     * @param documentId    文档 id
     * @param title         标题
     * @param fileName      原始文件名（用于选择解析器）
     * @param content       文件字节
     * @param accessControl 访问控制策略
     * @return 生成的片段数量；未提取到文本时返回 0
     */
    public int ingestFile(String documentId, String title, String fileName,
                          byte[] content, AccessControl accessControl) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文件内容为空: " + fileName);
        }
        DocumentParser parser = resolveParser(fileName);

        String text;
        try {
            text = parser.parse(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new IllegalStateException("解析文件失败: " + fileName + " (" + e.getMessage() + ")", e);
        }

        if (text == null || text.isBlank()) {
            log.warn("文件 {} 未提取到任何文本（扫描件需 OCR），跳过入库", fileName);
            return 0;
        }
        log.info("文件 {} 解析完成，解析器={}，共 {} 字符", fileName, parser.name(), text.length());

        Document document = Document.builder()
                .id(documentId)
                .title(title)
                .content(text)
                .source(fileName)
                .accessControl(accessControl)
                .build();
        return ingest(document);
    }

    /**
     * 入库已解析好的文档。
     *
     * @param document 文档
     * @return 生成的片段数量
     */
    public int ingest(Document document) {
        if (document == null || document.getId() == null) {
            throw new IllegalArgumentException("document 及其 id 不能为空");
        }
        List<String> pieces = textSplitter.split(document.getContent());
        if (pieces.isEmpty()) {
            log.warn("文档 {} 内容为空，跳过入库", document.getId());
            return 0;
        }

        List<Chunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            chunks.add(Chunk.builder()
                    .id(document.getId() + "#" + i)
                    .documentId(document.getId())
                    .documentTitle(document.getTitle())
                    .content(pieces.get(i))
                    .index(i)
                    .metadata(document.getMetadata())
                    .accessControl(document.getAccessControl())
                    .build());
        }

        // 1. 片段库
        chunkStore.saveAll(chunks);

        // 2. 向量化
        List<float[]> vectors = embeddingModel.embedBatch(pieces);

        // 3. 写入检索后端
        HybridSearchIndex hybrid = hybridSearchIndex.getIfAvailable();
        if (hybrid != null) {
            // 文本与向量同存一个 ES 索引，检索时由 ES 一次查询完成融合
            hybrid.indexAll(chunks, vectors);
            log.info("文档 {} 入库完成，共 {} 个片段，后端={}", document.getId(), chunks.size(), hybrid.name());
        } else {
            vectorStore.addAll(chunks, vectors);
            bm25Index.indexAll(chunks);
            log.info("文档 {} 入库完成，共 {} 个片段", document.getId(), chunks.size());
        }
        return chunks.size();
    }

    /**
     * 批量入库。
     *
     * @param documents 文档列表
     * @return 累计片段数量
     */
    public int ingestAll(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Document document : documents) {
            total += ingest(document);
        }
        return total;
    }

    /** 按文件名选择第一个匹配的解析器。 */
    private DocumentParser resolveParser(String fileName) {
        for (DocumentParser parser : parsers) {
            if (parser.supports(fileName)) {
                return parser;
            }
        }
        throw new IllegalArgumentException("不支持的文件类型: " + fileName);
    }
}

package com.hospital.rag.ingest;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.embedding.EmbeddingModel;
import com.hospital.rag.model.AccessControl;
import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.Document;
import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.retrieve.HybridSearchIndex;
import com.hospital.rag.retrieve.InMemoryBm25Index;
import com.hospital.rag.store.ChunkStore;
import com.hospital.rag.store.InMemoryVectorStore;
import com.hospital.rag.util.Tokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentIngestionService} 的写入路由测试。
 *
 * <p>核心约束：存在混合检索索引时，片段与向量只写入该索引，
 * 不再重复写本地向量库与 BM25 索引，避免内存翻倍与数据不一致。</p>
 */
class DocumentIngestionServiceTest {

    private static final EmbeddingModel EMBEDDING_MODEL = new EmbeddingModel() {
        @Override
        public float[] embed(String text) {
            return new float[]{0.1f, 0.2f, 0.3f};
        }
    };

    private final RagProperties properties = new RagProperties();
    private final TextSplitter textSplitter = new TextSplitter(properties);

    @Test
    @DisplayName("存在混合索引时只写混合索引，本地向量库与 BM25 保持为空")
    void writesOnlyToHybridIndexWhenPresent() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        InMemoryBm25Index bm25Index = new InMemoryBm25Index(new Tokenizer());
        RecordingHybridIndex hybridIndex = new RecordingHybridIndex();
        ChunkStore chunkStore = new ChunkStore();

        DocumentIngestionService service = newService(chunkStore, vectorStore, bm25Index, hybridIndex);
        int chunks = service.ingest(document());

        assertThat(chunks).isGreaterThan(0);
        assertThat(hybridIndex.indexed).hasSize(chunks);
        assertThat(hybridIndex.indexedEmbeddings).hasSize(chunks);
        assertThat(vectorStore.size()).isZero();
        assertThat(bm25Index.size()).isZero();
        // 片段库始终写入，供统计与调试使用
        assertThat(chunkStore.size()).isEqualTo(chunks);
    }

    @Test
    @DisplayName("无混合索引时按原有方式写入向量库与 BM25 索引")
    void writesToLocalIndexesWhenHybridAbsent() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        InMemoryBm25Index bm25Index = new InMemoryBm25Index(new Tokenizer());
        ChunkStore chunkStore = new ChunkStore();

        DocumentIngestionService service = newService(chunkStore, vectorStore, bm25Index, null);
        int chunks = service.ingest(document());

        assertThat(chunks).isGreaterThan(0);
        assertThat(vectorStore.size()).isEqualTo(chunks);
        assertThat(bm25Index.size()).isEqualTo(chunks);
        assertThat(chunkStore.size()).isEqualTo(chunks);
    }

    private DocumentIngestionService newService(ChunkStore chunkStore,
                                                InMemoryVectorStore vectorStore,
                                                InMemoryBm25Index bm25Index,
                                                HybridSearchIndex hybridIndex) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        if (hybridIndex != null) {
            beanFactory.registerSingleton("hybridSearchIndex", hybridIndex);
        }
        ObjectProvider<HybridSearchIndex> provider = beanFactory.getBeanProvider(HybridSearchIndex.class);

        return new DocumentIngestionService(textSplitter, EMBEDDING_MODEL, chunkStore,
                vectorStore, bm25Index, provider, List.of(new PlainTextDocumentParser()));
    }

    private static Document document() {
        return Document.builder()
                .id("doc-1")
                .title("高血压诊疗指南")
                .content("高血压患者应低盐饮食，每日食盐摄入量不超过五克。"
                        + "常用降压药物包括 ACEI、ARB、CCB 与利尿剂。"
                        + "用药期间需监测血压与肾功能，出现干咳应考虑 ACEI 相关不良反应。")
                .source("guide.txt")
                .accessControl(AccessControl.publicResource())
                .build();
    }

    /** 记录写入内容的混合索引替身。 */
    private static class RecordingHybridIndex implements HybridSearchIndex {

        private final List<Chunk> indexed = new ArrayList<>();
        private final List<float[]> indexedEmbeddings = new ArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void indexAll(Collection<Chunk> chunks, List<float[]> embeddings) {
            indexed.addAll(chunks);
            indexedEmbeddings.addAll(embeddings);
        }

        @Override
        public List<ScoredChunk> search(String queryText, float[] queryVector, int topK) {
            return List.of();
        }

        @Override
        public int size() {
            return indexed.size();
        }

        @Override
        public void clear() {
            indexed.clear();
            indexedEmbeddings.clear();
        }
    }
}

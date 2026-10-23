package com.hospital.rag.retrieve;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.embedding.EmbeddingModel;
import com.hospital.rag.model.AccessControl;
import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.model.UserContext;
import com.hospital.rag.store.InMemoryVectorStore;
import com.hospital.rag.util.Tokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HybridRetriever} 的路由与权限过滤测试。
 *
 * <p>不依赖真实 ES：用 {@link FakeHybridIndex} 模拟混合检索后端，
 * 验证「有 HybridSearchIndex 时走 ES 单次查询」与「无该 Bean 时回退本地融合」两条路径。</p>
 */
class HybridRetrieverTest {

    private static final EmbeddingModel EMBEDDING_MODEL = new EmbeddingModel() {
        @Override
        public float[] embed(String text) {
            return new float[]{1f, 0f, 0f};
        }
    };

    private final RagProperties properties = new RagProperties();

    @Test
    @DisplayName("存在混合索引 Bean 时优先走 ES 路径，且不再访问本地向量库与 BM25")
    void esPathTakesPrecedenceOverLocalIndexes() {
        // 本地索引里放一条「应该被忽略」的数据
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        InMemoryBm25Index bm25Index = new InMemoryBm25Index(new Tokenizer());
        Chunk local = chunk("local-1", "高血压用药", AccessControl.publicResource());
        vectorStore.add(local, new float[]{1f, 0f, 0f});
        bm25Index.index(local);

        FakeHybridIndex esIndex = new FakeHybridIndex(List.of(
                ScoredChunk.builder()
                        .chunk(chunk("es-1", "高血压诊疗指南", AccessControl.publicResource()))
                        .score(0.9)
                        .source("hybrid-es-rrf")
                        .build()));

        HybridRetriever retriever = newRetriever(vectorStore, bm25Index, esIndex);
        List<ScoredChunk> results = retriever.retrieve("高血压用药", UserContext.guest(), 2);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunk().getId()).isEqualTo("es-1");
        assertThat(results.get(0).getRank()).isEqualTo(1);
        assertThat(esIndex.searchCalls).isEqualTo(1);
        // 权限过滤前会扩大候选窗口：topK=2 -> max(2*4, 2+10) = 12
        assertThat(esIndex.lastTopK).isEqualTo(12);
    }

    @Test
    @DisplayName("ES 结果按用户权限过滤，越权片段被丢弃且不占名额")
    void esResultsAreFilteredByAcl() {
        FakeHybridIndex esIndex = new FakeHybridIndex(List.of(
                ScoredChunk.builder()
                        .chunk(chunk("secret-1", "骨科手术记录", acl(Set.of("骨科"), Set.of("DOCTOR"), 1)))
                        .score(0.95)
                        .source("hybrid-es-rrf")
                        .build(),
                ScoredChunk.builder()
                        .chunk(chunk("ok-1", "心内科用药规范", acl(Set.of("心内科"), Set.of("DOCTOR"), 1)))
                        .score(0.80)
                        .source("hybrid-es-rrf")
                        .build()));

        HybridRetriever retriever = newRetriever(new InMemoryVectorStore(),
                new InMemoryBm25Index(new Tokenizer()), esIndex);

        UserContext cardiologist = UserContext.builder()
                .userId("D001")
                .departments(new HashSet<>(Set.of("心内科")))
                .roles(new HashSet<>(Set.of("DOCTOR")))
                .clearanceLevel(1)
                .build();

        List<ScoredChunk> results = retriever.retrieve("用药", cardiologist, 5);

        assertThat(results).extracting(sc -> sc.getChunk().getId()).containsExactly("ok-1");
        assertThat(results.get(0).getRank()).isEqualTo(1);
    }

    @Test
    @DisplayName("无混合索引 Bean 时回退到本地两路召回 + RRF 融合")
    void fallsBackToLocalFusion() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        InMemoryBm25Index bm25Index = new InMemoryBm25Index(new Tokenizer());
        Chunk chunk = chunk("local-1", "高血压用药指南", AccessControl.publicResource());
        vectorStore.add(chunk, new float[]{1f, 0f, 0f});
        bm25Index.index(chunk);

        HybridRetriever retriever = newRetriever(vectorStore, bm25Index, null);
        List<ScoredChunk> results = retriever.retrieve("高血压用药指南", UserContext.guest(), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunk().getId()).isEqualTo("local-1");
        assertThat(results.get(0).getSource()).isEqualTo("hybrid");
    }

    @Test
    @DisplayName("空查询直接返回空结果，不触碰任何后端")
    void blankQueryReturnsEmpty() {
        FakeHybridIndex esIndex = new FakeHybridIndex(List.of());
        HybridRetriever retriever = newRetriever(new InMemoryVectorStore(),
                new InMemoryBm25Index(new Tokenizer()), esIndex);

        assertThat(retriever.retrieve("  ", UserContext.guest(), 5)).isEmpty();
        assertThat(retriever.retrieve(null, UserContext.guest(), 5)).isEmpty();
        assertThat(esIndex.searchCalls).isZero();
    }

    // ---------- 辅助方法 ----------

    private HybridRetriever newRetriever(InMemoryVectorStore vectorStore,
                                         InMemoryBm25Index bm25Index,
                                         HybridSearchIndex hybridIndex) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        if (hybridIndex != null) {
            beanFactory.registerSingleton("hybridSearchIndex", hybridIndex);
        }
        ObjectProvider<HybridSearchIndex> provider = beanFactory.getBeanProvider(HybridSearchIndex.class);

        return new HybridRetriever(
                new VectorRetriever(EMBEDDING_MODEL, vectorStore),
                new Bm25Retriever(bm25Index),
                EMBEDDING_MODEL,
                provider,
                properties);
    }

    private static Chunk chunk(String id, String content, AccessControl acl) {
        return Chunk.builder()
                .id(id)
                .documentId(id.split("#")[0])
                .documentTitle("测试文档")
                .content(content)
                .index(0)
                .accessControl(acl)
                .build();
    }

    private static AccessControl acl(Set<String> departments, Set<String> roles, int level) {
        return AccessControl.builder()
                .departments(new HashSet<>(departments))
                .roles(new HashSet<>(roles))
                .sensitivityLevel(level)
                .publicAccess(false)
                .build();
    }

    /** 模拟 ES 混合检索后端：记录调用次数与 topK，按给定顺序返回。 */
    private static class FakeHybridIndex implements HybridSearchIndex {

        private final List<ScoredChunk> results;
        private int searchCalls;
        private int lastTopK;

        private FakeHybridIndex(List<ScoredChunk> results) {
            this.results = results;
        }

        @Override
        public String name() {
            return "fake-es";
        }

        @Override
        public void indexAll(Collection<Chunk> chunks, List<float[]> embeddings) {
            // 测试无需写入
        }

        @Override
        public List<ScoredChunk> search(String queryText, float[] queryVector, int topK) {
            searchCalls++;
            lastTopK = topK;
            List<ScoredChunk> hits = new ArrayList<>();
            for (ScoredChunk result : results) {
                if (hits.size() >= topK) {
                    break;
                }
                hits.add(result);
            }
            return hits;
        }

        @Override
        public int size() {
            return results.size();
        }

        @Override
        public void clear() {
            results.clear();
        }
    }
}

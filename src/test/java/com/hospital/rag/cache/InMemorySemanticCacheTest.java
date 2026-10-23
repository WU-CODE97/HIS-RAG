package com.hospital.rag.cache;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.embedding.EmbeddingModel;
import com.hospital.rag.model.RagAnswer;
import com.hospital.rag.model.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义缓存核心行为测试（重点覆盖权限域隔离这一安全约束）。
 */
class InMemorySemanticCacheTest {

    private FakeEmbeddingModel embeddingModel;
    private RagProperties properties;
    private InMemorySemanticCache cache;

    private UserContext cardiologyDoctor;
    private UserContext neurologyDoctor;
    private UserContext admin;

    @BeforeEach
    void setUp() {
        embeddingModel = new FakeEmbeddingModel();
        // 「高血压用药禁忌」与其同义改写向量几乎一致（余弦 ≈ 0.995）
        embeddingModel.put("高血压用药禁忌", 1.0f, 0.0f, 0.0f);
        embeddingModel.put("高血压患者用药有哪些禁忌", 0.99f, 0.1f, 0.0f);
        // 语义无关的问题
        embeddingModel.put("糖尿病饮食方案", 0.0f, 1.0f, 0.0f);

        properties = new RagProperties();
        properties.getCache().setEnabled(true);
        properties.getCache().setSimilarityThreshold(0.95);
        properties.getCache().setTtl(Duration.ofMinutes(30));
        properties.getCache().setMaxSize(100);

        cache = new InMemorySemanticCache(embeddingModel, properties);

        cardiologyDoctor = user("D001", "心内科", "DOCTOR", 1);
        neurologyDoctor = user("D002", "神经内科", "DOCTOR", 1);
        admin = UserContext.builder().userId("A001").roles(Set.of("ADMIN")).clearanceLevel(9).build();
    }

    @Test
    @DisplayName("完全相同的问题应命中缓存")
    void shouldHitOnIdenticalQuery() {
        cache.put("高血压用药禁忌", cardiologyDoctor, answer("ACEI 禁忌为妊娠……"));

        Optional<RagAnswer> hit = cache.get("高血压用药禁忌", cardiologyDoctor);

        assertThat(hit).isPresent();
        assertThat(hit.get().getAnswer()).isEqualTo("ACEI 禁忌为妊娠……");
    }

    @Test
    @DisplayName("换种说法的同义问题也应命中缓存")
    void shouldHitOnSemanticallySimilarQuery() {
        cache.put("高血压用药禁忌", cardiologyDoctor, answer("ACEI 禁忌为妊娠……"));

        Optional<RagAnswer> hit = cache.get("高血压患者用药有哪些禁忌", cardiologyDoctor);

        assertThat(hit).isPresent();
        assertThat(hit.get().getAnswer()).isEqualTo("ACEI 禁忌为妊娠……");
    }

    @Test
    @DisplayName("语义不相关的问题不应命中缓存")
    void shouldMissOnUnrelatedQuery() {
        cache.put("高血压用药禁忌", cardiologyDoctor, answer("ACEI 禁忌为妊娠……"));

        assertThat(cache.get("糖尿病饮食方案", cardiologyDoctor)).isEmpty();
    }

    @Test
    @DisplayName("【安全】不同科室的用户不得共享缓存")
    void shouldNotLeakAcrossDepartments() {
        cache.put("高血压用药禁忌", cardiologyDoctor, answer("心内科内部资料"));

        assertThat(cache.get("高血压用药禁忌", neurologyDoctor)).isEmpty();
    }

    @Test
    @DisplayName("【安全】管理员缓存的答案不得泄漏给普通用户")
    void shouldNotLeakFromAdminToNormalUser() {
        cache.put("高血压用药禁忌", admin, answer("含全院敏感数据"));

        assertThat(cache.get("高血压用药禁忌", cardiologyDoctor)).isEmpty();
    }

    @Test
    @DisplayName("【安全】密级不同即视为不同权限域")
    void shouldNotLeakAcrossClearanceLevels() {
        UserContext highClearance = user("D003", "心内科", "DOCTOR", 3);
        cache.put("高血压用药禁忌", highClearance, answer("高密级内容"));

        assertThat(cache.get("高血压用药禁忌", cardiologyDoctor)).isEmpty();
    }

    @Test
    @DisplayName("生成失败的答案不应被缓存")
    void shouldNotCacheFailedAnswer() {
        RagAnswer failed = RagAnswer.builder().query("高血压用药禁忌").answer("抱歉，生成答案时发生错误").error(true).build();
        cache.put("高血压用药禁忌", cardiologyDoctor, failed);

        assertThat(cache.get("高血压用药禁忌", cardiologyDoctor)).isEmpty();
        assertThat(cache.stats().size()).isZero();
    }

    @Test
    @DisplayName("命中缓存返回的是副本，修改结果不影响缓存内容")
    void shouldReturnDefensiveCopy() {
        cache.put("高血压用药禁忌", cardiologyDoctor, answer("原始答案"));

        RagAnswer first = cache.get("高血压用药禁忌", cardiologyDoctor).orElseThrow();
        first.setAnswer("被篡改");
        first.getCitations().clear();

        RagAnswer second = cache.get("高血压用药禁忌", cardiologyDoctor).orElseThrow();
        assertThat(second.getAnswer()).isEqualTo("原始答案");
        assertThat(second.getCitations()).hasSize(1);
    }

    @Test
    @DisplayName("invalidateAll 应清空全部条目")
    void shouldInvalidateAll() {
        cache.put("高血压用药禁忌", cardiologyDoctor, answer("a"));
        cache.put("糖尿病饮食方案", cardiologyDoctor, answer("b"));
        assertThat(cache.stats().size()).isEqualTo(2);

        cache.invalidateAll();

        assertThat(cache.stats().size()).isZero();
        assertThat(cache.get("高血压用药禁忌", cardiologyDoctor)).isEmpty();
    }

    @Test
    @DisplayName("统计应正确反映命中率")
    void shouldReportHitRate() {
        cache.put("高血压用药禁忌", cardiologyDoctor, answer("a"));

        cache.get("高血压用药禁忌", cardiologyDoctor);   // hit
        cache.get("糖尿病饮食方案", cardiologyDoctor);   // miss

        SemanticCache.CacheStats stats = cache.stats();
        assertThat(stats.hits()).isEqualTo(1);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.hitRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("关闭开关后缓存应完全失效")
    void shouldBeDisabledWhenTurnedOff() {
        properties.getCache().setEnabled(false);
        cache.put("高血压用药禁忌", cardiologyDoctor, answer("a"));

        assertThat(cache.get("高血压用药禁忌", cardiologyDoctor)).isEmpty();
        assertThat(cache.stats().enabled()).isFalse();
    }

    // ---------- 测试夹具 ----------

    private UserContext user(String id, String department, String role, int level) {
        return UserContext.builder()
                .userId(id)
                .departments(new HashSet<>(Set.of(department)))
                .roles(new HashSet<>(Set.of(role)))
                .clearanceLevel(level)
                .build();
    }

    private RagAnswer answer(String text) {
        RagAnswer.Citation citation = RagAnswer.Citation.builder()
                .chunkId("doc1#0").documentId("doc1").documentTitle("用药手册").chunkIndex(0).score(0.9).snippet("...")
                .build();
        return RagAnswer.builder().query("q").answer(text).citations(List.of(citation)).build();
    }

    /** 可控的向量模型：文本 -> 预设向量。 */
    private static class FakeEmbeddingModel implements EmbeddingModel {
        private final Map<String, float[]> vectors = new HashMap<>();

        void put(String text, float... vector) {
            vectors.put(text, vector);
        }

        @Override
        public float[] embed(String text) {
            float[] vector = vectors.get(text);
            return vector == null ? new float[]{0f, 0f, 1f} : vector;
        }
    }
}

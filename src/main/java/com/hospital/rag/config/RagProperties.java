package com.hospital.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * RAG 全量可配置项，前缀 {@code rag}。
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private Embedding embedding = new Embedding();
    private Llm llm = new Llm();
    private Bm25 bm25 = new Bm25();
    private Vector vector = new Vector();
    private Hybrid hybrid = new Hybrid();
    private Retrieval retrieval = new Retrieval();
    private Chunking chunk = new Chunking();
    private Security security = new Security();
    private Cache cache = new Cache();
    private Conversation conversation = new Conversation();

    /** Embedding 模型配置（OpenAI 兼容）。 */
    @Data
    public static class Embedding {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "text-embedding-3-small";
        private int dimension = 1536;
        /** 批量向量化时每批条数。 */
        private int batchSize = 16;
    }

    /** LLM 配置（OpenAI 兼容）。 */
    @Data
    public static class Llm {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        private double temperature = 0.2;
        private int maxTokens = 1024;
    }

    /** BM25 检索后端配置。 */
    @Data
    public static class Bm25 {
        /** 后端类型：{@code memory} 或 {@code elasticsearch}。 */
        private String backend = "memory";
        /** Elasticsearch 索引名。 */
        private String indexName = "his_rag_chunks";
        /** 默认分词器；安装 IK 插件后可改为 {@code ik_max_word}。 */
        private String analyzer = "standard";
    }

    /** 向量检索后端配置。 */
    @Data
    public static class Vector {
        /** 后端类型：{@code memory} 或 {@code pgvector}。 */
        private String backend = "memory";
        /** pgvector 表名。 */
        private String table = "his_rag_chunks";
        /** 向量维度，必须与 embedding 模型输出一致。 */
        private int dimension = 1536;
        /** 索引类型：{@code hnsw} / {@code ivfflat} / {@code none}。 */
        private String indexType = "hnsw";
    }

    /** ES 原生混合检索配置。 */
    @Data
    public static class Hybrid {
        /** 后端类型：{@code local}（本地两路召回 + Java 融合）或 {@code elasticsearch}（单次 ES 查询）。 */
        private String backend = "local";
        /** ES 索引名。 */
        private String indexName = "his_rag_hybrid";
        /** 向量维度，必须与 embedding 模型输出一致。 */
        private int dimension = 1536;
        /** 默认分词器；安装 IK 插件后可改为 {@code ik_max_word}。 */
        private String analyzer = "standard";
        /** 融合方式：{@code rrf}（ES 原生 RRF，需 8.8+）或 {@code knn}（分数加权求和）。 */
        private String fusion = "rrf";
        /** kNN 每分片返回的候选数。 */
        private int knnK = 100;
        /** kNN 每分片探索的候选数，应大于等于 knnK。 */
        private int numCandidates = 200;
        /** RRF 平滑常数。 */
        private long rankConstant = 60L;
        /** RRF 参与排序的窗口大小。 */
        private long windowSize = 100L;
        /** 全文检索权重（knn 模式下生效）。 */
        private double textBoost = 1.0;
        /** 向量检索权重（knn 模式下生效）。 */
        private double knnBoost = 1.0;
    }

    /** 检索与融合参数。 */
    @Data
    public static class Retrieval {
        private int vectorTopK = 20;
        private int bm25TopK = 20;
        private int candidateTopK = 30;
        private int finalTopK = 5;
        private double vectorWeight = 0.6;
        private double bm25Weight = 0.4;
        private boolean useRrf = true;
        private int rrfK = 60;
        private boolean enableRerank = true;
    }

    /** 文档切分参数。 */
    @Data
    public static class Chunking {
        private int size = 400;
        private int overlap = 80;
    }

    /** 安全相关参数。 */
    @Data
    public static class Security {
        private boolean maskPii = true;
    }

    /** 语义缓存配置。 */
    @Data
    public static class Cache {
        private boolean enabled = true;
        /** 命中阈值：查询向量余弦相似度需 >= 该值。 */
        private double similarityThreshold = 0.95;
        /** 条目存活时间。 */
        private Duration ttl = Duration.ofMinutes(30);
        /** 最大条目数，超出后按最近访问时间淘汰。 */
        private int maxSize = 1000;
    }

    /** 多轮对话配置。 */
    @Data
    public static class Conversation {
        /** 多轮对话总开关；关闭后退化为单轮问答，历史既不读也不写。 */
        private boolean enabled = true;
        /** 是否启用基于历史的问题改写。 */
        private boolean rewriteEnabled = true;
        /** 参与改写的最近对话轮数（1 轮 = 1 问 + 1 答）。 */
        private int maxHistoryTurns = 6;
        /** 会话空闲存活时间，超过后历史自动失效。 */
        private Duration sessionTtl = Duration.ofHours(2);
        /** 最大并发会话数，超出后按最久未访问淘汰。 */
        private int maxSessions = 10000;
        /** 单会话保留的最大消息条数（1 轮 = 2 条），超出按轮淘汰最早的。 */
        private int maxMessagesPerSession = 40;
    }
}

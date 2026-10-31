package com.hospital.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private Rerank rerank = new Rerank();
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
        /** 连接超时（毫秒）。 */
        private int connectTimeoutMs = 10_000;
        /** 非流式调用的读超时（毫秒）。 */
        private int readTimeoutMs = 120_000;
        /** 流式调用的整体读超时（毫秒），到达后中断并保留已产出内容。 */
        private int streamTimeoutMs = 180_000;
        /**
         * 原样透传到上游请求体的额外字段，用于传递各家厂商的私有参数。
         *
         * <p>例如通义 / Qwen 系列需要 {@code enable_thinking=false} 才会关闭思维链，
         * 否则单次调用会生成 3000~5000 token 的推理内容（实测重排 49s → 1.1s）。
         * 做成透传而非硬编码，是为了换厂商时不必改代码。</p>
         */
        private Map<String, Object> extraBody = new LinkedHashMap<>();
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

        /**
         * 重排锚点前后补回的相邻片段数（0=关闭）。
         *
         * <p>切片按固定长度硬切，表格 / 参数列表天然跨片段；cross-encoder 逐片段打分时
         * 看不到片段边界，中间那一片常因缺少表头而得分极低被截掉，答案就会「少一截」。
         * 补回相邻片段可恢复完整性，代价只是少量上下文。</p>
         */
        private int neighborExpand = 1;

        /** 进入生成上下文的片段数上限（含补回的相邻片段）。 */
        private int maxContextChunks = 12;
    }

    /** 重排配置。 */
    @Data
    public static class Rerank {
        /** 重排实现：{@code gte}（专用 cross-encoder 接口）/ {@code llm}（LLM 列表式）/ {@code none}。 */
        private String provider = "gte";
        /** 重排接口地址，默认 DashScope text-rerank。 */
        private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        private String apiKey = "";
        private String model = "gte-rerank-v2";
        /** 接口协议：{@code dashscope}（input/parameters 包裹）或 {@code cohere}（query/documents 平铺）。 */
        private String apiStyle = "dashscope";
        /** 单次重排超时，超时后用原始召回顺序兜底。 */
        private int timeoutMs = 2_000;
        /** 送入重排的最大候选数；cross-encoder 成本随候选数线性增长，需与 candidate-top-k 对齐。 */
        private int maxCandidates = 30;
        /** 单篇文档截断长度（字符），控制请求体大小。 */
        private int maxDocChars = 512;
        /** 重排接口不可用时是否降级到 LLM 重排（默认关闭，直接回退召回顺序更快）。 */
        private boolean failOpenToLlm = false;
    }

    /** 文档切分参数。 */
    @Data
    public static class Chunking {
        /** 单个片段的目标字符数。 */
        private int size = 400;
        /** 超长段落开窗时的重叠字符数。 */
        private int overlap = 80;
        /** 是否按标题层级 + 表格行结构化切分；关闭后回到「按字数硬切」。 */
        private boolean structureAware = true;
        /** 是否给每个片段加「文档 + 章节」上下文头（提升召回与重排准确率）。 */
        private boolean contextHeader = true;
    }

    /** 安全相关参数。 */
    @Data
    public static class Security {
        private boolean maskPii = true;
        /**
         * 流式输出时保留的尾部字符数，不足该长度的内容先缓冲、不立即下发。
         *
         * <p>脱敏是基于完整文本的正则匹配，若把一个身份证号切成两段分别脱敏，两段都匹配不上。
         * 保留 24 个字符（大于 18 位身份证 / 19 位卡号）即可避免绝大多数跨块漏脱敏。</p>
         */
        private int piiTailGuard = 24;
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
        /** 改写用的模型；留空则沿用 {@code rag.llm.model}。 */
        private String rewriteModel = "";
        /** 改写超时（毫秒），超时直接回退为用户原问题，不阻塞主链路。 */
        private int rewriteTimeoutMs = 3_000;
        /** 改写输出的 max-tokens；改写只需输出一句话，给多了反而更慢。 */
        private int rewriteMaxTokens = 128;
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

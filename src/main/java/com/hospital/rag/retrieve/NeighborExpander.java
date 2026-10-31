package com.hospital.rag.retrieve;

import com.hospital.rag.config.RagProperties;
import com.hospital.rag.model.Chunk;
import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.model.UserContext;
import com.hospital.rag.store.ChunkStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 邻接扩展器：把重排命中的「锚点」片段扩展成连续片段块。
 *
 * <p><b>定位</b>：这是<b>兜底</b>，不是主力。切片已经按章节与表格行结构化（见 TextSplitter），
 * 表格不会断头、每片自带出处，重排能正常给分。但当某一节本身长于 {@code chunk.size}、
 * 被拆成多片时，仅命中其中一片仍会丢上下文，这里再把前后各一片补回来。</p>
 *
 * <p>历史背景：早期版本按字数硬切，工伤接口 Q002「出参有哪些」的 12 个出参被切成
 * #4 / #5 / #6 三片，#5（第 2~9 个参数）因缺表头重排得分仅 0.006 排第 9 被截掉，
 * 答案只剩 4 个出参。结构化切片上线后该场景由切片层解决，本类退为兜底。</p>
 *
 * <p>补片只取同一章节内的相邻片段：跨节的邻片多半是上一节的尾巴，带进来只会稀释上下文。</p>
 *
 * <p><b>怎么做</b>：重排结果只当锚点，再按 {@code documentId + chunkIndex} 把锚点前后各
 * {@code neighbor-expand} 片取回来。片段来源按运行形态决定：ES 模式直接查索引，
 * 本地模式查 {@link ChunkStore}。</p>
 *
 * <p><b>顺序</b>：按锚点排名组织，第一名锚点的邻域排最前，同一邻域内按下标升序，
 * 保证模型读到的仍是「最相关的内容优先」，同时每块内部保持原文连续。</p>
 */
@Component
public class NeighborExpander {

    private static final Logger log = LoggerFactory.getLogger(NeighborExpander.class);

    private final ChunkStore chunkStore;
    private final ObjectProvider<HybridSearchIndex> hybridSearchIndex;
    private final RagProperties.Retrieval config;

    public NeighborExpander(ChunkStore chunkStore,
                            ObjectProvider<HybridSearchIndex> hybridSearchIndex,
                            RagProperties properties) {
        this.chunkStore = chunkStore;
        this.hybridSearchIndex = hybridSearchIndex;
        this.config = properties.getRetrieval();
    }

    /**
     * 扩展锚点上下文。
     *
     * @param anchors 重排（或截断）后的锚点片段，按相关度降序
     * @param user    当前用户上下文，补回的片段同样要通过权限校验
     * @return 扩展后的上下文片段，未开启扩展时原样返回锚点
     */
    public List<ScoredChunk> expand(List<ScoredChunk> anchors, UserContext user) {
        int radius = config.getNeighborExpand();
        if (radius <= 0 || anchors == null || anchors.isEmpty()) {
            return anchors;
        }
        int cap = Math.max(config.getMaxContextChunks(), anchors.size());
        Map<String, ScoredChunk> merged = new LinkedHashMap<>();

        for (ScoredChunk anchor : anchors) {
            if (merged.size() >= cap) {
                break;
            }
            Chunk chunk = anchor.getChunk();
            if (chunk == null || chunk.getId() == null) {
                continue;
            }
            List<Chunk> window = loadWindow(chunk.getDocumentId(),
                    Math.max(0, chunk.getIndex() - radius), chunk.getIndex() + radius);
            if (window.isEmpty()) {
                merged.putIfAbsent(chunk.getId(), anchor);
                continue;
            }
            for (Chunk neighbor : window) {
                if (merged.size() >= cap) {
                    break;
                }
                String id = neighbor.getId();
                if (id == null || merged.containsKey(id) || !neighbor.isAccessible(user)) {
                    continue;
                }
                // 结构化切片后，同一节的内容落在相邻下标上；跨节的邻片多半是上一节的尾巴，不带进来
                if (!id.equals(chunk.getId()) && !sameSection(chunk, neighbor)) {
                    continue;
                }
                // 锚点沿用自身分数与排名；补回的相邻片段借用锚点分数，来源标记为 neighbor 便于排查
                merged.put(id, id.equals(chunk.getId()) ? anchor : ScoredChunk.builder()
                        .chunk(neighbor)
                        .score(anchor.getScore())
                        .source("neighbor")
                        .rank(anchor.getRank())
                        .build());
            }
        }

        if (merged.size() > anchors.size()) {
            log.debug("邻接扩展: 锚点={} -> 上下文={}", anchors.size(), merged.size());
        }
        return new ArrayList<>(merged.values());
    }

    /** 按文档取片段的连续区间，ES 优先，本地模式回退内存片段库。 */
    private List<Chunk> loadWindow(String documentId, int fromIndex, int toIndex) {
        HybridSearchIndex esIndex = hybridSearchIndex.getIfAvailable();
        if (esIndex != null) {
            return esIndex.loadWindow(documentId, fromIndex, toIndex);
        }
        return chunkStore.findByDocumentBetween(documentId, fromIndex, toIndex);
    }

    /** 两个片段是否属于同一章节；锚点没有章节信息时不过滤（纯文本文档）。 */
    private boolean sameSection(Chunk anchor, Chunk candidate) {
        String section = anchor.getSection();
        if (section == null || section.isBlank()) {
            return true;
        }
        return Objects.equals(section, candidate.getSection());
    }
}

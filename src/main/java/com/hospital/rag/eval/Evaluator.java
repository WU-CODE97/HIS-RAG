package com.hospital.rag.eval;

import com.hospital.rag.model.ScoredChunk;
import com.hospital.rag.model.UserContext;
import com.hospital.rag.retrieve.Retriever;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索效果评估器。
 *
 * <p>基于人工标注的「问题 → 相关文档」集合，计算 Recall@K、Precision@K 与 MRR。</p>
 */
@Service
public class Evaluator {

    private final Retriever retriever;

    public Evaluator(Retriever retriever) {
        this.retriever = retriever;
    }

    /**
     * 执行评估。
     *
     * @param cases 评估用例列表
     * @param user  评估所用用户上下文
     * @param k     截断位置
     * @return 汇总指标与逐条明细
     */
    public EvalResult evaluate(List<EvalCase> cases, UserContext user, int k) {
        List<CaseResult> details = new ArrayList<>();
        if (cases == null || cases.isEmpty()) {
            return new EvalResult(0.0, 0.0, 0.0, 0, details);
        }
        UserContext effectiveUser = user == null ? UserContext.guest() : user;

        double recallSum = 0.0;
        double precisionSum = 0.0;
        double rrSum = 0.0;

        for (EvalCase evalCase : cases) {
            List<ScoredChunk> retrieved = retriever.retrieve(evalCase.query(), effectiveUser, k);
            List<String> retrievedDocs = new ArrayList<>();
            for (ScoredChunk sc : retrieved) {
                String docId = sc.getChunk().getDocumentId();
                if (!retrievedDocs.contains(docId)) {
                    retrievedDocs.add(docId);
                }
            }

            Set<String> relevant = new HashSet<>(evalCase.relevantDocumentIds());
            long hits = retrievedDocs.stream().filter(relevant::contains).count();
            double recall = relevant.isEmpty() ? 0.0 : (double) hits / relevant.size();
            double precision = retrievedDocs.isEmpty() ? 0.0 : (double) hits / retrievedDocs.size();

            int firstHitRank = -1;
            for (int i = 0; i < retrievedDocs.size(); i++) {
                if (relevant.contains(retrievedDocs.get(i))) {
                    firstHitRank = i + 1;
                    break;
                }
            }
            double reciprocalRank = firstHitRank > 0 ? 1.0 / firstHitRank : 0.0;

            recallSum += recall;
            precisionSum += precision;
            rrSum += reciprocalRank;

            details.add(new CaseResult(evalCase.query(), retrievedDocs, recall, precision, reciprocalRank));
        }

        int n = cases.size();
        return new EvalResult(recallSum / n, precisionSum / n, rrSum / n, n, details);
    }

    /** 单条评估用例：问题 + 相关文档 id 集合。 */
    public record EvalCase(String query, List<String> relevantDocumentIds) {
    }

    /** 单条评估结果。 */
    public record CaseResult(String query,
                             List<String> retrievedDocumentIds,
                             double recall,
                             double precision,
                             double reciprocalRank) {
    }

    /** 汇总评估结果。 */
    public record EvalResult(double avgRecall,
                             double avgPrecision,
                             double mrr,
                             int caseCount,
                             List<CaseResult> details) {
    }
}

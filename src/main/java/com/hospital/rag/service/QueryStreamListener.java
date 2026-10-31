package com.hospital.rag.service;

import com.hospital.rag.model.RagAnswer;

import java.util.List;

/**
 * 流式问答的事件回调。
 *
 * <p>把「怎么推给客户端」与「怎么算」解耦：{@link RagService} 只负责按顺序回调，
 * SSE、WebSocket 或命令行输出各自实现本接口即可。</p>
 *
 * <p>回调顺序固定为：{@link #onStart} → {@link #onDelta} × N → {@link #onComplete}。</p>
 */
public interface QueryStreamListener {

    /**
     * 检索与重排已完成，开始生成。此时引用列表已确定，前端可以先渲染引用再等正文。
     *
     * @param sessionId      本轮会话 id
     * @param rewrittenQuery 多轮改写后实际用于检索的问题
     * @param citations      引用来源
     * @param cacheHit       是否命中语义缓存（命中时正文会一次性回调完毕）
     */
    void onStart(String sessionId, String rewrittenQuery, List<RagAnswer.Citation> citations, boolean cacheHit);

    /** 增量正文，可能被调用多次，已做隐私脱敏。 */
    default void onDelta(String text) {
    }

    /** 生成结束，携带完整答案与耗时等元信息。 */
    default void onComplete(RagAnswer answer) {
    }

    /** 链路异常（检索 / 生成失败）。 */
    default void onError(Throwable error) {
    }
}

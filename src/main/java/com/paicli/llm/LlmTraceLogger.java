package com.paicli.llm;

import org.slf4j.Logger;

/**
 * Diagnostic logging for model-side traces that are otherwise only streamed to
 * the terminal. Keep this focused on model text; request bodies may contain
 * large base64 images and should not be logged here.
 * 中文：为平时仅流式输出到终端的模型轨迹提供诊断日志。只记录模型文本，避免记录可能含大量 Base64 图片的请求正文。
 */
public final class LlmTraceLogger {
    private LlmTraceLogger() {}

    public static void logReasoning(Logger log, String scope, LlmClient llmClient, String reasoningContent) {
        if (log == null || reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        String normalized = reasoningContent.replace("\r\n", "\n").replace('\r', '\n').trim();
        log.info("LLM reasoning [{}] provider={} model={} chars={}\n{}",
                scope == null || scope.isBlank() ? "unknown" : scope,
                llmClient == null ? "unknown" : llmClient.getProviderName(),
                llmClient == null ? "unknown" : llmClient.getModelName(),
                normalized.length(),
                normalized);
    }
}

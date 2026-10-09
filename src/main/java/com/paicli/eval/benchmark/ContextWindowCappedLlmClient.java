package com.paicli.eval.benchmark;

import com.paicli.llm.LlmClient;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.relay.F3ToolResultAudit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Request-scoped capability view that enforces a frozen context cap before and after each
 * provider call.
 *
 * <p>The request estimator deliberately counts JSON-escaped UTF-8 bytes as byte-budget units and
 * adds conservative structural and provider-field overhead. Byte-budget units are not presented
 * as tokenizer output: they are a deterministic pre-call safety bound, while provider usage is
 * the post-call token evidence.
 * The default mode uses the non-listening provider overload and replays only the gated final
 * response. Explicit F3 mode instead bounds and preserves actual adapter callback fragments,
 * delivering them only after the complete response passes that same post-call usage/cap gate.
 * Neither mode exposes a response that violates the cap to the Candidate.
 * 中文：请求级能力视图，在每次供应商调用前后强制冻结上下文上限。估算以 JSON 转义 UTF-8
 * 字节为预算单位，并保守增加结构和供应商字段开销；这不是分词器输出，而是调用前安全界限，调用后用量才是 Token
 * 证据。默认使用无监听重载，仅重放通过门禁的最终响应；显式 F3
 * 模式保留有界真实回调片段，完整响应通过同一用量门禁后才交付。两种模式均不向候选暴露超限响应。
 */
final class ContextWindowCappedLlmClient implements LlmClient {
    static final int MIN_CONTEXT_WINDOW_CAP_TOKENS = 8_000;
    static final int MAX_OUTPUT_TOKENS_PER_CALL = 16_384;
    static final int MAX_OBSERVED_STREAM_DELTAS = 65_536;

    private static final long ROOT_AND_PROVIDER_FIELD_OVERHEAD_UNITS = 4_096;
    private static final long MESSAGE_OVERHEAD_UNITS = 256;
    private static final long CONTENT_PART_OVERHEAD_UNITS = 256;
    private static final long TOOL_CALL_OVERHEAD_UNITS = 256;
    private static final long TOOL_OVERHEAD_UNITS = 256;

    private final LlmClient delegate;
    private final int requestedContextWindowCapTokens;
    private final int effectiveContextWindowCapTokens;
    private final int maxOutputTokensPerCall;
    private final F3ToolResultAudit f3Audit;

    private ContextWindowCappedLlmClient(LlmClient delegate,
                                         int contextWindowCapTokens,
                                         int maxOutputTokensPerCall) {
        this(delegate, contextWindowCapTokens, maxOutputTokensPerCall, null);
    }

    private ContextWindowCappedLlmClient(LlmClient delegate, int contextWindowCapTokens,
                                         int maxOutputTokensPerCall, F3ToolResultAudit f3Audit) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.f3Audit = f3Audit;
        if (contextWindowCapTokens < MIN_CONTEXT_WINDOW_CAP_TOKENS
                || contextWindowCapTokens > 10_000_000) {
            throw new IllegalArgumentException("contextWindowCapTokens out of range");
        }
        if (delegate.maxContextWindow() <= 0) {
            throw new IllegalArgumentException("delegate maxContextWindow must be positive");
        }
        this.requestedContextWindowCapTokens = contextWindowCapTokens;
        this.effectiveContextWindowCapTokens = Math.min(
                delegate.maxContextWindow(), contextWindowCapTokens);
        if (maxOutputTokensPerCall <= 0
                || maxOutputTokensPerCall > MAX_OUTPUT_TOKENS_PER_CALL
                || maxOutputTokensPerCall > contextWindowCapTokens) {
            throw new IllegalArgumentException("maxOutputTokensPerCall out of range");
        }
        this.maxOutputTokensPerCall = maxOutputTokensPerCall;
    }

    static ContextWindowCappedLlmClient cap(LlmClient delegate,
                                             int contextWindowCapTokens,
                                             int maxOutputTokensPerCall) {
        return new ContextWindowCappedLlmClient(
                delegate, contextWindowCapTokens, maxOutputTokensPerCall);
    }

    /**
     * Explicit F3 diagnostic mode. Observe actual adapter callbacks, then deliver the unchanged
     * nonempty fragments only after the complete response passes the usage/cap gate. This is not
     * an HTTP/SSE byte audit. A listening adapter may conservatively stop retries after a fragment;
     * the default factory and every non-F3 caller retain the retry-safe, final-only behavior.
     * 中文：显式 F3 诊断模式观测真实适配器回调，完整响应通过用量和上限门禁后，原样交付非空片段；不属于 HTTP 或 SSE
     * 字节审计。监听适配器可能在片段出现后保守停止重试，默认工厂和非 F3 调用方保留可安全重试的仅最终响应行为。
     */
    static ContextWindowCappedLlmClient capPreservingObservedDeltas(LlmClient delegate,
            int contextWindowCapTokens, int maxOutputTokensPerCall, F3ToolResultAudit audit) {
        return new ContextWindowCappedLlmClient(delegate, contextWindowCapTokens,
                maxOutputTokensPerCall, Objects.requireNonNull(audit, "F3 audit"));
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        if (f3Audit != null) return chat(messages, tools, StreamListener.NO_OP);
        enforceRequest(messages, tools);
        return enforceResponse(delegate.chat(messages, tools));
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools,
                             StreamListener listener) throws IOException {
        enforceRequest(messages, tools);
        if (f3Audit != null) return chatPreservingObservedDeltas(messages, tools, listener);
        // The provider sees the non-listening overload, so its retry policy knows no output has（中文：供应商使用无监听重载，SSE 尝试中断时重试策略知道尚无）
        // escaped if an SSE attempt is interrupted. Only the final, gated response is replayed.（中文：输出逸出，仅重放最终通过门禁的响应。）
        ChatResponse response = enforceResponse(delegate.chat(messages, tools));
        StreamListener target = listener == null ? StreamListener.NO_OP : listener;
        if (response.reasoningContent() != null && !response.reasoningContent().isEmpty()) {
            target.onReasoningDelta(response.reasoningContent());
        }
        if (response.content() != null && !response.content().isEmpty()) {
            target.onContentDelta(response.content());
        }
        return response;
    }

    private ChatResponse chatPreservingObservedDeltas(List<Message> messages, List<Tool> tools,
                                                     StreamListener listener) throws IOException {
        f3Audit.requireHealthy();
        var buffer = new ObservedDeltaBuffer(f3Audit);
        final ChatResponse response;
        try {
            response = delegate.chat(messages, tools, buffer);
        } catch (IOException | RuntimeException error) {
            buffer.abort();
            buffer.requireHealthy(); // Sticky capture failure wins even if a provider wrapped it.（中文：即使供应商包装异常，不可覆盖的捕获失败仍优先。）
            throw error;
        }
        List<ObservedDelta> observed = buffer.finish();
        ChatResponse gated = enforceResponse(response);
        f3Audit.requireHealthy();
        StreamListener target = listener == null ? StreamListener.NO_OP : listener;
        for (ObservedDelta delta : observed) {
            if (delta.reasoning()) target.onReasoningDelta(delta.value());
            else target.onContentDelta(delta.value());
        }
        return gated;
    }

    private record ObservedDelta(boolean reasoning, String value) { }

    private static final class ObservedDeltaBuffer implements StreamListener {
        private final F3ToolResultAudit audit;
        private final List<ObservedDelta> deltas = new ArrayList<>();
        private int chars;
        private boolean closed;
        private F3ToolResultAudit.Failure failure;

        private ObservedDeltaBuffer(F3ToolResultAudit audit) { this.audit = audit; }
        @Override public void onReasoningDelta(String delta) { add(true, delta); }
        @Override public void onContentDelta(String delta) { add(false, delta); }
        private synchronized void add(boolean reasoning, String value) {
            if (failure != null) throw new UncheckedIOException(failure);
            if (closed) throw invalid();
            if (value == null || value.isEmpty()) return; // Same inert-fragment policy as CredentialGuard.（中文：采用与 CredentialGuard 相同的无作用片段策略。）
            // The wire can carry at most MAX_TEXT_CHARS per unchanged fragment; never split it（中文：每个原样片段在传输上最多 MAX_TEXT_CHARS 字符，不得拆分）
            // and pretend the resulting relay frame boundaries were observed from the adapter.（中文：后假称所得中继帧边界来自适配器观测。）
            if (value.length() > BenchmarkRelayProtocol.MAX_TEXT_CHARS || deltas.size() >= MAX_OBSERVED_STREAM_DELTAS
                    || value.length() > BenchmarkRelayProtocol.MAX_RESULT_CHARS - chars) throw invalid();
            deltas.add(new ObservedDelta(reasoning, value)); chars += value.length();
        }
        private UncheckedIOException invalid() {
            failure = audit.invalidateStreamObservation(); return new UncheckedIOException(failure);
        }
        private synchronized void requireHealthy() throws F3ToolResultAudit.Failure {
            if (failure != null) throw failure;
        }
        private synchronized List<ObservedDelta> finish() throws F3ToolResultAudit.Failure {
            closed = true; requireHealthy(); return List.copyOf(deltas);
        }
        private synchronized void abort() { closed = true; deltas.clear(); }
    }

    private void enforceRequest(List<Message> messages, List<Tool> tools) throws IOException {
        long estimatedInputBudgetUnits = estimateInputBudgetUnits(messages, tools);
        long estimatedTotal = saturatedAdd(estimatedInputBudgetUnits, maxOutputTokensPerCall);
        if (estimatedTotal > effectiveContextWindowCapTokens) {
            throw ContextWindowCapExceededException.requestEstimate(
                    requestedContextWindowCapTokens,
                    effectiveContextWindowCapTokens,
                    estimatedInputBudgetUnits,
                    maxOutputTokensPerCall);
        }
    }

    private ChatResponse enforceResponse(ChatResponse response) throws IOException {
        if (response == null) {
            throw ContextWindowCapExceededException.invalidProviderUsage(
                    requestedContextWindowCapTokens, effectiveContextWindowCapTokens,
                    maxOutputTokensPerCall, -1, -1);
        }
        if (!response.usagePresent()) {
            throw ContextWindowCapExceededException.missingProviderUsage(
                    requestedContextWindowCapTokens,
                    effectiveContextWindowCapTokens, maxOutputTokensPerCall);
        }
        if (response.inputTokens() < 0 || response.outputTokens() < 0) {
            throw ContextWindowCapExceededException.invalidProviderUsage(
                    requestedContextWindowCapTokens, effectiveContextWindowCapTokens,
                    maxOutputTokensPerCall, response.inputTokens(), response.outputTokens());
        }
        long observedTotalTokens = saturatedAdd(response.inputTokens(), response.outputTokens());
        if (response.outputTokens() > maxOutputTokensPerCall) {
            throw ContextWindowCapExceededException.providerOutputExceeded(
                    requestedContextWindowCapTokens, effectiveContextWindowCapTokens,
                    maxOutputTokensPerCall, response.inputTokens(), response.outputTokens(),
                    observedTotalTokens);
        }
        if (observedTotalTokens > effectiveContextWindowCapTokens) {
            throw ContextWindowCapExceededException.providerContextExceeded(
                    requestedContextWindowCapTokens, effectiveContextWindowCapTokens,
                    maxOutputTokensPerCall, response.inputTokens(), response.outputTokens(),
                    observedTotalTokens);
        }
        return response;
    }

    /** Conservative byte-budget estimate. It is package-visible for structural-bound tests. 中文：保守字节预算估计，包级可见以供结构边界测试。 */
    static long estimateInputBudgetUnits(List<Message> messages, List<Tool> tools) {
        long estimate = ROOT_AND_PROVIDER_FIELD_OVERHEAD_UNITS;
        if (messages != null) {
            for (Message message : messages) {
                if (message == null) {
                    estimate = saturatedAdd(estimate, MESSAGE_OVERHEAD_UNITS);
                    continue;
                }
                estimate = saturatedAdd(estimate, MESSAGE_OVERHEAD_UNITS);
                estimate = addJsonStringBytes(estimate, message.role());
                estimate = addJsonStringBytes(estimate, message.content());
                estimate = addJsonStringBytes(estimate, message.reasoningContent());
                estimate = addJsonStringBytes(estimate, message.toolCallId());
                if (message.toolCalls() != null) {
                    for (ToolCall call : message.toolCalls()) {
                        estimate = saturatedAdd(estimate, TOOL_CALL_OVERHEAD_UNITS);
                        if (call == null) {
                            continue;
                        }
                        estimate = addJsonStringBytes(estimate, call.id());
                        if (call.function() != null) {
                            estimate = addJsonStringBytes(estimate, call.function().name());
                            estimate = addJsonStringBytes(estimate, call.function().arguments());
                        }
                    }
                }
                if (message.contentParts() != null) {
                    for (ContentPart part : message.contentParts()) {
                        estimate = saturatedAdd(estimate, CONTENT_PART_OVERHEAD_UNITS);
                        if (part == null) {
                            continue;
                        }
                        estimate = addJsonStringBytes(estimate, part.type());
                        estimate = addJsonStringBytes(estimate, part.text());
                        estimate = addJsonStringBytes(estimate, part.imageBase64());
                        estimate = addJsonStringBytes(estimate, part.imageUrl());
                        estimate = addJsonStringBytes(estimate, part.mimeType());
                    }
                }
            }
        }
        if (tools != null) {
            for (Tool tool : tools) {
                estimate = saturatedAdd(estimate, TOOL_OVERHEAD_UNITS);
                if (tool == null) {
                    continue;
                }
                estimate = addJsonStringBytes(estimate, tool.name());
                estimate = addJsonStringBytes(estimate, tool.description());
                if (tool.parameters() != null) {
                    // JsonNode.toString() is already the serialized JSON value; unlike a string（中文：JsonNode.toString() 已是序列化 JSON 值，不同于字符串）
                    // field, it is inserted into the request body without another escape layer.（中文：字段，插入请求正文时不再增加一层转义。）
                    estimate = addUtf8Bytes(estimate, tool.parameters().toString());
                }
            }
        }
        return estimate;
    }

    private static long addUtf8Bytes(long current, String value) {
        return saturatedAdd(current, utf8Length(value));
    }

    private static long addJsonStringBytes(long current, String value) {
        return saturatedAdd(current, jsonEscapedUtf8Length(value));
    }

    /** UTF-8 length of one JSON string value, including quotes and worst-case escaping. 中文：单个 JSON 字符串值的 UTF-8 长度，包含引号和最坏转义开销。 */
    private static long jsonEscapedUtf8Length(String value) {
        if (value == null) {
            return 0;
        }
        long bytes = 2; // surrounding JSON quotes（中文：JSON 字符串外围引号。）
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '"' || current == '\\') {
                bytes = saturatedAdd(bytes, 2);
            } else if (current <= 0x1f) {
                bytes = saturatedAdd(bytes, 6); // \\u00XX（中文：Unicode 转义表示。）
            } else if (current <= 0x7f) {
                bytes = saturatedAdd(bytes, 1);
            } else if (current <= 0x7ff) {
                bytes = saturatedAdd(bytes, 2);
            } else if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes = saturatedAdd(bytes, 4);
                index++;
            } else if (Character.isSurrogate(current)) {
                // This also covers serializers configured to escape an isolated surrogate.（中文：也涵盖配置为转义孤立代理字符的序列化器。）
                bytes = saturatedAdd(bytes, 6);
            } else {
                bytes = saturatedAdd(bytes, 3);
            }
        }
        return bytes;
    }

    private static long utf8Length(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) {
                bytes = saturatedAdd(bytes, 1);
            } else if (current <= 0x7ff) {
                bytes = saturatedAdd(bytes, 2);
            } else if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes = saturatedAdd(bytes, 4);
                index++;
            } else {
                // Three bytes also safely over-counts an isolated surrogate replacement.（中文：按三字节计也能安全高估孤立代理字符的替换开销。）
                bytes = saturatedAdd(bytes, 3);
            }
        }
        return bytes;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    int requestedContextWindowCapTokens() {
        return requestedContextWindowCapTokens;
    }

    int effectiveContextWindowCapTokens() {
        return effectiveContextWindowCapTokens;
    }

    int maxOutputTokensPerCall() {
        return maxOutputTokensPerCall;
    }

    @Override
    public void cancelInFlightCalls() {
        delegate.cancelInFlightCalls();
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public int maxContextWindow() {
        return effectiveContextWindowCapTokens;
    }

    @Override
    public boolean supportsPromptCaching() {
        return delegate.supportsPromptCaching();
    }

    @Override
    public boolean supportsTools() {
        return delegate.supportsTools();
    }

    @Override
    public boolean supportsImageInput() {
        return delegate.supportsImageInput();
    }

    @Override
    public String promptCacheMode() {
        return delegate.promptCacheMode();
    }

    static final class ContextWindowCapExceededException extends IOException {
        enum Kind {
            REQUEST_ESTIMATE_EXCEEDED,
            PROVIDER_USAGE_MISSING,
            PROVIDER_USAGE_INVALID,
            PROVIDER_OUTPUT_EXCEEDED,
            PROVIDER_CONTEXT_EXCEEDED
        }

        private final Kind kind;
        private final int requestedCapTokens;
        private final int effectiveCapTokens;
        private final long estimatedInputBudgetUnits;
        private final int observedInputTokens;
        private final int observedOutputTokens;
        private final long observedTotalTokens;
        private final int maxOutputTokensPerCall;

        private ContextWindowCapExceededException(
                Kind kind,
                int requestedCapTokens,
                int effectiveCapTokens,
                long estimatedInputBudgetUnits,
                int observedInputTokens,
                int observedOutputTokens,
                long observedTotalTokens,
                int maxOutputTokensPerCall) {
            super(message(kind, requestedCapTokens, effectiveCapTokens, estimatedInputBudgetUnits,
                    observedInputTokens, observedOutputTokens, observedTotalTokens,
                    maxOutputTokensPerCall));
            this.kind = kind;
            this.requestedCapTokens = requestedCapTokens;
            this.effectiveCapTokens = effectiveCapTokens;
            this.estimatedInputBudgetUnits = estimatedInputBudgetUnits;
            this.observedInputTokens = observedInputTokens;
            this.observedOutputTokens = observedOutputTokens;
            this.observedTotalTokens = observedTotalTokens;
            this.maxOutputTokensPerCall = maxOutputTokensPerCall;
        }

        static ContextWindowCapExceededException requestEstimate(
                int requestedCapTokens,
                int effectiveCapTokens,
                long estimatedInputBudgetUnits,
                int maxOutputTokensPerCall) {
            return new ContextWindowCapExceededException(
                    Kind.REQUEST_ESTIMATE_EXCEEDED,
                    requestedCapTokens,
                    effectiveCapTokens,
                    estimatedInputBudgetUnits,
                    -1,
                    -1,
                    -1,
                    maxOutputTokensPerCall);
        }

        static ContextWindowCapExceededException missingProviderUsage(
                int requestedCapTokens,
                int effectiveCapTokens,
                int maxOutputTokensPerCall) {
            return new ContextWindowCapExceededException(
                    Kind.PROVIDER_USAGE_MISSING,
                    requestedCapTokens,
                    effectiveCapTokens,
                    -1,
                    -1,
                    -1,
                    -1,
                    maxOutputTokensPerCall);
        }

        static ContextWindowCapExceededException invalidProviderUsage(
                int requestedCapTokens, int effectiveCapTokens, int maxOutputTokensPerCall,
                int observedInputTokens, int observedOutputTokens) {
            return providerFailure(
                    Kind.PROVIDER_USAGE_INVALID, requestedCapTokens, effectiveCapTokens,
                    maxOutputTokensPerCall, observedInputTokens, observedOutputTokens,
                    saturatedAdd(observedInputTokens, observedOutputTokens));
        }

        static ContextWindowCapExceededException providerOutputExceeded(
                int requestedCapTokens, int effectiveCapTokens, int maxOutputTokensPerCall,
                int observedInputTokens, int observedOutputTokens, long observedTotalTokens) {
            return providerFailure(
                    Kind.PROVIDER_OUTPUT_EXCEEDED, requestedCapTokens, effectiveCapTokens,
                    maxOutputTokensPerCall, observedInputTokens, observedOutputTokens,
                    observedTotalTokens);
        }

        static ContextWindowCapExceededException providerContextExceeded(
                int requestedCapTokens, int effectiveCapTokens, int maxOutputTokensPerCall,
                int observedInputTokens, int observedOutputTokens, long observedTotalTokens) {
            return providerFailure(
                    Kind.PROVIDER_CONTEXT_EXCEEDED, requestedCapTokens, effectiveCapTokens,
                    maxOutputTokensPerCall, observedInputTokens, observedOutputTokens,
                    observedTotalTokens);
        }

        private static ContextWindowCapExceededException providerFailure(
                Kind kind, int requestedCapTokens, int effectiveCapTokens,
                int maxOutputTokensPerCall, int observedInputTokens, int observedOutputTokens,
                long observedTotalTokens) {
            return new ContextWindowCapExceededException(
                    kind, requestedCapTokens, effectiveCapTokens, -1, observedInputTokens,
                    observedOutputTokens, observedTotalTokens, maxOutputTokensPerCall);
        }

        private static String message(
                Kind kind,
                int requestedCapTokens,
                int effectiveCapTokens,
                long estimatedInputBudgetUnits,
                int observedInputTokens,
                int observedOutputTokens,
                long observedTotalTokens,
                int maxOutputTokensPerCall) {
            if (kind == Kind.REQUEST_ESTIMATE_EXCEEDED) {
                return "context window cap rejected request before provider call"
                        + " (estimatedInputBudgetUnits=" + estimatedInputBudgetUnits
                        + ", maxOutputTokensPerCall=" + maxOutputTokensPerCall
                        + ", requestedCapTokens=" + requestedCapTokens
                        + ", effectiveCapTokens=" + effectiveCapTokens + ")";
            }
            if (kind == Kind.PROVIDER_USAGE_MISSING) {
                return "context window cap rejected provider response without usage"
                        + " (requestedCapTokens=" + requestedCapTokens
                        + ", effectiveCapTokens=" + effectiveCapTokens
                        + ", maxOutputTokensPerCall=" + maxOutputTokensPerCall + ")";
            }
            return "context window cap rejected provider response"
                    + " (kind=" + kind
                    + ", observedInputTokens=" + observedInputTokens
                    + ", observedOutputTokens=" + observedOutputTokens
                    + ", observedTotalTokens=" + observedTotalTokens
                    + ", maxOutputTokensPerCall=" + maxOutputTokensPerCall
                    + ", requestedCapTokens=" + requestedCapTokens
                    + ", effectiveCapTokens=" + effectiveCapTokens + ")";
        }

        Kind kind() {
            return kind;
        }

        int requestedCapTokens() {
            return requestedCapTokens;
        }

        int effectiveCapTokens() {
            return effectiveCapTokens;
        }

        long estimatedInputBudgetUnits() {
            return estimatedInputBudgetUnits;
        }

        int observedInputTokens() {
            return observedInputTokens;
        }

        int observedOutputTokens() {
            return observedOutputTokens;
        }

        long observedTotalTokens() {
            return observedTotalTokens;
        }

        int maxOutputTokensPerCall() {
            return maxOutputTokensPerCall;
        }
    }
}

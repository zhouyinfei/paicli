package com.paicli.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Optional, in-process observation of native Plan execution. Nothing is persisted by default.
 * Callbacks run on the executing task thread and may be concurrent; observers must be fast,
 * thread-safe and non-blocking. These Candidate-side events are diagnostic observations, not
 * trusted benchmark evidence on their own. A verifier must independently bind them to provider
 * requests, tool execution and artifacts. Text fingerprints cover UTF-8 text, not image payloads.
 * 中文：可选的进程内原生 Plan 执行观测，默认不持久化。回调在执行任务线程运行，可并发，必须快速、线程安全且不阻塞。候选侧事件仅供诊断
 * ，需由验证器独立绑定供应商请求、工具执行和产物；文本指纹覆盖 UTF-8 文本，不覆盖图片载荷。
 */
@FunctionalInterface
public interface PlanExecutionObserver {
    void onEvent(Event event);

    sealed interface Event permits PlanStarted, TaskEntered, TaskInputPrepared, ToolBatchReturned,
            TaskExited {
        String executionId();
        long elapsedNanos();
    }

    record PlanStarted(String executionId, long elapsedNanos, String planId,
                       TextFingerprint goal, List<TaskNode> tasks, List<String> executionOrder)
            implements Event {
        public PlanStarted {
            tasks = List.copyOf(tasks);
            executionOrder = List.copyOf(executionOrder);
        }
    }

    record TaskNode(String taskId, Task.TaskType type, TextFingerprint description,
                    List<String> dependencies) {
        public TaskNode {
            dependencies = List.copyOf(dependencies);
        }
    }

    /** Emitted inside the task runnable, never at submission/queue time. 中文：在任务执行体内部发出，不在提交或排队时发出。 */
    record TaskEntered(String executionId, long elapsedNanos, String taskId, long threadId)
            implements Event {}

    /** Input availability is observable here; it does not prove semantic consumption by a model. 中文：此处可观测输入可用性，但不能证明模型语义上使用了输入。 */
    record TaskInputPrepared(String executionId, long elapsedNanos, String taskId,
                             TextFingerprint userText, int imagePartCount,
                             List<DependencyInput> dependencies) implements Event {
        public TaskInputPrepared {
            dependencies = List.copyOf(dependencies);
        }
    }

    record DependencyInput(String taskId, Task.TaskStatus status, TextFingerprint result) {}

    /**
     * Correlate by executionId + taskId + iteration + ordinal, not callId alone: different task
     * conversations may legitimately reuse a provider callId. Results include policy denials.
     * 中文：按 executionId、taskId、iteration 和 ordinal 关联，不能仅用
     * callId；不同任务对话可合法复用供应商调用 ID，结果包含策略拒绝。
     */
    record ToolBatchReturned(String executionId, long elapsedNanos, String taskId, int iteration,
                             List<ToolResult> results) implements Event {
        public ToolBatchReturned {
            results = List.copyOf(results);
        }
    }

    record ToolResult(int ordinal, String callId, String name, TextFingerprint arguments,
                      TextFingerprint result, boolean successful, boolean timedOut,
                      int imagePartCount) {}

    /** RETURNED includes normal, partial-budget and cancellation text; it is not task success. 中文：RETURNED 包含正常、预算部分完成及取消文本，不等于任务成功。 */
    enum ExitKind { RETURNED, THREW }

    /** Emitted before the runnable completes, never when its Future is joined. 中文：在执行体完成之前发出，不在等待 Future 完成时发出。 */
    record TaskExited(String executionId, long elapsedNanos, String taskId, ExitKind kind,
                      TextFingerprint result, String exceptionType) implements Event {}

    /** Null and empty text remain distinct. Raw prompts, tool arguments and output are not stored. 中文：null 与空文本保持区分，不存储原始提示、工具参数或输出。 */
    record TextFingerprint(boolean present, int utf8Bytes, String sha256) {
        public TextFingerprint {
            if (utf8Bytes < 0 || present && (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                    || !present && (utf8Bytes != 0 || sha256 != null))
                throw new IllegalArgumentException("invalid text fingerprint");
        }
        public static TextFingerprint of(String text) {
            if (text == null) {
                return new TextFingerprint(false, 0, null);
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            try {
                return new TextFingerprint(true, bytes.length, HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }
}

package com.paicli.tool;

import java.util.List;
import java.util.Objects;

/**
 * Opt-in, in-process command diagnostics. Nothing is persisted by default.
 * Callbacks run on tool threads and must be fast and thread-safe. Commands and argv may
 * be sensitive; consumers own redaction/storage. These events are not OS audit evidence.
 * STARTED means ProcessBuilder.start succeeded; FINISHED means the tool invocation
 * returned, not that every descendant process has exited. No authorization is changed.
 * 中文：显式启用的进程内命令诊断，默认不持久化。回调在工具线程运行，须快速且线程安全。命令与参数可能敏感，由消费者脱敏和存储；事件不是操作
 * 系统审计证据。STARTED 表示进程启动成功，FINISHED 表示工具调用返回，不代表全部后代退出，且不改变授权。
 */
@FunctionalInterface
public interface CommandExecutionObserver {
    void onEvent(Event event);

    enum Phase { REJECTED, STARTED, FINISHED }
    enum Outcome { NONE, EMPTY_COMMAND, POLICY_DENIED, EXITED, TIMED_OUT, INTERRUPTED, FAILED }

    /** invocationId is local to one registry, independent of provider call IDs. 中文：invocationId 仅在一个注册表内有效，与供应商调用 ID 独立。 */
    record Event(long invocationId, Phase phase, String command, String workingDirectory,
                 List<String> arguments, long processId, long timestampMillis, Integer exitCode,
                 Outcome outcome, String resultSha256, int resultChars) {
        public Event {
            Objects.requireNonNull(phase); Objects.requireNonNull(outcome);
            Objects.requireNonNull(command); Objects.requireNonNull(workingDirectory);
            Objects.requireNonNull(resultSha256);
            arguments = List.copyOf(arguments);
            if (invocationId <= 0 || processId < 0 || timestampMillis <= 0 || resultChars < 0
                    || command.length() > 1_048_576 || workingDirectory.length() > 16_384
                    || arguments.size() > 16 || arguments.stream().anyMatch(a -> a.length() > 1_048_576))
                throw new IllegalArgumentException("invalid command observation bounds");
            if (phase == Phase.STARTED && (processId == 0 || arguments.isEmpty()
                    || outcome != Outcome.NONE || exitCode != null || resultChars != 0 || !resultSha256.isEmpty()))
                throw new IllegalArgumentException("invalid command start observation");
            if (phase == Phase.REJECTED && (processId != 0 || !arguments.isEmpty() || exitCode != null
                    || resultChars != 0 || !resultSha256.isEmpty()
                    || outcome != Outcome.EMPTY_COMMAND && outcome != Outcome.POLICY_DENIED))
                throw new IllegalArgumentException("invalid command rejection observation");
            if (phase == Phase.FINISHED && (!resultSha256.matches("[0-9a-f]{64}")
                    || outcome == Outcome.NONE || outcome == Outcome.EMPTY_COMMAND || outcome == Outcome.POLICY_DENIED
                    || outcome == Outcome.EXITED && (processId == 0 || exitCode == null)))
                throw new IllegalArgumentException("invalid command terminal observation");
        }
    }
}

package com.paicli.memory;

import com.paicli.llm.LlmClient;

/**
 * 运行时以 user 角色注入、但不是用户输入的消息：load_skill 的正文注入和 runaway guard 提醒。
 *
 * <p>压缩器按 user 消息切分轮次，必须先把这两类排除掉：否则一次 load_skill 就多出一个假轮次，
 * 挤掉真实的用户上下文；Skill 正文还会被喂给摘要模型压成一两句，甚至被记成用户要求。</p>
 */
public final class InternalUserMessages {

    /** Skill 注入消息的首行标记，由 {@code LoadedSkillMessages} 写入。 */
    public static final String SKILL_INJECTION_MARKER = "[PAICLI_SKILL_INJECTION]";
    /** {@code RunawayGuard} 提醒的固定前缀，提示词里也按这个前缀向模型解释。 */
    public static final String RUNAWAY_REMINDER_PREFIX = "[runaway guard]";

    private InternalUserMessages() {
    }

    public static boolean isSkillInjection(LlmClient.Message message) {
        return startsWith(message, SKILL_INJECTION_MARKER);
    }

    public static boolean isRuntimeReminder(LlmClient.Message message) {
        return startsWith(message, RUNAWAY_REMINDER_PREFIX);
    }

    /** 不应计入用户轮次、也不应交给摘要模型的内部消息。 */
    public static boolean isInternal(LlmClient.Message message) {
        return isSkillInjection(message) || isRuntimeReminder(message);
    }

    private static boolean startsWith(LlmClient.Message message, String prefix) {
        return message != null
                && "user".equals(message.role())
                && message.content() != null
                && message.content().startsWith(prefix);
    }
}

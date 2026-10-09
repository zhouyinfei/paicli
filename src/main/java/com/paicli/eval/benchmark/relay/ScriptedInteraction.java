package com.paicli.eval.benchmark.relay;

import java.io.IOException;
import java.util.List;

/** Host-only two-turn episode driver. No instance or private definition enters the Candidate. 中文：仅主机的双轮回合驱动器，实例和私有定义均不进入候选。 */
public interface ScriptedInteraction extends BenchmarkProviderRelay.MockMcpEndpoint {
    /** Host observation of the actual provider input/response, not Candidate-supplied audit JSON. 中文：主机观测的真实供应商输入响应，不是候选提供的审计 JSON。 */
    default void recordProviderTurn(int turn, List<BenchmarkRelayProtocol.WireMessage> messages,
                                    List<BenchmarkRelayProtocol.WireTool> tools,
                                    BenchmarkRelayProtocol.WireChatResponse response) throws IOException { }

    String nextUserMessage(String firstAnswer) throws IOException;

    Decision approve(String toolName, String argumentsJson) throws IOException;

    /** Newly completed native calls, after the relay matched every id/name/argument to upstream calls. 中文：中继逐一匹配上游调用 ID、名称和参数后，新完成的原生调用。 */
    void recordCompletedTools(List<BenchmarkRelayProtocol.WireToolExecution> tools) throws IOException;

    /** Private host audit of the ordered MCP, approval, and user-boundary frames (not LLM payloads). 中文：有序 MCP、审批与用户边界帧的私有主机审计，不包含 LLM 载荷。 */
    void recordExchange(int turn, BenchmarkRelayProtocol.Frame request, BenchmarkRelayProtocol.Frame response) throws IOException;

    record Decision(boolean approved, String reason) { }
}

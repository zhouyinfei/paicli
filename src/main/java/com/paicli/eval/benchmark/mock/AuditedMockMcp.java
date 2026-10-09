package com.paicli.eval.benchmark.mock;

import com.paicli.eval.benchmark.relay.BenchmarkProviderRelay;
import java.util.List;
import java.util.Map;

/** Host-owned evidence sources, never implemented or supplied by the Candidate. 中文：主机拥有的证据来源，不由候选实现或提供。 */
public sealed interface AuditedMockMcp extends BenchmarkProviderRelay.MockMcpEndpoint
        permits D1ToolSelectionMock, D2ReadOnlyJoinMock, D3ApprovalCalendarMock, F4PendingDeletionMock {
    String prompt();
    List<?> audit();
    int sideEffects();
    default Map<String, String> initialStateDigests() { return Map.of(); }
    default Map<String, String> stateDigests() { return Map.of(); }
}

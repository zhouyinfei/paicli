package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.formal.FormalBenchmarkAdmission;
import com.paicli.eval.benchmark.formal.FormalExecutionPlan;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** All-or-nothing preparation of the complete registered batch, with no provider calls. 中文：完整注册批次的全有或全无准备，不调用供应商。 */
public final class FormalBatchPreparation {
    private FormalBatchPreparation() {}

    public static ReadyBatch prepare(FormalBenchmarkAdmission.AdmittedBatch admission,
                                     CredentialSource credentials)
            throws IOException, FormalEpisodeRequestFactory.PreparationException {
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(credentials, "credentials");
        admission.verifyUnchanged();
        var plan = admission.plan();
        // Never launch the supported subset and silently omit the rest of the frozen suite.（中文：不得只启动受支持子集并静默省略冻结套件其余部分。）
        for (var episode : plan.episodes())
            FormalEpisodeRequestFactory.validateCapabilities(plan, episode);
        Map<String, FormalEpisodeRequestFactory.HostCredential> hostCredentials =
                new LinkedHashMap<>();
        for (var model : plan.formalBatchContract().models()) {
            var credential = credentials.resolve(model.provider());
            if (credential == null)
                throw new IOException("missing formal credential for " + model.provider());
            hostCredentials.put(model.provider(), credential);
        }
        List<FormalEpisodeRequestFactory.PreparedRequest> requests =
                new ArrayList<>(plan.formalBatchContract().expectedEpisodeCount());
        for (var episode : plan.episodes())
            requests.add(FormalEpisodeRequestFactory.create(plan, episode,
                    hostCredentials.get(episode.model().provider())));
        admission.verifyUnchanged();
        return new ReadyBatch(admission, requests);
    }

    @FunctionalInterface
    public interface CredentialSource {
        FormalEpisodeRequestFactory.HostCredential resolve(String provider) throws IOException;
    }

    /** Host-only capability for the executor. There is no public plan/JSON constructor. 中文：执行器的主机专属能力，不提供公开计划或 JSON 构造器。 */
    public static final class ReadyBatch {
        private final FormalBenchmarkAdmission.AdmittedBatch admission;
        private final List<FormalEpisodeRequestFactory.PreparedRequest> requests;

        private ReadyBatch(FormalBenchmarkAdmission.AdmittedBatch admission,
                           List<FormalEpisodeRequestFactory.PreparedRequest> requests) {
            this.admission = admission;
            this.requests = List.copyOf(requests);
            if (requests.size() != admission.plan().formalBatchContract().expectedEpisodeCount())
                throw new IllegalArgumentException("formal batch must retain every registered episode");
        }

        public String batchSha256() { return admission.batchSha256(); }
        public int episodeCount() { return requests.size(); }
        public int caseCount() { return admission.plan().cases().size(); }
        public boolean publishable() { return false; }

        FormalExecutionPlan plan() { return admission.plan(); }
        List<FormalEpisodeRequestFactory.PreparedRequest> requests() { return requests; }
        void verifyBeforeExecution() throws IOException { admission.verifyUnchanged(); }
        void requireSeparateOutput(java.nio.file.Path output) throws IOException {
            admission.requireSeparateOutput(output);
        }

        @Override public String toString() {
            return "ReadyBatch[batch=" + batchSha256() + ", cases=" + caseCount()
                    + ", episodes=" + episodeCount() + ", publishable=false]";
        }
    }
}

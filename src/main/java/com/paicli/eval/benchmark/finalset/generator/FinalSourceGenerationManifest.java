package com.paicli.eval.benchmark.finalset.generator;

import java.util.List;

/**
 * Deterministic, seed-redacted provenance for one private final-source materialization.
 *
 * <p>The manifest deliberately records only a one-way seed fingerprint. The private generation
 * seed and all generated source bytes remain below the owner-only destination root.</p>
 * 中文：单次私有最终来源物化的确定性且隐藏种子的来源记录。清单仅记录单向种子指纹，私有生成种子与全部来源字节保留在仅所有者可访问的目标根目
 * 录下。
 */
public record FinalSourceGenerationManifest(
        int manifestVersion,
        String format,
        String suiteId,
        String blueprintVersion,
        String seedFingerprint,
        boolean finalReady,
        int recipeCount,
        int implementedRecipeCount,
        List<String> missingCaseIds,
        String payloadTreeSha256,
        List<CaseRecipe> cases
) {
    public static final int CURRENT_VERSION = 3;
    public static final String FORMAT = "paicli-private-final-source-generation-v3";

    public FinalSourceGenerationManifest {
        missingCaseIds = List.copyOf(missingCaseIds);
        cases = List.copyOf(cases);
    }

    /** One blueprint-bound recipe and the generated sibling selected by the private seed. 中文：一个绑定蓝图的配方及私有种子选出的生成变体。 */
    public record CaseRecipe(
            String id,
            String title,
            String category,
            String level,
            int weight,
            String mode,
            String toolProfile,
            String verifierProfile,
            String recipeVersion,
            String implementationStatus,
            String executionMaturity,
            String runnerIntegrationStatus,
            boolean publicationEligible,
            String variantId,
            String variantTreeSha256,
            String publicPromptPath,
            String fixturePath,
            String verifierEntryPath,
            String privateOraclePath,
            String scoringContractPath,
            String caseContractPath,
            String caseContractSha256,
            String referenceWorkspacePath,
            String failClosedReason
    ) {
    }
}

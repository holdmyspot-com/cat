package io.github.cowwoc.cat.common.optimization.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests deterministic certification-contract validation and identity.
 */
public final class CertificationContractTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	/**
	 * Creates the test fixture.
	 */
	public CertificationContractTest()
	{
	}

	/**
	 * Verifies a fully traced contract freezes with a reproducible identity independent of input list order.
	 */
	@Test
	public void freezesCanonicalContract()
	{
		CertificationContract.Result first = CertificationContract.freeze(validDraft(SHA_A, false));
		CertificationContract.Result reordered = CertificationContract.freeze(validDraft(SHA_A, true));

		requireThat(first.status(), "status").isEqualTo(CertificationContract.Status.READY);
		requireThat(first.issues(), "issues").isEmpty();
		requireThat(first.contract().orElseThrow().fingerprint(), "fingerprint").isEqualTo(
			reordered.contract().orElseThrow().fingerprint());
	}

	/**
	 * Verifies prompt-test content changes create a different contract identity.
	 */
	@Test
	public void changesIdentityWithPromptTest()
	{
		String first = CertificationContract.freeze(validDraft(SHA_A, false)).contract().orElseThrow().fingerprint();
		String second = CertificationContract.freeze(validDraft(SHA_B, false)).contract().orElseThrow().fingerprint();

		requireThat(first, "firstFingerprint").isNotEqualTo(second);
	}

	/**
	 * Verifies missing source trace and family coverage block freezing and identify every defect.
	 */
	@Test
	public void blocksIncompleteContract()
	{
		CertificationContract.Draft draft = validDraft(SHA_A, false);
		CertificationContract.Requirement untraced = new CertificationContract.Requirement("req-untraced",
			"Explain the reason for every surprising decision.", List.of("missing-span"), List.of("always"), List.of());
		draft = new CertificationContract.Draft(draft.schemaVersion(), draft.artifacts(), draft.sourceSpans(),
			List.of(draft.requirements().get(0), untraced), draft.applicabilityRules(), draft.distributions(),
			draft.families(), draft.assertions(), draft.evaluatorProtocolIds(), draft.promptTests());

		CertificationContract.Result result = CertificationContract.freeze(draft);

		requireThat(result.status(), "status").isEqualTo(CertificationContract.Status.BLOCKED);
		requireThat(result.contract(), "contract").isEmpty();
		List<CertificationContract.IssueCode> issueCodes = result.issues().stream().
			map(CertificationContract.Issue::code).toList();
		requireThat(issueCodes.contains(CertificationContract.IssueCode.MISSING_SOURCE_SPAN),
			"missingSourceSpan").isTrue();
		requireThat(issueCodes.contains(CertificationContract.IssueCode.UNCOVERED_REQUIREMENT),
			"uncoveredRequirement").isTrue();
	}

	/**
	 * Verifies duplicate identifiers cannot silently replace one extraction result.
	 */
	@Test
	public void blocksDuplicateIdentifiers()
	{
		CertificationContract.Draft draft = validDraft(SHA_A, false);
		CertificationContract.Draft duplicated = new CertificationContract.Draft(draft.schemaVersion(),
			draft.artifacts(), draft.sourceSpans(), List.of(draft.requirements().get(0), draft.requirements().get(0)),
			draft.applicabilityRules(), draft.distributions(), draft.families(), draft.assertions(),
			draft.evaluatorProtocolIds(), draft.promptTests());

		CertificationContract.Result result = CertificationContract.freeze(duplicated);

		requireThat(result.status(), "status").isEqualTo(CertificationContract.Status.BLOCKED);
		List<CertificationContract.IssueCode> issueCodes = result.issues().stream().
			map(CertificationContract.Issue::code).toList();
		requireThat(issueCodes.contains(CertificationContract.IssueCode.DUPLICATE_IDENTIFIER),
			"duplicateIdentifier").isTrue();
	}

	/**
	 * Verifies an unknown contract schema is rejected rather than interpreted as the current revision.
	 */
	@Test
	public void rejectsUnknownSchemaVersion()
	{
		CertificationContract.Draft draft = validDraft(SHA_A, false);
		draft = new CertificationContract.Draft("certification-contract-v2", draft.artifacts(), draft.sourceSpans(),
			draft.requirements(), draft.applicabilityRules(), draft.distributions(), draft.families(), draft.assertions(),
			draft.evaluatorProtocolIds(), draft.promptTests());

		CertificationContract.Result result = CertificationContract.freeze(draft);

		requireThat(result.status(), "status").isEqualTo(CertificationContract.Status.BLOCKED);
		requireThat(result.issues().getFirst().code(), "issueCode").
			isEqualTo(CertificationContract.IssueCode.UNSUPPORTED_SCHEMA_VERSION);
	}

	/**
	 * Creates one valid contract draft.
	 *
	 * @param promptTestSha the prompt-test content fingerprint
	 * @param reverse whether to reverse independent input lists
	 * @return the draft
	 */
	static CertificationContract.Draft validDraft(String promptTestSha, boolean reverse)
	{
		CertificationContract.Artifact prompt = new CertificationContract.Artifact("artifact-prompt", "AGENTS.md", SHA_A);
		CertificationContract.SourceSpan span = new CertificationContract.SourceSpan("span-clear", "artifact-prompt",
			0, 42, "Explain surprising decisions clearly.");
		CertificationContract.ApplicabilityRule rule = new CertificationContract.ApplicabilityRule("always",
			"true", Set.of("executor-output"), Set.of(CertificationContract.ApplicabilityOutcome.APPLICABLE,
			CertificationContract.ApplicabilityOutcome.NOT_APPLICABLE,
			CertificationContract.ApplicabilityOutcome.INVALID_EVIDENCE));
		CertificationContract.Requirement requirement = new CertificationContract.Requirement("req-clear",
			"Explain surprising decisions clearly.", List.of("span-clear"), List.of("always"), List.of());
		CertificationContract.Distribution distribution = new CertificationContract.Distribution("dist-clear",
			"sampler-clear-v1", List.of("identifier", "wording"), List.of(), "clear-v1", "support-clear-v1");
		CertificationContract.Assertion assertion = new CertificationContract.Assertion("assert-clear", "req-clear",
			CertificationContract.AssertionClassification.SEMANTIC, "The output explains every surprising decision.",
			Set.of("executor-output"), "judge-clear-v1",
			CertificationContract.InfrastructureFailureRule.EXCLUDE_FROM_FORMAL_OUTCOMES);
		CertificationContract.LeafFamily family = new CertificationContract.LeafFamily("family-clear", null,
			List.of("req-clear"), List.of("always"), "Explanations of surprising implementation decisions.",
			"dist-clear", "generator-clear-v1", List.of("assert-clear"));
		CertificationContract.PromptTest promptTest = new CertificationContract.PromptTest("test-clear",
			".cat/tests/clear.yaml", promptTestSha);
		List<String> protocols = List.of("judge-clear-v1", "unused-v1");
		if (reverse)
			protocols = List.of("unused-v1", "judge-clear-v1");
		return new CertificationContract.Draft("certification-contract-v1", List.of(prompt), List.of(span),
			List.of(requirement), List.of(rule), List.of(distribution), List.of(family), List.of(assertion), protocols,
			List.of(promptTest));
	}
}

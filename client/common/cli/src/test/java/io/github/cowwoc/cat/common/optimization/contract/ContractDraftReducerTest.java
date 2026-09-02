package io.github.cowwoc.cat.common.optimization.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests monotonic whole-contract reduction across independent extraction agents.
 */
public final class ContractDraftReducerTest
{
	private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	/**
	 * Creates the test fixture.
	 */
	public ContractDraftReducerTest()
	{
	}

	/**
	 * Verifies a unique challenger artifact is retained without deleting the primary extraction.
	 */
	@Test
	public void preservesUniqueRecordsFromBothAgents()
	{
		CertificationContract.Draft primary = CertificationContractTest.validDraft(SHA_A, false);
		List<CertificationContract.Artifact> artifacts = new ArrayList<>(primary.artifacts());
		artifacts.add(new CertificationContract.Artifact("artifact-context", "CLAUDE.md", SHA_B));
		CertificationContract.Draft challenger = replaceArtifacts(primary, artifacts);

		ContractDraftReducer.Result result = ContractDraftReducer.merge(primary, challenger, List.of(), 1);

		requireThat(result.status(), "status").isEqualTo(ContractDraftReducer.Status.READY);
		requireThat(result.contract().orElseThrow().content().artifacts().size(), "artifactCount").isEqualTo(2);
	}

	/**
	 * Verifies competing content under one identifier is named and blocks automatic freezing.
	 */
	@Test
	public void blocksConflictingRecordIdentity()
	{
		CertificationContract.Draft primary = CertificationContractTest.validDraft(SHA_A, false);
		CertificationContract.Artifact conflicting = new CertificationContract.Artifact("artifact-prompt", "AGENTS.md",
			SHA_B);
		CertificationContract.Draft challenger = replaceArtifacts(primary, List.of(conflicting));

		ContractDraftReducer.Result result = ContractDraftReducer.merge(primary, challenger, List.of(), 1);

		requireThat(result.status(), "status").isEqualTo(ContractDraftReducer.Status.BLOCKED);
		requireThat(result.conflicts().getFirst().kind(), "kind").isEqualTo(ContractDraftReducer.RecordKind.ARTIFACT);
		requireThat(result.conflicts().getFirst().identifier(), "identifier").isEqualTo("artifact-prompt");
	}

	/**
	 * Replaces a draft's artifact inventory.
	 *
	 * @param source the source draft
	 * @param artifacts the replacement artifacts
	 * @return the changed draft
	 */
	private static CertificationContract.Draft replaceArtifacts(CertificationContract.Draft source,
		List<CertificationContract.Artifact> artifacts)
	{
		return new CertificationContract.Draft(source.schemaVersion(), artifacts, source.sourceSpans(),
			source.requirements(), source.applicabilityRules(), source.distributions(), source.families(),
			source.assertions(), source.evaluatorProtocolIds(), source.promptTests());
	}
}

package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests output gate selection and tree resolution using captured replay decisions.
 */
public final class GateSelectionTest
{
	/** Creates the test fixture. */
	public GateSelectionTest()
	{
	}

	/**
	 * Verifies all or selected gate targets retain witnessed order, result trees, and the supplied command.
	 *
	 * @throws IOException if a retained output cannot be selected
	 */
	@Test
	public void selectsWitnessedOutputTargets() throws IOException
	{
		List<OutputWitness> witnesses = List.of(new OutputWitness("first", "base", "tree-1"),
			new OutputWitness("second", "tree-1", "tree-2"));
		List<String> command = List.of("build", "--verify");
		OutputGates.Target first = new OutputGates.Target("first", "tree-1", command);
		OutputGates.Target second = new OutputGates.Target("second", "tree-2", command);

		requireThat(GitTopicReplayEditor.selectOutputTargets(witnesses, null, command), "allTargets").isEqualTo(
			List.of(first, second));
		requireThat(GitTopicReplayEditor.selectOutputTargets(witnesses, "first", command), "selectedTarget").isEqualTo(
			List.of(first));
	}

	/**
	 * Verifies an unknown retained output receives the existing gate selection diagnostic.
	 *
	 * @throws IOException for the expected missing output
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*cannot find retained output missing in the sealed manifest.*")
	public void rejectsUnknownOutputTarget() throws IOException
	{
		GitTopicReplayEditor.selectOutputTargets(List.of(new OutputWitness("first", "base", "tree")), "missing",
			List.of("build"));
	}

	/** Verifies all, selected, and unknown acceptance selections retain their existing behavior. */
	@Test
	public void selectsAcceptanceCandidates()
	{
		SemanticCandidate first = candidate("first");
		SemanticCandidate second = candidate("second");
		List<SemanticCandidate> candidates = List.of(first, second);

		requireThat(DeclaredOutputAcceptance.selectCandidates(candidates, null), "allCandidates").isEqualTo(candidates);
		requireThat(DeclaredOutputAcceptance.selectCandidates(candidates, "second"), "selectedCandidate").isEqualTo(
			List.of(second));
		requireThat(DeclaredOutputAcceptance.selectCandidates(candidates, "missing"), "unknownSelection").isEmpty();
	}

	/**
	 * Verifies acceptance targets use their retained or proposed tree and the exact reviewer-declared command.
	 *
	 * @throws IOException if a captured tree cannot be resolved
	 */
	@Test
	public void resolvesAcceptanceTrees() throws IOException
	{
		SemanticCandidate candidate = candidate("first");
		requireThat(DeclaredOutputAcceptance.target(candidate,
			Map.of("first", new OutputWitness("first", "base", "sealed"))), "sealedTarget").isEqualTo(
			new OutputGates.Target("first", "sealed", candidate.acceptanceCommand()));
		requireThat(DraftOutputAcceptance.target(candidate, Map.of("first", "draft")), "draftTarget").isEqualTo(
			new OutputGates.Target("first", "draft", candidate.acceptanceCommand()));
	}

	/**
	 * Verifies declared acceptance rejects a missing retained witness.
	 *
	 * @throws IOException for the expected missing witness
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*cannot find retained output first in the sealed manifest.*")
	public void rejectsMissingAcceptanceWitness() throws IOException
	{
		DeclaredOutputAcceptance.target(candidate("first"), Map.of());
	}

	/**
	 * Verifies draft acceptance rejects a candidate without a proposed retained tree.
	 *
	 * @throws IOException for the expected missing tree
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*draft acceptance cannot find a retained tree for first.*")
	public void rejectsMissingDraftTree() throws IOException
	{
		DraftOutputAcceptance.target(candidate("first"), Map.of());
	}

	/**
	 * Verifies the command fixture excludes an unknown mode at its raw argument boundary.
	 *
	 * @throws IOException if an unknown mode unexpectedly performs filesystem work
	 * @throws InterruptedException if an unknown mode unexpectedly starts waiting
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsUnknownCommandMode() throws IOException, InterruptedException
	{
		GateCommandFixture.main(new String[] {"UNKNOWN"});
	}

	/**
	 * Supplies a reviewer-authored candidate with one exact command.
	 *
	 * @param output the selected logical output
	 * @return the captured acceptance declaration
	 */
	private static SemanticCandidate candidate(String output)
	{
		return new SemanticCandidate(output, "observable " + output, "reviewed behavior", "separately usable",
			List.of(output), output, List.of(), List.of("verify", output), List.of(), List.of(), List.of(), "reviewed");
	}
}

package com.holdmyspot.cat.common.git.replay.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.git.replay.CandidateUniverseEvidence;
import com.holdmyspot.cat.common.git.replay.CombinedCandidateBlocker;
import com.holdmyspot.cat.common.git.replay.History;
import com.holdmyspot.cat.common.git.replay.PatchArtifacts;
import com.holdmyspot.cat.common.git.replay.RejectedEarlierOutput;
import com.holdmyspot.cat.common.git.replay.ReplayJson;
import com.holdmyspot.cat.common.git.replay.ReviewEvidence;
import com.holdmyspot.cat.common.git.replay.SemanticCandidate;
import com.holdmyspot.cat.common.git.replay.SemanticEvidence;
import com.holdmyspot.cat.common.git.replay.SemanticPlanValidation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests semantic readiness and completed placement policy without reconstructing Git history.
 */
public final class SemanticPlanTest
{
	/** Creates the test fixture. */
	public SemanticPlanTest()
	{
	}

	/**
	 * Verifies resolved challenges and a complete plan matching its ledger satisfy semantic readiness.
	 *
	 * @throws IOException if valid reviewer evidence cannot be written or accepted
	 */
	@Test
	public void acceptsCompletedSemanticPlan() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "semantic-ready-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			ReviewEvidence review = new ReviewEvidence(ReplayJson.newMapper());
			ReplayJson.newMapper().writeValue(review.semanticPlanFile(manifest).toFile(), SemanticPlanFixtures.candidates());
			ReplayJson.newMapper().writeValue(review.semanticPlacementLedgerFile(manifest).toFile(),
				SemanticPlanFixtures.candidates());
			Files.writeString(review.reviewPacketFile(manifest), "Captured artifacts and behaviors reviewed\n");
			Files.writeString(review.challengeLedgerFile(manifest),
				"[{\"operation\":\"add\",\"status\":\"resolved\",\"evidence\":\"Counterfactual checked\"}]\n");

			review.validateReplayReadiness(manifest);
			semanticEvidence(directory, review).validatePlan(manifest, SemanticPlanFixtures.manifest(), evidence());
		}
	}

	/**
	 * Verifies an open, supported challenge prevents replay readiness.
	 *
	 * @throws IOException for the expected unresolved challenge
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*must resolve every challenge.*")
	public void rejectsUnresolvedChallenges() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "semantic-challenge-"));
			Path packet = directory.resolve("review.md");
			Files.writeString(packet, "Captured artifacts reviewed\n");
			Path ledger = directory.resolve("challenges.json");
			Files.writeString(ledger,
				"[{\"operation\":\"split\",\"status\":\"open\",\"evidence\":\"Independent behavior still needs review\"}]\n");

			new ReviewEvidence(ReplayJson.newMapper()).validateReplayReadiness(packet, ledger);
		}
	}

	/**
	 * Verifies a candidate cannot omit the behavior-first discovery basis.
	 *
	 * @throws IOException for the expected incomplete candidate
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*behavior-first discovery basis.*")
	public void rejectsMissingDiscovery() throws IOException
	{
		SemanticCandidate original = SemanticPlanFixtures.candidates().getFirst();
		SemanticCandidate changed = copy(original, "", original.splitCounterfactual(), original.candidateUniverseIds(),
			original.rejectedEarlierOutputs(), original.combinedCandidateBlockers(), original.evidence());
		validate(replaceFirst(changed), evidence());
	}

	/**
	 * Verifies a reviewer-named semantic candidate identifier must be snake_case.
	 *
	 * @throws IOException for the expected identifier rejection
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		"Semantic candidate id \"feature-a\" must be snake_case.*such as \"feature_a\".*semantic-plan\\.json")
	public void rejectsNonSnakeCaseCandidateId() throws IOException
	{
		SemanticCandidate original = SemanticPlanFixtures.candidates().getFirst();
		SemanticCandidate renamed = new SemanticCandidate("feature-a", original.observableOutcome(),
			original.candidateDiscovery(), original.splitCounterfactual(), original.candidateUniverseIds(),
			original.output(), original.units(), original.acceptanceCommand(), original.prerequisites(),
			original.rejectedEarlierOutputs(), original.combinedCandidateBlockers(), original.evidence());
		validate(replaceFirst(renamed), evidence());
	}

	/**
	 * Verifies a candidate cannot omit its split counterfactual.
	 *
	 * @throws IOException for the expected incomplete candidate
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*split counterfactual.*")
	public void rejectsMissingSplitCounterfactual() throws IOException
	{
		SemanticCandidate original = SemanticPlanFixtures.candidates().getFirst();
		SemanticCandidate changed = copy(original, original.candidateDiscovery(), "", original.candidateUniverseIds(),
			original.rejectedEarlierOutputs(), original.combinedCandidateBlockers(), original.evidence());
		validate(replaceFirst(changed), evidence());
	}

	/**
	 * Verifies complete artifact placement cannot omit an independently discovered reviewer behavior.
	 *
	 * @throws IOException for the expected omitted reviewer record
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*must account for every source-neutral reviewer discovery record.*")
	public void rejectsOmittedReviewerCandidate() throws IOException
	{
		validate(SemanticPlanFixtures.candidates(), SemanticPlanFixtures.evidence(
			Set.of("reviewer_a", "reviewer_b", "reviewer_c", "reviewer_omitted")));
	}

	/**
	 * Verifies a later output must explain why every earlier retained output cannot own its behavior.
	 *
	 * @throws IOException for the expected missing earlier-output rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*must reject every earlier retained output.*")
	public void rejectsUnreviewedEarlierOutput() throws IOException
	{
		List<SemanticCandidate> candidates = new ArrayList<>(SemanticPlanFixtures.candidates());
		SemanticCandidate note = candidates.getLast();
		candidates.set(2, copy(note, note.candidateDiscovery(), note.splitCounterfactual(), note.candidateUniverseIds(),
			List.of(), note.combinedCandidateBlockers(), note.evidence()));
		validate(candidates, evidence());
	}

	/**
	 * Verifies two behavior candidates sharing one output retain their concrete split blocker.
	 *
	 * @throws IOException for the expected missing split blocker
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*require a split blocker.*")
	public void rejectsMissingCombinedBlocker() throws IOException
	{
		SemanticCandidate original = SemanticPlanFixtures.candidates().getFirst();
		SemanticCandidate changed = copy(original, original.candidateDiscovery(), original.splitCounterfactual(),
			original.candidateUniverseIds(), original.rejectedEarlierOutputs(), List.of(), original.evidence());
		validate(replaceFirst(changed), evidence());
	}

	/**
	 * Verifies individually valid plans and ledgers must still reproduce the same reviewer decisions.
	 *
	 * @throws IOException for the expected plan/ledger divergence
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*must exactly reproduce the declared semantic-placement ledger.*")
	public void rejectsPlanLedgerDivergence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "semantic-ledger-"));
			Path manifest = directory.resolve("manifest.json");
			Files.writeString(manifest, "{}\n");
			ReviewEvidence review = new ReviewEvidence(ReplayJson.newMapper());
			ReplayJson.newMapper().writeValue(review.semanticPlacementLedgerFile(manifest).toFile(),
				SemanticPlanFixtures.candidates());
			SemanticCandidate original = SemanticPlanFixtures.candidates().getFirst();
			SemanticCandidate changed = copy(original, original.candidateDiscovery(), original.splitCounterfactual(),
				original.candidateUniverseIds(), original.rejectedEarlierOutputs(), original.combinedCandidateBlockers(),
				"Different documented evidence");
			ReplayJson.newMapper().writeValue(review.semanticPlanFile(manifest).toFile(), replaceFirst(changed));

			semanticEvidence(directory, review).validatePlan(manifest, SemanticPlanFixtures.manifest(), evidence());
		}
	}

	/**
	 * Validates captured candidates through their responsible production policy.
	 *
	 * @param candidates the reviewer decisions
	 * @param evidence the validated discovery facts
	 * @throws IOException if the decisions contradict semantic policy
	 */
	private static void validate(List<SemanticCandidate> candidates, CandidateUniverseEvidence evidence)
		throws IOException
	{
		new SemanticPlanValidation(new ReviewEvidence(ReplayJson.newMapper())).validate(SemanticPlanFixtures.manifest(),
			candidates, Set.of("first", "second"), true, Path.of("semantic-plan.json"), evidence);
	}

	/**
	 * Supplies validated reviewer discovery for the three retained behaviors.
	 *
	 * @return the captured reviewer facts
	 */
	private static CandidateUniverseEvidence evidence()
	{
		return SemanticPlanFixtures.evidence(Set.of("reviewer_a", "reviewer_b", "reviewer_c"));
	}

	/**
	 * Creates the semantic artifact owner from the existing policy and repository boundaries.
	 *
	 * @param directory the test-owned directory
	 * @param review the reviewer-artifact owner
	 * @return the semantic owner
	 */
	private static SemanticEvidence semanticEvidence(Path directory, ReviewEvidence review)
	{
		return new SemanticEvidence(new History(directory, new Git(directory)), new PatchArtifacts(), review,
			new SemanticPlanValidation(review));
	}

	/**
	 * Replaces one fixture decision while retaining all other independently declared candidates.
	 *
	 * @param first the replacement first candidate
	 * @return the candidate list
	 */
	private static List<SemanticCandidate> replaceFirst(SemanticCandidate first)
	{
		List<SemanticCandidate> candidates = SemanticPlanFixtures.candidates();
		return List.of(first, candidates.get(1), candidates.getLast());
	}

	/**
	 * Copies a candidate with the review fields selected by a policy scenario.
	 *
	 * @param source the captured candidate
	 * @param discovery the behavior-first discovery basis
	 * @param counterfactual the split counterfactual
	 * @param reviewerIds the cited discovery records
	 * @param rejections the rejected earlier outputs
	 * @param blockers the combined-output split blockers
	 * @param evidence the candidate-specific evidence
	 * @return the edited candidate
	 */
	private static SemanticCandidate copy(SemanticCandidate source, String discovery, String counterfactual,
		List<String> reviewerIds, List<RejectedEarlierOutput> rejections, List<CombinedCandidateBlocker> blockers,
		String evidence)
	{
		return new SemanticCandidate(source.id(), source.observableOutcome(), discovery, counterfactual, reviewerIds,
			source.output(), source.units(), source.acceptanceCommand(), source.prerequisites(), rejections, blockers,
			evidence);
	}
}

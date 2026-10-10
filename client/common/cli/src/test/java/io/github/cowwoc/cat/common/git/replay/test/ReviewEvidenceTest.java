package io.github.cowwoc.cat.common.git.replay.test;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.replay.ActionResultMatrix;
import io.github.cowwoc.cat.common.git.replay.CandidateUniverse;
import io.github.cowwoc.cat.common.git.replay.CandidateUniverseEvidence;
import io.github.cowwoc.cat.common.git.replay.CandidateUniverseRequirements;
import io.github.cowwoc.cat.common.git.replay.ReplayJson;
import io.github.cowwoc.cat.common.git.replay.ReviewEvidence;
import io.github.cowwoc.cat.common.git.replay.SemanticCandidate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Tests reviewer-evidence artifact reading for topic replay.
 */
public final class ReviewEvidenceTest
{
	/**
	 * Creates the test fixture.
	 */
	public ReviewEvidenceTest()
	{
	}

	/**
	 * Verifies a missing semantic-candidate artifact cannot enter review validation.
	 *
	 * @throws IOException if the isolated fixture root cannot be created
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*requires an auditable semantic plan.*")
	public void rejectsMissingSemanticCandidateArtifact() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(),
				"topic-replay-review-evidence-"));
			new ReviewEvidence(ReplayJson.newMapper()).readSemanticCandidates(
				root.resolve("semantic-plan.json"), "semantic plan");
		}
	}

	/**
	 * Verifies a blank packet cannot authorize an otherwise resolved challenge ledger.
	 *
	 * @throws IOException if the isolated fixture root cannot be created or its evidence cannot be written
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*requires a non-empty review packet.*")
	public void rejectsBlankReviewPacket() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(),
				"topic-replay-review-evidence-"));
			Path packet = root.resolve("review-packet.md");
			Path ledger = root.resolve("adversarial-challenges.json");
			Files.writeString(packet, " \n\t");
			Files.writeString(ledger, "[{\"operation\":\"add\",\"status\":\"resolved\",\"evidence\":\"review\"}]");

			new ReviewEvidence(ReplayJson.newMapper()).validateReplayReadiness(packet, ledger);
		}
	}

	/**
	 * Verifies a planner challenge cannot request an unsupported operation even when it is marked resolved.
	 *
	 * @throws IOException if the isolated fixture root cannot be created or its evidence cannot be written
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*may only request an evidence-backed add or split.*")
	public void rejectsUnsupportedChallenge() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(),
				"topic-replay-review-evidence-"));
			Path packet = root.resolve("review-packet.md");
			Path ledger = root.resolve("adversarial-challenges.json");
			Files.writeString(packet, "reviewed inventory\n");
			Files.writeString(ledger, "[{\"operation\":\"merge\",\"status\":\"resolved\",\"evidence\":\"review\"}]");

			new ReviewEvidence(ReplayJson.newMapper()).validateReplayReadiness(packet, ledger);
		}
	}

	/**
	 * Verifies a discovery record is rejected unless its terminal pass re-examines every candidate and finds none.
	 *
	 * @throws IOException if the isolated fixture root cannot be created or its evidence cannot be written
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*discovery evidence must end with a complete pass.*")
	public void rejectsIncompleteDiscoveryTerminalPass() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(),
				"topic-replay-review-evidence-"));
			Path evidence = root.resolve("passes.json");
			Files.writeString(evidence, """
				{"passes":[
				  {"number":1,"examined_candidate_ids":["candidate_a"],"new_candidate_ids":["candidate_a"]},
				  {"number":2,"examined_candidate_ids":[],"new_candidate_ids":[]}
				]}
				""");

			new ReviewEvidence(ReplayJson.newMapper()).validateCandidateDiscoveryPasses(evidence,
				Set.of("candidate_a"), "topic reviewer");
		}
	}

	/**
	 * Verifies an action-result matrix cannot omit a candidate from its observable-behavior rows.
	 *
	 * @throws IOException if the isolated fixture root cannot be created or its evidence cannot be written
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*action-result matrix must cover every candidate.*")
	public void rejectsMatrixMissingCandidateRow() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(),
				"topic-replay-review-evidence-"));
			Path matrix = root.resolve("action-result-matrix.json");
			Files.writeString(matrix, """
				{"rows":[
				  {"candidate_id":"candidate_a","observable_behavior":"behavior-a","observable_result":"result-a",
				   "acceptance_check":"check-a","required_artifacts":["artifact-a"],"deferred_artifacts":[]}
				],"pairs":[]}
				""");

			new ReviewEvidence(ReplayJson.newMapper()).validateActionResultMatrix(matrix,
				Set.of("candidate_a", "candidate_b"), "topic reviewer");
		}
	}

	/**
	 * Verifies the candidate universe cannot omit a captured artifact.
	 *
	 * @throws IOException if the isolated fixture root cannot be created
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Candidate universe must cover every captured artifact.*")
	public void rejectsUniverseMissingInventoryArtifact() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(),
				"topic-replay-review-evidence-"));
			CandidateUniverseRequirements requirements = new CandidateUniverseRequirements(Set.of("inventory-a"),
				List.of(), Set.of(), false);
			CandidateUniverse universe = new CandidateUniverse("topic reviewer", List.of(), List.of());

			new ReviewEvidence(ReplayJson.newMapper()).validateCandidateUniverse(requirements,
				universe, root.resolve("candidate-universe.json"));
		}
	}

	/**
	 * Verifies a final semantic candidate cannot omit the topic reviewer's discovery record.
	 *
	 * @throws IOException if the isolated fixture root cannot be created
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*must cite a source-neutral reviewer discovery record.*")
	public void rejectsCandidateWithoutReviewerDiscovery() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(),
				"topic-replay-review-evidence-"));
			SemanticCandidate candidate = new SemanticCandidate("candidate_a", "outcome", "discovery", "split",
				List.of("unknown"), "output_a", List.of(), List.of(), List.of(), List.of(), List.of(), "evidence");
			CandidateUniverseEvidence evidence = new CandidateUniverseEvidence(Set.of("reviewer_a"),
				new ActionResultMatrix(List.of(), List.of()));

			new ReviewEvidence(ReplayJson.newMapper()).validateCandidateUniverseLinks(
				List.of(candidate), evidence, root.resolve("semantic-plan.json"));
		}
	}
}

package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests reviewer-evidence artifact reading for topic replay.
 */
public final class ReviewEvidenceTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public ReviewEvidenceTest()
	{
	}

	/**
	 * Closes the operation scope after this test class finishes.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
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
			new ReviewEvidence(scope.json()).readSemanticCandidates(
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

			new ReviewEvidence(scope.json()).validateReplayReadiness(packet, ledger);
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

			new ReviewEvidence(scope.json()).validateReplayReadiness(packet, ledger);
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
				  {"number":1,"examinedCandidateIds":["candidate-a"],"newCandidateIds":["candidate-a"]},
				  {"number":2,"examinedCandidateIds":[],"newCandidateIds":[]}
				]}
				""");

			new ReviewEvidence(scope.json()).validateCandidateDiscoveryPasses(evidence,
				Set.of("candidate-a"), "topic reviewer");
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
				  {"candidateId":"candidate-a","observableBehavior":"behavior-a","observableResult":"result-a",
				   "acceptanceCheck":"check-a","requiredArtifacts":["artifact-a"],"deferredArtifacts":[]}
				],"pairs":[]}
				""");

			new ReviewEvidence(scope.json()).validateActionResultMatrix(matrix,
				Set.of("candidate-a", "candidate-b"), "topic reviewer");
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

			new ReviewEvidence(scope.json()).validateCandidateUniverse(requirements,
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
			SemanticCandidate candidate = new SemanticCandidate("candidate-a", "outcome", "discovery", "split",
				List.of("unknown"), "output-a", List.of(), List.of(), List.of(), List.of(), List.of(), "evidence");
			CandidateUniverseEvidence evidence = new CandidateUniverseEvidence(Set.of("reviewer-a"),
				new ActionResultMatrix(List.of(), List.of()));

			new ReviewEvidence(scope.json()).validateCandidateUniverseLinks(
				List.of(candidate), evidence, root.resolve("semantic-plan.json"));
		}
	}
}

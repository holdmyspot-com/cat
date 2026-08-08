package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.commit;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.invocationDirectory;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.invocationManifest;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayCandidateUniverseFixtures.recordCandidateUniverseEvidence;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayActionResultFixtures.write;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayAttestations.recordIndependentReviewAttestation;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayReviewEvidenceFixtures.recordReviewEvidence;
import static io.github.cowwoc.cat.common.git.replay.TopicReplaySemanticPlacementFixtures.recordSemanticPlacementLedger;

import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests {@link GitTopicReplayEditor} command behavior.
 */
public final class EditorTest
{
	/**
	 * Creates the test fixture.
	 */
	public EditorTest()
	{
	}

	/**
	 * Verifies reviewers can place multiple source commits in one independently declared topic output.
	 *
	 * @throws IOException if the fixture or replay operations cannot be created
	 */
	@Test
	public void combinesSourcesInOneTopicOutput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			String implementation = commit(git, repository, "feature.txt", "implementation\n", "implementation");
			String documentation = commit(git, repository, "README.md", "documentation\n", "documentation");
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, documentation, manifest);
			Path outputs = replay.resolve("topic-outputs.json");
			Files.writeString(outputs, "[{\"id\":\"feature\",\"subject\":\"feature: add feature\"}]\n");
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject missing independent candidate evidence");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("candidate universe");
			}
			recordCandidateUniverseEvidence(manifest);
			Path reviewerUniverse = manifest.resolveSibling(manifest.getFileName() +
				".independent-candidate-universe.json");
			String firstSource = git.run("rev-parse", implementation).trim();
			String sourceBoundUniverse = Files.readString(reviewerUniverse).replace(
				"independent fixture raw evidence 0", "Raw diff for source " + firstSource);
			Files.writeString(reviewerUniverse, sourceBoundUniverse);
			recordIndependentReviewAttestation(manifest);
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject source-bound candidate discovery evidence");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("source-neutral discovery evidence");
			}
			recordCandidateUniverseEvidence(manifest);
			Path reviewerPasses = manifest.resolveSibling(manifest.getFileName() +
				".independent-candidate-discovery-passes.json");
			Files.delete(reviewerPasses);
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject missing fixed-point discovery evidence");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("candidate-discovery passes");
			}
			recordCandidateUniverseEvidence(manifest);
			Files.writeString(reviewerPasses, Files.readString(reviewerPasses).replace("\"newCandidateIds\":[]",
				"\"newCandidateIds\":[\"independent-0\"]"));
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject a discovery pass that finds another candidate");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("finds no new candidate");
			}
			recordCandidateUniverseEvidence(manifest);
			Path reviewerMatrix = manifest.resolveSibling(manifest.getFileName() +
				".independent-action-result-matrix.json");
			Files.delete(reviewerMatrix);
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject a missing observable-behavior matrix");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("independent-action-result-matrix");
			}
			recordCandidateUniverseEvidence(manifest);
			String incompleteMatrix = Files.readString(reviewerMatrix);
			int pairs = incompleteMatrix.indexOf("\"pairs\":[");
			Files.writeString(reviewerMatrix, incompleteMatrix.substring(0, pairs) + "\"pairs\":[]}\n");
			recordIndependentReviewAttestation(manifest);
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject incomplete observable-behavior pair evidence");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("assess every candidate pair");
			}
			recordCandidateUniverseEvidence(manifest);
			Files.delete(manifest.resolveSibling(manifest.getFileName() + ".independent-review-attestation.json"));
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject missing independent-review handoff evidence");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("independent-review attestation");
			}
			recordCandidateUniverseEvidence(manifest);
			Files.delete(manifest.resolveSibling(manifest.getFileName() + ".scope-attestation.json"));
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject missing scope evidence");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("scope attestation");
			}
			recordCandidateUniverseEvidence(manifest);

			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject a missing candidate inventory");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("semantic-placement ledger");
			}
			recordSemanticPlacementLedger(manifest, List.of("feature"), Map.of(
				implementation + ":1", "feature", documentation + ":1", "feature"));
			Path incompleteLedger = manifest.resolveSibling(manifest.getFileName() + ".semantic-placement-ledger.json");
			Files.writeString(incompleteLedger, Files.readString(incompleteLedger).replace(
				"\"acceptanceCommand\":[\"/bin/sh\",\"-c\",\"test -f feature.txt\"],", ""));
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject a missing runnable acceptance command");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("runnable acceptance command");
			}
			recordSemanticPlacementLedger(manifest, List.of("feature"), Map.of(
				implementation + ":1", "feature", documentation + ":1", "feature"));
			write(manifest, "planner", "planner-", 2, "separate");
			write(manifest, "independent", "independent-", 2, "separate");
			recordIndependentReviewAttestation(manifest);
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject an observable-behavior pair kept together");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("topic-reviewer matrix");
			}
			recordCandidateUniverseEvidence(manifest);
			Path ledger = manifest.resolveSibling(manifest.getFileName() + ".semantic-placement-ledger.json");
			Files.writeString(ledger, Files.readString(ledger).replace("\"independent-0\"", "\"unknown-candidate\""));
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject an unlinked semantic candidate");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("source-neutral reviewer discovery record");
			}
			recordSemanticPlacementLedger(manifest, List.of("feature"), Map.of(
				implementation + ":1", "feature", documentation + ":1", "feature"));
			editor.declareTopicOutputs(manifest, outputs);
			editor.bootstrapPreservedOutputBatches(manifest);
			Path reviews = replay.resolve("source-reviews.json");
			Files.writeString(reviews, """
				[{"source":"%s","disposition":"whole","destinations":["feature"],
				"counterfactual":"The implementation is part of the feature.",
				"evidence":"The feature requires its implementation."},
				{"source":"%s","disposition":"whole","destinations":["feature"],
				"counterfactual":"The documentation completes the same feature.",
				"evidence":"The feature requires its documentation."}]
				""".formatted(implementation, documentation));
			editor.recordSourceReviews(manifest, reviews);
			Path originReviews = replay.resolve("origin-reviews.json");
			Files.writeString(originReviews, "[]\n");
			editor.recordOriginReviews(manifest, originReviews);
			Path plan = replay.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"outputId":"feature","disposition":"retained","batches":[
				{"id":"implementation","source":"%s","patch":"patches/%s.raw.patch"},
				{"id":"documentation","source":"%s","patch":"patches/%s.raw.patch"}]}]
				""".formatted(implementation, implementation, documentation, documentation));

			editor.sealOutputPlan(manifest, plan);
			editor.recordPlannedOutput(manifest, "feature");
			recordReviewEvidence(manifest);
			editor.replay(manifest);

			requireThat(git.run("log", "-1", "--format=%s").trim(), "subject").isEqualTo("feature: add feature");
			requireThat(git.run("rev-parse", "HEAD^").trim(), "replacementParent").isEqualTo(base);
		}
	}

	/**
	 * Verifies topic-output declarations reject a subject that does not identify a supported change type.
	 *
	 * @throws IOException if the fixture repository or review evidence cannot be created
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*type: summary.*")
	public void rejectsUntypedTopicOutputSubject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			String tip = commit(git, repository, "feature.txt", "feature\n", "add feature");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, tip, manifest);
			recordCandidateUniverseEvidence(manifest);
			recordSemanticPlacementLedger(manifest, List.of("feature"), Map.of());
			Path outputs = manifest.getParent().resolve("topic-outputs.json");
			Files.writeString(outputs, "[{\"id\":\"feature\",\"subject\":\"add feature\"}]\n");

			editor.declareTopicOutputs(manifest, outputs);
		}
	}

	/**
	 * Verifies reconciliation cannot collapse an independent reviewer's separate behavior pair.
	 *
	 * @throws IOException if the fixture repository or replay evidence cannot be created
	 */
	@Test
	public void rejectsSeparateCandidateCollapse() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository, "codex.txt", "Codex integration\n", "Add Codex integration");
			String tip = commit(git, repository, "claude.txt", "Claude integration\n", "Add Claude integration");
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, tip, manifest);
			recordCandidateUniverseEvidence(manifest);
			Path planner = manifest.resolveSibling(manifest.getFileName() + ".candidate-universe.json");
			String universe = Files.readString(planner).replace(
				"\"plannerCandidateId\":\"planner-1\",\"independentCandidateId\":\"independent-1\"",
				"\"plannerCandidateId\":\"planner-1\",\"independentCandidateId\":\"independent-0\"");
			String collapsedReconciliation = "\"reconciliation\":[{\"plannerCandidateId\":\"planner-0\"," +
				"\"independentCandidateId\":\"independent-1\",\"disposition\":\"matched\",\"evidence\":" +
				"\"fixture collapse\"},";
			universe = universe.replace("\"reconciliation\":[", collapsedReconciliation);
			Files.writeString(planner, universe);
			write(manifest, "independent", "independent-", 2, "separate");
			recordIndependentReviewAttestation(manifest);
			Path outputs = replay.resolve("topic-outputs.json");
			Files.writeString(outputs, "[{\"id\":\"feature\",\"subject\":\"feature: add integrations\"}]\n");
			try
			{
				editor.declareTopicOutputs(manifest, outputs);
				throw new AssertionError("Expected declaration to reject reconciliation that collapses separate candidates");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("auditable semantic-placement ledger");
			}
		}
	}

/**
	 * Verifies initialization rejects tracked changes in the editor's explicit repository.
	 *
	 * @throws IOException if the fixture cannot be created or initialization reaches an unexpected failure
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*requires no tracked worktree or index changes.*")
	public void rejectsTrackedChangesForRepository() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			String base = new Git(repository).run("rev-parse", "HEAD").trim();
			Files.writeString(repository.resolve("tracked.txt"), "committed\n");
			new Git(repository).run("add", "tracked.txt");
			new Git(repository).run("commit", "--quiet", "--message", "feature");
			Files.writeString(repository.resolve("tracked.txt"), "modified\n");

			new GitTopicReplayEditor(repository).initialize(base, "HEAD", invocationManifest(repository));
		}
	}

	/**
	 * Verifies root preparation includes the initial import and its descendant changes as topic sources.
	 *
	 * @throws IOException if the fixture or manifest cannot be created
	 */
	@Test
	public void includesOrphanRootAsTopicSource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			Git git = new Git(repository);
			git.run("init", "--quiet");
			git.run("config", "user.name", "CAT Test");
			git.run("config", "user.email", "cat-test@example.invalid");
			Files.writeString(repository.resolve("root.txt"), "root\n");
			git.run("add", "root.txt");
			git.run("commit", "--quiet", "--message", "initial import");
			String root = git.run("rev-parse", "HEAD").trim();
			Files.writeString(repository.resolve("later.txt"), "later\n");
			git.run("add", "later.txt");
			git.run("commit", "--quiet", "--message", "later change");
			String later = git.run("rev-parse", "HEAD").trim();
			Path manifest = invocationManifest(repository);

			new GitTopicReplayEditor(repository).prepare("--root", later, manifest);

			JsonNode value = scope.json().readTree(Files.readString(manifest));
			requireThat(value.path("base").stringValue(), "base").isEqualTo("--root");
			requireThat(value.path("sources").size(), "sourceCount").isEqualTo(2);
			requireThat(value.path("sources").get(0).path("hash").stringValue(), "rootSource").isEqualTo(root);
			requireThat(value.path("sources").get(1).path("hash").stringValue(), "laterSource").isEqualTo(later);
		}
	}

	/**
	 * Verifies an incremental review preserves a known-complete prefix while exposing only later sources for review.
	 *
	 * @throws IOException if the fixture or manifest cannot be created
	 */
	@Test
	public void preparesFixedPrefixForIncrementalReview() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			Files.writeString(repository.resolve("prefix.txt"), "prefix\n");
			git.run("add", "prefix.txt");
			git.run("commit", "--quiet", "--message", "prefix");
			String fixedTip = git.run("rev-parse", "HEAD").trim();
			Files.writeString(repository.resolve("later.txt"), "later\n");
			git.run("add", "later.txt");
			git.run("commit", "--quiet", "--message", "later");
			Path manifest = invocationManifest(repository);

			new GitTopicReplayEditor(repository).prepareFixedPrefix(base, fixedTip, "HEAD", manifest);

			JsonNode value = scope.json().readTree(Files.readString(manifest));
			requireThat(value.path("fixedSources").size(), "fixedSourceCount").isEqualTo(1);
			requireThat(value.path("fixedSources").get(0).stringValue(), "fixedSource").isEqualTo(fixedTip);
		}
	}

	/**
	 * Verifies a fixed prefix does not require a duplicate semantic review before later work is reviewed.
	 *
	 * @throws IOException if the fixture or manifest cannot be created
	 */
	@Test
	public void reviewsOnlySourcesAfterFixedPrefix() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			Files.writeString(repository.resolve("prefix.txt"), "prefix\n");
			git.run("add", "prefix.txt");
			git.run("commit", "--quiet", "--message", "prefix");
			String fixedTip = git.run("rev-parse", "HEAD").trim();
			Files.writeString(repository.resolve("later.txt"), "later\n");
			git.run("add", "later.txt");
			git.run("commit", "--quiet", "--message", "later");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepareFixedPrefix(base, fixedTip, "HEAD", manifest);
			editor.bootstrapPreservedOutputBatches(manifest);
			String later = git.run("rev-parse", "HEAD").trim();
			Path review = manifest.resolveSibling("source-review.json");
			scope.json().writerWithDefaultPrettyPrinter().writeValue(review.toFile(), List.of(Map.of(
				"source", later,
				"disposition", "whole",
				"destinations", List.of("output-2"),
				"counterfactual", "The later commit has no earlier responsibility.",
				"evidence", "It introduces the later file.")));

			editor.recordSourceReviews(manifest, review);

			JsonNode value = scope.json().readTree(Files.readString(manifest));
			requireThat(value.path("sourceReviews").size(), "sourceReviewCount").isEqualTo(1);
		}
	}


	/**
	 * Verifies a draft plan rejects a batch that sends a source to an output excluded by its completed review.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void rejectsDraftReviewedDestination() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			String first = commit(git, repository, "first.txt", "first\n", "first");
			String second = commit(git, repository, "second.txt", "second\n", "second");
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, "HEAD", manifest);
			editor.captureSourcePatch(manifest, first, replay.resolve("first.patch"));
			editor.captureSourcePatch(manifest, second, replay.resolve("second.patch"));
			Path reviews = replay.resolve("source-reviews.json");
			Files.writeString(reviews, """
				[{"source":"%s","disposition":"whole","destinations":["output-1"],"counterfactual":"first",
				"evidence":"first"},{"source":"%s","disposition":"whole","destinations":["output-2"],
				"counterfactual":"second","evidence":"second"}]
				""".formatted(first, second));
			editor.recordSourceReviews(manifest, reviews);
			Path plan = replay.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"outputId":"output-1","disposition":"retained","batches":[{"id":"first","source":"%s",
				"patch":"first.patch"},{"id":"second","source":"%s","patch":"second.patch"}]},
				{"outputId":"output-2","disposition":"discarded","batches":[]}]
				""".formatted(first, second));

			try
			{
				editor.writeDraftOutputTree(manifest, plan, "output-1");
				throw new AssertionError("Expected draft destination validation failure");
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failureMessage").contains("Draft batch").contains(second).
					contains("output-1").contains("output-2").contains("Move the batch");
			}
		}
	}

	/**
	 * Verifies Git path-transition facts remain raw facts until a reviewer records semantic decisions.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void reportsRawPathTransitionFacts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository, "old.txt", "original\n", "feature");
			git.run("mv", "old.txt", "new.txt");
			Files.writeString(repository.resolve("added.txt"), "added\n");
			git.run("add", "added.txt");
			git.run("commit", "--quiet", "--message", "transition");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, "HEAD", manifest);
			Path outputs = repository.resolve("outputs.json");
			Files.writeString(outputs, """
				[{"id":"output-1","subject":"feature: add feature"},{"id":"output-2","subject":"feature: add transition"}]
				""");
			recordCandidateUniverseEvidence(manifest);
			recordSemanticPlacementLedger(manifest, List.of("output-1", "output-2"), Map.of());
			editor.declareTopicOutputs(manifest, outputs);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writePathTransitionFacts(manifest, output);
			String facts = output.toString(StandardCharsets.UTF_8);

			requireThat(facts, "pathTransitionFacts").contains("old.txt").contains("new.txt").contains("added.txt");
			Path decisions = manifest.resolveSibling("path-transition-decisions.json");
			Files.writeString(decisions, "[]\n");
			editor.recordPathTransitionDecisions(manifest, decisions);
		}
	}

	/**
	 * Verifies a failed quality gate preserves its diagnostic output for replay recovery.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void preservesFailingQualityGateOutput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository, "gate.txt", "gate fixture\n", "gate-fixture");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.initialize(base, "HEAD", manifest);

			try
			{
				editor.runGate(manifest, "failing-gate", List.of("/bin/sh", "-c", "echo diagnostic >&2; exit 7"));
				throw new AssertionError("Expected the failing quality gate to report its exit code");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("failing-gate failed with exit code 7");
			}
			Path gateOutput = manifest.getParent().resolve("gates/failing-gate.log");
			requireThat(Files.readString(gateOutput).strip(), "gateOutput").isEqualTo(
			"diagnostic");
		}
	}

	/**
	 * Verifies a changed existing configuration path requires an item-level origin review.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void reportsConfigurationOriginCandidate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository, "release.yml", "version: 1.0\n", "release-automation");
			commit(git, repository, "release.yml", "version: ${CAT_VERSION}\n", "centralize-version");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.initialize(base, "HEAD", manifest);
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			editor.writeOriginCandidates(manifest, output);

			requireThat(output.toString(StandardCharsets.UTF_8), "originCandidates").contains("release.yml");
		}
	}

	/**
	 * Verifies a sealed output plan cannot be invalidated after it records an output witness.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsInvalidatingSealedOutputSuffix() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository, "feature.txt", "feature\n", "feature");
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, "HEAD", manifest);
			editor.bootstrapPreservedOutputBatches(manifest);
			String source = scope.json().readTree(Files.readString(manifest)).path("sources").get(0).path("hash").
				stringValue();
			Path sourceReviews = replay.resolve("source-reviews.json");
			Files.writeString(sourceReviews, """
				[{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"The source supplies the first retained output.",
				"evidence":"The source remains in its preserved output."}]
				""".formatted(source));
			editor.recordSourceReviews(manifest, sourceReviews);
			Path originReviews = replay.resolve("origin-reviews.json");
			Files.writeString(originReviews, "[]\n");
			editor.recordOriginReviews(manifest, originReviews);
			Path plan = replay.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"outputId":"output-1","disposition":"retained","batches":[
				{"id":"source","source":"%s","patch":"patches/%s.raw.patch"}]}]
				""".formatted(source, source));
			editor.sealOutputPlan(manifest, plan);
			editor.recordPlannedOutput(manifest, "output-1");

			try
			{
				editor.invalidateOutputSuffix(manifest, "output-1");
				throw new AssertionError("Expected a sealed output plan to reject in-place invalidation");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("sealed output plan cannot be changed in place");
			}
			JsonNode sealed = scope.json().readTree(Files.readString(manifest));
			requireThat(sealed.path("outputWitnesses").size(), "outputWitnessCount").isEqualTo(1);
			requireThat(sealed.path("batches").size(), "batchCount").isEqualTo(1);
		}
	}
}

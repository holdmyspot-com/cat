package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayCandidateUniverseFixtures.recordCandidateUniverseEvidence;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayActionResultFixtures.write;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayAttestations.recordIndependentReviewAttestation;
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
import java.util.Set;
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path replay = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "topic-output-"));
			Path manifest = writeManifest(scope, replay);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(replay, replay);
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
			String implementation = "source-1";
			String sourceBoundUniverse = Files.readString(reviewerUniverse).replace(
				"independent fixture raw evidence 0", "Raw diff for source " + implementation);
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
			String documentation = "source-2";
			recordSemanticPlacementLedger(manifest, List.of("feature"), Map.of(
				implementation + ":1", "feature", documentation + ":1", "feature"));
			Path incompleteLedger = manifest.resolveSibling(manifest.getFileName() + ".semantic-placement-ledger.json");
			Files.writeString(incompleteLedger, Files.readString(incompleteLedger).replace(
				"\"acceptanceCommand\":" + scope.json().writeValueAsString(
					GateCommandFixture.command(GateCommandFixture.Mode.CHECK_FEATURE)) + ",", ""));
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
			BatchManifest declared = scope.json().readValue(manifest.toFile(), BatchManifest.class);
			Batch implementationBatch = new Batch("implementation", implementation, "feature", null, null);
			Batch documentationBatch = new Batch("documentation", documentation, "feature", null, null);
			BatchManifest reviewed = declared.withBatches(List.of(implementationBatch, documentationBatch));
			List<Output> retained = ReplayExecution.retainedOutputs(reviewed);

			requireThat(retained, "retainedOutputs").containsExactly(
				List.of(new Output("feature", null, "feature: add feature")), "declaredTopic");
			requireThat(ReplayExecution.outputBatches(reviewed, "feature"), "outputBatches").containsExactly(
				List.of(implementationBatch, documentationBatch), "contributingSources");
			requireThat(ReplayExecution.authorSource(reviewed, retained.getFirst()), "authorSource").
				isEqualTo(implementation);
			requireThat(ReplayExecution.commitArguments(retained.getFirst(), "topic-tree", declared.base()),
				"replacementCommitCommand").containsExactly(
				List.of("commit-tree", "topic-tree", "-p", "base", "-m", "feature: add feature"), "commandArguments");
		}
	}

	/**
	 * Verifies topic-output declarations reject a subject that does not identify a supported change type.
	 *
	 * @throws IOException if the subject violates the declaration format
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*type: summary.*")
	public void rejectsUntypedTopicOutputSubject() throws IOException
	{
		TopicOutputDeclaration.convertDeclarations(new TopicOutputRequest[]{
			new TopicOutputRequest("feature", "add feature")});
	}

	/**
	 * Verifies a proposed candidate collapse cannot proceed without its semantic-placement ledger.
	 *
	 * @throws IOException if the typed manifest or evidence cannot be written
	 */
	@Test
	public void rejectsCollapseWithoutPlacementLedger() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path replay = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "topic-collapse-"));
			Path manifest;
			try (ToolScope scope = new ToolScope())
			{
				manifest = writeManifest(scope, replay);
			}
			GitTopicReplayEditor editor = new GitTopicReplayEditor(replay, replay);
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
	 * Verifies a semantic candidate cannot combine behaviors the topic reviewer explicitly kept separate.
	 *
	 * @throws IOException if the separate behaviors are collapsed
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*may not be collapsed.*")
	public void rejectsSeparateCandidateCollapse() throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			SemanticCandidate candidate = new SemanticCandidate("collapsed", "combined behavior", "discovery",
				"split assessment", List.of("independent-0", "independent-1"), "feature", List.of(), List.of(),
				List.of(), List.of(), List.of(), "placement evidence");
			CandidateUniverseEvidence evidence = new CandidateUniverseEvidence(Set.of("independent-0", "independent-1"),
				new ActionResultMatrix(List.of(), List.of(new ActionPairAssessment("independent-0", "independent-1",
					"separate", "Each behavior remains usable separately.", List.of("feature.txt"), null))));

			new ReviewEvidence(scope.json()).validateCandidateUniverseLinks(List.of(candidate), evidence,
				Path.of("semantic-placement-ledger.json"));
		}
	}

	/**
	 * Verifies planning rejects supplied tracked worktree changes.
	 *
	 * @throws IOException if tracked changes are present
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*requires no tracked worktree or index changes.*")
	public void rejectsTrackedChangesForRepository() throws IOException
	{
		PlanningState.rejectTrackedChanges(List.of(" M tracked.txt"));
	}

	/**
	 * Verifies captured root-range facts retain the initial import and its descendant changes as ordered topic sources.
	 */
	@Test
	public void includesOrphanRootAsTopicSource()
	{
		BatchManifest manifest = ReplayManifestInitialization.initialManifest(History.ROOT_BASE, "later-tree", List.of(
			new SourceSnapshot("root", History.EMPTY_TREE, "root-tree"),
			new SourceSnapshot("later", "root-tree", "later-tree")), List.of());

		requireThat(manifest.base(), "base").isEqualTo("--root");
		requireThat(manifest.sources().size(), "sourceCount").isEqualTo(2);
		requireThat(manifest.sources().get(0).hash(), "rootSource").isEqualTo("root");
		requireThat(manifest.sources().get(1).hash(), "laterSource").isEqualTo("later");
		requireThat(manifest.outputs().stream().map(Output::source).toList(), "outputSources").containsExactly(
			List.of("root", "later"), "capturedSources");
	}

	/**
	 * Verifies an incremental review preserves a known-complete prefix while exposing only later sources for review.
	 *
	 * @throws IOException if the fixture or manifest cannot be created
	 */
	@Test
	public void preparesFixedPrefixForIncrementalReview() throws IOException
	{
		BatchManifest manifest = ReplayManifestInitialization.withFixedPrefix(capturedManifest(), List.of("source-1"));

		requireThat(manifest.fixedSources().size(), "fixedSourceCount").isEqualTo(1);
		requireThat(manifest.fixedSources().getFirst(), "fixedSource").isEqualTo("source-1");
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
			Path root = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "topic-fixed-prefix-"));
			Path manifestFile = root.resolve("manifest.json");
			BatchManifest manifest = ReplayManifestInitialization.withFixedPrefix(capturedManifest(), List.of("source-1"));
			scope.json().writeValue(manifestFile.toFile(), manifest);
			List<SourceReview> reviews = List.of(new SourceReview("source-2", "whole", List.of("output-2"), List.of(),
				"The later commit has no earlier responsibility.", "It introduces the later file."));
			Path reviewFile = root.resolve("source-review.json");
			scope.json().writeValue(reviewFile.toFile(), reviews);
			SourceReviewValidation validation = new SourceReviewValidation();
			validation.validateInput(manifest, reviews, true);
			ManifestStore<BatchManifest> manifests = new ManifestStore<>(root, scope.json(), BatchManifest.class);

			new SourceReviewRecording(new Git(root), scope.json(), validation, manifests).recordBatches(manifestFile,
				List.of(reviewFile));

			BatchManifest recorded = manifests.readUnfinalized(manifestFile);
			requireThat(recorded.sourceReviews().size(), "sourceReviewCount").isEqualTo(1);
			requireThat(recorded.sourceReviews(), "sourceReviews").containsExactly(reviews, "submittedReviews");
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
		BatchRequest batch = new BatchRequest("second", "source-2", "second.patch");
		SourceReview review = new SourceReview("source-2", "whole", List.of("output-2"), List.of(), "second", "second");

		try
		{
			OutputPlanValidation.validateDraftDestination(batch, new Output("output-1", "source-1", "first"), review);
			throw new AssertionError("Expected draft destination validation failure");
		}
		catch (IOException failure)
		{
			requireThat(failure.getMessage(), "failureMessage").contains("Draft batch").contains("source-2").
				contains("output-1").contains("output-2").contains("Move the batch");
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
		try (ToolScope scope = new ToolScope())
		{
			List<PathTransitionFact> transitions = PathTransitionFacts.parse("source-2", null,
				List.of("R100\told.txt\tnew.txt", "A\tadded.txt"));
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			scope.json().writerWithDefaultPrettyPrinter().writeValue(output, transitions);
			String facts = output.toString(StandardCharsets.UTF_8);

			requireThat(facts, "pathTransitionFacts").contains("old.txt").contains("new.txt").contains("added.txt");
			requireThat(transitions, "rawTransitions").containsExactly(List.of(
				new PathTransitionFact("source-2:transition:1", "source-2", null, "R100", "old.txt", "new.txt"),
				new PathTransitionFact("source-2:transition:2", "source-2", null, "A", "added.txt", null)), "parsedFacts");
			new PathTransitionValidation().validate(capturedManifest(), transitions, List.of(), false,
				new SourceReviewValidation());
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
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "topic-gate-"));
			Path manifest = Files.writeString(repository.resolve("manifest.json"), "{}\n");
			Gates gates = new Gates(new CommandRunner(repository));

			try
			{
				gates.run(manifest, "failing-gate", GateCommandFixture.command(GateCommandFixture.Mode.FAILURE));
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "topic-origin-"));
			Path manifest = OriginReviewFixtures.writeManifest(scope.json(), repository, false, "release.yml");
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			new GitTopicReplayEditor(repository, repository).writeOriginCandidates(manifest, output);

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
			Path replay = cleanup.add(Files.createTempDirectory(ReplayFixtures.temporaryRoot(), "topic-sealed-suffix-"));
			Path manifest = replay.resolve("manifest.json");
			BatchManifest sealed = capturedManifest().withBatches(List.of(
				new Batch("source", "source-1", "output-1", null, null))).withOutputWitnesses(List.of(
				new OutputWitness("output-1", "tree-0", "tree-1"))).withOutputPlan(new OutputPlan(List.of(
				new PlannedOutput("output-1", "retained", List.of(new PlannedBatch("source", "source-1", null))),
				new PlannedOutput("output-2", "discarded", List.of()))));
			scope.json().writeValue(manifest.toFile(), sealed);

			try
			{
				new GitTopicReplayEditor(replay, replay).invalidateOutputSuffix(manifest, "output-1");
				throw new AssertionError("Expected a sealed output plan to reject in-place invalidation");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("sealed output plan cannot be changed in place");
			}
			JsonNode recorded = scope.json().readTree(Files.readString(manifest));
			requireThat(recorded.path("outputWitnesses").size(), "outputWitnessCount").isEqualTo(1);
			requireThat(recorded.path("batches").size(), "batchCount").isEqualTo(1);
			requireThat(scope.json().readValue(manifest.toFile(), BatchManifest.class), "sealedManifest").isEqualTo(sealed);
		}
	}

	/**
	 * Writes captured source facts without preparing a Git repository.
	 *
	 * @param scope the fixture JSON mapper scope
	 * @param directory the test-owned manifest directory
	 * @return the written manifest
	 * @throws IOException if the captured facts cannot be written
	 */
	private Path writeManifest(ToolScope scope, Path directory) throws IOException
	{
		Path manifest = directory.resolve("manifest.json");
		scope.json().writeValue(manifest.toFile(), capturedManifest());
		return manifest;
	}

	/**
	 * Supplies two chronological sources whose paths are ordinary artifacts, without exposed product entry points.
	 *
	 * @return captured source snapshots and path inventory
	 */
	private BatchManifest capturedManifest()
	{
		return ReplayManifestInitialization.initialManifest("base", "tree-2", List.of(
			new SourceSnapshot("source-1", "tree-0", "tree-1"),
			new SourceSnapshot("source-2", "tree-1", "tree-2")), List.of(
			new InventoryItem("source-1:1", "source-1", "feature.txt", "000000", "0".repeat(40), "100644", "feature-blob"),
			new InventoryItem("source-2:1", "source-2", "README.md", "000000", "0".repeat(40), "100644", "readme-blob")));
	}
}

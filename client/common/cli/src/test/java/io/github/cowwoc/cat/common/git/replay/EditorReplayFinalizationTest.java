package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.commit;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.invocationDirectory;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayReviewFixtures.originReviews;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayReviewEvidenceFixtures.recordReviewEvidence;

import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests replay finalization and absorbed-source outcomes.
 */
public final class EditorReplayFinalizationTest
{
	/**
	 * Creates the test fixture.
	 */
	public EditorReplayFinalizationTest()
	{
	}

	/**
	 * Verifies replay handles a fully absorbed correction when a tag has the current branch's name.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void replaysAbsorbedSourceWithMatchingTag() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			String feature = commit(git, repository, "feature.txt", "original\n", "feature");
			String correction = commit(git, repository, "feature.txt", "corrected\n", "correction");
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, correction, manifest);
			Path featurePatch = replay.resolve("feature.patch");
			Path correctionPatch = replay.resolve("correction.patch");
			editor.captureSourcePatch(manifest, feature, featurePatch);
			editor.captureSourcePatch(manifest, correction, correctionPatch);
			Path batches = replay.resolve("output-1.json");
			Files.writeString(batches, """
				[{"id":"feature","source":"%s","patch":"feature.patch"},
				{"id":"correction","source":"%s","patch":"correction.patch"}]
				""".formatted(feature, correction));
			editor.recordSourcePatchReconstruction(manifest, feature, featurePatch);
			editor.recordSourcePatchReconstruction(manifest, correction, correctionPatch);
			Path sourceReviews = replay.resolve("source-reviews.json");
			Files.writeString(sourceReviews, """
				[{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"The feature supplies the first retained output.",
				"evidence":"The feature patch is the complete first output."},
				{"source":"%s","disposition":"absorbed","destinations":["output-1"],
				"counterfactual":"The correction completes the earlier feature and leaves no independent output.",
				"evidence":"The correction patch is included in output-1 and output-2 is discarded."}]
				""".formatted(feature, correction));
			editor.recordSourceReviews(manifest, sourceReviews);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginCandidates(manifest, output);
			Path originReviews = replay.resolve("origin-reviews.json");
			Files.writeString(originReviews, originReviews(scope.json().readTree(output.toString(StandardCharsets.UTF_8))));
			editor.recordOriginReviews(manifest, originReviews);
			Path plan = replay.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"outputId":"output-1","disposition":"retained","batches":%s},
				{"outputId":"output-2","disposition":"discarded","batches":[]}]
				""".formatted(Files.readString(batches)));
			editor.sealOutputPlan(manifest, plan);
			editor.recordPlannedOutput(manifest, "output-1");
			editor.discardOutput(manifest, "output-2");
			recordReviewEvidence(manifest);
			String branch = git.run("branch", "--show-current").trim();
			git.run("tag", branch);

			editor.replay(manifest);

			requireThat(Integer.parseInt(git.run("rev-list", "--count", base + "..HEAD").trim()), "replacementCount").
				isEqualTo(1);
			requireThat(Files.readString(repository.resolve("feature.txt")).strip(), "feature").isEqualTo("corrected");
		}
	}

	/**
	 * Verifies plan sealing rejects batches that contradict recorded origin placement decisions.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsPlanConflictingWithOriginReview() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			Files.writeString(repository.resolve("feature.txt"), "original\n");
			Files.writeString(repository.resolve("secondary.txt"), "original\n");
			git.run("add", "feature.txt", "secondary.txt");
			git.run("commit", "--quiet", "--message", "feature");
			String feature = git.run("rev-parse", "HEAD").trim();
			Files.writeString(repository.resolve("feature.txt"), "corrected\n");
			Files.writeString(repository.resolve("secondary.txt"), "corrected\n");
			git.run("add", "feature.txt", "secondary.txt");
			git.run("commit", "--quiet", "--message", "correction");
			String correction = git.run("rev-parse", "HEAD").trim();
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, "HEAD", manifest);
			editor.captureSourcePatch(manifest, feature, replay.resolve("feature.patch"));
			editor.captureSourcePatch(manifest, correction, replay.resolve("correction.patch"));
			Path sourceReviews = replay.resolve("source-reviews.json");
			Files.writeString(sourceReviews, """
				[{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"The feature begins the first output.","evidence":"The feature has no predecessor."},
				{"source":"%s","disposition":"whole","destinations":["output-2"],
				"counterfactual":"The correction is planned in the later output.",
				"evidence":"The reviewed plan separates it."}]
				""".formatted(feature, correction));
			editor.recordSourceReviews(manifest, sourceReviews);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginReviewScaffold(manifest, output);
			Path originReviews = replay.resolve("origin-reviews.json");
			Files.writeString(originReviews, originReviews(scope.json().readTree(output.toString(StandardCharsets.UTF_8))));
			editor.recordOriginReviews(manifest, originReviews);
			Path plan = replay.resolve("plan.json");
			Files.writeString(plan, """
				[{"outputId":"output-1","disposition":"retained","batches":[{"id":"feature","source":"%s",
				"patch":"feature.patch"}]},{"outputId":"output-2","disposition":"retained",
				"batches":[{"id":"correction","source":"%s","patch":"correction.patch"}]}]
				""".formatted(feature, correction));

			try
			{
				editor.sealOutputPlan(manifest, plan);
				throw new AssertionError("Expected a plan that contradicts origin placement to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains(
					"Topic-replay output plan conflicts with recorded origin decisions").contains("feature.txt").contains(
					"secondary.txt").contains("output-1");
			}
			JsonNode unchanged = scope.json().readTree(Files.readString(manifest));
			requireThat(unchanged.path("outputPlan").isNull(), "outputPlan").isTrue();
			requireThat(unchanged.path("outputWitnesses").size(), "outputWitnessCount").isEqualTo(0);
		}
	}

	/**
	 * Verifies a later same-output correction preserves the earlier origin review and the final feature state.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void acceptsLaterSameOutputCorrection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			String feature = commit(git, repository, "feature.txt", "original\n", "feature");
			String correction = commit(git, repository, "feature.txt", "corrected\n", "correction");
			String documentation = commit(git, repository, "feature.txt", "documented correction\n", "documentation");
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, documentation, manifest);
			Path featurePatch = replay.resolve(feature + ".patch");
			Path correctionPatch = replay.resolve(correction + ".patch");
			Path documentationPatch = replay.resolve(documentation + ".patch");
			editor.captureSourcePatch(manifest, feature, featurePatch);
			editor.captureSourcePatch(manifest, correction, correctionPatch);
			editor.captureSourcePatch(manifest, documentation, documentationPatch);
			Path batches = replay.resolve("output-1.json");
			Files.writeString(batches, """
				[{"id":"feature","source":"%s","patch":"%s.patch"},
				{"id":"correction","source":"%s","patch":"%s.patch"},
				{"id":"documentation","source":"%s","patch":"%s.patch"}]
				""".formatted(feature, feature, correction, correction, documentation, documentation));
			editor.recordSourcePatchReconstruction(manifest, feature, featurePatch);
			editor.recordSourcePatchReconstruction(manifest, correction, correctionPatch);
			editor.recordSourcePatchReconstruction(manifest, documentation, documentationPatch);
			Path sourceReviews = replay.resolve("source-reviews.json");
			Files.writeString(sourceReviews, """
				[{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"All reviewed work belongs to the one feature output.",
				"evidence":"The output contains the reviewed feature work."},
				{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"All reviewed work belongs to the one feature output.",
				"evidence":"The output contains the reviewed feature work."},
				{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"All reviewed work belongs to the one feature output.",
				"evidence":"The output contains the reviewed feature work."}]
				""".formatted(feature, correction, documentation));
			editor.recordSourceReviews(manifest, sourceReviews);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginCandidates(manifest, output);
			Path originReviews = replay.resolve("origin-reviews.json");
			Files.writeString(originReviews, originReviews(scope.json().readTree(output.toString(StandardCharsets.UTF_8))));
			editor.recordOriginReviews(manifest, originReviews);
			Path plan = replay.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"outputId":"output-1","disposition":"retained","batches":%s},
				{"outputId":"output-2","disposition":"discarded","batches":[]},
				{"outputId":"output-3","disposition":"discarded","batches":[]}]
				""".formatted(Files.readString(batches)));
			editor.sealOutputPlan(manifest, plan);
			editor.recordPlannedOutput(manifest, "output-1");
			editor.discardOutput(manifest, "output-2");
			editor.discardOutput(manifest, "output-3");
			recordReviewEvidence(manifest);

			editor.replay(manifest);

			requireThat(Files.readString(repository.resolve("feature.txt")).strip(), "feature").isEqualTo(
				"documented correction");
		}
	}

	/**
	 * Verifies an absorbed source needs no batch when the retained target-relative patch already contains its state.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void acceptsAbsorbedSourceWithoutBatch() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			commit(git, repository, "feature.txt", "initial\n", "baseline");
			String base = git.run("rev-parse", "HEAD").trim();
			String feature = commit(git, repository, "feature.txt", "feature\n", "feature");
			String absorbed = commit(git, repository, "feature.txt", "feature with cleanup\n", "absorbed-cleanup");
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, absorbed, manifest);
			Path featurePatch = replay.resolve("feature.patch");
			Path absorbedPatch = replay.resolve("absorbed.patch");
			editor.captureSourcePatch(manifest, feature, featurePatch);
			editor.captureSourcePatch(manifest, absorbed, absorbedPatch);
			Path finalFeature = replay.resolve("feature.txt");
			editor.writeTreePath(manifest, absorbed, "feature.txt", finalFeature);
			Path finalPatch = replay.resolve("feature-final.patch");
			editor.captureTargetAdaptedPathPatch(manifest, feature, finalPatch, base, "feature.txt", finalFeature);
			Path batches = replay.resolve("output-1.json");
			Files.writeString(batches, """
				[{"id":"feature","source":"%s","patch":"feature-final.patch"}]
				""".formatted(feature));
			editor.recordSourcePatchReconstruction(manifest, feature, featurePatch);
			editor.recordSourcePatchReconstruction(manifest, absorbed, absorbedPatch);
			Path sourceReviews = replay.resolve("source-reviews.json");
			Files.writeString(sourceReviews, """
				[{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"The feature supplies the retained output.",
				"evidence":"The feature patch is the complete first output."},
				{"source":"%s","disposition":"absorbed","destinations":["output-1"],
				"counterfactual":"The cleanup completes the earlier feature and leaves no independent output.",
				"evidence":"The retained feature patch already contains the cleanup state and the source output is discarded."}]
				""".formatted(feature, absorbed));
			editor.recordSourceReviews(manifest, sourceReviews);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginCandidates(manifest, output);
			Path originReviews = replay.resolve("origin-reviews.json");
			Files.writeString(originReviews, originReviews(scope.json().readTree(output.toString(StandardCharsets.UTF_8))));
			editor.recordOriginReviews(manifest, originReviews);
			Path plan = replay.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"outputId":"output-1","disposition":"retained","batches":%s},
				{"outputId":"output-2","disposition":"discarded","batches":[]}]
				""".formatted(Files.readString(batches)));

			editor.sealOutputPlan(manifest, plan);

			requireThat(scope.json().readTree(Files.readString(manifest)).path("outputPlan").isNull(), "outputPlan").
				isFalse();
		}
	}

	/**
	 * Verifies an absorbed source needs no batch when a retained target-relative patch supersedes its path state.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void acceptsSupersededAbsorbedSource() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			commit(git, repository, "feature.txt", "initial\n", "baseline");
			String base = git.run("rev-parse", "HEAD").trim();
			String feature = commit(git, repository, "feature.txt", "feature\n", "feature");
			String absorbed = commit(git, repository, "feature.txt", "feature with cleanup\n", "absorbed-cleanup");
			String finalRevision = commit(git, repository, "feature.txt", "feature with later revision\n",
				"later-revision");
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, finalRevision, manifest);
			Path featurePatch = replay.resolve("feature.patch");
			Path absorbedPatch = replay.resolve("absorbed.patch");
			Path finalRevisionPatch = replay.resolve("final-revision.patch");
			editor.captureSourcePatch(manifest, feature, featurePatch);
			editor.captureSourcePatch(manifest, absorbed, absorbedPatch);
			editor.captureSourcePatch(manifest, finalRevision, finalRevisionPatch);
			Path finalFeature = replay.resolve("feature.txt");
			editor.writeTreePath(manifest, finalRevision, "feature.txt", finalFeature);
			Path finalPatch = replay.resolve("feature-final.patch");
			editor.captureTargetAdaptedPathPatch(manifest, feature, finalPatch, base, "feature.txt", finalFeature);
			Path batches = replay.resolve("output-1.json");
			Files.writeString(batches, """
				[{"id":"feature","source":"%s","patch":"feature-final.patch"}]
				""".formatted(feature));
			editor.recordSourcePatchReconstruction(manifest, feature, featurePatch);
			editor.recordSourcePatchReconstruction(manifest, absorbed, absorbedPatch);
			editor.recordSourcePatchReconstruction(manifest, finalRevision, finalRevisionPatch);
			Path sourceReviews = replay.resolve("source-reviews.json");
			Files.writeString(sourceReviews, """
				[{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"The feature supplies the retained output.",
				"evidence":"The feature patch is the complete first output."},
				{"source":"%s","disposition":"absorbed","destinations":["output-1"],
				"counterfactual":"The cleanup is superseded by the retained feature result and leaves no independent output.",
				"evidence":"The retained feature patch includes the later revision and output-2 is discarded."},
				{"source":"%s","disposition":"absorbed","destinations":["output-1"],
				"counterfactual":"The later revision is included in the retained feature result and has no independent output.",
				"evidence":"The retained feature patch contains the final revision and output-3 is discarded."}]
				""".formatted(feature, absorbed, finalRevision));
			editor.recordSourceReviews(manifest, sourceReviews);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginCandidates(manifest, output);
			Path originReviews = replay.resolve("origin-reviews.json");
			Files.writeString(originReviews, originReviews(scope.json().readTree(output.toString(StandardCharsets.UTF_8))));
			editor.recordOriginReviews(manifest, originReviews);
			Path plan = replay.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"outputId":"output-1","disposition":"retained","batches":%s},
				{"outputId":"output-2","disposition":"discarded","batches":[]},
				{"outputId":"output-3","disposition":"discarded","batches":[]}]
				""".formatted(Files.readString(batches)));

			editor.sealOutputPlan(manifest, plan);

			requireThat(scope.json().readTree(Files.readString(manifest)).path("outputPlan").isNull(), "outputPlan").
				isFalse();
			editor.recordPlannedOutput(manifest, "output-1");
			editor.discardOutput(manifest, "output-2");
			editor.discardOutput(manifest, "output-3");
			recordReviewEvidence(manifest);

			editor.replay(manifest);

			requireThat(Files.readString(repository.resolve("feature.txt")).strip(), "feature").isEqualTo(
				"feature with later revision");
		}
	}

	/**
	 * Verifies a mixed source folds an invalid test removal into its feature while retaining independent routing work.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void foldsInvalidTestRemovalIntoFeature() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			Files.writeString(repository.resolve("structural-test.txt"), "claims runtime behavior but reads a manifest\n");
			Files.writeString(repository.resolve("feature.txt"), "feature implementation\n");
			git.run("add", "structural-test.txt", "feature.txt");
			git.run("commit", "--quiet", "--message", "feature");
			String feature = git.run("rev-parse", "HEAD").trim();
			git.run("rm", "--quiet", "structural-test.txt");
			Files.writeString(repository.resolve("runtime-routing.txt"), "generated launcher runs routing behavior\n");
			git.run("add", "runtime-routing.txt");
			git.run("commit", "--quiet", "--message", "runtime-routing");
			String routing = git.run("rev-parse", "HEAD").trim();
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, routing, manifest);
			Path featurePatch = replay.resolve("feature.patch");
			Path routingSourcePatch = replay.resolve("routing-source.patch");
			editor.captureSourcePatch(manifest, feature, featurePatch);
			editor.captureSourcePatch(manifest, routing, routingSourcePatch);
			Path removalPatch = replay.resolve("remove-test.patch");
			Path routingPatch = replay.resolve("routing.patch");
			editor.captureSourcePathPatch(manifest, routing, removalPatch, List.of("structural-test.txt"));
			editor.captureSourcePathPatch(manifest, routing, routingPatch, List.of("runtime-routing.txt"));
			Path output1 = replay.resolve("output-1.json");
			Path output2 = replay.resolve("output-2.json");
			Files.writeString(output1, """
				[{"id":"feature","source":"%s","patch":"feature.patch"},
				{"id":"remove-test","source":"%s","patch":"remove-test.patch"}]
				""".formatted(feature, routing));
			Files.writeString(output2, """
				[{"id":"routing","source":"%s","patch":"routing.patch"}]
				""".formatted(routing));
			editor.recordSourcePatchReconstruction(manifest, feature, featurePatch);
			editor.recordSourcePatchReconstruction(manifest, routing, routingSourcePatch);
			Path sourceReviews = replay.resolve("source-reviews.json");
			Files.writeString(sourceReviews, """
				[{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"The feature output introduces the implementation and must not retain the invalid test.",
				"evidence":"The feature batch contains its implementation and the later test removal."},
				{"source":"%s","disposition":"split","destinations":["output-1","output-2"],
				"placements":[{"inventoryId":"%s:1","affectedUnit":"runtime routing",
				"owningResponsibility":"runtime routing","destination":"output-2",
				"evidence":"The runtime routing file introduces the independent routing behavior."}],
				"counterfactual":"The invalid test removal belongs with the feature while routing is independent.",
				"evidence":"The reviewed batches have different output destinations."}]
				""".formatted(feature, routing, routing));
			try
			{
				editor.recordSourceReviews(manifest, sourceReviews);
				throw new AssertionError("Expected an unreviewed split-source artifact to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("explicitly place every changed path");
			}
			Files.writeString(sourceReviews, """
				[{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"The feature output introduces the implementation and must not retain the invalid test.",
				"evidence":"The feature batch contains its implementation and the later test removal."},
				{"source":"%s","disposition":"split","destinations":["output-1","output-2"],
				"placements":[{"inventoryId":"%s:1","affectedUnit":"runtime routing",
				"owningResponsibility":"runtime routing","destination":"output-2",
				"evidence":"The runtime routing file introduces the independent routing behavior."},
				{"inventoryId":"%s:2","affectedUnit":"structural test removal",
				"owningResponsibility":"feature","destination":"output-1",
				"evidence":"The removed test belongs with the feature it previously described."}],
				"counterfactual":"The invalid test removal belongs with the feature while routing is independent.",
				"evidence":"The reviewed batches have different output destinations."}]
				""".formatted(feature, routing, routing, routing));
			editor.recordSourceReviews(manifest, sourceReviews);
			Path plan = replay.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"outputId":"output-1","disposition":"retained","batches":%s},
				{"outputId":"output-2","disposition":"retained","batches":%s}]
				""".formatted(Files.readString(output1), Files.readString(output2)));

			try
			{
				editor.sealOutputPlan(manifest, plan);
				throw new AssertionError("Expected a plan without an origin review to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "missingReviewMessage").contains("requires origin reviews");
			}
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginCandidates(manifest, output);
			JsonNode candidate = scope.json().readTree(output.toString(StandardCharsets.UTF_8)).get(0);
			Path originReviews = replay.resolve("origin-reviews.json");
			Files.writeString(originReviews, """
				[{"inventoryId":"%s","affectedUnit":"structural-test.txt","owningResponsibility":"feature",
				"predecessorRelationship":"removes-invalid-predecessor","destination":"output-1",
				"rejectedDestinations":[],"evidence":
				"The feature output removes the test whose claim exceeds its exercised boundary."}]
				""".formatted(candidate.path("inventoryId").stringValue()));
			editor.recordOriginReviews(manifest, originReviews);
			editor.sealOutputPlan(manifest, plan);
			editor.recordPlannedOutput(manifest, "output-1");
			editor.recordPlannedOutput(manifest, "output-2");
			recordReviewEvidence(manifest);

			editor.replay(manifest);

			requireThat(Files.exists(repository.resolve("structural-test.txt")), "structuralTest").isFalse();
			requireThat(Files.readString(repository.resolve("feature.txt")).strip(), "feature").isEqualTo(
				"feature implementation");
			requireThat(Files.readString(repository.resolve("runtime-routing.txt")).strip(), "routing").isEqualTo(
				"generated launcher runs routing behavior");
		}
	}
}

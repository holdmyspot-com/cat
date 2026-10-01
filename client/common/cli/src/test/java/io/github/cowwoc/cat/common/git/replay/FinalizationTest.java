package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.commit;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.invocationDirectory;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.invocationManifest;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayCandidateUniverseFixtures.recordCandidateUniverseEvidence;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayReviewEvidenceFixtures.recordReviewEvidence;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayReviewFixtures.originReviews;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests Git topic-replay finalization behavior.
 */
public final class FinalizationTest
{
	/**
	 * Creates the test fixture.
	 */
	public FinalizationTest()
	{
	}

	/**
	 * Verifies a manually reworded composite source has no finalization proof even when its patch is unchanged.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsManualRewordWithoutReceipt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			Files.writeString(repository.resolve("feature.txt"), "feature\n");
			Files.writeString(repository.resolve("documentation.txt"), "documentation\n");
			git.run("add", "feature.txt", "documentation.txt");
			git.run("commit", "--quiet", "--message", "composite source");
			String composite = git.run("rev-parse", "HEAD").trim();
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, composite, manifest);
			String reworded = git.run("commit-tree", composite + "^{tree}", "-p", base, "-m", "renamed composite").trim();
			git.run("update-ref", "HEAD", reworded, composite);
			try
			{
				editor.verifyFinalization(manifest, base, reworded);
				throw new AssertionError("Expected a manually reworded composite to be unreviewed");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("history is unreviewed");
			}
		}
	}

	/**
	 * Verifies replay refuses unresolved adversarial review evidence before creating any replacement output.
	 * <p>
	 * This test takes about 0.8 seconds alone and can exceed 1 second under full-build load. Each later claim needs the
	 * real Git chain before it, so splitting the test would repeat that chain. It starts 227 Git processes: about 0.23
	 * seconds builds and seals a two-source, two-output manifest; about 0.19 seconds runs eight rejected replays and
	 * three draft-acceptance worktrees; and about 0.35 seconds replays, gates each output in its own worktree, and
	 * finalizes and verifies the result.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void rejectsUnresolvedChallengesBeforeReplay() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			String feature = commit(git, repository, "feature.txt", "original\n", "feature");
			Files.writeString(repository.resolve("feature.txt"), "corrected\n");
			Files.writeString(repository.resolve("note.txt"), "supplemental\n");
			git.run("add", "feature.txt", "note.txt");
			git.run("commit", "--quiet", "--message", "split");
			String split = git.run("rev-parse", "HEAD").trim();
			Path replay = invocationDirectory(repository, "replay");
			Path manifest = replay.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, split, manifest);
			Path featurePatch = replay.resolve("feature.patch");
			Path splitPatch = replay.resolve("split.patch");
			editor.captureSourcePatch(manifest, feature, featurePatch);
			editor.captureSourcePatch(manifest, split, splitPatch);
			editor.captureSourcePathPatch(manifest, split, replay.resolve("split-feature.patch"), List.of("feature.txt"));
			editor.captureSourcePathPatch(manifest, split, replay.resolve("split-note.patch"), List.of("note.txt"));
			Path output1 = replay.resolve("output-1.json");
			Path output2 = replay.resolve("output-2.json");
			Files.writeString(output1, """
				[{"id":"feature","source":"%s","patch":"feature.patch"},
				{"id":"split-feature","source":"%s","patch":"split-feature.patch"}]
				""".formatted(feature, split));
			Files.writeString(output2, """
				[{"id":"split-note","source":"%s","patch":"split-note.patch"}]
				""".formatted(split));
			editor.recordSourcePatchReconstruction(manifest, feature, featurePatch);
			editor.recordSourcePatchReconstruction(manifest, split, splitPatch);
			Path sourceReviews = replay.resolve("source-reviews.json");
			Files.writeString(sourceReviews, """
				[{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"No earlier output needs this source because it supplies the first retained output.",
				"evidence":"The source has no predecessor in the selected range."},
				{"source":"%s","disposition":"split","destinations":["output-1","output-2"],
				"placements":[{"inventoryId":"%s:1","affectedUnit":"feature",
				"owningResponsibility":"feature","destination":"output-1",
				"evidence":"The feature file completes the feature output."},
				{"inventoryId":"%s:2","affectedUnit":"supplemental note",
				"owningResponsibility":"supplemental note","destination":"output-2",
				"evidence":"The note has its own reader and output."}],
				"counterfactual":"The corrected feature and supplemental note belong to different retained outputs.",
				"evidence":"The two batches have different declared destinations."}]
				""".formatted(feature, split, split, split));
			editor.recordSourceReviews(manifest, sourceReviews);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginCandidates(manifest, output);
			Path originReviews = replay.resolve("origin-reviews.json");
			Files.writeString(originReviews, originReviews(scope.json().readTree(output.toString(StandardCharsets.UTF_8))));
			editor.recordOriginReviews(manifest, originReviews);
			Path plan = replay.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"outputId":"output-1","disposition":"retained","batches":%s},
				{"outputId":"output-2","disposition":"retained","batches":%s}]
				""".formatted(Files.readString(output1), Files.readString(output2)));
			editor.sealOutputPlan(manifest, plan);
			editor.recordPlannedOutput(manifest, "output-1");
			editor.recordPlannedOutput(manifest, "output-2");
			git.run("branch", "backup/unrelated");
			recordCandidateUniverseEvidence(manifest);
			Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".review-packet.md"),
				"fixture artifact inventory reviewed\n");
			Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".adversarial-challenges.json"),
				"[{\"operation\":\"add\",\"status\":\"resolved\",\"evidence\":\"fixture review completed\"}]\n");
			try
			{
				editor.replay(manifest);
				throw new AssertionError("Expected replay to reject a missing semantic plan");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("requires an auditable semantic plan");
			}
			recordReviewEvidence(manifest);
			Files.writeString(manifest.resolveSibling(manifest.getFileName() + ".adversarial-challenges.json"),
				"[{\"operation\":\"add\",\"status\":\"open\",\"evidence\":\"fixture review is incomplete\"}]\n");

			try
			{
				editor.replay(manifest);
				throw new AssertionError("Expected replay to reject unresolved adversarial review evidence");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains(
					"adversarial challenge ledger must resolve every challenge");
			}
			recordReviewEvidence(manifest);
			editor.validateDraftDeclaredOutputAcceptance(manifest, plan);
			requireThat(Files.isRegularFile(replay.resolve("gates/draft-per-output-acceptance-output-1.log")),
				"firstDraftAcceptanceGateLog").isTrue();
			requireThat(Files.isRegularFile(replay.resolve("gates/draft-per-output-acceptance-output-2.log")),
				"secondDraftAcceptanceGateLog").isTrue();
			Path semanticPlan = manifest.resolveSibling(manifest.getFileName() + ".semantic-plan.json");
			String validSemanticPlan = Files.readString(semanticPlan);
			editor.runGate(manifest, "candidate-fixture-build", List.of("/bin/sh", "-c", "test -f feature.txt"));
			Files.writeString(semanticPlan,
				validSemanticPlan.replaceFirst(",\\\"candidateDiscovery\\\":\\\"[^\\\"]*\\\"", ""));
			try
			{
				editor.replay(manifest);
				throw new AssertionError("Expected replay to reject a candidate without behavior-first discovery evidence");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("behavior-first discovery basis");
			}
			Files.writeString(semanticPlan, validSemanticPlan);
			Files.writeString(semanticPlan,
				validSemanticPlan.replaceFirst(",\\\"splitCounterfactual\\\":\\\"[^\\\"]*\\\"", ""));
			try
			{
				editor.replay(manifest);
				throw new AssertionError("Expected replay to reject a candidate without a split counterfactual");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("split counterfactual");
			}
			Files.writeString(semanticPlan, validSemanticPlan);
			int omittedCandidate = validSemanticPlan.lastIndexOf("{\"id\":\"output-2-0\"");
			Files.writeString(semanticPlan, validSemanticPlan.substring(0, omittedCandidate - 1) + "]\n");
			try
			{
				editor.replay(manifest);
				throw new AssertionError("Expected replay to reject an omitted independent candidate after a passing build");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("account for every source-neutral reviewer");
			}
			Files.writeString(semanticPlan, validSemanticPlan);
			Files.writeString(semanticPlan, validSemanticPlan.replace("fixture evidence output-1-0",
				"changed fixture evidence output-1-0"));
			try
			{
				editor.replay(manifest);
				throw new AssertionError("Expected replay to reject a semantic plan that diverges from its ledger");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("exactly reproduce the declared");
			}
			Files.writeString(semanticPlan, validSemanticPlan);
			Files.writeString(semanticPlan, validSemanticPlan.replace(
				"\"rejectedEarlierOutputs\":[{\"output\":\"output-1\",\"reason\":\"Fixture output lacks " +
					"the later behavior.\"}]",
				"\"rejectedEarlierOutputs\":[]"));
			try
			{
				editor.replay(manifest);
				throw new AssertionError("Expected replay to reject an unreviewed earlier output");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("must reject every earlier retained output");
			}
			Files.writeString(semanticPlan, validSemanticPlan);
			Files.writeString(semanticPlan, validSemanticPlan.replace(
				"\"combinedCandidateBlockers\":[{\"candidateId\":\"output-1-1\",\"reason\":\"Fixture artifacts " +
					"require one output.\"}]", "\"combinedCandidateBlockers\":[]"));
			try
			{
				editor.replay(manifest);
				throw new AssertionError("Expected replay to reject an undocumented combined output");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("require a split blocker");
			}
			Files.writeString(semanticPlan, validSemanticPlan);

			editor.replay(manifest);

			String backup = git.run("for-each-ref", "--format=%(refname:short)", "refs/heads/backup/topic-replay-*").trim();
			requireThat(backup, "backup").isNotBlank();
			requireThat(Integer.parseInt(git.run("rev-list", "--count", base + "..HEAD").trim()), "replacementCount").
				isEqualTo(2);
			requireThat(Files.readString(repository.resolve("feature.txt")).strip(), "feature").isEqualTo("corrected");
			requireThat(Files.readString(repository.resolve("note.txt")).strip(), "note").isEqualTo("supplemental");
			editor.runGate(manifest, "replay-result", List.of("/bin/sh", "-c", "test -f feature.txt -a -f note.txt"));
			requireThat(Files.isRegularFile(replay.resolve("gates/replay-result.log")), "gateLog").isTrue();
			try
			{
				editor.finalizeReplay(manifest);
				throw new AssertionError("Expected finalization to reject missing per-output gates");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("per-output-build");
			}
			try
			{
				editor.runOutputGate(manifest, "per-output-build", List.of("true"));
				throw new AssertionError("Expected per-output evidence to reject a no-op command");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("no-op command");
			}
			editor.runOutputGate(manifest, "per-output-build", "output-1",
				List.of("/bin/sh", "-c", "test -f feature.txt"));
			requireThat(Files.isRegularFile(replay.resolve("gates/per-output-build-output-1.log")), "firstBuildGateLog").
				isTrue();
			requireThat(Files.exists(replay.resolve("gates/per-output-build-output-2.log")), "secondBuildGateLog").isFalse();
			editor.runOutputGate(manifest, "per-output-build", List.of("/bin/sh", "-c", "test -f feature.txt"));
			requireThat(Files.isRegularFile(replay.resolve("gates/per-output-build-output-2.log")), "secondBuildGateLog").
				isTrue();
			editor.runDeclaredOutputAcceptance(manifest, null);
			requireThat(Files.isRegularFile(replay.resolve("gates/per-output-acceptance-output-1.log")),
				"firstAcceptanceGateLog").isTrue();
			requireThat(Files.isRegularFile(replay.resolve("gates/per-output-acceptance-output-2.log")),
				"secondAcceptanceGateLog").isTrue();
			editor.verifyIdentity(base, "HEAD", "CAT Test", "cat-test@example.invalid");
			git.run("branch", "-D", backup);
			try
			{
				editor.finalizeReplay(manifest);
				throw new AssertionError("Expected finalization to retain its receipt when backup cleanup fails");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("recorded backup branch is missing");
			}
			Path receipt = replay.resolve("manifest.json.finalization-receipt.json");
			requireThat(Files.isRegularFile(receipt), "finalizationReceipt").isTrue();
			git.run("branch", backup, split);
			editor.finalizeReplay(manifest);
			editor.verifyFinalization(manifest, base, "HEAD");
			editor.verifyFinalization(manifest, base, git.run("rev-parse", "HEAD").trim());
			Files.writeString(replay.resolve("gates/replay-result.log"), "tampered\n");
			try
			{
				editor.verifyFinalization(manifest, base, git.run("rev-parse", "HEAD").trim());
				throw new AssertionError("Expected finalization verification to reject changed gate evidence");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("evidence changed");
			}
			Files.writeString(featurePatch, Files.readString(featurePatch) + "\n");
			try
			{
				editor.verifyFinalization(manifest, base, git.run("rev-parse", "HEAD").trim());
				throw new AssertionError("Expected finalization verification to reject a changed source patch");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("source-patch hash does not match");
			}
			requireThat(git.run("for-each-ref", "--format=%(refname:short)", "refs/heads/" + backup).trim(),
				"deletedBackup").isEmpty();
			requireThat(git.run("for-each-ref", "--format=%(refname:short)", "refs/heads/backup/unrelated").trim(),
				"unrelatedBackup").isEqualTo("backup/unrelated");
		}
	}
}

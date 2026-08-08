package io.github.cowwoc.cat.common.git.replay;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.commit;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.invocationManifest;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayReviewFixtures.originReviews;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayReviewFixtures.predecessors;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.git.Git;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests origin-review evidence through {@link GitTopicReplayEditor}.
 */
public final class EditorOriginReviewTest
{
	/**
	 * Creates the test fixture.
	 */
	public EditorOriginReviewTest()
	{
	}

	/**
	 * Verifies the editor supplies predecessor facts and reports their recorded semantic decisions.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void scaffoldsAndAuditsOriginDecisions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
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
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, "HEAD", manifest);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginReviewScaffold(manifest, output);
			JsonNode scaffold = scope.json().readTree(output.toString(StandardCharsets.UTF_8));
			requireThat(scaffold.size(), "scaffoldCount").isEqualTo(2);
			requireThat(scaffold.get(0).path("path").stringValue(), "firstPath").isEqualTo("feature.txt");
			requireThat(scaffold.get(0).path("candidates").get(0).path("output").stringValue(), "firstOutput").
				isEqualTo("output-1");
			output.reset();
			editor.writeOriginReviewAudit(manifest, output);
			JsonNode unreviewed = scope.json().readTree(output.toString(StandardCharsets.UTF_8));
			requireThat(unreviewed.get(0).path("status").stringValue(), "unreviewedStatus").isEqualTo("unreviewed");
			requireThat(unreviewed.get(0).path("path").stringValue(), "unreviewedPath").isEqualTo("feature.txt");
			Path reviews = manifest.resolveSibling("origin-reviews.json");
			Files.writeString(reviews, originReviews(scaffold));
			editor.recordOriginReviews(manifest, reviews);
			output.reset();
			editor.writeOriginReviewAudit(manifest, output);
			JsonNode audit = scope.json().readTree(output.toString(StandardCharsets.UTF_8));
			requireThat(audit.get(0).path("status").stringValue(), "firstStatus").isEqualTo("reviewed");
			requireThat(audit.get(0).path("classification").stringValue(), "firstClassification").isEqualTo("same-unit");
			requireThat(audit.get(0).path("destination").stringValue(), "firstDestination").isEqualTo("output-1");
			requireThat(scope.json().readTree(Files.readString(manifest.resolveSibling("manifest.json.origin-reviews.json"))).
				get(0).path("predecessors").get(0).stringValue(), "recordedPredecessor").isEqualTo(feature);
		}
	}

	/**
	 * Verifies an origin decision cannot retain a correction after its earlier same-unit output without a prerequisite.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void rejectsUnexplainedLaterOriginDecision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository, "feature.txt", "original\n", "feature");
			commit(git, repository, "feature.txt", "corrected\n", "correction");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, "HEAD", manifest);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginReviewScaffold(manifest, output);
			Path reviews = manifest.resolveSibling("origin-reviews.json");
			JsonNode scaffold = scope.json().readTree(output.toString(StandardCharsets.UTF_8));
			Files.writeString(reviews, originReviews(scaffold, "output-2"));

			try
			{
				editor.recordOriginReviews(manifest, reviews);
				throw new AssertionError("Expected the unexplained later origin decision to be rejected");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failureMessage").contains("feature.txt").contains(
					"earlier possible output").contains("Select the earlier output").contains("required prerequisite");
			}
		}
	}

	/**
	 * Verifies a reviewer may classify a later change to the same path as an independent responsibility.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void recordsDifferentUnitOriginDecision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository, "shared.txt", "feature\n", "feature");
			commit(git, repository, "shared.txt", "independent note\n", "note");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, "HEAD", manifest);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginReviewScaffold(manifest, output);
			JsonNode scaffold = scope.json().readTree(output.toString(StandardCharsets.UTF_8));
			Path reviews = manifest.resolveSibling("origin-reviews.json");
			Files.writeString(reviews, """
				[{"inventoryId":"%s","classification":"different-unit","affectedUnit":"independent note",
				"owningResponsibility":"independent note","predecessorRelationship":
				"introduces-independent-responsibility","evidence":
				"The later text replaces the same file but documents an independent topic."}]
				""".formatted(scaffold.get(0).path("inventoryId").stringValue()));

			editor.recordOriginReviews(manifest, reviews);

			JsonNode recorded = scope.json().readTree(Files.readString(manifest.resolveSibling(
				"manifest.json.origin-reviews.json")));
			requireThat(recorded.get(0).path("classification").stringValue(), "classification").isEqualTo(
				"different-unit");
		}
	}

	/**
	 * Verifies a reviewer may record that a later change reverses an earlier same-unit change.
	 *
	 * @throws IOException if the fixture or review operation cannot be created
	 */
	@Test
	public void recordsRevertedPredecessorOriginDecision() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository, "feature.txt", "feature\n", "feature");
			Files.delete(repository.resolve("feature.txt"));
			git.run("add", "--all");
			git.run("commit", "--quiet", "--message", "revert feature");
			Path manifest = invocationManifest(repository);
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, "HEAD", manifest);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginReviewScaffold(manifest, output);
			JsonNode candidate = scope.json().readTree(output.toString(StandardCharsets.UTF_8)).get(0);
			Path reviews = manifest.resolveSibling("origin-reviews.json");
			Files.writeString(reviews, """
				[{"inventoryId":"%s","predecessors":%s,"classification":"same-unit",
				"affectedUnit":"feature removal","owningResponsibility":"feature",
				"predecessorRelationship":"reverts-predecessor","destination":"output-1",
				"rejectedDestinations":[],"evidence":"The later change removes the feature introduced by its predecessor."}]
				""".formatted(candidate.path("inventoryId").stringValue(), predecessors(candidate.path("candidates"))));

			editor.recordOriginReviews(manifest, reviews);

			JsonNode recorded = scope.json().readTree(Files.readString(manifest.resolveSibling(
				"manifest.json.origin-reviews.json")));
			requireThat(recorded.get(0).path("predecessorRelationship").stringValue(), "relationship").isEqualTo(
				"reverts-predecessor");
		}
	}
}

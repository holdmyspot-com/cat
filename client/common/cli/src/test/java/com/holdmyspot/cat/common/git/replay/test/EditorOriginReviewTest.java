package com.holdmyspot.cat.common.git.replay.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static com.holdmyspot.cat.common.git.replay.test.OriginReviewFixtures.writeManifest;
import static com.holdmyspot.cat.common.git.replay.test.TopicReplayReviewFixtures.originReviews;
import static com.holdmyspot.cat.common.git.replay.test.TopicReplayReviewFixtures.predecessors;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.replay.GitTopicReplayEditor;
import com.holdmyspot.cat.common.git.replay.ReplayJson;
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "origin-review-"));
			Path manifest = writeManifest(ReplayJson.newMapper(), repository, false, "feature.txt", "secondary.txt");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository, repository, Yielding.fileReplacer());
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginReviewScaffold(manifest, output);
			JsonNode scaffold = ReplayJson.newMapper().readTree(output.toString(StandardCharsets.UTF_8));
			requireThat(scaffold.size(), "scaffoldCount").isEqualTo(2);
			requireThat(scaffold.get(0).path("path").stringValue(), "firstPath").isEqualTo("feature.txt");
			requireThat(scaffold.get(0).path("candidates").get(0).path("output").stringValue(), "firstOutput").
				isEqualTo("output_1");
			output.reset();
			editor.writeOriginReviewAudit(manifest, output);
			JsonNode unreviewed = ReplayJson.newMapper().readTree(output.toString(StandardCharsets.UTF_8));
			requireThat(unreviewed.get(0).path("status").stringValue(), "unreviewedStatus").isEqualTo("unreviewed");
			requireThat(unreviewed.get(0).path("path").stringValue(), "unreviewedPath").isEqualTo("feature.txt");
			Path reviews = manifest.resolveSibling("origin-reviews.json");
			Files.writeString(reviews, originReviews(scaffold));
			editor.recordOriginReviews(manifest, reviews);
			output.reset();
			editor.writeOriginReviewAudit(manifest, output);
			JsonNode audit = ReplayJson.newMapper().readTree(output.toString(StandardCharsets.UTF_8));
			requireThat(audit.get(0).path("status").stringValue(), "firstStatus").isEqualTo("reviewed");
			requireThat(audit.get(0).path("classification").stringValue(), "firstClassification").isEqualTo("same_unit");
			requireThat(audit.get(0).path("destination").stringValue(), "firstDestination").isEqualTo("output_1");
			requireThat(ReplayJson.newMapper().readTree(Files.readString(manifest.resolveSibling(
				"manifest.json.origin-reviews.json"))).get(0).path("predecessors").get(0).stringValue(),
				"recordedPredecessor").isEqualTo("source-1");
		}
	}

	/**
	 * Verifies an origin decision cannot retain a correction after its earlier same-unit output without a prerequisite.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		"(?s).*feature.txt.*earlier possible output.*Select the earlier output.*required prerequisite.*")
	public void rejectsUnexplainedLaterOriginDecision() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "origin-review-"));
			Path manifest = writeManifest(ReplayJson.newMapper(), repository, false, "feature.txt");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository, repository, Yielding.fileReplacer());
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginReviewScaffold(manifest, output);
			Path reviews = manifest.resolveSibling("origin-reviews.json");
			JsonNode scaffold = ReplayJson.newMapper().readTree(output.toString(StandardCharsets.UTF_8));
			Files.writeString(reviews, originReviews(scaffold, "output_2"));

			editor.recordOriginReviews(manifest, reviews);
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "origin-review-"));
			Path manifest = writeManifest(ReplayJson.newMapper(), repository, false, "shared.txt");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository, repository, Yielding.fileReplacer());
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginReviewScaffold(manifest, output);
			JsonNode scaffold = ReplayJson.newMapper().readTree(output.toString(StandardCharsets.UTF_8));
			Path reviews = manifest.resolveSibling("origin-reviews.json");
			Files.writeString(reviews, """
				[{"inventory_id":"%s","classification":"different_unit","affected_unit":"independent note",
				"owning_responsibility":"independent note","predecessor_relationship":
				"introduces_independent_responsibility","evidence":
				"The later text replaces the same file but documents an independent topic."}]
				""".formatted(scaffold.get(0).path("inventory_id").stringValue()));

			editor.recordOriginReviews(manifest, reviews);

			JsonNode recorded = ReplayJson.newMapper().readTree(Files.readString(manifest.resolveSibling(
				"manifest.json.origin-reviews.json")));
			requireThat(recorded.get(0).path("classification").stringValue(), "classification").isEqualTo(
				"different_unit");
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path repository = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "origin-review-"));
			Path manifest = writeManifest(ReplayJson.newMapper(), repository, true, "feature.txt");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository, repository, Yielding.fileReplacer());
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			editor.writeOriginReviewScaffold(manifest, output);
			JsonNode candidate = ReplayJson.newMapper().readTree(output.toString(StandardCharsets.UTF_8)).get(0);
			Path reviews = manifest.resolveSibling("origin-reviews.json");
			Files.writeString(reviews, """
				[{"inventory_id":"%s","predecessors":%s,"classification":"same_unit",
				"affected_unit":"feature removal","owning_responsibility":"feature",
				"predecessor_relationship":"reverts_predecessor","destination":"output_1",
				"rejected_destinations":[],"evidence":"The later change removes the feature introduced by its predecessor."}]
				""".formatted(candidate.path("inventory_id").stringValue(), predecessors(candidate.path("candidates"))));

			editor.recordOriginReviews(manifest, reviews);

			JsonNode recorded = ReplayJson.newMapper().readTree(Files.readString(manifest.resolveSibling(
				"manifest.json.origin-reviews.json")));
			requireThat(recorded.get(0).path("predecessor_relationship").stringValue(), "relationship").isEqualTo(
				"reverts_predecessor");
		}
	}
}

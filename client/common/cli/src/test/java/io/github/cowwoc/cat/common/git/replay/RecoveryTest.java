package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.commit;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.initializeRepository;
import static io.github.cowwoc.cat.common.git.replay.TopicReplayRepository.invocationDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests Git topic-replay recovery behavior.
 */
public final class RecoveryTest
{
	/**
	 * Creates the test fixture.
	 */
	public RecoveryTest()
	{
	}

	/**
	 * Verifies a current replay revalidates its sealed prefix and reports the affected suffix.
	 *
	 * @throws IOException if the fixture or replay operation cannot be created
	 */
	@Test
	public void validatesSealedPrefixAndSuffix() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			 ToolScope scope = new ToolScope())
		{
			Path repository = cleanup.add(Files.createTempDirectory("cat-topic-replay-"));
			initializeRepository(repository);
			Git git = new Git(repository);
			String base = git.run("rev-parse", "HEAD").trim();
			commit(git, repository, "first.txt", "first\n", "first");
			commit(git, repository, "second.txt", "second\n", "second");
			Path previousDirectory = invocationDirectory(repository, "previous");
			Path previous = previousDirectory.resolve("manifest.json");
			GitTopicReplayEditor editor = new GitTopicReplayEditor(repository);
			editor.prepare(base, "HEAD", previous);
			editor.bootstrapPreservedOutputBatches(previous);
			JsonNode previousManifest = scope.json().readTree(Files.readString(previous));
			String first = previousManifest.path("sources").get(0).path("hash").stringValue();
			String second = previousManifest.path("sources").get(1).path("hash").stringValue();
			Path sourceReviews = previousDirectory.resolve("source-reviews.json");
			Files.writeString(sourceReviews, """
				[{"source":"%s","disposition":"whole","destinations":["output-1"],
				"counterfactual":"The first source supplies the first retained output.",
				"evidence":"The first source remains in its preserved output."},
				{"source":"%s","disposition":"whole","destinations":["output-2"],
				"counterfactual":"The second source supplies the second retained output.",
				"evidence":"The second source remains in its preserved output."}]
				""".formatted(first, second));
			editor.recordSourceReviews(previous, sourceReviews);
			Path originReviews = previousDirectory.resolve("origin-reviews.json");
			Files.writeString(originReviews, "[]\n");
			editor.recordOriginReviews(previous, originReviews);
			Path plan = previousDirectory.resolve("output-plan.json");
			Files.writeString(plan, """
				[{"outputId":"output-1","disposition":"retained","batches":[
				{"id":"first","source":"%s","patch":"patches/%s.raw.patch"}]},
				{"outputId":"output-2","disposition":"retained","batches":[
				{"id":"second","source":"%s","patch":"patches/%s.raw.patch"}]}]
				""".formatted(first, first, second, second));
			editor.sealOutputPlan(previous, plan);
			editor.recordPlannedOutput(previous, "output-1");
			editor.validateSealedPrefix(previous);
			requireThat(editor.affectedSuffix(previous, "output-1"), "affectedSuffix").containsExactly(List.of(
				"output-1", "output-2"), "expectedSuffix");
		}
	}
}

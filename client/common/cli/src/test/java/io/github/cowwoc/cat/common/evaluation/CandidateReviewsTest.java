package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link CandidateReviews}.
 */
public final class CandidateReviewsTest
{
	/**
	 * Creates the test fixture.
	 */
	public CandidateReviewsTest()
	{
	}

	/**
	 * Verifies an independent reviewer receives only copied selected evidence and one writable result path.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void createsSealedReviewBundle() throws IOException
	{
		try (ToolScope scope = new ToolScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-reviews"));
			Path temporary = root.resolve(".cat/work/temp");
			Path question = write(root, "question.md", "Can this evidence satisfy the assertion?\n");
			Path sourceFile = write(root, "evidence/result.txt", "result\n");
			Path sourceDirectory = write(root, "evidence/project/source.txt", "source\n").getParent();

			ObjectNode receipt = CandidateReviews.createBundle(scope.json(), temporary, question,
				temporary.resolve("review-bundle"), "review-result.md", List.of(sourceFile, sourceDirectory));

			Path result = Path.of(receipt.path("result").asString());
			requireThat(CandidateReviews.isPrepared(result.getParent()), "preparedBundle").isTrue();
			requireThat(Path.of(receipt.path("question").asString()), "question").isRegularFile();
			requireThat(result.getParent(), "resultParent").isDirectory();
			requireThat(Path.of(receipt.path("evidence").get(0).asString()), "copiedFile").isRegularFile();
			requireThat(Path.of(receipt.path("evidence").get(1).asString()).resolve("source.txt"), "copiedDirectoryFile").
			isRegularFile();
		}
	}

	/**
	 * Verifies the recurrence ledger atomically binds a completed conclusion to the pending review that owns its bundle.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsRecurrenceConclusion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-reviews"));
			Path state = write(root, "candidate-state.json", "{}");
			Path bundle = root.resolve("recurrence-review");
			Files.createDirectories(bundle);
			Files.writeString(bundle.resolve(".cat-prompt-review-bundle"), "\n");
			Path result = write(bundle, "review-result.json", """
				{"findings":[{
				  "signature_key":"required-explanation",
				  "decision":"restructure",
				  "preserved_obligations":["State the caller-facing result."]
				}]}
				""");

			CandidateReviews.prepareRecurrence(scope.json(), state, bundle, root.resolve("source-run.json"),
				List.of("required-explanation"));
			ObjectNode completed = CandidateReviews.completeRecurrence(scope.json(), state, bundle, result);

			requireThat(completed.path("status").asString(), "completedStatus").isEqualTo("completed");
			ObjectNode recorded = (ObjectNode) scope.json().readTree(state.toFile()).path("recurrence_reviews").get(0);
			requireThat(recorded.path("result").asString(), "recordedResult").isEqualTo(result.toString());
			requireThat(recorded.path("result_sha256").asString(), "recordedResultHash").isNotBlank();
		}
	}

	/**
	 * Writes one fixture file below the test root.
	 *
	 * @param root the fixture root
	 * @param name the root-relative file name
	 * @param contents the file content
	 * @return the created fixture file
	 * @throws IOException if the fixture cannot be written
	 */
	private Path write(Path root, String name, String contents) throws IOException
	{
		Path file = root.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, contents);
		return file;
	}
}

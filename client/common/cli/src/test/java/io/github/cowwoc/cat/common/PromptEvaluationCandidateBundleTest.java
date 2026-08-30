package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests prompt-evaluation workflow composition that materializes candidate bundles.
 */
public final class PromptEvaluationCandidateBundleTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationCandidateBundleTest()
	{
	}

	/**
	 * Verifies the evaluator materialization boundary retains a candidate only within CAT work storage.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void materializesWithinCatWork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-bundle"));
			Path workRoot = root.resolve(".cat/work");
			Path sourceRules = write(root, "source-rules/common/write-clearly.md", "Current rule\n").getParent().
				getParent();
			Path bundle = workRoot.resolve("candidate");

			ObjectNode receipt = PromptEvaluation.materializeCandidateBundle(workRoot, sourceRules, bundle,
				"common/write-clearly.md", List.of());

			requireThat(receipt.path("primary_rule").asString(), "primaryRule").isEqualTo("common/write-clearly.md");
			requireThat(Files.readString(bundle.resolve("rules/common/write-clearly.md")), "bundleContents").
				isEqualTo("Current rule\n");
		}
	}

	/**
	 * Verifies the evaluator materialization boundary rejects a bundle location outside CAT work storage.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Candidate bundle must remain beneath the CAT work root.*")
	public void rejectsBundleOutsideCatWork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-bundle"));
			Path sourceRules = write(root, "source-rules/common/write-clearly.md", "Current rule\n").getParent().
				getParent();

			PromptEvaluation.materializeCandidateBundle(root.resolve(".cat/work"), sourceRules, root.resolve("outside"),
				"common/write-clearly.md", List.of());
		}
	}

	/**
	 * Verifies historical materialization reads the selected committed rule version rather than the worktree.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void materializesHistoricalCandidate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-bundle"));
			Path repository = root.resolve("repository");
			Files.createDirectories(repository);
			write(repository, "rules/common/write-clearly.md", "Committed rule\n");
			write(repository, "rules/common/default-rules.md", "Committed router\n");
			runGit(repository, "init", "--quiet");
			runGit(repository, "add", "--all");
			runGit(repository, "-c", "user.name=CAT test", "-c", "user.email=cat-test@example.invalid",
				"commit", "--quiet", "-m", "initial rules");
			String revision = runGit(repository, "rev-parse", "HEAD").strip();
			write(repository, "rules/common/write-clearly.md", "Uncommitted rule\n");
			Path bundle = root.resolve(".cat/work/historical-candidate");

			ObjectNode receipt = PromptEvaluation.materializeCandidateBundleFromGit(root.resolve(".cat/work"), repository,
				revision, "rules", bundle, "common/write-clearly.md", List.of("common/default-rules.md"),
				Map.of("common/target-audience.md", "common/write-clearly.md"));

			requireThat(Files.readString(bundle.resolve("rules/common/write-clearly.md")), "historicalPrimary").
				isEqualTo("Committed rule\n");
			requireThat(Files.readString(bundle.resolve("rules/common/default-rules.md")), "historicalRouter").
				isEqualTo("Committed router\n");
			requireThat(receipt.path("sha256").asString(), "historicalCandidateFingerprint").isNotBlank();
			requireThat(receipt.path("route_replacements").path("common/target-audience.md").asString(),
				"historicalRouteReplacement").isEqualTo("common/write-clearly.md");
		}
	}

	/**
	 * Writes one fixture file.
	 *
	 * @param root the fixture root
	 * @param name the root-relative file name
	 * @param contents the file content
	 * @return the written file
	 * @throws IOException if the fixture cannot be written
	 */
	private static Path write(Path root, String name, String contents) throws IOException
	{
		Path file = root.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, contents);
		return file;
	}

	/**
	 * Runs Git while preparing a committed historical-rule fixture.
	 *
	 * @param repository the fixture repository
	 * @param arguments the Git arguments
	 * @return Git's standard output
	 * @throws IOException if Git cannot prepare the fixture
	 */
	private static String runGit(Path repository, String... arguments) throws IOException
	{
		String[] command = new String[arguments.length + 1];
		command[0] = "git";
		System.arraycopy(arguments, 0, command, 1, arguments.length);
		ProcessBuilder builder = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true);
		try (Process process = builder.start())
		{
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			try
			{
				if (process.waitFor() != 0)
					throw new IOException("Git fixture command failed: " + String.join(" ", command) + ": " + output);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while preparing Git fixture", e);
			}
			return output;
		}
	}
}

package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests Git-facing candidate delivery behavior in a test-owned repository.
 */
public final class CandidateGitTransactionTest
{
	/**
	 * Creates the test fixture.
	 */
	public CandidateGitTransactionTest()
	{
	}

	/**
	 * Delivery leaves HEAD unchanged; the test-owned Git user commits, reverts, and then requests a second revision.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws InterruptedException if Git fixture execution is interrupted
	 */
	@Test
	public void leavesGitActionsToUser() throws IOException, InterruptedException
	{
		Path project = Files.createTempDirectory("candidate-git");
		try
		{
			git(project, "init");
			git(project, "config", "user.email", "test@example.invalid");
			git(project, "config", "user.name", "Candidate Test");
			write(project, "prompts/rule.md", "base");
			git(project, "add", ".");
			git(project, "commit", "-m", "base");
			PromptSnapshot baseline = PromptSnapshot.capture(project, List.of(Path.of("prompts")));
			PromptSnapshot first = snapshot("candidate-git-first", "first");
			PromptSnapshot second = snapshot("candidate-git-second", "second");
			try (CandidateDeliveryFixture fixture = new CandidateDeliveryFixture(project,
				Map.of(baseline.id(), baseline, first.id(), first, second.id(), second), List.of(Path.of("prompts"))))
			{
			String initialHead = git(project, "rev-parse", "HEAD");
			fixture.delivery().reconcile(baseline.id(), null, first.id());
			requireThat(git(project, "rev-parse", "HEAD"), "headAfterDelivery").isEqualTo(initialHead);
			git(project, "add", ".");
			git(project, "commit", "-m", "user accepts candidate");
			git(project, "revert", "--no-edit", "HEAD");
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "userRevert").isEqualTo("base");

			String revertedHead = git(project, "rev-parse", "HEAD");
			fixture.delivery().reconcile(baseline.id(), null, second.id());
			requireThat(git(project, "rev-parse", "HEAD"), "headAfterSecondDelivery").isEqualTo(revertedHead);
			requireThat(Files.readString(project.resolve("prompts/rule.md")), "secondRevision").isEqualTo("second");
			}
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(project);
		}
	}

	/**
	 * Captures one source-owned candidate revision.
	 *
	 * @param prefix the temporary-directory prefix
	 * @param content the prompt content
	 * @return the captured revision
	 */
	private static PromptSnapshot snapshot(String prefix, String content) throws IOException
	{
		Path source = Files.createTempDirectory(prefix);
		try
		{
			write(source, "prompts/rule.md", content);
			return PromptSnapshot.capture(source, List.of(Path.of("prompts")));
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(source);
		}
	}

	/**
	 * Runs a Git command in the test-owned repository.
	 *
	 * @param project the test-owned repository
	 * @param arguments the Git command arguments
	 * @return standard output from the completed command
	 */
	private static String git(Path project, String... arguments) throws IOException, InterruptedException
	{
		List<String> command = new java.util.ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		try (Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start())
		{
			String output = new String(process.getInputStream().readAllBytes(), UTF_8).strip();
			if (process.waitFor() != 0)
				throw new IOException("Git fixture failed: " + output);
			return output;
		}
	}

	/**
	 * Writes a test-owned file.
	 *
	 * @param root the fixture root
	 * @param relativePath the fixture-relative file path
	 * @param content the exact file content
	 */
	private static void write(Path root, String relativePath, String content) throws IOException
	{
		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, UTF_8);
	}
}

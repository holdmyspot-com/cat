package io.github.cowwoc.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.runner.ClaudeRunner;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeRunner}.
 */
public final class ClaudeRunnerTest
{
	/**
	 * Verifies the Claude launcher creates its Bubblewrap launch configuration beside the run-owned Claude temporary
	 * directory so run cleanup removes the workaround with the completed run.
	 */
	@Test
	public void resolvesRunLaunchConfiguration()
	{
		Path temporaryDirectory = Path.of("/system-temporary-directory/.cat/claude/claude/run-id/claude");

		requireThat(ClaudeRunner.launchConfigurationPath(temporaryDirectory), "launchConfiguration").isEqualTo(
			Path.of("/system-temporary-directory/.cat/claude/claude/run-id/.claude/launch.json"));
	}

	/**
	 * Verifies an isolated home uses a credential file only when no usable environment authentication is available.
	 */
	@Test
	public void copiesCredentialFileWhenNeeded()
	{
		requireThat(ClaudeRunner.requiresCredentialFiles(Map.of()), "withoutAuthentication").isTrue();
		requireThat(ClaudeRunner.requiresCredentialFiles(Map.of("ANTHROPIC_API_KEY", "token")),
			"withAuthentication").isFalse();
	}

	/**
	 * Verifies an isolated one-shot session cannot retain or resume host conversation state or override its generated
	 * sandbox policy.
	 */
	@Test
	public void disablesSessionPersistence()
	{
		List<String> command = ClaudeRunner.command("claude", "sonnet", Path.of("marketplace"), false,
			Path.of("hook-results"));

		requireThat(command, "command").contains("--no-session-persistence");
		requireThat(command, "command").doesNotContain("--settings");
	}

	/**
	 * Verifies a CAT run grants Claude access to spilled hook context without exposing the isolated home.
	 */
	@Test
	public void permitsOnlyHookResultsForSpilledContext()
	{
		List<String> command = ClaudeRunner.command("claude", "sonnet", Path.of("marketplace"), true,
			Path.of("hook-results"));

		requireThat(command, "command").contains("marketplace/plugins/cat").contains("hook-results").
			doesNotContain("home");
	}

	/**
	 * Verifies a Claude worktree subagent can resolve its reported spill-context path without access beyond hook results.
	 */
	@Test
	public void aliasesClaudeReportedSpillContextPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path capture = cleanup.add(Files.createTempDirectory("claude-capture-"));
			Path directory = Files.createDirectories(capture.resolve("runs/claude/abc123-def456"));
			HarnessRun run = new HarnessRun("claude", "abc123-def456", directory);
			Path hookResults = ClaudeRunner.createHookResultsDirectory(directory.resolve("home"), run);
			Path worktree = directory.resolve("project").toAbsolutePath().normalize();
			String canonicalName = worktree.toString().replaceAll("[^A-Za-z0-9_-]", "-");
			String parentName = capture.resolve("runs").toAbsolutePath().normalize().toString().replaceAll(
				"[^A-Za-z0-9_-]", "-");
			Path canonical = hookResults.resolve(canonicalName);
			Path alias = hookResults.resolve(parentName).resolve("claude/abc123-def456-project");

			requireThat(Files.isSymbolicLink(alias), "alias").isTrue();
			requireThat(Files.isSameFile(alias, canonical), "aliasTarget").isTrue();
		}
	}
}

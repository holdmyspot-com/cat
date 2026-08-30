package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
	 * Verifies an unowned Claude route resolves the fixed CAT evaluation profile instead of an account default.
	 *
	 * @throws IOException if profile resolution fails
	 */
	@Test
	public void resolvesFixedDefaultProfile() throws IOException
	{
		requireThat(ClaudeRunnerCli.resolveProfile(new String[] {"--resolve-profile"}), "profile").isEqualTo("""
			harness=claude
			owner=default
			model=haiku-4.5
			effort=medium
			""");
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
	 * Verifies the runner passes an explicit profile and selected agent through to Claude.
	 */
	@Test
	public void configuresExplicitProfileAndAgent()
	{
		List<String> command = ClaudeRunner.command("claude", "haiku-4.5", "medium", "testing-low",
			Path.of("marketplace"), true, Path.of("hook-results"), List.of());

		requireThat(command, "command").contains("--model").contains("haiku-4.5").contains("--effort").
			contains("medium").contains("--agent").contains("testing-low");
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
	 * Verifies a validator exposes a raw evidence file through its containing read-only directory because Claude's
	 * {@code --add-dir} contract accepts directories, not individual files.
	 */
	@Test
	public void exposesEvidenceFilesThroughDirectories() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path evidence = cleanup.add(Files.createTempDirectory("claude-evidence-"));
			Path project = Files.createDirectory(evidence.resolve("project"));
			Path rawResult = Files.writeString(evidence.resolve("executor-output.jsonl"), "events\n");

			List<String> command = ClaudeRunner.command("claude", "sonnet", Path.of("marketplace"), false,
				Path.of("hook-results"), List.of(project, rawResult));

			requireThat(command, "command").contains(project.toString()).contains(evidence.toString()).
				doesNotContain(rawResult.toString());
		}
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

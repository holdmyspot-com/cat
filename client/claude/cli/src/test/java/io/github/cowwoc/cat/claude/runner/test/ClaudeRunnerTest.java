package io.github.cowwoc.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.runner.ClaudeRunner;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
		Path temporaryDirectory = Path.of("/system-temporary-directory/.cat/claude/claude/run-id/claude").toAbsolutePath();

		requireThat(ClaudeRunner.launchConfigurationPath(temporaryDirectory), "launchConfiguration").isEqualTo(
			Path.of("/system-temporary-directory/.cat/claude/claude/run-id/.claude/launch.json").toAbsolutePath());
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

		requireThat(command, "command").contains(Path.of("marketplace/plugins/cat").toString()).contains("hook-results").
			doesNotContain("home");
	}

	/**
	 * Verifies a plugin under test installs from the marketplace that bears its name.
	 */
	// Requirement: R-W12
	@Test
	public void installsPluginUnderTestByName()
	{
		List<List<String>> commands = ClaudeRunner.pluginSetupCommands("claude", Path.of("release-docs-marketplace"),
			"release-docs");

		requireThat(commands, "commands").isEqualTo(List.of(
			List.of("claude", "plugin", "marketplace", "add", "release-docs-marketplace"),
			List.of("claude", "plugin", "install", "release-docs@release-docs")));
	}

	/**
	 * Verifies a run with a plugin under test grants Claude access to that plugin and to spilled hook context.
	 */
	// Requirement: R-W12
	@Test
	public void permitsPluginUnderTestDirectory()
	{
		List<String> command = ClaudeRunner.command("claude", "sonnet", "", "",
			List.of(Path.of("release-docs-marketplace/plugins/release-docs")), Path.of("hook-results"), List.of());

		requireThat(command, "command").contains(Path.of("release-docs-marketplace/plugins/release-docs").toString()).
			contains("hook-results");
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

	/**
	 * Verifies that a session is denied reading the runner's records of its own run, namely the run's artifacts,
	 * manifest, lock and initial project, the login that the run copied from the host, and the other runs and
	 * fixtures under the run root, besides the paths that the caller denies. The session can still read its project,
	 * the rest of its home, its Claude temporary directory and the evidence that it validates.
	 *
	 * @throws IOException if the run cannot be created
	 */
	@Test
	public void deniesReadingRunRecordsOtherRunsAndLogin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = Files.createDirectories(Path.of(System.getProperty("cat.work.temp")).normalize());
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
			HarnessRun run = manager.create("claude");
			Path directory = run.directory();
			manager.snapshotProject(run);
			Files.createDirectories(directory.resolve("claude"));
			Path login = Files.writeString(Files.createDirectories(directory.resolve("home/.claude")).
				resolve(".credentials.json"), "{}");
			HarnessRun other = manager.create("claude");
			HarnessRun evidence = manager.create("claude");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));
			Path fixtures = Files.createDirectories(root.resolve("fixtures"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, run, List.of(evidenceArtifacts),
				List.of(heldOut));

			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(heldOut,
				directory.resolve("artifacts"), directory.resolve("run.json"), directory.resolve(".lock"),
				directory.resolve("initial-project"), login, other.directory(), evidence.directory().resolve("project"),
				evidence.directory().resolve("home"), evidence.directory().resolve("run.json"),
				evidence.directory().resolve(".lock"), fixtures));
		}
	}
}

package com.holdmyspot.cat.claude.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.ClaudeSessionEndHook;
import com.holdmyspot.cat.claude.secrets.ClaudeSessionStartHookSecrets;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.launcher.LauncherRecord;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart.DetachedStarter;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that Claude Code's SessionStart hook starts the session launcher of its Claude Code process before it injects
 * CAT's rules, and that its SessionEnd hook stops that launcher.
 */
public final class ClaudeSessionLauncherHookTest
{
	private static final String SESSION_ID = "3f2a6c1e-8b4d-4e7a-9c2f-1a5b6d7e8f90";
	private static final long CLAUDE_PID = 4242;
	private static final Instant CLAUDE_START = Instant.parse("2026-11-05T12:00:00.123Z");
	/**
	 * Claude Code's process, as the operating system reports it.
	 */
	private static final HarnessProcesses PROCESSES = pid ->
	{
		if (pid != CLAUDE_PID)
			return Optional.empty();
		return Optional.of(new ProcessDescription(pid, Optional.of(CLAUDE_START), Optional.of("/usr/bin/claude"),
			Optional.of(List.of())));
	};
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public ClaudeSessionLauncherHookTest()
	{
	}

	/**
	 * Verifies that the hook starts the plugin's launcher for the Claude Code process that {@code CLAUDE_PID} names,
	 * with the hook's data, before it writes its response.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsLauncherWithHookData() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-launcher-")).toRealPath();
			Fixture fixture = new Fixture(root);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			RecordingStarter starter = new RecordingStarter(output);

			String error = runSessionStart(fixture, fixture.environment(), Platform.LINUX_X64, starter, output);

			Path folder = new SessionLauncherChannel(fixture.home).launcherDirectory("claude", CLAUDE_PID,
				CLAUDE_START);
			requireThat(error, "error").isEmpty();
			requireThat(starter.commands, "commands").isEqualTo(List.of(List.of(
				fixture.plugin.resolve("bin/session-launcher").toString(), folder.toString())));
			requireThat(starter.outputBeforeStart, "outputBeforeStart").isEqualTo(List.of(0));
			requireThat(output.size(), "outputSize").isPositive();
			LauncherRecord expected = new LauncherRecord("claude", new ProcessIdentity(CLAUDE_PID, CLAUDE_START),
				SESSION_ID, fixture.project, Optional.of(fixture.transcript), "startup", fixture.configuration,
				PluginInfo.CAT_VERSION);
			requireThat(LauncherRecord.read(json, folder.resolve(SessionLauncherChannel.LAUNCHER_FILE)), "record").
				isEqualTo(expected);
		}
	}

	/**
	 * Verifies that the hook of a session that CAT's runner started starts no launcher, and still injects the rules.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsNoneInCatRun() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-launcher-")).toRealPath();
			Fixture fixture = new Fixture(root);
			Map<String, String> environment = new HashMap<>(fixture.environment());
			environment.put(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			RecordingStarter starter = new RecordingStarter(output);

			String error = runSessionStart(fixture, environment, Platform.LINUX_X64, starter, output);

			requireThat(error, "error").isEmpty();
			requireThat(starter.commands, "commands").isEmpty();
			requireThat(output.toString(UTF_8), "output").contains("additionalContext");
		}
	}

	/**
	 * Verifies that the hook starts no launcher on native Windows.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsNoneOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-launcher-")).toRealPath();
			Fixture fixture = new Fixture(root);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			RecordingStarter starter = new RecordingStarter(output);

			runSessionStart(fixture, fixture.environment(), Platform.WINDOWS_X64, starter, output);

			requireThat(starter.commands, "commands").isEmpty();
		}
	}

	/**
	 * Verifies that SessionEnd asks the launcher of its Claude Code process to stop.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void sessionEndStopsLauncher() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-launcher-")).toRealPath();
			Fixture fixture = new Fixture(root);
			Path folder = Files.createDirectories(new SessionLauncherChannel(fixture.home).launcherDirectory("claude",
				CLAUDE_PID, CLAUDE_START));

			String error = runSessionEnd(fixture, "logout");

			requireThat(error, "error").isEmpty();
			requireThat(Files.exists(folder.resolve(SessionLauncherChannel.STOP_FILE), LinkOption.NOFOLLOW_LINKS),
				"stop").isTrue();
		}
	}

	/**
	 * Verifies that SessionEnd leaves the launcher running when Claude Code clears the conversation, because the same
	 * Claude Code process goes on in a new session that the launcher serves.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void clearKeepsLauncher() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-launcher-")).toRealPath();
			Fixture fixture = new Fixture(root);
			Path folder = Files.createDirectories(new SessionLauncherChannel(fixture.home).launcherDirectory("claude",
				CLAUDE_PID, CLAUDE_START));

			String error = runSessionEnd(fixture, "clear");

			requireThat(error, "error").isEmpty();
			requireThat(Files.exists(folder.resolve(SessionLauncherChannel.STOP_FILE), LinkOption.NOFOLLOW_LINKS),
				"stop").isFalse();
		}
	}

	/**
	 * Runs the SessionStart hook.
	 *
	 * @param fixture     the session's folders
	 * @param environment the hook's environment
	 * @param platform    the platform that the hook runs on
	 * @param starter     starts the launcher
	 * @param output      receives the hook's response
	 * @return the hook's error output
	 * @throws IOException if the input cannot be written
	 */
	private String runSessionStart(Fixture fixture, Map<String, String> environment, Platform platform,
		DetachedStarter starter, ByteArrayOutputStream output) throws IOException
	{
		Map<String, Object> event = new HashMap<>();
		event.put("session_id", SESSION_ID);
		event.put("transcript_path", fixture.transcript.toString());
		event.put("cwd", fixture.project.toString());
		event.put("hook_event_name", "SessionStart");
		event.put("source", "startup");
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		ClaudeSessionStartHookSecrets.getClaudeSessionStartHookAccess().run(new String[0],
			new ByteArrayInputStream(json.writeValueAsBytes(event)), output, new PrintStream(error, true, UTF_8),
			environment, fixture.project, Yielding.waiting(), GitProjects.IGNORING, platform, PROCESSES, starter);
		return error.toString(UTF_8);
	}

	/**
	 * Runs the SessionEnd hook.
	 *
	 * @param fixture the session's folders
	 * @param reason  why the session ended
	 * @return the hook's error output
	 * @throws IOException if the input cannot be written
	 */
	private String runSessionEnd(Fixture fixture, String reason) throws IOException
	{
		Map<String, Object> event = Map.of("session_id", SESSION_ID, "cwd", fixture.project.toString(),
			"hook_event_name", "SessionEnd", "reason", reason);
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		ClaudeSessionEndHook.run(new String[0], new ByteArrayInputStream(json.writeValueAsBytes(event)),
			new PrintStream(error, true, UTF_8), fixture.environment(), fixture.project, Yielding.waiting(), PROCESSES,
			GitProjects.IGNORING);
		return error.toString(UTF_8);
	}

	/**
	 * The folders of a Claude Code session.
	 */
	private static final class Fixture
	{
		private final Path home;
		private final Path configuration;
		private final Path plugin;
		private final Path project;
		private final Path transcript;

		/**
		 * Creates the folders.
		 *
		 * @param root the test's folder
		 * @throws IOException if a folder cannot be created
		 */
		Fixture(Path root) throws IOException
		{
			home = Files.createDirectories(root.resolve("home"));
			configuration = Files.createDirectories(root.resolve("configuration"));
			plugin = Files.createDirectories(root.resolve("plugin"));
			Files.createDirectories(plugin.resolve("rules/common"));
			Files.writeString(plugin.resolve("rules/common/rules.md"), "rules");
			project = Files.createDirectories(root.resolve("project"));
			transcript = configuration.resolve("projects/" + SESSION_ID + ".jsonl");
		}

		/**
		 * Returns the hook's environment, which names the home in the home variable of the platform that runs the
		 * test and in that of Linux, the platform that a hook which starts a launcher simulates.
		 *
		 * @return the environment
		 */
		Map<String, String> environment()
		{
			Map<String, String> environment = new HashMap<>(Map.of("CLAUDE_PLUGIN_ROOT", plugin.toString(),
				"CLAUDE_PID", String.valueOf(CLAUDE_PID), "CLAUDE_PROJECT_DIR", project.toString(), "CLAUDE_CONFIG_DIR",
				configuration.toString()));
			environment.put(Platform.LINUX_X64.homeVariable(), home.toString());
			environment.put(Platform.current().homeVariable(), home.toString());
			return Map.copyOf(environment);
		}
	}

	/**
	 * Records the launchers that the hook starts, and how much of its response the hook had written by then.
	 */
	private static final class RecordingStarter implements DetachedStarter
	{
		private final ByteArrayOutputStream output;
		private final List<List<String>> commands = new ArrayList<>();
		private final List<Integer> outputBeforeStart = new ArrayList<>();

		/**
		 * Creates a starter.
		 *
		 * @param output the hook's response
		 */
		RecordingStarter(ByteArrayOutputStream output)
		{
			this.output = output;
		}

		@Override
		public ProcessHandle start(List<String> command, Path log)
		{
			commands.add(List.copyOf(command));
			outputBeforeStart.add(output.size());
			return ProcessHandle.current();
		}
	}
}

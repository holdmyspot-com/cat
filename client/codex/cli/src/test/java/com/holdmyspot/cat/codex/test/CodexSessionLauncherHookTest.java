package com.holdmyspot.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.secrets.CodexSessionStartHookSecrets;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.GitAnswers;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.launcher.LauncherRecord;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart.DetachedStarter;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
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
 * Tests that Codex's SessionStart hook starts the session launcher of the Codex process that runs it before it
 * injects CAT's rules.
 */
public final class CodexSessionLauncherHookTest
{
	private static final String SESSION_ID = "019a2b3c-4d5e-7f60-8a1b-2c3d4e5f6a7b";
	private static final long CODEX_PID = 5151;
	private static final Instant CODEX_START = Instant.parse("2026-11-05T12:00:00.456Z");
	/**
	 * The hook's ancestry, nearest first: the shell that runs the hook's command, then Codex.
	 */
	private static final List<ProcessDescription> ANCESTORS = List.of(
		new ProcessDescription(6000, Optional.of(CODEX_START.plusSeconds(1)), Optional.of("/bin/sh"),
			Optional.of(List.of("-c", "session-start"))),
		new ProcessDescription(CODEX_PID, Optional.of(CODEX_START), Optional.of("/usr/lib/codex/bin/codex"),
			Optional.of(List.of())));
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public CodexSessionLauncherHookTest()
	{
	}

	/**
	 * Verifies that the hook starts the plugin's launcher for the nearest Codex process among its ancestors, with the
	 * hook's data, before it writes its response.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsLauncherWithHookData() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-launcher-")).toRealPath();
			Fixture fixture = new Fixture(root);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			RecordingStarter starter = new RecordingStarter(output);

			String error = runSessionStart(fixture, fixture.environment(), Platform.LINUX_X64, starter, output);

			Path folder = new SessionLauncherChannel(fixture.home).launcherDirectory("codex", CODEX_PID, CODEX_START);
			requireThat(error, "error").isEmpty();
			requireThat(starter.commands, "commands").isEqualTo(List.of(List.of(
				fixture.plugin.resolve("bin/session-launcher").toString(), folder.toString())));
			requireThat(starter.outputBeforeStart, "outputBeforeStart").isEqualTo(List.of(0));
			requireThat(output.size(), "outputSize").isPositive();
			LauncherRecord expected = new LauncherRecord("codex", new ProcessIdentity(CODEX_PID, CODEX_START),
				SESSION_ID, fixture.project, Optional.of(fixture.transcript), "startup", fixture.codexHome,
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-launcher-")).toRealPath();
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
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-launcher-")).toRealPath();
			Fixture fixture = new Fixture(root);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			RecordingStarter starter = new RecordingStarter(output);

			runSessionStart(fixture, fixture.environment(), Platform.WINDOWS_X64, starter, output);

			requireThat(starter.commands, "commands").isEmpty();
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
		CodexSessionStartHookSecrets.getCodexSessionStartHookAccess().run(new String[0],
			new ByteArrayInputStream(json.writeValueAsBytes(event)), output, new PrintStream(error, true, UTF_8),
			environment, fixture.project, Yielding.waiting(), GitAnswers.IGNORED, platform, () -> ANCESTORS, starter);
		return error.toString(UTF_8);
	}

	/**
	 * The folders of a Codex session.
	 */
	private static final class Fixture
	{
		private final Path home;
		private final Path codexHome;
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
			codexHome = Files.createDirectories(root.resolve("codex-home"));
			plugin = Files.createDirectories(root.resolve("plugin"));
			Files.createDirectories(plugin.resolve("rules/common"));
			Files.writeString(plugin.resolve("rules/common/rules.md"), "rules");
			project = Files.createDirectories(root.resolve("project"));
			transcript = codexHome.resolve("sessions/rollout.jsonl");
		}

		/**
		 * Returns the hook's environment, which names the home in the home variable of the platform that runs the
		 * test and in that of Linux, the platform that a hook which starts a launcher simulates.
		 *
		 * @return the environment
		 */
		Map<String, String> environment()
		{
			Map<String, String> environment = new HashMap<>(Map.of("PLUGIN_ROOT", plugin.toString(), "CODEX_HOME",
				codexHome.toString()));
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

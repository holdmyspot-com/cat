package com.holdmyspot.cat.codex.optimizer.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.fakes.test.RequestingNestedSession;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.optimizer.CodexOptimizerCommandScope;
import com.holdmyspot.cat.codex.optimizer.CodexPromptOptimizer;
import com.holdmyspot.cat.codex.secrets.CodexOptimizerCommandScopeAccess;
import com.holdmyspot.cat.codex.secrets.CodexOptimizerCommandScopeSecrets;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.cli.PromptOptimizerCli;
import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tests {@link CodexOptimizerCommandScope}'s resolution of the environment, through the commands that the
 * {@link CodexPromptOptimizer} launcher runs, without starting a harness.
 */
public final class CodexPromptOptimizerTest
{
	private static final Path FIXTURE = Path.of("../../common/cli/src/test/resources/optimizer/cli/campaign.json");
	private static final String CAMPAIGN = "fixture-campaign";
	private static final String SESSION_ID = "2c4e6a8b-1d3f-4a5c-9e7b-0f2d4c6e8a1b";
	private static final String LAUNCH_REFUSAL = "No request server serves this session";
	private static final String NO_HOST_SESSION = "A campaign belongs to the codex session that runs it, but " +
		"CODEX_THREAD_ID is not set.";

	/**
	 * Creates the test.
	 */
	public CodexPromptOptimizerTest()
	{
	}

	/**
	 * A project with the fixture campaign, and a home directory.
	 *
	 * @param root the project
	 * @param home the home directory
	 */
	private record Setup(Path root, Path home)
	{
	}

	/**
	 * Creates a project and a home directory.
	 *
	 * @param scope removes them
	 * @return the setup
	 * @throws IOException if a file cannot be written
	 */
	private static Setup setup(TemporaryDirectories scope) throws IOException
	{
		Path root = scope.add(Files.createTempDirectory("codex-optimizer-project")).toRealPath();
		Files.createDirectory(root.resolve(".git"));
		Path campaign = root.resolve(".cat/sessions").resolve(SESSION_ID).resolve("optimizer").resolve(CAMPAIGN).
			resolve("held_out");
		Files.createDirectories(campaign);
		Files.copy(FIXTURE, campaign.resolve("campaign.json"));
		Path home = scope.add(Files.createTempDirectory("codex-optimizer-home")).toRealPath();
		return new Setup(root, home);
	}

	/**
	 * Runs the launcher on Linux.
	 *
	 * @param setup the project and home
	 * @param environment the environment
	 * @param args the arguments
	 * @param output receives the output
	 * @return the exit code
	 * @throws IOException if the scope cannot be created
	 */
	private static int run(Setup setup, Map<String, String> environment, List<String> args, StringBuilder output)
		throws IOException
	{
		return run(setup, environment, Platform.LINUX_X64, args, output);
	}

	/**
	 * Runs the launcher.
	 *
	 * @param setup the project and home
	 * @param environment the environment
	 * @param platform the platform that runs the launcher
	 * @param args the arguments
	 * @param output receives the output
	 * @return the exit code
	 * @throws IOException if the scope cannot be created
	 */
	private static int run(Setup setup, Map<String, String> environment, Platform platform, List<String> args,
		StringBuilder output)
		throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int exit;
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			try (CodexOptimizerCommandScope scope = CodexOptimizerCommandScopeSecrets.getCodexOptimizerCommandScopeAccess().
				newCodexOptimizerCommandScope(environment, setup.home(), setup.root(), setup.home().resolve("artifact"),
					Yielding.waiting()))
			{
				exit = PromptOptimizerCli.run(scope, args, ZoneOffset.UTC, platform, out);
			}
		}
		output.append(bytes.toString(StandardCharsets.UTF_8));
		return exit;
	}

	/**
	 * Verifies that a metadata query resolves the plugin through the Codex installation for this evaluator.
	 *
	 * @throws IOException if the installation fixture cannot be created
	 */
	@Test
	public void findsInstalledEvaluatorPluginInCodexHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("codex-evaluator-project")).toRealPath();
			Path home = cleanup.add(Files.createTempDirectory("codex-evaluator-home")).toRealPath();
			Path codexHome = Files.createDirectory(home.resolve("custom-codex-home"));
			Path base = codexHome.resolve("plugins/cache/fixture/docs/1.0");
			Path manifest = base.resolve(".codex-plugin/plugin.json");
			Files.createDirectories(manifest.getParent());
			Files.writeString(manifest, "{\"name\":\"docs\"}");
			Files.writeString(codexHome.resolve("config.toml"), "[plugins.\"docs@fixture\"]\nenabled = true\n");
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			String[] args = {"evaluator-info", "installed-plugins", project.toString(), "docs@fixture"};
			try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
			{
				int exit = PromptOptimizerCli.run(args, _ ->
					CodexOptimizerCommandScopeSecrets.getCodexOptimizerCommandScopeAccess().
						newCodexOptimizerCommandScope(Map.of("CODEX_HOME", codexHome.toString()), home, project,
							home.resolve("artifact"), Yielding.waiting()), ZoneOffset.UTC, out);
				requireThat(exit, "exit").isEqualTo(0);
			}
			JsonNode report = JsonMapper.builder().build().readTree(bytes.toString(StandardCharsets.UTF_8));
			requireThat(report.path("harness").stringValue(), "harness").isEqualTo("CODEX");
			requireThat(report.path("installed").get(0).stringValue(), "installed").isEqualTo(base.toString());
			requireThat(report.path("installed").size(), "installedCount").isEqualTo(1);
			requireThat(Files.exists(project.resolve(".cat/sessions")), "sessionsCreated").isFalse();
		}
	}

	/**
	 * Verifies that an installed-plugins query for a plugin name without a marketplace is refused as invalid input
	 * that states the form of the name, rather than as a failure to inspect the installation.
	 *
	 * @throws IOException if the installation fixture cannot be created
	 */
	@Test
	public void rejectsPluginNameWithoutMarketplace() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("codex-evaluator-project")).toRealPath();
			Path home = cleanup.add(Files.createTempDirectory("codex-evaluator-home")).toRealPath();
			Path codexHome = Files.createDirectory(home.resolve("custom-codex-home"));
			Files.writeString(codexHome.resolve("config.toml"), "[plugins.\"docs@fixture\"]\nenabled = true\n");
			StringBuilder output = new StringBuilder();
			int exit = runInstalledPlugins(project, home, codexHome, "docs", output);
			requireThat(exit, "exit").isEqualTo(2);
			JsonNode report = JsonMapper.builder().build().readTree(output.toString());
			requireThat(report.path("status").stringValue(), "status").isEqualTo("invalid_input");
			requireThat(report.path("message").stringValue(), "message").contains("docs").
				contains("<plugin>@<marketplace>");
		}
	}

	/**
	 * Verifies that an installed-plugins query whose install records cannot be parsed reports that the records could
	 * not be read and names them, without quoting their content.
	 *
	 * @throws IOException if the installation fixture cannot be created
	 */
	@Test
	public void namesUnreadableInstallRecordsInFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("codex-evaluator-project")).toRealPath();
			Path home = cleanup.add(Files.createTempDirectory("codex-evaluator-home")).toRealPath();
			Path codexHome = Files.createDirectory(home.resolve("custom-codex-home"));
			Path configuration = Files.writeString(codexHome.resolve("config.toml"), "token = \"secret-value\n[plugins");
			StringBuilder output = new StringBuilder();
			int exit = runInstalledPlugins(project, home, codexHome, "docs@fixture", output);
			requireThat(exit, "exit").isEqualTo(1);
			JsonNode report = JsonMapper.builder().build().readTree(output.toString());
			requireThat(report.path("status").stringValue(), "status").isEqualTo("error");
			requireThat(report.path("message").stringValue(), "message").contains("install records").
				contains(configuration.toString()).doesNotContain("secret-value");
		}
	}

	/**
	 * Runs {@code evaluator-info installed-plugins} against a Codex home.
	 *
	 * @param project the project
	 * @param home the user's home directory
	 * @param codexHome the Codex home directory
	 * @param name the plugin name to look up
	 * @param output receives the command's output
	 * @return the exit code
	 */
	private static int runInstalledPlugins(Path project, Path home, Path codexHome, String name,
		StringBuilder output)
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		String[] args = {"evaluator-info", "installed-plugins", project.toString(), name};
		int exit;
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			exit = PromptOptimizerCli.run(args, _ ->
				CodexOptimizerCommandScopeSecrets.getCodexOptimizerCommandScopeAccess().
					newCodexOptimizerCommandScope(Map.of("CODEX_HOME", codexHome.toString()), home, project,
						home.resolve("artifact"), Yielding.waiting()), ZoneOffset.UTC, out);
		}
		output.append(bytes.toString(StandardCharsets.UTF_8));
		return exit;
	}

	/**
	 * Startup-hook inspection reports native home overlays and adapter inputs without running the declared command.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void describesEvaluatorStartupHooks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-evaluator-hooks")).toRealPath();
			Path project = Files.createDirectory(root.resolve("project"));
			Path codex = Files.createDirectories(project.resolve(".codex"));
			Files.writeString(codex.resolve("hooks.json"), """
				{"hooks":{"UserPromptSubmit":[{"hooks":[{"type":"command",
				  "command":"echo invoked > unexpected-hook-effect", "timeout":3}]}]}}
				""");
			Path plugins = Files.writeString(root.resolve("plugins.json"), "[]");
			Path home = Files.createDirectory(root.resolve("native-home"));
			Path codexHome = Files.createDirectory(home.resolve("custom-codex"));
			Path trialHome = Files.createDirectory(root.resolve("trial-home"));
			Map<String, String> inherited = Map.of("CODEX_HOME", codexHome.toString(),
				"OPENAI_API_KEY", "fixture-secret-value");
			JsonMapper json = JsonMapper.builder().build();
			for (Path selectedHome : List.of(home, trialHome))
			{
				List<String> args = new ArrayList<>(List.of("evaluator-info", "early-hooks", project.toString(),
					project.toString(), plugins.toString()));
				Path configuration = codexHome;
				if (selectedHome.equals(trialHome))
				{
					args.add(trialHome.toString());
					configuration = trialHome.resolve(".codex");
				}
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
				{
					int exit = PromptOptimizerCli.run(args.toArray(String[]::new), _ ->
						CodexOptimizerCommandScopeSecrets.getCodexOptimizerCommandScopeAccess().
							newCodexOptimizerCommandScope(inherited, home, project, root.resolve("artifact"),
								Yielding.waiting()), ZoneOffset.UTC, out);
					requireThat(exit, "exit").isEqualTo(0);
				}
				String response = bytes.toString(StandardCharsets.UTF_8);
				requireThat(response, "response").doesNotContain("OPENAI_API_KEY").doesNotContain("fixture-secret-value");
				JsonNode report = json.readTree(response);
				requireThat(report.path("harness").stringValue(), "harness").isEqualTo("codex");
				requireThat(report.path("hooks").size(), "hookCount").isEqualTo(1);
				JsonNode hook = report.path("hooks").get(0);
				Map<String, String> expectedOverlay = new HashMap<>(
					HarnessEnvironment.homeVariables(selectedHome, Platform.current()));
				expectedOverlay.put("CODEX_HOME", configuration.toString());
				JsonNode expectedEnvironment = json.valueToTree(expectedOverlay);
				requireThat(hook.get("environment"), "environment").isEqualTo(expectedEnvironment);
				JsonNode input = json.readTree(hook.path("input").stringValue());
				requireThat(input.path("cwd").stringValue(), "cwd").isEqualTo(project.toString());
				requireThat(input.path("prompt").stringValue(), "prompt").isEqualTo("Describe this project.");
				requireThat(hook.path("timeout").stringValue(), "timeout").isEqualTo("PT3S");
			}
			requireThat(Files.exists(project.resolve("unexpected-hook-effect")), "hookRan").isFalse();
		}
	}

	/**
	 * Verifies that on native Windows, where every Codex sandbox implementation counts as a sandbox, {@code start} is
	 * not refused for the platform: it ends with the same exit status and result status as on Linux, and its message
	 * names neither native Windows nor WSL2.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void startNotRefusedForNativeWindows() throws IOException
	{
		// Requirement: R-I44
		List<String> results = new ArrayList<>();
		for (Platform platform : List.of(Platform.WINDOWS_X64, Platform.LINUX_X64))
		{
			try (TemporaryDirectories scope = new TemporaryDirectories())
			{
				Setup setup = setup(scope);
				StringBuilder output = new StringBuilder();
				int exit = run(setup, Map.of("CODEX_THREAD_ID", SESSION_ID), platform, List.of("start", "prompts/a.md"),
					output);
				JsonNode json = JsonMapper.builder().build().readTree(output.toString());
				requireThat(json.path("message").asString(""), "message").withContext(platform, "platform").
					doesNotContain("native Windows").doesNotContain("WSL2");
				results.add(exit + " " + json.path("status").asString(""));
			}
		}
		requireThat(results.get(0), "windows").isEqualTo(results.get(1));
	}

	/**
	 * Verifies that {@code status} prints the status JSON of a campaign that the other harness started.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void statusPrintsFixtureCampaign() throws IOException
	{
		// Requirement: R-I4
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			StringBuilder output = new StringBuilder();
			int exit = run(setup, Map.of("CODEX_THREAD_ID", SESSION_ID), List.of("status", CAMPAIGN), output);
			requireThat(exit, "exit").isEqualTo(0);
			JsonNode json = JsonMapper.builder().build().readTree(output.toString());
			requireThat(json.get("campaign").asString(), "campaign").isEqualTo(CAMPAIGN);
			requireThat(json.get("harness").asString(), "harness").isEqualTo("claude");
			requireThat(json.has("choices"), "hasChoices").isTrue();
		}
	}

	/**
	 * Verifies that polling for host interrupts fails with a clear message if the host session has no rollout.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void missingRolloutFailsPolling() throws IOException
	{
		// Requirement: R-I4
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path configuration = scope.add(Files.createTempDirectory("codex-optimizer-config"));
			HostInterruptSignal signal = CodexOptimizerCommandScopeSecrets.getCodexOptimizerCommandScopeAccess().interrupts(
				Map.of("CODEX_THREAD_ID", "session-1"), configuration, Clock.systemUTC());
			IOException failure = null;
			try
			{
				signal.isInterruptedAfter(Instant.EPOCH);
			}
			catch (IOException e)
			{
				failure = e;
			}
			requireThat(failure, "failure").isNotNull();
			requireThat(failure.getMessage(), "message").contains("session-1");
		}
	}

	/**
	 * Verifies that the rollout below {@code CODEX_HOME} is found when the host session is named.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void findsRollout() throws IOException
	{
		// Requirement: R-I4
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path configuration = scope.add(Files.createTempDirectory("codex-optimizer-config"));
			Path sessions = Files.createDirectories(configuration.resolve("sessions").resolve("2026"));
			Files.writeString(sessions.resolve("rollout-2026-10-01T00-00-00-session-1.jsonl"), "");
			HostInterruptSignal signal = CodexOptimizerCommandScopeSecrets.getCodexOptimizerCommandScopeAccess().interrupts(
				Map.of("CODEX_THREAD_ID", "session-1"), configuration, Clock.systemUTC());
			requireThat(signal.isInterruptedAfter(Instant.EPOCH), "interrupted").isFalse();
		}
	}

	/**
	 * Verifies that a {@code CODEX_HOME} that names no folder is refused, as is a blank one, which Codex reads as the
	 * name of a folder rather than as the default home.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void refusesHostCodexHomeThatCodexRefuses() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			CodexOptimizerCommandScopeAccess access = CodexOptimizerCommandScopeSecrets.
				getCodexOptimizerCommandScopeAccess();
			for (String value : List.of("  ", setup.home().resolve("missing").toString()))
			{
				try (CodexOptimizerCommandScope _ = access.newCodexOptimizerCommandScope(Map.of("CODEX_HOME", value),
					setup.home(), setup.root(), setup.home().resolve("artifact"), Yielding.waiting()))
				{
					throw new AssertionError("A scope was created for CODEX_HOME \"" + value + "\"");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains("CODEX_HOME");
				}
			}
		}
	}

	/**
	 * Verifies that a command is refused, naming the variable that it lacks, when the environment names no host
	 * session to own the campaigns.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void statusWithoutHostSessionIsRefused() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			StringBuilder output = new StringBuilder();
			int exit = run(setup, Map.of("CLAUDECODE", "1"), List.of("status", CAMPAIGN), output);
			requireThat(exit, "exit").isEqualTo(1);
			requireThat(output.toString(), "output").contains("CODEX_THREAD_ID").contains("codex session");
			requireThat(Files.exists(setup.root().resolve(".cat/sessions").resolve(SESSION_ID).resolve("temp")),
				"sessionCreated").isFalse();
		}
	}

	/**
	 * Verifies that invalid input is rejected with exit code 2.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void invalidCommandExitsWithTwo() throws IOException
	{
		// Requirement: R-I4
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			StringBuilder output = new StringBuilder();
			int exit = run(setup, Map.of("CODEX_THREAD_ID", SESSION_ID), List.of("frobnicate"), output);
			requireThat(exit, "exit").isEqualTo(2);
		}
	}

	/**
	 * Returns environments of a harness session's {@code start}, each with how its refusal begins when no request
	 * server serves the session that runs it, and its exit status: a command whose worktree requests go to the missing
	 * server is refused with status 2 before it changes anything.
	 *
	 * @return the environments, the beginnings of the refusals and the exit statuses
	 */
	@DataProvider
	public static Object[][] launchRoutes()
	{
		return new Object[][]
		{
			// Codex runs the command, so its sessions go to its request server, and none serves the session
			{Map.of("CODEX_THREAD_ID", SESSION_ID), LAUNCH_REFUSAL, 2},
			// Only Claude Code runs the command, so each of its sessions would be refused when it starts, but no Codex
			// session hosts a campaign
			{Map.of("CLAUDECODE", "1"), NO_HOST_SESSION, 1}
		};
	}

	/**
	 * Verifies that {@code start} is refused, before it starts any session, for want of a request server only when
	 * its sessions go to the session's request server. On every other route the command goes on, here to refuse the
	 * campaign that no Codex session hosts.
	 *
	 * @param variables the environment's variables, besides the home folder
	 * @param refusal   how the refusal begins
	 * @param status    the command's exit status
	 * @throws IOException if a file cannot be written
	 */
	@Test(dataProvider = "launchRoutes")
	public void startNeedsServerOnServedRoute(Map<String, String> variables, String refusal, int status)
		throws IOException
	{
		// Requirement: R-W17
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			Map<String, String> environment = new HashMap<>(variables);
			environment.put(Platform.current().homeVariable(), setup.home().toString());
			List<String> before = tree(setup.root());
			StringBuilder output = new StringBuilder();

			int exit = run(setup, environment, List.of("start", "prompts/missing.md"), output);

			requireThat(exit, "exit").isEqualTo(status);
			JsonNode json = JsonMapper.builder().build().readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("refused");
			requireThat(json.get("message").asString(), "message").startsWith(refusal);
			if (!variables.containsKey("CODEX_THREAD_ID"))
				requireThat(tree(setup.root()), "tree").isEqualTo(before);
		}
	}

	/**
	 * Verifies that each command that may start sessions, run in a nested session whose caller lets it request
	 * no sessions, is refused with exit code 2 before it changes anything, with a message that names the caller's
	 * options that would allow the sessions and does not suggest starting Codex with {@code --no-daemon}.
	 *
	 * @param command the command and its arguments
	 * @throws IOException if a file cannot be read or written
	 */
	@Test(dataProvider = "sessionCommands")
	public void nestedCommandRefusedWithoutRequests(List<String> command) throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			Map<String, String> environment = new HashMap<>(Map.of("CODEX_THREAD_ID", SESSION_ID, "CAT_NESTED_SESSION",
				"1"));
			environment.put(Platform.current().homeVariable(), setup.home().toString());
			List<String> before = tree(setup.root());
			StringBuilder output = new StringBuilder();

			int exit = run(setup, environment, command, output);

			requireThat(exit, "exit").isEqualTo(2);
			JsonNode json = JsonMapper.builder().build().readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("refused");
			requireThat(json.get("message").asString(), "message").contains("--session-requests <count>").
				contains("--request-depth <levels>").doesNotContain("--no-daemon");
			requireThat(tree(setup.root()), "tree").isEqualTo(before);
		}
	}

	/**
	 * Returns every command that may start sessions, each with the session requests of a nested session whose runner
	 * refuses the next session and whether the nested session first serves the one session it allows.
	 *
	 * @return the commands and their arguments, the nested session's session requests and whether one session is served
	 *         first
	 */
	@DataProvider
	public static Object[][] spentSessions()
	{
		List<Object[]> cases = new ArrayList<>();
		for (Object[] command : sessionCommands())
		{
			cases.add(new Object[]{command[0], 0, false});
			cases.add(new Object[]{command[0], 1, true});
		}
		return cases.toArray(Object[][]::new);
	}

	/**
	 * Verifies that each command that may start sessions, run in a nested session whose runner serves its
	 * sessions but whose limits, as the runner states them, allow no further session, is refused with exit code 2
	 * before it changes anything, with the message that names the caller's options that would allow the sessions.
	 *
	 * @param command         the command and its arguments
	 * @param sessionRequests how many sessions the nested session allows
	 * @param serveFirst      indicates whether the nested session serves one session before
	 * @throws IOException if a file cannot be read or written
	 */
	@Test(dataProvider = "spentSessions", timeOut = 30_000)
	public void nestedCommandRefusedWhenNoFurtherSession(List<String> command, int sessionRequests, boolean serveFirst)
		throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			Path results = scope.add(Files.createTempDirectory("codex-optimizer-results")).toRealPath();
			try (RequestingNestedSession parent = new RequestingNestedSession(results.resolve("parent"), new FileLocks(),
				sessionRequests))
			{
				if (serveFirst)
				{
					AtomicInteger checks = new AtomicInteger();
					Path project = Files.createDirectories(results.resolve("project"));
					SessionRequestForwarder.serve(RunnerSessions.COMMAND, "codex", results, List.of("--project",
						project.toString(), "--results", results.resolve("results").toString(), "--model",
						"model-a", "--effort", "effort-a"), new StringReader("{\"type\":\"turn\",\"prompt\":\"task\"}\n" +
						"{\"type\":\"complete\"}\n"), new StringWriter(), parent.requests(), () ->
					{
						if (checks.incrementAndGet() > 10_000_000)
							throw new IOException("The first session did not end");
						Thread.yield();
					});
				}
				Map<String, String> environment = new HashMap<>(Map.of("CODEX_THREAD_ID", SESSION_ID, "CAT_NESTED_SESSION",
					"1"));
				environment.put(SessionRequestAccess.REQUESTS_VARIABLE, parent.requests().toString());
				environment.put(Platform.current().homeVariable(), setup.home().toString());
				List<String> before = tree(setup.root());
				String reason;
				if (sessionRequests == 0)
					reason = SessionRequestAccess.NO_REQUESTS_REASON;
				else
					reason = SessionRequestAccess.limitReason(sessionRequests);
				StringBuilder output = new StringBuilder();

				int exit = run(setup, environment, command, output);

				requireThat(exit, "exit").isEqualTo(2);
				JsonNode json = JsonMapper.builder().build().readTree(output.toString());
				requireThat(json.get("status").asString(), "status").isEqualTo("refused");
				requireThat(json.get("message").asString(), "message").
					contains(SessionRequestAccess.refusalMessage(RunnerSessions.COMMAND, reason)).
					doesNotContain("--no-daemon");
				requireThat(tree(setup.root()), "tree").isEqualTo(before);
				requireThat(parent.launches().size(), "launches").isEqualTo(sessionRequests);
			}
		}
	}

	/**
	 * Returns every command that may start sessions.
	 *
	 * @return the commands and their arguments
	 */
	@DataProvider
	public static Object[][] sessionCommands()
	{
		return new Object[][]{{List.of("start", "prompts/a.md")}, {List.of("continue", CAMPAIGN)},
			{List.of("approve", CAMPAIGN)}, {List.of("request-changes", CAMPAIGN, "feedback")}};
	}

	/**
	 * Returns environments in which no harness session runs a command, each naming a session variable or none.
	 *
	 * @return the environments' variables
	 */
	@DataProvider
	public static Object[][] environmentsOfNoHarness()
	{
		return new Object[][]
		{
			// A terminal
			{Map.of()},
			// Another harness's session variable alone is no marker of that harness
			{Map.of("CLAUDE_CODE_SESSION_ID", SESSION_ID)}
		};
	}

	/**
	 * Verifies that a command that no harness session runs is refused with exit code 2 and one message that tells the
	 * user to run it from a Claude Code or Codex session, and that the command changes nothing in the project.
	 *
	 * @param variables the environment's variables, besides the home folder
	 * @param command   the command and its arguments
	 * @throws IOException if a file cannot be read or written
	 */
	@Test(dataProvider = "commandsOfNoHarness")
	public void noHarnessCommandChangesNothing(Map<String, String> variables, List<String> command)
		throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			Map<String, String> environment = new HashMap<>(variables);
			environment.put(Platform.current().homeVariable(), setup.home().toString());
			List<String> before = tree(setup.root());
			StringBuilder output = new StringBuilder();

			int exit = run(setup, environment, command, output);

			requireThat(exit, "exit").isEqualTo(2);
			JsonNode json = JsonMapper.builder().build().readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("refused");
			requireThat(json.get("message").asString(), "message").
				contains("Run it from a Claude Code or Codex session.");
			requireThat(tree(setup.root()), "tree").isEqualTo(before);
			requireThat(tree(setup.home()), "home").isEmpty();
		}
	}

	/**
	 * Returns every command and the environments in which no harness session runs it.
	 *
	 * @return the environments' variables and the commands
	 */
	@DataProvider
	public static Object[][] commandsOfNoHarness()
	{
		List<List<String>> commands = List.of(List.of("start", "prompts/a.md"), List.of("continue", CAMPAIGN),
			List.of("status", CAMPAIGN), List.of("status"), List.of("approve", CAMPAIGN),
			List.of("request-changes", CAMPAIGN, "feedback"), List.of("stop", CAMPAIGN));
		List<Object[]> rows = new ArrayList<>();
		for (Object[] environment : environmentsOfNoHarness())
		{
			for (List<String> command : commands)
				rows.add(new Object[]{environment[0], command});
		}
		return rows.toArray(Object[][]::new);
	}

	/**
	 * Verifies that the usage text needs no harness session.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void helpNeedsNoHarness() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			StringBuilder output = new StringBuilder();

			int exit = run(setup, Map.of(), List.of("help"), output);

			requireThat(exit, "exit").isEqualTo(0);
			JsonNode json = JsonMapper.builder().build().readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("help");
		}
	}

	/**
	 * Lists every file and folder below a folder.
	 *
	 * @param root the folder
	 * @return the paths relative to {@code root}, sorted, each with its size if it is a file
	 * @throws IOException if the folder cannot be read
	 */
	private static List<String> tree(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			List<String> entries = new ArrayList<>();
			for (Path path : (Iterable<Path>) paths::iterator)
			{
				StringBuilder entry = new StringBuilder(root.relativize(path).toString());
				if (Files.isRegularFile(path))
					entry.append(' ').append(Files.size(path));
				entries.add(entry.toString());
			}
			entries.remove("");
			Collections.sort(entries);
			return entries;
		}
	}
}

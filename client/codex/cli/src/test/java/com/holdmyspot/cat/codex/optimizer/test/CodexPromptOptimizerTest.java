package com.holdmyspot.cat.codex.optimizer.test;

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
import com.holdmyspot.cat.common.fakes.test.Yielding;

/**
 * Tests {@link CodexOptimizerCommandScope}'s resolution of the environment, through the commands that the
 * {@link CodexPromptOptimizer} launcher runs, without starting a harness.
 */
public final class CodexPromptOptimizerTest
{
	private static final Path FIXTURE = Path.of("../../common/cli/src/test/resources/optimizer/cli/campaign.json");
	private static final String CAMPAIGN = "fixture-campaign";
	private static final String SESSION_ID = "2c4e6a8b-1d3f-4a5c-9e7b-0f2d4c6e8a1b";
	private static final String LAUNCH_REFUSAL = "No CAT session launcher serves this session";
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
	 * Verifies that on native Windows, where CAT does not start Codex trials, {@code start} is refused with the reason,
	 * which names what is missing and directs the user to WSL2.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void startRefusedOnNativeWindows() throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			StringBuilder output = new StringBuilder();
			int exit = run(setup, Map.of("CODEX_THREAD_ID", SESSION_ID), Platform.WINDOWS_X64,
				List.of("start", "prompts/a.md"), output);
			requireThat(exit, "exit").isEqualTo(1);
			JsonNode json = JsonMapper.builder().build().readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("refused");
			requireThat(json.get("message").asString(), "message").
				contains("CAT has not yet verified that Codex's sandbox on native Windows").contains("WSL2");
		}
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
	 * Verifies that a command that never polls for host interrupts does not need the host session's rollout.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void statusDoesNotNeedRollout() throws IOException
	{
		// Requirement: R-I4
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			StringBuilder output = new StringBuilder();
			int exit = run(setup, Map.of("CODEX_THREAD_ID", SESSION_ID), List.of("status", CAMPAIGN), output);
			requireThat(exit, "exit").isEqualTo(0);
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
	 * Returns environments of a harness session's {@code start}, each with how its refusal begins when no session
	 * launcher serves the session that runs it.
	 *
	 * @return the environments and the beginnings of the refusals
	 */
	@DataProvider
	public static Object[][] launchRoutes()
	{
		return new Object[][]
		{
			// Codex runs the command, so its sessions go to the session launcher, and none serves the session
			{Map.of("CODEX_THREAD_ID", SESSION_ID), LAUNCH_REFUSAL},
			// Only Claude Code runs the command, so each of its sessions would be refused when it starts, but no Codex
			// session hosts a campaign
			{Map.of("CLAUDECODE", "1"), NO_HOST_SESSION}
		};
	}

	/**
	 * Verifies that {@code start} is refused, before it starts any session, for want of a session launcher only when
	 * its sessions go to the session launcher. On every other route the command goes on, here to refuse the campaign
	 * that no Codex session hosts.
	 *
	 * @param variables the environment's variables, besides the home folder
	 * @param refusal   how the refusal begins
	 * @throws IOException if a file cannot be written
	 */
	@Test(dataProvider = "launchRoutes")
	public void startNeedsLauncherOnLauncherRoute(Map<String, String> variables, String refusal)
		throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			Map<String, String> environment = new HashMap<>(variables);
			environment.put(Platform.current().homeVariable(), setup.home().toString());
			StringBuilder output = new StringBuilder();

			int exit = run(setup, environment, List.of("start", "prompts/missing.md"), output);

			requireThat(exit, "exit").isEqualTo(1);
			JsonNode json = JsonMapper.builder().build().readTree(output.toString());
			requireThat(json.get("status").asString(), "status").isEqualTo("refused");
			requireThat(json.get("message").asString(), "message").startsWith(refusal);
		}
	}

	/**
	 * Verifies that each command that may start sessions, run in a session of a CAT run whose caller lets it request
	 * no sessions, is refused with exit code 2 before it changes anything, with a message that names the caller's
	 * options that would allow the sessions and does not suggest starting Codex with {@code --no-daemon}.
	 *
	 * @param command the command and its arguments
	 * @throws IOException if a file cannot be read or written
	 */
	@Test(dataProvider = "sessionCommands")
	public void inRunCommandRefusedWithoutRequests(List<String> command) throws IOException
	{
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			Map<String, String> environment = new HashMap<>(Map.of("CODEX_THREAD_ID", SESSION_ID, "CAT_RUN", "1"));
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
	 * Returns every command that may start sessions.
	 *
	 * @return the commands and their arguments
	 */
	@DataProvider
	public static Object[][] sessionCommands()
	{
		return new Object[][]{{List.of("start", "prompts/a.md")}, {List.of("run", CAMPAIGN)},
			{List.of("confirm", CAMPAIGN)}, {List.of("revise", CAMPAIGN, "feedback")}};
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
		List<List<String>> commands = List.of(List.of("start", "prompts/a.md"), List.of("run", CAMPAIGN),
			List.of("status", CAMPAIGN), List.of("status"), List.of("confirm", CAMPAIGN),
			List.of("revise", CAMPAIGN, "feedback"), List.of("cancel", CAMPAIGN));
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

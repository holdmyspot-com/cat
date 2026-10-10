package io.github.cowwoc.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.optimizer.CodexOptimizerCommandScope;
import io.github.cowwoc.cat.codex.optimizer.CodexPromptOptimizer;
import io.github.cowwoc.cat.codex.secrets.CodexOptimizerCommandScopeSecrets;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.cli.PromptOptimizerCli;
import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;
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
import java.util.List;
import java.util.Map;

/**
 * Tests {@link CodexOptimizerCommandScope}'s resolution of the environment, through the commands that the
 * {@link CodexPromptOptimizer} launcher runs, without starting a harness.
 */
public final class CodexPromptOptimizerTest
{
	private static final Path FIXTURE = Path.of("../../common/cli/src/test/resources/optimizer/cli/campaign.json");
	private static final String CAMPAIGN = "fixture-campaign";

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
		Path campaign = root.resolve(".cat/work/optimizer").resolve(CAMPAIGN).resolve("held_out");
		Files.createDirectories(campaign);
		Files.copy(FIXTURE, campaign.resolve("campaign.json"));
		Path home = scope.add(Files.createTempDirectory("codex-optimizer-home")).toRealPath();
		return new Setup(root, home);
	}

	/**
	 * Runs the launcher.
	 *
	 * @param setup the project and home
	 * @param environment the environment
	 * @param args the arguments
	 * @param output receives the output
	 * @return the exit code
	 */
	private static int run(Setup setup, Map<String, String> environment, List<String> args, StringBuilder output)
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int exit;
		try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			try (CodexOptimizerCommandScope scope = CodexOptimizerCommandScopeSecrets.access().
				newCodexOptimizerCommandScope(environment, setup.home(), setup.root(), setup.home().resolve("artifact")))
			{
				exit = PromptOptimizerCli.run(scope, args, ZoneOffset.UTC, out);
			}
		}
		output.append(bytes.toString(StandardCharsets.UTF_8));
		return exit;
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
			int exit = run(setup, Map.of(), List.of("status", CAMPAIGN), output);
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
			int exit = run(setup, Map.of("CODEX_THREAD_ID", "session-1"), List.of("status", CAMPAIGN), output);
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
			HostInterruptSignal signal = CodexOptimizerCommandScopeSecrets.access().interrupts(
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
			HostInterruptSignal signal = CodexOptimizerCommandScopeSecrets.access().interrupts(
				Map.of("CODEX_THREAD_ID", "session-1"), configuration, Clock.systemUTC());
			requireThat(signal.isInterruptedAfter(Instant.EPOCH), "interrupted").isFalse();
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
			int exit = run(setup, Map.of(), List.of("frobnicate"), output);
			requireThat(exit, "exit").isEqualTo(2);
		}
	}
}

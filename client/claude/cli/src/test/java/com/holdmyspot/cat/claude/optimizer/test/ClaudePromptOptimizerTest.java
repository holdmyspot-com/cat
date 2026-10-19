package com.holdmyspot.cat.claude.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.optimizer.ClaudeOptimizerCommandScope;
import com.holdmyspot.cat.claude.optimizer.ClaudePromptOptimizer;
import com.holdmyspot.cat.claude.secrets.ClaudeOptimizerCommandScopeSecrets;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.cli.PromptOptimizerCli;
import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;
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
 * Tests {@link ClaudeOptimizerCommandScope}'s resolution of the environment, through the commands that the
 * {@link ClaudePromptOptimizer} launcher runs, without starting a harness.
 */
public final class ClaudePromptOptimizerTest
{
	private static final Path FIXTURE = Path.of("../../common/cli/src/test/resources/optimizer/cli/campaign.json");
	private static final String CAMPAIGN = "fixture-campaign";
	private static final String SESSION_ID = "2c4e6a8b-1d3f-4a5c-9e7b-0f2d4c6e8a1b";

	/**
	 * Creates the test.
	 */
	public ClaudePromptOptimizerTest()
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
		Path root = scope.add(Files.createTempDirectory("claude-optimizer-project")).toRealPath();
		Files.createDirectory(root.resolve(".git"));
		Path campaign = root.resolve(".cat/sessions").resolve(SESSION_ID).resolve("optimizer").resolve(CAMPAIGN).
			resolve("held_out");
		Files.createDirectories(campaign);
		Files.copy(FIXTURE, campaign.resolve("campaign.json"));
		Path home = scope.add(Files.createTempDirectory("claude-optimizer-home")).toRealPath();
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
			try (ClaudeOptimizerCommandScope scope = ClaudeOptimizerCommandScopeSecrets.access().
				newClaudeOptimizerCommandScope(environment, setup.home(), setup.root(), setup.home().resolve("artifact")))
			{
				exit = PromptOptimizerCli.run(scope, args, ZoneOffset.UTC, out);
			}
		}
		output.append(bytes.toString(StandardCharsets.UTF_8));
		return exit;
	}

	/**
	 * Verifies that {@code status} prints the campaign's status JSON.
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
			int exit = run(setup, Map.of("CLAUDE_CODE_SESSION_ID", SESSION_ID), List.of("status", CAMPAIGN), output);
			requireThat(exit, "exit").isEqualTo(0);
			JsonNode json = JsonMapper.builder().build().readTree(output.toString());
			requireThat(json.get("campaign").asString(), "campaign").isEqualTo(CAMPAIGN);
			requireThat(json.get("harness").asString(), "harness").isEqualTo("claude");
			requireThat(json.has("choices"), "hasChoices").isTrue();
		}
	}

	/**
	 * Verifies that a command that never polls for host interrupts does not need the host session's transcript.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void statusDoesNotNeedTranscript() throws IOException
	{
		// Requirement: R-I4
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Setup setup = setup(scope);
			StringBuilder output = new StringBuilder();
			int exit = run(setup, Map.of("CLAUDE_CODE_SESSION_ID", SESSION_ID), List.of("status", CAMPAIGN), output);
			requireThat(exit, "exit").isEqualTo(0);
		}
	}

	/**
	 * Verifies that polling for host interrupts fails with a clear message if the host session has no transcript.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void missingTranscriptFailsPolling() throws IOException
	{
		// Requirement: R-I4
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path configuration = scope.add(Files.createTempDirectory("claude-optimizer-config"));
			HostInterruptSignal signal = ClaudeOptimizerCommandScopeSecrets.access().interrupts(
				Map.of("CLAUDE_CODE_SESSION_ID", "session-1"), configuration, Clock.systemUTC());
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
	 * Verifies that the transcript below {@code CLAUDE_CONFIG_DIR} is found when the host session is named.
	 *
	 * @throws IOException if a file cannot be written
	 */
	@Test
	public void findsTranscript() throws IOException
	{
		// Requirement: R-I4
		try (TemporaryDirectories scope = new TemporaryDirectories())
		{
			Path configuration = scope.add(Files.createTempDirectory("claude-optimizer-config"));
			Path projects = Files.createDirectories(configuration.resolve("projects").resolve("p"));
			Files.writeString(projects.resolve("session-1.jsonl"), "");
			HostInterruptSignal signal = ClaudeOptimizerCommandScopeSecrets.access().interrupts(
				Map.of("CLAUDE_CODE_SESSION_ID", "session-1"), configuration, Clock.systemUTC());
			requireThat(signal.isInterruptedAfter(Instant.EPOCH), "interrupted").isFalse();
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
			int exit = run(setup, Map.of(), List.of("status", CAMPAIGN), output);
			requireThat(exit, "exit").isEqualTo(1);
			requireThat(output.toString(), "output").contains("CLAUDE_CODE_SESSION_ID").contains("claude session");
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
			int exit = run(setup, Map.of(), List.of("frobnicate"), output);
			requireThat(exit, "exit").isEqualTo(2);
		}
	}
}

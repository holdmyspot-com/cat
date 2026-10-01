package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
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
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Tests {@link ClaudePromptOptimizer}'s resolution of the environment, without starting a harness.
 */
public final class ClaudePromptOptimizerTest
{
	private static final Path FIXTURE = Path.of("../../common/cli/src/test/resources/optimizer/cli/campaign.json");
	private static final String CAMPAIGN = "fixture-campaign";

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
		Path campaign = root.resolve(".cat/work/optimizer").resolve(CAMPAIGN);
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
			exit = ClaudePromptOptimizer.run(args, environment, setup.home(), setup.root(), setup.home().resolve("artifact"),
				Clock.systemUTC(), RandomGenerator.getDefault(), out);
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
			int exit = run(setup, Map.of(), List.of("status", CAMPAIGN), output);
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
			int exit = run(setup, Map.of("CLAUDE_CODE_SESSION_ID", "session-1"), List.of("status", CAMPAIGN), output);
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
			HostInterruptSignal signal = ClaudePromptOptimizer.interrupts(
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
			HostInterruptSignal signal = ClaudePromptOptimizer.interrupts(
				Map.of("CLAUDE_CODE_SESSION_ID", "session-1"), configuration, Clock.systemUTC());
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

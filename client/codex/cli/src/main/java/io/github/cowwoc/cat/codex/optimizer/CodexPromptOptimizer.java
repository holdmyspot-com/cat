package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.cli.HarnessVersion;
import io.github.cowwoc.cat.common.optimizer.cli.Host;
import io.github.cowwoc.cat.common.optimizer.cli.PromptOptimizerCli;
import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * The {@code prompt-optimizer} launcher for Codex. This class alone reads the process environment and the home
 * directory, and wires the Codex adapters into the harness-neutral core.
 */
public final class CodexPromptOptimizer
{
	private static final String HARNESS = "codex";
	private static final String SESSION_ID = "CODEX_THREAD_ID";
	private static final List<Path> LOADER_CONFIGURATION = List.of(Path.of(".codex/config.toml"));

	/**
	 * Prevents instantiation.
	 */
	private CodexPromptOptimizer()
	{
	}

	/**
	 * Runs one command and exits with its code if it is not zero.
	 *
	 * @param args the command and its arguments
	 */
	public static void main(String[] args)
	{
		requireThat(args, "args").isNotNull();
		Map<String, String> environment = System.getenv();
		String home = environment.get("HOME");
		int exitCode;
		if (home == null || home.isBlank())
		{
			exitCode = PromptOptimizerCli.fail(JsonMapper.builder().build(), System.out, "HOME is not set.");
		}
		else
		{
			Path artifact = Path.of(System.getProperty("java.home")).toAbsolutePath().getParent();
			exitCode = run(List.of(args), environment, Path.of(home), Path.of("").toAbsolutePath(), artifact,
				Clock.systemUTC(), RandomGenerator.getDefault(), System.out);
		}
		if (exitCode != 0)
			System.exit(exitCode);
	}

	/**
	 * Runs one command.
	 *
	 * @param args the command and its arguments
	 * @param environment the process environment
	 * @param home the user's home directory
	 * @param directory the working directory
	 * @param artifact the complete Codex artifact that nested sessions install
	 * @param clock the clock
	 * @param random the source of identifiers and seeds
	 * @param out where the JSON goes
	 * @return the exit code
	 * @throws NullPointerException if any argument is null
	 */
	static int run(List<String> args, Map<String, String> environment, Path home, Path directory, Path artifact,
		Clock clock, RandomGenerator random, PrintStream out)
	{
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(home, "home").isNotNull();
		requireThat(out, "out").isNotNull();
		JsonMapper json = JsonMapper.builder().build();
		Host host = host(environment, home, directory, artifact, clock, random, json);
		return PromptOptimizerCli.run(host, args, out);
	}

	/**
	 * Resolves the host.
	 *
	 * @param environment the process environment
	 * @param home the user's home directory
	 * @param directory the working directory
	 * @param artifact the complete Codex artifact that nested sessions install
	 * @param clock the clock
	 * @param random the source of identifiers and seeds
	 * @param json the JSON mapper
	 * @return the host
	 * @throws NullPointerException if any argument is null
	 */
	static Host host(Map<String, String> environment, Path home, Path directory, Path artifact,
		Clock clock, RandomGenerator random, JsonMapper json)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(random, "random").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(home, "home").isNotNull();
		Path configurationDirectory = RolloutInterruptSignal.codexHome(home,
			environment.get("CODEX_HOME"));
		Path root = directory.toAbsolutePath().normalize();
		Path runRoot = root.resolve(".cat").resolve("work").resolve("optimizer-runs").resolve(HARNESS);
		CodexSessions.Settings settings = new CodexSessions.Settings(artifact, runRoot, "", "", true,
			environment, configurationDirectory, HarnessCapacity.forHome(home, HARNESS,
				HarnessCapacity.MAXIMUM_CONCURRENCY), clock);
		return Host.withDefaults(HARNESS, new CodexSessions(settings), interrupts(environment, configurationDirectory,
			clock), clock, random, json, directory,
			() -> HarnessVersion.read("codex", HarnessVersion.process()), LOADER_CONFIGURATION);
	}

	/**
	 * Returns the signal that reports the host user's interrupts. The host session's rollout is located when the
	 * signal is first polled, so commands that never poll it, such as {@code status}, do not need it.
	 *
	 * @param environment the process environment
	 * @param configurationDirectory the host's Codex configuration directory
	 * @param clock the clock
	 * @return a signal that never reports an interrupt if the host session is not named in the environment. Otherwise
	 *   its first poll throws {@code IOException} if the session has no rollout.
	 */
	static HostInterruptSignal interrupts(Map<String, String> environment, Path configurationDirectory,
		Clock clock)
	{
		String sessionId = environment.get(SESSION_ID);
		if (sessionId == null || sessionId.isBlank())
			return _ -> false;
		AtomicReference<HostInterruptSignal> located = new AtomicReference<>();
		return start ->
		{
			HostInterruptSignal signal = located.get();
			if (signal == null)
			{
				Optional<Path> rollout = RolloutInterruptSignal.locate(configurationDirectory, sessionId);
				if (rollout.isEmpty())
				{
					throw new IOException("The rollout of host session " + sessionId + " is not below " +
						configurationDirectory + ", so a host interrupt cannot be detected.");
				}
				signal = new RolloutInterruptSignal(rollout.get(), clock);
				located.set(signal);
			}
			return signal.isInterruptedAfter(start);
		};
	}
}

package com.holdmyspot.cat.claude.runner;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.HarnessRunStarter;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.runner.HarnessRunnerMaintenance;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Runs the Claude JSONL bridge or maintains isolated execution directories.
 */
public final class ClaudeRunnerCli
{
	/**
	 * Prints the usage, resolves a profile, verifies an artifact, runs a lifecycle-only command, or serves JSONL turns
	 * through one persistent isolated Claude process, as the arguments select. A command that names no
	 * {@code --artifact} runs the artifact that holds this launcher.
	 * <p>
	 * Inside a run whose runner lets its session request sessions, the launch becomes a session request instead: the
	 * run's own runner starts the session outside the run's sandbox, and this command returns its transcript and
	 * receipt.
	 *
	 * @param args the command's options
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	public static void main(String[] args) throws IOException
	{
		args = CommandLineArguments.recover(args);
		if (args.length == 1 && args[0].equals("--help"))
		{
			System.out.println(HarnessRunnerOptions.usage("claude-runner"));
			return;
		}
		if (args.length > 0 && args[0].equals("--resolve-profile"))
		{
			System.out.print(resolveProfile(args));
			return;
		}
		if (args.length == 2 && args[0].equals("--verify-artifact"))
		{
			System.out.println(HarnessArtifact.validate(Path.of(args[1]), Path.of(".claude-plugin/plugin.json")));
			return;
		}
		if (HarnessRunnerMaintenance.isMaintenanceCommand(args))
		{
			executeMaintenance(args);
			return;
		}
		Map<String, String> environment = Platform.current().environment(System.getenv());
		String requests = environment.get(SessionRequestAccess.REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank())
		{
			System.exit(SessionRequestForwarder.forward(HARNESS_ID, args,
				new InputStreamReader(System.in, UTF_8), Path.of(requests), Pause.sleeping(Duration.ofMillis(100)),
				System.out, new PrintWriter(System.err, true, UTF_8)));
		}
		args = HarnessRunnerOptions.withArtifact(args,
			Path.of(System.getProperty("java.home")).toAbsolutePath().getParent());
		Path home = Platform.current().homeDirectory(environment);
		FileLocks fileLocks = new FileLocks();
		try
		{
			serve(fileLocks, args, new BufferedReader(new InputStreamReader(System.in, UTF_8)),
				new OutputStreamWriter(System.out, UTF_8), environment,
				maximum -> HarnessCapacity.forHome(fileLocks, home, HARNESS_ID, maximum));
		}
		catch (HarnessCapacityWaitExpiredException e)
		{
			System.err.println(e.getMessage());
			System.exit(HarnessCapacityWaitExpiredException.EXIT_STATUS);
		}
	}

	/**
	 * Runs one lifecycle-only command without starting Claude.
	 *
	 * @param args lifecycle command arguments
	 * @throws IOException if owned-run cleanup fails
	 */
	public static void executeMaintenance(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		Map<String, String> environment = Platform.current().environment(System.getenv());
		try (ToolScope scope = ToolScope.forSession(Path.of(""), environment, SessionHarness.installed()))
		{
			HarnessRunnerMaintenance.execute(scope.fileLocks(), args, HARNESS_ID, HarnessTemporaryRoot.from(scope),
				environment, System.out);
		}
	}

	/**
	 * Serves one Claude bridge using caller-provided streams and environment.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param args bridge options
	 * @param input the bridge input
	 * @param output the bridge output
	 * @param environment the host environment
	 * @param capacityForMaximum creates the shared harness capacity for the {@code --max-concurrency} value that the
	 *   command selected
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	public static void serve(FileLocks fileLocks, String[] args, Reader input, Writer output,
		Map<String, String> environment, IntFunction<HarnessCapacity> capacityForMaximum) throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(fileLocks, "fileLocks").isNotNull();
		requireThat(capacityForMaximum, "capacityForMaximum").isNotNull();
		try (ToolScope scope = ToolScope.forSession(Path.of(""), environment, SessionHarness.installed()))
		{
			HarnessRunnerOptions options = HarnessRunnerOptions.parse(withoutClaudeOptions(args), "claude",
				HarnessTemporaryRoot.from(scope));
			ClaudeAgentDefinition agent = loadAgent(options.artifact(), agentName(args));
			List<Path> readOnlyEvidence = readOnlyEvidence(args);
			String instructionFilesMode = instructionFilesMode(args);
			FileReplacer replacer = FileReplacer.sleeping();
			HarnessRunManager manager = new HarnessRunManager(fileLocks, options.runRoot(),
				Clock.systemUTC(), replacer);
			manager.deleteStaleRuns(HarnessRunManager.DEFAULT_RETENTION);
			serve(scope, options, agent, readOnlyEvidence, instructionFilesMode, manager, input, output, environment,
				replacer, capacityForMaximum.apply(options.maximumConcurrency()));
		}
	}

	/**
	 * Runs one configured bridge and directs its JSONL transcript to the requested destination.
	 *
	 * @param scope the borrowed command scope
	 * @param options parsed shared bridge options
	 * @param agent optional direct CAT agent
	 * @param readOnlyEvidence completed executor evidence visible without write authority
	 * @param instructionFilesMode the choice of which instruction files load, or an empty string to keep the parent's
	 *   choice
	 * @param manager owned-run lifecycle manager
	 * @param input bridge input
	 * @param output bridge output when no durable destination was requested
	 * @param environment host environment
	 * @param replacer replaces the receipt
	 * @param capacity the shared harness capacity
	 * @throws IOException if bridge execution or retained-evidence writing fails
	 */
	private static void serve(ToolScope scope, HarnessRunnerOptions options, ClaudeAgentDefinition agent,
		List<Path> readOnlyEvidence,
		String instructionFilesMode, HarnessRunManager manager,
		Reader input, Writer output,
		Map<String, String> environment, FileReplacer replacer,
		HarnessCapacity capacity) throws IOException
	{
		assert options != null;
		assert instructionFilesMode != null;
		assert manager != null;
		assert input != null;
		assert output != null;
		assert environment != null;
		assert scope != null;
		assert replacer != null;
		assert capacity != null;
		try (HarnessCapacity.Permit _ = acquire(capacity, options))
		{
			HarnessRunStarter starter = () -> new ClaudeRunner().start(
				options.artifact(), options.project(), options.workspace(), options.plugins(), manager, options.executable(),
				environment,
				options.model(),
				options.effort(), agent, options.installCat(), options.includeUserRules(), readOnlyEvidence,
				options.deniedReads(), instructionFilesMode, options.sessionTranscripts(), options.sessionRequests());
			HarnessRunnerBridge.serve(starter, manager, options, scope.json(), replacer, input, output,
				(harness, arguments, directory) ->
			{
				RequestedRunner runner;
				if (harness.equals("claude"))
				{
					Path artifact = options.artifact().toAbsolutePath();
					String launcher = "claude-runner";
					if (Platform.current().isWindows())
						launcher += ".cmd";
					runner = new RequestedRunner(scope, artifact, artifact.resolve("bin").resolve(launcher),
						List.of());
				}
				else if (harness.equals("codex"))
					runner = RequestedRunner.resolved(scope, CodexArtifact.execute(environment), List.of("serve"));
				else
					throw new IllegalArgumentException("Unsupported requested harness: " + harness);
				return runner.start(arguments, directory, environment);
			});
		}
	}

	/**
	 * Takes a capacity permit, waiting no longer than the command's {@code --capacity-wait} if it named one.
	 *
	 * @param capacity the shared harness capacity
	 * @param options the parsed bridge options
	 * @return the permit
	 * @throws HarnessCapacityWaitExpiredException if the wait passed without a free permit
	 * @throws IOException if a slot cannot be locked or the wait fails
	 */
	private static HarnessCapacity.Permit acquire(HarnessCapacity capacity, HarnessRunnerOptions options)
		throws IOException
	{
		if (options.capacityWait() == null)
			return capacity.acquire();
		return capacity.acquire(options.capacityWait());
	}

	/**
	 * Prevents utility-class construction.
	 */
	private ClaudeRunnerCli()
	{
	}

	/**
	 * Resolves the configured CAT default or a selected CAT agent profile without starting a harness process.
	 *
	 * @param args the profile-resolution options
	 * @return a stable scalar profile result
	 * @throws IOException if the selected agent cannot be read
	 */
	static String resolveProfile(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (args.length == 1)
			return profile("default", "haiku-4.5", "medium");
		if (args.length != 5 || !args[1].equals("--artifact") || !args[3].equals("--agent"))
			throw new IllegalArgumentException("Usage: claude-runner --resolve-profile [--artifact <path> --agent <name>]");
		ClaudeAgentDefinition agent = ClaudeAgentDefinition.load(Path.of(args[2]), args[4]);
		return profile("agent:" + args[4], agent.model(), agent.effort());
	}

	/**
	 * Serializes one runner profile as a stable scalar result.
	 *
	 * @param owner the profile owner
	 * @param model the model name
	 * @param effort the reasoning effort
	 * @return the complete profile result
	 */
	private static String profile(String owner, String model, String effort)
	{
		assert owner != null;
		assert model != null;
		assert effort != null;
		return "harness=" + HARNESS_ID + "\nowner=" + owner + "\nmodel=" + model + "\neffort=" + effort + "\n";
	}

	/**
	 * Extracts the optional direct CAT agent name from one runner command.
	 *
	 * @param args the supplied command arguments
	 * @return the selected agent name, or an empty value
	 */
	private static String agentName(String[] args)
	{
		assert args != null;
		for (int index = 1; index < args.length; ++index)
		{
			if (!args[index].equals("--agent"))
				continue;
			if (index + 1 == args.length || args[index + 1].startsWith("--"))
				throw new IllegalArgumentException("Missing value for --agent");
			return args[index + 1];
		}
		return "";
	}

	/**
	 * Loads the requested CAT agent when one was selected.
	 *
	 * @param artifact the complete Claude artifact
	 * @param agentName the optional agent name
	 * @return the agent definition, or {@code null} when no agent was selected
	 * @throws IOException if the selected agent cannot be loaded
	 */
	private static ClaudeAgentDefinition loadAgent(Path artifact, String agentName) throws IOException
	{
		assert artifact != null;
		assert agentName != null;
		if (agentName.isBlank())
			return null;
		return ClaudeAgentDefinition.load(artifact, agentName);
	}

	/**
	 * Resolves repeated immutable evidence options.
	 *
	 * @param args the command-line arguments
	 * @return canonical evidence paths
	 * @throws IOException if an evidence path cannot be resolved
	 */
	private static List<Path> readOnlyEvidence(String[] args) throws IOException
	{
		List<Path> result = new ArrayList<>();
		for (int index = 0; index < args.length; ++index)
			if (args[index].equals("--read-only-evidence"))
			{
				if (index + 1 == args.length || args[index + 1].startsWith("--"))
					throw new IOException("Missing path after --read-only-evidence");
				index += 1;
				result.add(Path.of(args[index]).toRealPath());
			}
		return result;
	}

	/**
	 * Extracts the optional choice of which instruction files Claude Code loads, such as {@code claude-md}.
	 *
	 * @param args the command-line arguments
	 * @return the choice, or an empty string to keep the parent's choice
	 * @throws IllegalArgumentException if {@code --instruction-files} has no value
	 */
	private static String instructionFilesMode(String[] args)
	{
		assert args != null;
		for (int index = 0; index < args.length; ++index)
		{
			if (!args[index].equals("--instruction-files"))
				continue;
			if (index + 1 == args.length || args[index + 1].startsWith("--"))
				throw new IllegalArgumentException("Missing value for --instruction-files");
			return args[index + 1];
		}
		return "";
	}

	/**
	 * Removes Claude-specific options before shared option parsing.
	 *
	 * @param args the command-line arguments
	 * @return arguments accepted by the shared parser
	 */
	private static String[] withoutClaudeOptions(String[] args)
	{
		List<String> result = new ArrayList<>(args.length);
		for (int index = 0; index < args.length; ++index)
		{
			if (args[index].equals("--read-only-evidence") || args[index].equals("--agent") ||
				args[index].equals("--instruction-files"))
			{
				index += 1;
				continue;
			}
			result.add(args[index]);
		}
		return result.toArray(String[]::new);
	}
}

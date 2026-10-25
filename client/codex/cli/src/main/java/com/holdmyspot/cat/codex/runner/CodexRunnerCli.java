package com.holdmyspot.cat.codex.runner;

import static com.holdmyspot.cat.codex.CodexPluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessOneShotOptions;
import com.holdmyspot.cat.common.runner.HarnessOneShotRunner;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Parses Codex runner commands to serve JSON requests, run one prompt, or maintain isolated execution directories.
 */
public final class CodexRunnerCli
{
	/**
	 * The Codex-only bridge option that limits the tokens that the session keeps of each tool output.
	 */
	private static final String TOOL_OUTPUT_TOKEN_LIMIT = "--tool-output-token-limit";

	/**
	 * Prints the usage, runs a lifecycle-only command or one prompt, or serves JSONL turns through one persistent
	 * isolated Codex process, as the arguments select. A bridge command that names no {@code --artifact} runs the
	 * artifact that holds this launcher.
	 * <p>
	 * Inside a run whose runner lets its session request sessions, a bridge launch becomes a session request instead:
	 * the run's own runner starts the session outside the run's sandbox, and this command returns its transcript and
	 * receipt.
	 *
	 * @param args the command and its options
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	public static void main(String[] args) throws IOException
	{
		args = CommandLineArguments.recover(args);
		if (args.length == 1 && args[0].equals("--help"))
		{
			System.out.println(HarnessRunnerOptions.usage("codex-runner"));
			return;
		}
		if (HarnessRunnerMaintenance.isMaintenanceCommand(args))
		{
			executeMaintenance(args);
			return;
		}
		Map<String, String> environment = Platform.current().environment(System.getenv());
		String requests = environment.get(SessionRequestAccess.REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank() && !isOneShotCommand(args))
		{
			String[] launch = args;
			if (launch.length > 0 && launch[0].equals("serve"))
				launch = Arrays.copyOfRange(launch, 1, launch.length);
			System.exit(SessionRequestForwarder.forward(HARNESS_ID, launch,
				new InputStreamReader(System.in, UTF_8), Path.of(requests), Pause.sleeping(Duration.ofMillis(100)),
				System.out, new PrintWriter(System.err, true, UTF_8)));
		}
		Path artifact = Path.of(System.getProperty("java.home")).toAbsolutePath().getParent();
		if (!isOneShotCommand(args))
			args = HarnessRunnerOptions.withArtifact(args, artifact);
		Path home = Platform.current().homeDirectory(environment);
		FileLocks fileLocks = new FileLocks();
		if (isOneShotCommand(args))
		{
			run(fileLocks, args, environment,
				HarnessCapacity.forHome(fileLocks, home, HARNESS_ID, HarnessCapacity.MAXIMUM_CONCURRENCY));
			return;
		}
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
	 * Executes one prompt through an isolated Codex session.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param args the {@code run} command and its options
	 * @param environment the host environment
	 * @param capacity the shared harness capacity. The prompt waits for a permit before it starts Codex.
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the prompt, harness, output, or cleanup I/O fails
	 */
	public static void run(FileLocks fileLocks, String[] args, Map<String, String> environment, HarnessCapacity capacity)
		throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(fileLocks, "fileLocks").isNotNull();
		requireThat(capacity, "capacity").isNotNull();
		String agentName = agentName(args);
		HarnessOneShotOptions options;
		try (ToolScope scope = toolScope(Path.of(""), environment))
		{
			options = HarnessOneShotOptions.parse(withoutOptions(args, Set.of("--agent")), "codex",
				HarnessTemporaryRoot.from(scope));
		}
		CodexAgentDefinition agent = loadAgent(options.artifact(), agentName);
		HarnessRunManager manager = new HarnessRunManager(fileLocks, options.runRoot(), Clock.systemUTC(),
			FileReplacer.sleeping());
		String prompt = options.prompt();
		try (HarnessCapacity.Permit _ = capacity.acquire())
		{
			HarnessOneShotRunner.run(() -> new CodexRunner().start(options.artifact(),
				options.project(), manager,
				options.executable(), environment, options.model(), "", options.installCat(), agent), manager, prompt,
				options.output(), options.keepRun());
		}
	}

	/**
	 * Runs one lifecycle-only command without starting Codex.
	 *
	 * @param args lifecycle command arguments
	 * @throws IOException if owned-run cleanup fails
	 */
	public static void executeMaintenance(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		Map<String, String> environment = Platform.current().environment(System.getenv());
		try (ToolScope scope = toolScope(Path.of(""), environment))
		{
			HarnessRunnerMaintenance.execute(scope.fileLocks(), args, HARNESS_ID, HarnessTemporaryRoot.from(scope),
				environment, System.out);
		}
	}

	/**
	 * Opens the tool scope of a runner command. Its CAT session directory, whose temporary directory holds the runs
	 * of a command line that names no {@code --run-root}, belongs to the session whose tool shell runs the command, in
	 * the nearest CAT project.
	 *
	 * @param workingDirectory the directory from which the command was invoked
	 * @param environment the command's environment, whose session variable names the session
	 * @return an open tool scope
	 * @throws NullPointerException if any argument is null
	 */
	public static ToolScope toolScope(Path workingDirectory, Map<String, String> environment)
	{
		return ToolScope.forSession(workingDirectory, environment, SessionHarness.installed());
	}

	/**
	 * Serves one Codex bridge using caller-provided streams and environment.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param args the {@code serve} command and its options
	 * @param input the bridge input
	 * @param output the bridge output
	 * @param environment the host environment
	 * @param capacityForMaximum creates the shared harness capacity for the {@code --max-concurrency} value that the
	 *   command selected. The bridge waits for a permit before it starts Codex.
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
		try (ToolScope scope = toolScope(Path.of(""), environment))
		{
			String agentName = agentName(args);
			int toolOutputTokenLimit = toolOutputTokenLimit(args);
			String[] runnerArgs = withoutOptions(args, Set.of("--agent", TOOL_OUTPUT_TOKEN_LIMIT));
			HarnessTemporaryRoot temporaryRoot = HarnessTemporaryRoot.from(scope);
			HarnessRunnerOptions options;
			if (runnerArgs.length > 0 && runnerArgs[0].equals("serve"))
				options = HarnessRunnerOptions.parseServe(runnerArgs, "codex", temporaryRoot);
			else
				options = HarnessRunnerOptions.parse(runnerArgs, "codex", temporaryRoot);
			CodexAgentDefinition agent = loadAgent(options.artifact(), agentName);
			FileReplacer replacer = FileReplacer.sleeping();
			HarnessRunManager manager = new HarnessRunManager(fileLocks, options.runRoot(),
				Clock.systemUTC(), replacer);
			HarnessCapacity capacity = capacityForMaximum.apply(options.maximumConcurrency());
			HarnessCapacity.Permit permit;
			if (options.capacityWait() == null)
				permit = capacity.acquire();
			else
				permit = capacity.acquire(options.capacityWait());
			try (HarnessCapacity.Permit _ = permit)
			{
				HarnessRunnerBridge.serve(() -> new CodexRunner().start(options.artifact(),
					options.project(),
					options.workspace(), options.plugins(), manager, options.executable(), environment, options.model(),
					options.effort(), options.installCat(), agent, List.of(), options.deniedReads(), toolOutputTokenLimit,
					options.sessionTranscripts(), options.sessionRequests()),
					manager, options, scope.json(), replacer, input, output, (harness, arguments, directory) ->
			{
				RequestedRunner runner;
				if (harness.equals("codex"))
				{
					Path artifact = options.artifact().toAbsolutePath();
					String launcher = "codex-runner";
					if (Platform.current().isWindows())
						launcher += ".cmd";
					runner = new RequestedRunner(scope, artifact, artifact.resolve("bin").resolve(launcher),
						List.of("serve"));
				}
				else if (harness.equals("claude"))
					runner = RequestedRunner.resolved(scope, ClaudeArtifact.execute(environment), List.of());
				else
					throw new IllegalArgumentException("Unsupported requested harness: " + harness);
				return runner.start(arguments, directory, environment);
			});
			}
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private CodexRunnerCli()
	{
	}


	/**
	 * Indicates whether the arguments select a one-shot run.
	 *
	 * @param args command-line arguments
	 * @return {@code true} if the first argument is {@code run}
	 */
	private static boolean isOneShotCommand(String[] args)
	{
		assert args != null;
		return args.length > 0 && args[0].equals("run");
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
	 * Extracts the optional tool-output token limit from one runner command.
	 *
	 * @param args the supplied command arguments
	 * @return the most tokens of each tool output that the session keeps, or {@code 0} for Codex's default
	 */
	public static int toolOutputTokenLimit(String[] args)
	{
		assert args != null;
		for (int index = 1; index < args.length; ++index)
		{
			if (!args[index].equals(TOOL_OUTPUT_TOKEN_LIMIT))
				continue;
			if (index + 1 == args.length || args[index + 1].startsWith("--"))
				throw new IllegalArgumentException("Missing value for " + TOOL_OUTPUT_TOKEN_LIMIT);
			String value = args[index + 1];
			int tokens;
			try
			{
				tokens = Integer.parseInt(value);
			}
			catch (NumberFormatException e)
			{
				throw new IllegalArgumentException(TOOL_OUTPUT_TOKEN_LIMIT + " must be a positive number of tokens: " +
					value, e);
			}
			if (tokens <= 0)
			{
				throw new IllegalArgumentException(TOOL_OUTPUT_TOKEN_LIMIT + " must be a positive number of tokens: " +
					value);
			}
			return tokens;
		}
		return 0;
	}

	/**
	 * Loads the requested CAT agent when one was selected.
	 *
	 * @param artifact the complete Codex artifact
	 * @param agentName the optional agent name
	 * @return the agent definition, or {@code null} when no agent was selected
	 * @throws IOException if the selected agent cannot be loaded
	 */
	private static CodexAgentDefinition loadAgent(Path artifact, String agentName) throws IOException
	{
		assert artifact != null;
		assert agentName != null;
		if (agentName.isBlank())
			return null;
		return CodexAgentDefinition.load(artifact, agentName);
	}

	/**
	 * Removes Codex-only options before parsing the shared runner options.
	 *
	 * @param args the supplied command arguments
	 * @param options the Codex-only options to remove, each with its value
	 * @return arguments accepted by the shared parser
	 */
	private static String[] withoutOptions(String[] args, Set<String> options)
	{
		assert args != null;
		assert options != null;
		List<String> result = new ArrayList<>(args.length);
		for (int index = 0; index < args.length; ++index)
		{
			if (options.contains(args[index]))
			{
				index += 1;
				continue;
			}
			result.add(args[index]);
		}
		return result.toArray(String[]::new);
	}
}

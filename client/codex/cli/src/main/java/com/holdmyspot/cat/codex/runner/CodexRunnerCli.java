package com.holdmyspot.cat.codex.runner;

import static com.holdmyspot.cat.codex.CodexPluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.secrets.CodexRunnerCliSecrets;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.ParentSandbox;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import com.holdmyspot.cat.common.runner.launcher.client.SessionLauncherClient;

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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses Codex runner commands to serve JSON requests.
 */
public final class CodexRunnerCli
{
	static
	{
		CodexRunnerCliSecrets.setCodexRunnerCliAccess(CodexRunnerCli::toolScope);
	}

	/**
	 * The Codex-only bridge option that limits the tokens that the session keeps of each tool output.
	 */
	private static final String TOOL_OUTPUT_TOKEN_LIMIT = "--tool-output-token-limit";

	/**
	 * Prints the usage, verifies an artifact, or serves JSONL turns through one persistent isolated Codex process, as
	 * the arguments select. The launcher always runs the artifact that holds it and the {@code codex} command that the
	 * {@code PATH} finds: before anything else, it refuses {@code --artifact} with exit status
	 * {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} unless it forwards the launch as a session request. It also
	 * refuses, with the same status, any launch that starts a session on a platform where CAT does not let Codex
	 * sandbox the session, or from a Codex sandbox that has network off.
	 * <p>
	 * Inside a run whose runner lets its session request sessions, the launch becomes a session request instead: the
	 * run's own runner starts the session outside the run's sandbox, and this command returns its transcript and
	 * receipt. Outside a run, the launch becomes a request to the session launcher of the harness process that runs
	 * the command, which starts the session outside the command's sandbox, unless a session launcher started the
	 * launch itself: that launch names {@value SessionLauncherClient#POLICY_BOUND_OPTION} and serves the session in
	 * place.
	 * <p>
	 * With {@code serve}, {@value ChildNeedsReport#OPTION} and a file, it writes what a Codex child session needs to
	 * that file, as {@link CodexRunner#childNeeds(Map)} reports it, for the session launcher that asks before it
	 * starts the child, and starts nothing.
	 *
	 * @param args the command and its options
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	public static void main(String[] args) throws IOException
	{
		args = CommandLineArguments.recover(args);
		Map<String, String> environment = Platform.current().environment(System.getenv());
		if (refusesLaunch(args, environment, Platform.current(), new PrintWriter(System.err, true, UTF_8)))
			System.exit(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
		if (args.length == 1 && args[0].equals("--help"))
		{
			System.out.println(HarnessRunnerOptions.usage("codex-runner"));
			return;
		}
		if (args.length == 2 && args[0].equals("--verify-artifact"))
		{
			System.out.println(HarnessArtifact.validate(Path.of(args[1]), Path.of(".codex-plugin/plugin.json")));
			return;
		}
		if (args.length == 3 && args[0].equals("serve") && args[1].equals(ChildNeedsReport.OPTION))
		{
			CodexRunner.childNeeds(environment).write(JsonMapper.builder().build(), Path.of(args[2]));
			return;
		}
		String[] launch = args;
		if (launch.length > 0 && launch[0].equals("serve"))
			launch = Arrays.copyOfRange(launch, 1, launch.length);
		String[] serveArguments = launch;
		String requests = environment.get(SessionRequestAccess.REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank())
		{
			System.exit(HarnessRunnerOptions.refusingInvalidArguments(() -> SessionRequestForwarder.forward(
				HARNESS_ID, serveArguments, new InputStreamReader(System.in, UTF_8), Path.of(requests),
				Pause.sleeping(Duration.ofMillis(100)), System.out, errors()), errors()));
		}
		if (SessionLauncherClient.serves(Platform.current(), args))
		{
			System.exit(HarnessRunnerOptions.refusingInvalidArguments(() -> SessionLauncherClient.launchFromMain(
				HARNESS_ID, serveArguments, environment, Pause::sleeping, new InputStreamReader(System.in, UTF_8),
				System.out, errors()), errors()));
		}
		String[] bridgeArguments = args;
		int status = HarnessRunnerOptions.refusingInvalidArguments(
			() -> serveInProcess(bridgeArguments, environment, Pause::sleeping), errors());
		if (status != 0)
			System.exit(status);
	}

	/**
	 * Returns a writer for the launcher's standard error that flushes each line.
	 *
	 * @return the writer
	 */
	private static PrintWriter errors()
	{
		return new PrintWriter(System.err, true, UTF_8);
	}

	/**
	 * Serves the launch in this process.
	 *
	 * @param args        the command and its options
	 * @param environment the launcher's environment
	 * @param waiting     the process's waiting
	 * @return the exit status
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	private static int serveInProcess(String[] args, Map<String, String> environment, Waiting waiting)
		throws IOException
	{
		args = HarnessRunnerOptions.withArtifact(args, PluginDirectory.of(CodexRunnerCli.class));
		Path home = Platform.current().homeDirectory(environment);
		try (JvmScope process = new MainJvmScope(environment, waiting))
		{
			serve(process, Path.of(""), args, new BufferedReader(new InputStreamReader(System.in, UTF_8)),
				new OutputStreamWriter(System.out, UTF_8), environment, maximum -> HarnessCapacity.forHome(
					process.fileLocks(), home, HARNESS_ID, maximum, process.waiting()));
			return 0;
		}
		catch (HarnessCapacityWaitExpiredException e)
		{
			System.err.println(e.getMessage());
			return HarnessCapacityWaitExpiredException.EXIT_STATUS;
		}
		catch (SessionRefusedException e)
		{
			System.err.println(e.getMessage());
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		}
	}

	/**
	 * Refuses a launch that names {@code --artifact}, other than inside a run whose runner lets its session request
	 * sessions. There every launch becomes a session request, whose runner ignores the option.
	 *
	 * @param args        the command and its options
	 * @param environment the launcher's environment
	 * @param errors      receives the reason for a refusal
	 * @return {@code true} if the launch is refused
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean refusesOverride(String[] args, Map<String, String> environment, PrintWriter errors)
	{
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(errors, "errors").isNotNull();
		String requests = environment.get(SessionRequestAccess.REQUESTS_VARIABLE);
		boolean forwarded = requests != null && !requests.isBlank();
		return !forwarded && HarnessRunnerOptions.refusesOverride(HARNESS_ID, args, errors);
	}

	/**
	 * Refuses a launch that would start a session, but must not or cannot. That is a launch that names
	 * {@code --artifact}, as {@link #refusesOverride} refuses it, any launch on a platform where CAT does not let Codex
	 * confine the session in a sandbox, as {@link CodexSessionSandbox} decides, and any launch from a Codex sandbox
	 * that has network off, whose session could not reach its model. Printing the usage and verifying an artifact
	 * start no session, so they are never refused for the platform or the sandbox.
	 *
	 * @param args        the command and its options
	 * @param environment the launcher's environment
	 * @param platform    the platform that the launcher runs on
	 * @param errors      receives the reason for a refusal
	 * @return {@code true} if the launch is refused
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean refusesLaunch(String[] args, Map<String, String> environment, Platform platform,
		PrintWriter errors)
	{
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(errors, "errors").isNotNull();
		if (refusesOverride(args, environment, errors))
			return true;
		boolean startsNothing = args.length == 1 && args[0].equals("--help") ||
			args.length == 2 && args[0].equals("--verify-artifact");
		if (startsNothing)
			return false;
		Optional<String> refusal = CodexSessionSandbox.refusal(platform);
		if (refusal.isPresent())
		{
			errors.println(refusal.get());
			return true;
		}
		return ParentSandbox.refusesNetworkOff(HARNESS_ID, environment, errors);
	}

	/**
	 * Opens the tool scope of a runner command. Its CAT session directory, whose temporary directory holds the runs
	 * of a command line that names no {@code --run-root}, belongs to the session whose tool shell runs the command, in
	 * the nearest CAT project.
	 *
	 * @param workingDirectory the directory from which the command was invoked
	 * @param environment the command's environment, whose session variable names the session
	 * @param waiting how the command waits between the attempts of a retried operation
	 * @return an open tool scope
	 * @throws NullPointerException if any argument is null
	 */
	public static ToolScope toolScope(Path workingDirectory, Map<String, String> environment, Waiting waiting)
	{
		return toolScope(workingDirectory, environment, waiting, SessionIgnore.GIT_PROCESS);
	}

	/**
	 * Opens the tool scope of a runner command, as {@link #toolScope(Path, Map, Waiting)} does, whose CAT session
	 * directory decides what to add to the project's {@code .gitignore} from the answers that a given Git gives.
	 *
	 * @param workingDirectory the directory from which the command was invoked
	 * @param environment      the command's environment, whose session variable names the session
	 * @param waiting          how the command waits between the attempts of a retried operation
	 * @param git              answers what Git ignores in the project
	 * @return an open tool scope
	 * @throws NullPointerException if any argument is null
	 */
	private static ToolScope toolScope(Path workingDirectory, Map<String, String> environment, Waiting waiting,
		SessionIgnore.GitQueries git)
	{
		return ToolScope.forSession(workingDirectory, environment, SessionHarness.installed(), waiting, git);
	}

	/**
	 * Serves one Codex bridge using caller-provided streams and environment.
	 *
	 * @param process the process scope, whose lock coordinator and waiting the bridge uses
	 * @param workingDirectory the directory from which the command was invoked
	 * @param args the {@code serve} command and its options
	 * @param input the bridge input
	 * @param output the bridge output
	 * @param environment the host environment
	 * @param capacityForMaximum creates the shared harness capacity for the {@code --max-concurrency} value that the
	 *   command selected. The bridge waits for a permit before it starts Codex, unless the command names
	 *   {@code --capacity-held}.
	 * @throws NullPointerException    if any argument is null
	 * @throws SessionRefusedException if the launch names no {@code --policy-bound}, or the session's sandbox would
	 *                                 not confine it as its policy requires
	 * @throws IOException             if the policy bound cannot be read, or runner startup, the harness, or bridge
	 *                                 I/O fails
	 */
	public static void serve(JvmScope process, Path workingDirectory, String[] args, Reader input, Writer output,
		Map<String, String> environment, IntFunction<HarnessCapacity> capacityForMaximum) throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(process, "process").isNotNull();
		requireThat(capacityForMaximum, "capacityForMaximum").isNotNull();
		try (ToolScope scope = toolScope(workingDirectory, environment, process.waiting()))
		{
			String agentName = agentName(args);
			int toolOutputTokenLimit = toolOutputTokenLimit(args);
			HarnessRunnerOptions options = options(args, HarnessTemporaryRoot.from(scope));
			Path cat = options.catArtifact(CodexRunner.MANIFEST);
			CodexAgentDefinition agent = loadAgent(cat, agentName);
			ParentPolicy bound = policyBound(scope.json(), options);
			FileReplacer replacer = FileReplacer.retrying(process.waiting());
			HarnessRunManager manager = HarnessRunManager.openRunRoot(process.fileLocks(), options.runRoot(),
				Clock.systemUTC(), replacer);
			HarnessCapacity.Permit permit = null;
			if (!options.capacityHeld())
			{
				HarnessCapacity capacity = capacityForMaximum.apply(options.maximumConcurrency());
				if (options.capacityWait() == null)
					permit = capacity.acquire();
				else
					permit = capacity.acquire(options.capacityWait());
			}
			try (HarnessCapacity.Permit _ = permit)
			{
				AtomicReference<ChildPolicy> policy = new AtomicReference<>();
				List<Path> trialPlugins = options.trialPlugins(CodexRunner.MANIFEST);
				HarnessRunnerBridge.serve(() -> new CodexRunner(process.waiting()).start(cat, options.project(),
					options.workspace(), trialPlugins, manager, "codex", environment, options.model(),
					options.effort(), options.installCat(), agent, options.readOnlyEvidence(), options.deniedReads(),
					toolOutputTokenLimit, options.sessionTranscripts(), options.sessionRequests(), bound, policy::set,
					options.gitName(), options.gitEmail()),
					manager, options, scope.json(), replacer, input, output, (harness, arguments, directory) ->
					requestedRunner(scope, options.artifact(), harness, environment).start(PolicyBoundFile.
						boundArguments(scope.json(), arguments, directory, policy.get()), directory, environment));
			}
		}
	}

	/**
	 * Returns the runner of a harness that a Codex session requests: the {@code codex-runner} of this plugin, or the
	 * {@code claude-runner} of the Claude artifact that the {@code claude-artifact} launcher finds.
	 *
	 * @param scope       the borrowed process scope
	 * @param artifact    this plugin's folder
	 * @param harness     the requested harness's identifier
	 * @param environment the host environment
	 * @return the runner
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if CAT has no runner for {@code harness}
	 * @throws IOException              if the Claude artifact cannot be found
	 */
	public static RequestedRunner requestedRunner(JvmScope scope, Path artifact, String harness,
		Map<String, String> environment) throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(harness, "harness").isNotNull();
		requireThat(environment, "environment").isNotNull();
		return switch (harness)
		{
			case HARNESS_ID ->
			{
				String launcher = "codex-runner";
				if (Platform.current().isWindows())
					launcher += ".cmd";
				yield new RequestedRunner(scope, artifact.toAbsolutePath().resolve("bin").resolve(launcher),
					List.of("serve"));
			}
			case "claude" -> RequestedRunner.resolved(scope, ClaudeArtifact.execute(environment), List.of());
			default -> throw new IllegalArgumentException("Unsupported requested harness: " + harness);
		};
	}

	/**
	 * Reads the policy that bounds the session's sandbox policy, as the policy of the session's parent.
	 *
	 * @param json    the JSON mapper
	 * @param options the launch's options
	 * @return the bound
	 * @throws SessionRefusedException if the launch names no bound
	 * @throws IOException             if the bound's file cannot be read or is not a bound
	 */
	private static ParentPolicy policyBound(JsonMapper json, HarnessRunnerOptions options) throws IOException
	{
		if (options.policyBound() == null)
		{
			throw new SessionRefusedException("codex-runner starts a session only through CAT's session launcher, " +
				"which names the policy that bounds the session's sandbox with " + PolicyBoundFile.OPTION + ", and " +
				"this launch names none, so CAT does not start the session. Start the session from a Claude Code or " +
				"Codex session in which CAT is installed");
		}
		return PolicyBoundFile.read(json, options.policyBound().toAbsolutePath()).asParentPolicy();
	}

	/**
	 * Parses the options of a bridge command, other than the Codex-only {@code --tool-output-token-limit}. A command
	 * that selects a CAT agent definition with {@code --agent} runs under the model and effort that the definition
	 * names.
	 *
	 * @param args          the bridge command and its options, optionally starting with {@code serve}
	 * @param temporaryRoot where the runs are kept when the command names no {@code --run-root}
	 * @return the parsed options
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if an option is unknown, has no value or has an invalid value, such as a
	 *                                  relative path after {@code --deny-read} or {@code --read-only-evidence}, or if
	 *                                  the command names no {@code --run-root} outside a harness session, if it names
	 *                                  {@code --agent} together with {@code --model} or {@code --effort}, or if it
	 *                                  names neither {@code --agent} nor both {@code --model} and {@code --effort}
	 * @throws IOException              if the selected agent definition is absent or does not name both a model and
	 *                                  an effort
	 */
	public static HarnessRunnerOptions options(String[] args, HarnessTemporaryRoot temporaryRoot)
		throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		String[] runnerArgs = withAgentProfile(withoutOptions(args, Set.of("--agent", TOOL_OUTPUT_TOKEN_LIMIT)),
			agentName(args));
		if (runnerArgs.length > 0 && runnerArgs[0].equals("serve"))
			return HarnessRunnerOptions.parseServe(runnerArgs, "codex", temporaryRoot);
		return HarnessRunnerOptions.parse(runnerArgs, "codex", temporaryRoot);
	}

	/**
	 * Prevents utility-class construction.
	 */
	private CodexRunnerCli()
	{
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
	 * Gives a launch that selects a CAT agent definition the model and effort that the definition names.
	 *
	 * @param runnerArgs the launch's options without {@code --agent}
	 * @param agentName  the selected agent's name, or an empty string if the launch selects none
	 * @return {@code runnerArgs}, followed by the agent's {@code --model} and {@code --effort} if the launch selects an
	 *   agent and names an artifact
	 * @throws IllegalArgumentException if the launch selects an agent and names {@code --model} or {@code --effort}
	 * @throws IOException              if the selected agent definition is absent or does not name both a model and
	 *                                  an effort
	 */
	private static String[] withAgentProfile(String[] runnerArgs, String agentName) throws IOException
	{
		assert runnerArgs != null;
		assert agentName != null;
		if (agentName.isBlank())
			return runnerArgs;
		HarnessRunnerOptions.requireNoProfileBesideAgent(runnerArgs);
		Path artifact = HarnessRunnerOptions.catArtifact(runnerArgs, CodexRunner.MANIFEST);
		if (artifact == null)
			return runnerArgs;
		CodexAgentDefinition agent = CodexAgentDefinition.load(artifact, agentName);
		return HarnessRunnerOptions.withAgentProfile(runnerArgs, agent.model(), agent.effort());
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

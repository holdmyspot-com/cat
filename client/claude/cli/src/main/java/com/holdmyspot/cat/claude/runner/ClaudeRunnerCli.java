package com.holdmyspot.cat.claude.runner;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.policy.ClaudeParentPolicy;
import com.holdmyspot.cat.claude.policy.ClaudePolicyReader;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.HarnessRunStarter;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.MainHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.policy.InPlaceBound;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;

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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the Claude JSONL bridge.
 */
public final class ClaudeRunnerCli
{
	/**
	 * Prints the usage, verifies an artifact, or serves JSONL turns through one persistent isolated Claude process, as
	 * the arguments select. The launcher always runs the artifact that holds it and the {@code claude} command that the
	 * {@code PATH} finds: before anything else, it refuses {@code --artifact} with exit status
	 * {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} unless it forwards the launch as a session request. It also
	 * refuses, with the same status, any launch that starts a session on a platform where Claude Code cannot sandbox
	 * the session.
	 * <p>
	 * Inside a run whose runner lets its session request sessions, the launch becomes a session request instead: the
	 * run's own runner starts the session outside the run's sandbox, and this command returns its transcript and
	 * receipt. Inside a run that lets its session request no sessions, the launch is refused. Outside a run, the
	 * session starts in place, inside the sandbox of the command that runs this launcher, as
	 * {@link #serve(JvmScope, Path, String[], Reader, Writer, Map, IntFunction, InPlaceBound)} describes.
	 * <p>
	 * With {@value ChildNeedsReport#OPTION} and a file, it writes what a Claude child session needs to that file, as
	 * {@link ClaudeRunner#childNeeds(HarnessRunnerScope, Map)} reports it, for the runner of another harness that asks
	 * before it starts the child, and starts nothing.
	 *
	 * @param args the command's options
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
			System.out.println(HarnessRunnerOptions.usage("claude-runner"));
			return;
		}
		if (args.length == 2 && args[0].equals("--verify-artifact"))
		{
			System.out.println(HarnessArtifact.validate(Path.of(args[1]), Path.of(".claude-plugin/plugin.json")));
			return;
		}
		if (args.length == 2 && args[0].equals(ChildNeedsReport.OPTION))
		{
			try (HarnessRunnerScope scope = new MainHarnessRunnerScope(environment, Pause::sleeping))
			{
				ClaudeRunner.childNeeds(scope, environment).write(scope.json(), Path.of(args[1]));
			}
			return;
		}
		String[] launch = args;
		String requests = environment.get(SessionRequestAccess.REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank())
		{
			System.exit(HarnessRunnerOptions.refusingInvalidArguments(() -> SessionRequestForwarder.forward(
				HARNESS_ID, launch, new InputStreamReader(System.in, UTF_8), Path.of(requests),
				Pause.sleeping(Duration.ofMillis(100)), System.out, errors()), errors()));
		}
		int status = HarnessRunnerOptions.refusingInvalidArguments(
			() -> serveInProcess(launch, environment, Pause::sleeping), errors());
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
	 * @param args        the command's options
	 * @param environment the launcher's environment
	 * @param waiting     the process's waiting
	 * @return the exit status
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	private static int serveInProcess(String[] args, Map<String, String> environment, Waiting waiting)
		throws IOException
	{
		args = HarnessRunnerOptions.withArtifact(args, PluginDirectory.of(ClaudeRunnerCli.class));
		Path home = Platform.current().homeDirectory(environment);
		try (JvmScope process = new MainJvmScope(environment, waiting))
		{
			serve(process, Path.of(""), args, new BufferedReader(new InputStreamReader(System.in, UTF_8)),
				new OutputStreamWriter(System.out, UTF_8), environment, maximum -> HarnessCapacity.forHome(
					process.fileLocks(), home, HARNESS_ID, maximum, process.waiting()),
				inPlaceBound(environment, waiting));
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
	 * @param args        the launch's options
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
		if (requests != null && !requests.isBlank())
			return false;
		return HarnessRunnerOptions.refusesOverride(HARNESS_ID, args, errors);
	}

	/**
	 * Refuses a launch that would start a session, but must not. That is a launch that names {@code --artifact}, as
	 * {@link #refusesOverride} refuses it, and any launch on a platform where Claude Code cannot confine the session
	 * in a sandbox, as {@link ClaudeSessionSandbox} decides. Printing the usage and verifying an artifact start no
	 * session, so they are never refused for the platform.
	 *
	 * @param args        the launch's options
	 * @param environment the launcher's environment
	 * @param platform    the platform that the launcher runs on
	 * @param errors      receives the reason for a refusal
	 * @return {@code true} if the launch is refused
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean refusesLaunch(String[] args, Map<String, String> environment, Platform platform,
		PrintWriter errors)
	{
		requireThat(platform, "platform").isNotNull();
		if (refusesOverride(args, environment, errors))
			return true;
		boolean startsNothing = args.length == 1 && args[0].equals("--help") ||
			args.length == 2 && args[0].equals("--verify-artifact");
		if (startsNothing)
			return false;
		Optional<String> refusal = ClaudeSessionSandbox.refusal(platform);
		if (refusal.isEmpty())
			return false;
		errors.println(refusal.get());
		return true;
	}

	/**
	 * Returns the bound of a Claude session that a command of the harness session that runs this process starts in
	 * place: the policy of that harness session, as {@link ClaudeParentPolicy} reads it from the command's
	 * environment, and what a Claude child needs, as {@link ClaudeRunner#childNeeds(HarnessRunnerScope, Map)} reports
	 * it.
	 *
	 * @param environment the environment of the command that runs this process
	 * @param waiting     the process's waiting
	 * @return the bound
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the environment names no home folder
	 */
	public static InPlaceBound inPlaceBound(Map<String, String> environment, Waiting waiting) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		Path home = Platform.current().homeDirectory(environment);
		return new InPlaceBound(new ClaudeParentPolicy(new ClaudePolicyReader(), environment, home,
			System.getProperty("user.name", "")), (harness, runFolder, deniedReads, bound) ->
		{
			try (HarnessRunnerScope scope = new MainHarnessRunnerScope(environment, waiting))
			{
				return ClaudeRunner.childNeeds(scope, environment).needs(harness, runFolder, deniedReads, bound);
			}
		});
	}

	/**
	 * Serves one Claude bridge using caller-provided streams and environment.
	 * <p>
	 * {@code --policy-bound} names the file of the policy that bounds the session's sandbox policy, which the runner
	 * that starts a requested session writes. A launch without it starts the session in place, inside the sandbox of
	 * the command that runs it: {@code inPlace} bounds the session by that command's policy, and the launch is refused
	 * before it takes a capacity slot or creates a run if the policy cannot be read or does not allow what the
	 * session needs. A launch from a session of a CAT run is refused, since such a session starts sessions only as
	 * requests to the run's own runner. A session that the bounded session requests is bounded in turn by the
	 * bounded session's policy.
	 *
	 * @param process            the process scope, whose lock coordinator and waiting the bridge uses
	 * @param workingDirectory   the directory from which the command was invoked
	 * @param args               bridge options
	 * @param input              the bridge input
	 * @param output             the bridge output
	 * @param environment        the host environment
	 * @param capacityForMaximum creates the shared harness capacity for the {@code --max-concurrency} value that the
	 *                           command selected
	 * @param inPlace            bounds a session that starts in place
	 * @throws NullPointerException      if any argument is null
	 * @throws SessionRefusedException   if the launch comes from a session of a CAT run, or the session's sandbox
	 *                                   would not confine it as its bound requires; the message says how to allow it
	 * @throws InvalidArgumentsException if the file of extra permissions that the launch names is missing or invalid
	 * @throws IOException               if the policy bound cannot be read, or runner startup, the harness, or bridge
	 *                                   I/O fails
	 */
	public static void serve(JvmScope process, Path workingDirectory, String[] args, Reader input, Writer output,
		Map<String, String> environment, IntFunction<HarnessCapacity> capacityForMaximum, InPlaceBound inPlace)
		throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(process, "process").isNotNull();
		requireThat(capacityForMaximum, "capacityForMaximum").isNotNull();
		requireThat(inPlace, "inPlace").isNotNull();
		Optional<String> inRun = HarnessEnvironment.inRunRefusal(HARNESS_ID, environment);
		if (inRun.isPresent())
			throw new SessionRefusedException(inRun.get());
		try (ToolScope scope = ToolScope.forSession(workingDirectory, environment, SessionHarness.installed(),
			process.waiting()))
		{
			HarnessRunnerOptions options = options(args, HarnessTemporaryRoot.from(scope));
			ClaudeAgentDefinition agent = loadAgent(options.catArtifact(ClaudeRunner.MANIFEST), agentName(args));
			List<Path> readOnlyEvidence = readOnlyEvidence(args);
			String instructionFilesMode = instructionFilesMode(args);
			ExtraPermissions extraPermissions = HarnessRunnerOptions.readExtraPermissions(scope.json(),
				options.extraPermissions());
			ChildPolicy bound = policyBound(scope.json(), options, extraPermissions, inPlace);
			FileReplacer replacer = FileReplacer.retrying(process.waiting());
			HarnessRunManager manager = HarnessRunManager.openRunRoot(process.fileLocks(), options.runRoot(),
				Clock.systemUTC(), replacer);
			HarnessCapacity capacity = capacityForMaximum.apply(options.maximumConcurrency());
			serve(scope, options, agent, readOnlyEvidence, instructionFilesMode, bound, extraPermissions, manager,
				input, output, environment, replacer, capacity);
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
	 * @param bound the policy that bounds the session's sandbox policy, or {@code null} if nothing bounds it
	 * @param extraPermissions the writes and hosts that the session's commands need beyond what Claude Code needs
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
		String instructionFilesMode, ChildPolicy bound, ExtraPermissions extraPermissions, HarnessRunManager manager,
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
		try (HarnessCapacity.Permit _ = acquire(capacity, options))
		{
			AtomicReference<ChildPolicy> policy = new AtomicReference<>();
			Path cat = options.catArtifact(ClaudeRunner.MANIFEST);
			List<Path> trialPlugins = options.trialPlugins(ClaudeRunner.MANIFEST);
			HarnessRunStarter starter = () -> new ClaudeRunner(scope.waiting()).start(cat, options.project(),
				options.workspace(), trialPlugins, manager, "claude", environment, options.model(), options.effort(),
				agent, options.installCat(), readOnlyEvidence, options.deniedReads(), instructionFilesMode,
				options.sessionTranscripts(), options.sessionRequests(), bound, policy::set,
				extraPermissions);
			HarnessRunnerBridge.serve(starter, manager, options, scope.json(), replacer, input, output,
				(harness, arguments, directory) -> requestedRunner(scope, options.artifact(), harness, environment).
					start(PolicyBoundFile.boundArguments(scope.json(), arguments, directory, policy.get()), directory,
						environment));
		}
	}

	/**
	 * Returns the runner of a harness that a Claude Code session requests: the {@code claude-runner} of this plugin,
	 * or the {@code codex-runner} of the Codex artifact that the {@code codex-artifact} launcher finds.
	 *
	 * @param scope       the borrowed process scope
	 * @param artifact    this plugin's folder
	 * @param harness     the requested harness's identifier
	 * @param environment the host environment
	 * @return the runner
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if CAT has no runner for {@code harness}
	 * @throws IOException              if the Codex artifact cannot be found
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
			case HARNESS_ID -> new RequestedRunner(scope, artifact.toAbsolutePath().resolve("bin/claude-runner"),
				List.of());
			case "codex" -> RequestedRunner.resolved(scope, CodexArtifact.execute(environment), List.of("serve"));
			default -> throw new IllegalArgumentException("Unsupported requested harness: " + harness);
		};
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
	 * Parses the options of a bridge command, other than the Claude-only {@code --read-only-evidence} and
	 * {@code --instruction-files}. A command that selects a CAT agent definition with {@code --agent} runs under the
	 * model and effort that the definition names.
	 *
	 * @param args          the bridge command's options
	 * @param temporaryRoot where the runs are kept when the command names no {@code --run-root}
	 * @return the parsed options
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if an option is unknown, has no value or has an invalid value, if the command
	 *                                  names {@code --agent} together with {@code --model} or {@code --effort}, or if
	 *                                  it names neither {@code --agent} nor both {@code --model} and {@code --effort}
	 * @throws IOException              if the selected agent definition is absent or does not name both a model and
	 *                                  an effort
	 */
	public static HarnessRunnerOptions options(String[] args, HarnessTemporaryRoot temporaryRoot)
		throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		String[] runnerArgs = withoutClaudeOptions(args);
		String agentName = agentName(args);
		if (!agentName.isBlank())
		{
			HarnessRunnerOptions.requireNoProfileBesideAgent(runnerArgs);
			Path artifact = HarnessRunnerOptions.catArtifact(runnerArgs, ClaudeRunner.MANIFEST);
			if (artifact != null)
			{
				ClaudeAgentDefinition agent = ClaudeAgentDefinition.load(artifact, agentName);
				runnerArgs = HarnessRunnerOptions.withAgentProfile(runnerArgs, agent.model(), agent.effort());
			}
		}
		return HarnessRunnerOptions.parse(runnerArgs, "claude", temporaryRoot);
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
				throw new InvalidArgumentsException("Missing value for --agent");
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
				throw new InvalidArgumentsException("Missing value for --instruction-files");
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

	/**
	 * Returns the policy that bounds the session's sandbox policy.
	 *
	 * @param json             the JSON mapper
	 * @param options          the launch's options
	 * @param extraPermissions the writes and hosts that the session's commands need beyond what Claude Code needs
	 * @param inPlace          bounds a session that starts in place
	 * @return the bound that the launch's {@code --policy-bound} names, or else the bound of a session that starts in
	 *         place
	 * @throws SessionRefusedException if the session starts in place and its bound cannot be established
	 * @throws IOException             if the bound's file cannot be read or is not a bound, or what the session needs
	 *                                 cannot be determined
	 */
	private static ChildPolicy policyBound(JsonMapper json, HarnessRunnerOptions options,
		ExtraPermissions extraPermissions, InPlaceBound inPlace) throws IOException
	{
		if (options.policyBound() == null)
			return inPlace.bound(HARNESS_ID, options.runRoot(), options.deniedReads(), extraPermissions);
		return PolicyBoundFile.read(json, options.policyBound().toAbsolutePath());
	}
}

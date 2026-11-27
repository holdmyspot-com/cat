package com.holdmyspot.cat.claude.runner;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.ClaudeConfiguration;
import com.holdmyspot.cat.claude.optimizer.ClaudeInstalledCopies;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessMarkers;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.InstalledCopies;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.LaunchRoute;
import com.holdmyspot.cat.common.runner.MainHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.NestedSessionLaunch;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.NestedSessionStarter;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.ProcessTreeStop;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the Claude JSONL bridge.
 */
public final class ClaudeRunnerCli
{
	/**
	 * The identifier of the Codex harness.
	 */
	static final String CODEX_ID = "codex";
	/**
	 * The markers of the harnesses whose sessions run a command: Claude Code sets {@code CLAUDECODE} to {@code 1} in the
	 * environment of the commands that it runs, and Codex sets {@code CODEX_THREAD_ID} to the ID of the thread whose
	 * command it runs.
	 */
	public static final HarnessMarkers MARKERS = new HarnessMarkers(List.of(
		HarnessMarkers.Marker.equalTo(HARNESS_ID, "CLAUDECODE", "1"),
		HarnessMarkers.Marker.notBlank(CODEX_ID, "CODEX_THREAD_ID")));
	/**
	 * How often a launch inside a nested session checks the requested session's progress.
	 */
	private static final Duration REQUEST_POLL = Duration.ofMillis(100);

	/**
	 * Prints the usage, verifies an artifact, or serves JSONL turns through one persistent isolated Claude process, as
	 * the arguments select. The launcher always runs the artifact that holds it and the {@code claude} command that the
	 * {@code PATH} finds: before anything else, it refuses {@code --artifact} with exit status
	 * {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} unless it forwards the launch as a session request. It also
	 * refuses, with the same status, any launch that starts a session on a platform where Claude Code cannot sandbox
	 * the session, or whose project is the user's home folder or contains it.
	 * <p>
	 * With {@code --verify-artifact} and an artifact, it verifies the artifact and prints its folder.
	 * <p>
	 * Every other launch goes along its route, as {@link #launch(String[], Map, Waiting, Reader, OutputStream,
	 * PrintWriter)} describes, and the launcher exits with the launch's status. If the launch fails, for example
	 * because the harness refuses to start the session, the launcher prints why, without a stack trace, and exits with
	 * status {@value HarnessRunnerOptions#FAILED_LAUNCH_STATUS}.
	 * <p>
	 * With {@value ChildNeedsReport#OPTION} and a file, optionally followed by the launch's
	 * {@value AuthenticationOptions#HOME_OPTION} and {@value AuthenticationOptions#LOGIN_CLASS_OPTION}, it writes what a
	 * Claude child session with that source home needs to that file, as {@link #reportChildNeeds} reports it, for the
	 * request server that asks before it starts the child, and starts nothing. When it cannot determine them, it prints
	 * the reason and exits with status {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS}.
	 *
	 * @param args the command's options
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	public static void main(String[] args) throws IOException
	{
		args = CommandLineArguments.recover(args);
		Map<String, String> environment = Platform.current().environment(System.getenv());
		if (refusesLaunch(args, Path.of("").toAbsolutePath(), environment, Platform.current(),
			new PrintWriter(System.err, true, UTF_8)))
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
		if (args.length >= 2 && args[0].equals(ChildNeedsReport.OPTION))
		{
			if (!MARKERS.running(environment).isEmpty())
			{
				errors().println(LaunchRoute.internalOptionRefusal(args).orElseThrow());
				System.exit(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			}
			int status;
			String[] needsArgs = args;
			try (HarnessRunnerScope scope = new MainHarnessRunnerScope(environment, Pause::sleeping))
			{
				status = HarnessRunnerOptions.reportingFailures(() -> reportChildNeeds(scope, environment,
					Path.of(needsArgs[1]), AuthenticationOptions.parseAlone(Arrays.copyOfRange(needsArgs, 2, needsArgs.length)),
					PluginDirectory.of(ClaudeRunnerCli.class), errors()), errors());
			}
			if (status != 0)
				System.exit(status);
			return;
		}
		String[] launch = args;
		ProcessTreeStop.onExit();
		System.exit(HarnessRunnerOptions.reportingFailures(() -> launch(launch, environment, Pause::sleeping,
			new InputStreamReader(System.in, UTF_8), System.out, errors()), errors()));
	}

	/**
	 * Writes what a Claude child session with a launch's source home needs to a file, as
	 * {@link ClaudeRunner#childNeeds(HarnessRunnerScope, Map, ClaudeAuthentication)} reports it, or refuses when it
	 * cannot be determined or the source home would refuse the launch, as {@link ClaudeAuthentication#read} decides.
	 *
	 * @param scope          the process scope
	 * @param environment    the caller's environment
	 * @param file           the file to write
	 * @param authentication the launch's choice of source home and gateway login class
	 * @param plugin         the folder of the Claude Code plugin that holds the runner
	 * @param errors         receives the reason, as one line, when the needs cannot be determined
	 * @return {@code 0} if the needs were written, or {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if they
	 *   cannot be determined or the source home refuses the launch, in which case no file is written
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be written
	 */
	public static int reportChildNeeds(HarnessRunnerScope scope, Map<String, String> environment, Path file,
		AuthenticationOptions authentication, Path plugin, PrintWriter errors) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(scope, "scope").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(file, "file").isNotNull();
		requireThat(authentication, "authentication").isNotNull();
		requireThat(errors, "errors").isNotNull();
		ChildNeedsReport needs;
		try
		{
			needs = ClaudeRunner.childNeeds(scope, environment, ClaudeAuthentication.read(scope.json(), authentication,
				environment, Platform.current(), plugin));
		}
		catch (IOException e)
		{
			errors.println(e.getMessage());
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		}
		needs.write(scope.json(), file);
		return 0;
	}

	/**
	 * Runs one launch along the route that {@link LaunchRoute#decide(String, String, Set, String[], Map)} gives it.
	 * The launcher refuses what it must before it calls this method. The routes are:
	 * <ul>
	 * <li>Inside a nested session whose runner lets its session request sessions, the launch becomes a session request:
	 * the
	 *   nested session's own runner starts the session outside the nested session's sandbox, and this command returns its
	 *   transcript and receipt.</li>
	 *   <li>Inside a nested session whose runner lets its session request none, the launch is refused.</li>
	 *   <li>When a harness runs the command, a launch that names an option that only CAT's own starters pass is
	 *   refused.</li>
	 *   <li>When Claude Code runs the command outside a nested session, the launch becomes a request to the session's
	 *   {@link RequestServer}, which starts the session outside the session's sandbox, and this command writes its
	 *   transcript, receipt and results copy. Without a request server that serves the session, the launch is refused.
	 *   </li>
	 *   <li>When only Codex runs the command, the launch is refused: this plugin's request server serves no Codex
	 *   session, and the message names the Codex plugin's runner instead.</li>
	 *   <li>A launch that names {@value PolicyBoundFile#OPTION}, as CAT's own starters, such as a nested session's
	 *   runner, name it, serves its session in this process within that bound.</li>
	 *   <li>Otherwise no harness session runs the command, as in a terminal or a script, and the launch is refused
	 *   with a message that says to run it from a Claude Code or Codex session.</li>
	 * </ul>
	 *
	 * @param args        the launch's options
	 * @param environment the launcher's environment
	 * @param waiting     the process's waiting
	 * @param input       the launch's records when it names no {@code --input}
	 * @param output      receives the session's records when the launch names no {@code --output}
	 * @param errors      receives why the launch was refused or did not complete
	 * @return the launch's exit status: {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if it is refused
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws InvalidArgumentsException if the launch's options are invalid
	 * @throws IOException               if what the session needs cannot be determined, the launch cannot be
	 *                                   forwarded or bounded, or runner startup, the harness, or bridge I/O fails
	 */
	public static int launch(String[] args, Map<String, String> environment, Waiting waiting, Reader input,
		OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		return launch(args, environment, waiting, input, output, errors,
			() -> PluginDirectory.of(ClaudeRunnerCli.class), installedCopies(environment),
			() -> ToolScope.forSession(Path.of(""), environment, SessionHarness.installed(), waiting));
	}

	/**
	 * Runs one launch along the route that {@link LaunchRoute#decide(String, String, Set, String[], Map)} gives it,
	 * as {@link #launch(String[], Map, Waiting, Reader, OutputStream, PrintWriter)} runs it.
	 *
	 * @param args        the launch's options
	 * @param environment the launcher's environment
	 * @param waiting     the process's waiting
	 * @param input       the launch's records when it names no {@code --input}
	 * @param output      receives the session's records when the launch names no {@code --output}
	 * @param errors      receives why the launch was refused or did not complete
	 * @param plugin      returns the absolute folder of the plugin that holds the launcher
	 * @param installed   finds the folders of the installed Claude Code plugin
	 * @param scopes      opens the tool scope of the session that runs the command, which gives its CAT session
	 *                    directory
	 * @return the launch's exit status: {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if it is refused
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws InvalidArgumentsException if the launch's options are invalid
	 * @throws IOException               if what the session needs cannot be determined, the launch cannot be
	 *                                   forwarded or bounded, or runner startup, the harness, or bridge I/O fails
	 */
	public static int launch(String[] args, Map<String, String> environment, Waiting waiting, Reader input,
		OutputStream output, PrintWriter errors, Supplier<Path> plugin, InstalledCopies installed,
		RequestServer.SessionScope scopes) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(installed, "installed").isNotNull();
		requireThat(scopes, "scopes").isNotNull();
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(errors, "errors").isNotNull();
		return switch (LaunchRoute.decide(HARNESS_ID, HARNESS_ID, MARKERS.running(environment), args, environment))
		{
			case LaunchRoute.Forward(Path requests) -> SessionRequestForwarder.forward(HARNESS_ID, args, input,
				requests, waiting.every(REQUEST_POLL), output, errors);
			case LaunchRoute.Refused(String reason) ->
			{
				errors.println(reason);
				yield HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
			}
			case LaunchRoute.Served _ -> RequestServer.forward(HARNESS_ID, args, plugin.get(), installed,
				scopes, input, waiting.every(REQUEST_POLL), output, errors);
			case LaunchRoute.Bounded _ -> serveInProcess(args, plugin.get(), environment, waiting, input, output,
				errors);
		};
	}

	/**
	 * Returns what finds the installed Claude Code plugin through Claude Code's install records, in the configuration
	 * directory that Claude Code uses for the environment.
	 *
	 * @param environment the launcher's environment
	 * @return what finds the installed copies
	 * @throws NullPointerException if {@code environment} is null
	 */
	public static InstalledCopies installedCopies(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		return () -> ClaudeInstalledCopies.in(ClaudeConfiguration.resolveDirectory(
			Platform.current().homeDirectory(environment), environment.get("CLAUDE_CONFIG_DIR"))).find();
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
	 * @param plugin      the absolute folder of the plugin that holds the launcher, which the session installs as CAT
	 * @param environment the launcher's environment, which the launch keeps without the harness markers, so that
	 *                    neither the runner nor the new session takes itself for the session that ran the launcher
	 * @param waiting     the process's waiting
	 * @param input       the bridge input
	 * @param output      the bridge output
	 * @param errors      receives why the session was refused or found no capacity
	 * @return the exit status
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	private static int serveInProcess(String[] args, Path plugin, Map<String, String> environment, Waiting waiting,
		Reader input, OutputStream output, PrintWriter errors) throws IOException
	{
		args = HarnessRunnerOptions.withArtifact(args, plugin);
		environment = MARKERS.without(environment);
		Path home = Platform.current().homeDirectory(environment);
		try (JvmScope process = new MainJvmScope(environment, waiting))
		{
			serve(process, Path.of(""), args, new BufferedReader(input), new OutputStreamWriter(output, UTF_8),
				environment, maximum -> HarnessCapacity.forHome(process.fileLocks(), home, HARNESS_ID, maximum,
					process.waiting()));
			return 0;
		}
		catch (HarnessCapacityWaitExpiredException e)
		{
			errors.println(e.getMessage());
			return HarnessCapacityWaitExpiredException.EXIT_STATUS;
		}
		catch (SessionRefusedException e)
		{
			errors.println(e.getMessage());
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		}
	}

	/**
	 * Refuses a launch that names {@code --artifact}, other than inside a nested session whose runner lets its session
	 * request sessions. There every launch becomes a session request, whose runner ignores the option.
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
	 * {@link #refusesOverride} refuses it, any launch on a platform where Claude Code cannot confine the session in a
	 * sandbox, as {@link ClaudeSessionSandbox} decides, unless the launch's role runs without a sandbox, and any launch
	 * whose project is the user's home folder or
	 * contains it, as {@link HarnessRunnerOptions#refusesHomeProject} decides. Printing the usage and verifying an
	 * artifact start no session, so they are never refused for the platform or the project.
	 *
	 * @param args             the launch's options
	 * @param workingDirectory the directory from which the launcher was started
	 * @param environment      the launcher's environment
	 * @param platform         the platform that the launcher runs on
	 * @param errors           receives the reason for a refusal
	 * @return {@code true} if the launch is refused
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean refusesLaunch(String[] args, Path workingDirectory, Map<String, String> environment,
		Platform platform, PrintWriter errors)
	{
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(platform, "platform").isNotNull();
		if (refusesOverride(args, environment, errors))
			return true;
		boolean startsNothing = args.length == 1 && args[0].equals("--help") ||
			args.length == 2 && args[0].equals("--verify-artifact");
		if (startsNothing)
			return false;
		if (needsSandbox(args))
		{
			Optional<String> refusal = ClaudeSessionSandbox.refusal(platform);
			if (refusal.isPresent())
			{
				errors.println(refusal.get());
				return true;
			}
		}
		return HarnessRunnerOptions.refusesHomeProject(args, workingDirectory, environment, platform, errors);
	}

	/**
	 * Serves one Claude bridge using caller-provided streams and environment.
	 * <p>
	 * {@code --policy-bound} names the file of the policy that bounds the session's sandbox policy, which CAT's own
	 * starter writes: a nested session's runner, a request server, or the launcher started outside every harness session.
	 * Every session starts through one of them, so a launch without it is refused before it takes a capacity slot or
	 * creates a nested session. A session that the bounded session requests is bounded in turn by the bounded session's
	 * policy.
	 * <p>
	 * The session takes its credential from the launch's source home, which the runner reads first, before it reads a
	 * resumed nested session, takes a capacity slot or creates a nested session. The source files that hold a secret join
	 * the launch's denied reads, so that neither the session nor any session that it requests may read them.
	 *
	 * @param process            the process scope, whose lock coordinator and waiting the bridge uses
	 * @param workingDirectory   the directory from which the command was invoked
	 * @param args               bridge options
	 * @param input              the bridge input
	 * @param output             the bridge output
	 * @param environment        the host environment
	 * @param capacityForMaximum creates the shared harness capacity for the {@code --max-concurrency} value that the
	 *                           command selected
	 * @throws NullPointerException    if any argument is null
	 * @throws SessionRefusedException if the launch's source home would refuse the launch, as
	 *                                 {@link ClaudeAuthentication#read} decides; if the launch names no
	 *                                 {@code --policy-bound}; if the launch chose its source home itself and its bound
	 *                                 denies reading a source file, as {@link ClaudeAuthentication#requireReadable}
	 *                                 decides; or if the session's sandbox would not confine it as its policy requires
	 * @throws InvalidArgumentsException if the file of extra permissions that the launch names is missing or invalid
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
		try (ToolScope scope = ToolScope.forSession(workingDirectory, environment, SessionHarness.installed(),
			process.waiting()))
		{
			// The source home decides before anything else, so that a refused launch reads no nested session
			Path plugin = HarnessRunnerOptions.artifactOption(args);
			if (plugin == null)
				throw new InvalidArgumentsException("Missing --artifact");
			plugin = plugin.toAbsolutePath();
			AuthenticationOptions authenticationOptions = AuthenticationOptions.parse(args);
			ClaudeAuthentication authentication = ClaudeAuthentication.read(scope.json(), authenticationOptions,
				environment, Platform.current(), plugin);
			// A runner that continues a nested session's harness session takes the options that the nested session's start
			// fixed from the nested session
			args = NestedSessionLaunch.resumedArguments(scope.json(), HARNESS_ID, args);
			HarnessRunnerOptions options = authentication.withDeniedReads(options(args, HarnessTemporaryRoot.from(scope)));
			ClaudeAgentDefinition agent = loadAgent(options.catArtifact(ClaudeRunner.MANIFEST), agentName(args));
			List<Path> readOnlyEvidence = readOnlyEvidence(args);
			String instructionFilesMode = instructionFilesMode(args);
			Optional<ClaudeSessionRole> role = sessionRole(args);
			ChildPolicy bound = policyBound(scope.json(), options);
			// A nested session's runner passes on its own launch's source home, whose secret files the nested session's
			// policy, and so the bound, denies by design
			if (!authenticationOptions.inherited())
				authentication.requireReadable(bound);
			ExtraPermissions extraPermissions = HarnessRunnerOptions.readExtraPermissions(scope.json(),
				options.extraPermissions());
			FileReplacer replacer = FileReplacer.retrying(process.waiting());
			NestedSessionManager opened = NestedSessionManager.openResultsFolder(process.fileLocks(), options.resultsFolder(),
				Clock.systemUTC(), replacer, options.slotId(),
				HarnessRunnerOptions.startupPermit(args).orElse(null));
			// A nested session in a slot keeps the slot's identifier, so that its paths repeat from one session to the next
			NestedSessionManager manager = inSlot(opened, options.slotId(), args);
			try
			{
				HarnessCapacity capacity = capacityForMaximum.apply(options.maximumConcurrency());
				serve(scope, options, agent, readOnlyEvidence, instructionFilesMode, role, bound, extraPermissions, manager,
					input, output, environment, replacer, capacity, authentication);
			}
			finally
			{
				manager.closeStartupLease();
			}
		}
	}

	/**
	 * Gives a nested-session manager the identifier of the slot that the nested session is in, if it is in one, and has a
	 * nested session in a slot record the launch options that its start fixed, so that a later runner can continue its
	 * harness session.
	 *
	 * @param manager the manager of the results folder
	 * @param slotId  the slot's ID, or {@code null} if the nested session is not in a slot
	 * @param args    the launch's options
	 * @return a manager whose nested sessions all have the slot's identifier, or {@code manager} if the nested session is
	 *         not in a slot
	 */
	private static NestedSessionManager inSlot(NestedSessionManager manager, String slotId, String[] args)
	{
		if (slotId == null)
			return manager;
		return manager.withFixedSlotId(slotId).withLaunchArguments(NestedSessionLaunch.fixedArguments(args));
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
	 * @param role the session's role, or empty for Claude Code's defaults
	 * @param bound the policy that bounds the session's sandbox policy, or {@code null} if nothing bounds it
	 * @param extraPermissions the writes and hosts that the session's commands need beyond what Claude Code needs
	 * @param manager nested-session lifecycle manager
	 * @param input bridge input
	 * @param output bridge output when no durable destination was requested
	 * @param environment host environment
	 * @param replacer replaces the receipt
	 * @param capacity the shared harness capacity
	 * @param authentication the credential that the session takes from the launch's source home
	 * @throws IOException if bridge execution or retained-evidence writing fails
	 */
	private static void serve(ToolScope scope, HarnessRunnerOptions options, ClaudeAgentDefinition agent,
		List<Path> readOnlyEvidence,
		String instructionFilesMode, Optional<ClaudeSessionRole> role, ChildPolicy bound,
		ExtraPermissions extraPermissions, NestedSessionManager manager,
		Reader input, Writer output,
		Map<String, String> environment, FileReplacer replacer,
		HarnessCapacity capacity, ClaudeAuthentication authentication) throws IOException
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
			NestedSessionStarter starter = () -> new ClaudeRunner(scope.waiting()).start(cat, options.project(),
				options.workspace(), trialPlugins, manager, "claude", environment, options.model(), options.effort(),
				agent, options.installCat(), readOnlyEvidence, options.deniedReads(), instructionFilesMode,
				options.sessionTranscripts(), options.sessionRequests(), bound, policy::set,
				extraPermissions, role.orElse(null), options.resumeSlot(), authentication);
			HarnessRunnerBridge.serve(starter, manager, options, scope.json(), replacer, input, output,
				(harness, arguments, directory) -> requestedRunner(scope, options.artifact(), harness, environment).
					start(PolicyBoundFile.boundArguments(scope.json(), arguments, directory, policy.get()), directory,
						MARKERS.without(environment)));
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
			case HARNESS_ID -> new RequestedRunner(scope, artifact.toAbsolutePath(),
				artifact.toAbsolutePath().resolve("bin/claude-runner"), List.of());
			case CODEX_ID -> RequestedRunner.resolved(scope, CodexArtifact.execute(environment), List.of("serve"));
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
	 * @param temporaryRoot where the nested sessions are kept when the command names no {@code --results}
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
		// A runner that continues a nested session's harness session runs it under the model and effort of the launch that
		// resumes it
		if (!agentName.isBlank() && !List.of(args).contains(RunnerArguments.RESUME_SLOT_OPTION))
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
	 * Returns whether a launch starts a session that Claude Code must confine in a sandbox. Only a launch that names a
	 * role that runs without one does not; a launch with a missing role, or one that this launcher does not know, is
	 * checked as a sandboxed session and fails later when the launch's options are parsed.
	 *
	 * @param args the launch's options
	 * @return {@code true} if the session needs the sandbox
	 */
	private static boolean needsSandbox(String[] args)
	{
		assert args != null;
		try
		{
			return sessionRole(args).map(ClaudeSessionRole::sandboxed).orElse(true);
		}
		catch (InvalidArgumentsException _)
		{
			return true;
		}
	}

	/**
	 * Extracts the optional role of the session.
	 *
	 * @param args the command-line arguments
	 * @return the role, or empty to keep Claude Code's defaults
	 * @throws InvalidArgumentsException if {@value ClaudeSessionRole#OPTION} has no value or an unknown one
	 */
	private static Optional<ClaudeSessionRole> sessionRole(String[] args)
	{
		assert args != null;
		for (int index = 0; index < args.length; ++index)
		{
			if (!args[index].equals(ClaudeSessionRole.OPTION))
				continue;
			if (index + 1 == args.length || args[index + 1].startsWith("--"))
				throw new InvalidArgumentsException("Missing value for " + ClaudeSessionRole.OPTION);
			return Optional.of(ClaudeSessionRole.parse(args[index + 1]));
		}
		return Optional.empty();
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
				args[index].equals("--instruction-files") ||
				args[index].equals(ClaudeSessionRole.OPTION))
			{
				index += 1;
				continue;
			}
			result.add(args[index]);
		}
		return result.toArray(String[]::new);
	}

	/**
	 * Reads the policy that bounds the session's sandbox policy.
	 *
	 * @param json    the JSON mapper
	 * @param options the launch's options
	 * @return the bound
	 * @throws SessionRefusedException if the launch names no bound
	 * @throws IOException             if the bound's file cannot be read or is not a bound
	 */
	private static ChildPolicy policyBound(JsonMapper json, HarnessRunnerOptions options) throws IOException
	{
		if (options.policyBound() == null)
		{
			throw new SessionRefusedException("claude-runner starts a session only " +
				"through CAT's own starters, which name the policy that bounds the session's sandbox with " +
				PolicyBoundFile.OPTION + ", and this launch names none, so CAT does not start the session. Start the " +
				"session from a Claude Code or Codex session in which CAT is installed");
		}
		return PolicyBoundFile.read(json, options.policyBound().toAbsolutePath());
	}
}

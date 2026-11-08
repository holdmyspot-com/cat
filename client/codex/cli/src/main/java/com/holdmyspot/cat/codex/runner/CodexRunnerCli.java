package com.holdmyspot.cat.codex.runner;

import static com.holdmyspot.cat.codex.CodexPluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.CodexSessionHarness;
import com.holdmyspot.cat.codex.secrets.CodexRunnerCliSecrets;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessMarkers;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.ParentSandbox;
import com.holdmyspot.cat.common.runner.DirectLaunch;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.ProcessTreeStop;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.LaunchRoute;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
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
 * Parses Codex runner commands to serve JSON requests.
 */
public final class CodexRunnerCli
{
	static
	{
		CodexRunnerCliSecrets.setCodexRunnerCliAccess(CodexRunnerCli::toolScope);
	}

	/**
	 * The identifier of the Claude Code harness.
	 */
	static final String CLAUDE_ID = "claude";
	/**
	 * The markers of the harnesses whose sessions run a command: Codex sets its session variable,
	 * {@code CODEX_THREAD_ID}, to the ID of the thread whose command it runs, and Claude Code sets {@code CLAUDECODE} to
	 * {@code 1} in the environment of the commands that it runs.
	 */
	public static final HarnessMarkers MARKERS = new HarnessMarkers(List.of(
		HarnessMarkers.Marker.notBlank(HARNESS_ID, CodexSessionHarness.HARNESS.sessionVariable()),
		HarnessMarkers.Marker.equalTo(CLAUDE_ID, "CLAUDECODE", "1")));
	/**
	 * How often a launch inside a run checks the requested session's progress.
	 */
	private static final Duration REQUEST_POLL = Duration.ofMillis(100);
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
	 * sandbox the session, whose project is the user's home folder or contains it, or from a Codex sandbox that has
	 * network off.
	 * <p>
	 * With {@code --verify-artifact} and an artifact, it verifies the artifact and prints its folder, unless
	 * {@link #refusesArtifactRead} refuses the verification, with the same status.
	 * <p>
	 * Every other launch goes along its route, as {@link #launch(String[], Map, Waiting, Reader, OutputStream,
	 * PrintWriter)} describes, and the launcher exits with the launch's status.
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
		if (refusesLaunch(args, Path.of("").toAbsolutePath(), environment, Platform.current(),
			new PrintWriter(System.err, true, UTF_8)))
			System.exit(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
		if (args.length == 1 && args[0].equals("--help"))
		{
			System.out.println(HarnessRunnerOptions.usage("codex-runner"));
			return;
		}
		if (args.length == 2 && args[0].equals("--verify-artifact"))
		{
			if (refusesArtifactRead(args, environment, errors(), () -> PluginDirectory.of(CodexRunnerCli.class),
				CodexDirectLaunch.sessions(environment)))
				System.exit(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			System.out.println(HarnessArtifact.validate(Path.of(args[1]), Path.of(".codex-plugin/plugin.json")));
			return;
		}
		if (args.length == 3 && args[0].equals("serve") && args[1].equals(ChildNeedsReport.OPTION))
		{
			if (!MARKERS.running(environment).isEmpty())
			{
				errors().println(DirectLaunch.internalOptionRefusal(args).orElseThrow());
				System.exit(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			}
			CodexRunner.childNeeds(environment).write(JsonMapper.builder().build(), Path.of(args[2]));
			return;
		}
		String[] launch = args;
		ProcessTreeStop.onExit();
		System.exit(HarnessRunnerOptions.refusingInvalidArguments(() -> launch(launch, environment, Pause::sleeping,
			new InputStreamReader(System.in, UTF_8), System.out, errors()), errors()));
	}

	/**
	 * Runs one launch along the route that {@link LaunchRoute#decide(String, String, Set, String[], Map)} gives it.
	 * The launcher refuses what it must before it calls this method. The routes are:
	 * <ul>
	 *   <li>Inside a run whose runner lets its session request sessions, the launch becomes a session request: the
	 *   run's own runner starts the session outside the run's sandbox, and this command returns its transcript and
	 *   receipt.</li>
	 *   <li>Inside a run whose runner lets its session request none, the launch is refused.</li>
	 *   <li>When a harness runs the command, a launch that names an option that only CAT's own starters pass is
	 *   refused.</li>
	 *   <li>When Codex runs the command, which it runs outside the session's sandbox, the launch serves its session in
	 *   this process within the bound that {@link #directArguments} gives it.</li>
	 *   <li>When only Claude Code runs the command, the launch is refused: this plugin cannot read a Claude Code
	 *   session's sandbox policy, and the message names the Claude Code plugin's runner instead.</li>
	 *   <li>A launch that names {@value PolicyBoundFile#OPTION}, as CAT's own starters, such as a run's runner, name
	 *   it, serves its session in this process within that bound.</li>
	 *   <li>When no harness runs the command, no harness sandbox confines it, and the launch serves its session in
	 *   this process within the bound that {@link #inPlaceArguments} gives it: exactly what the session needs.</li>
	 * </ul>
	 *
	 * @param args        the command and its options
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
			() -> PluginDirectory.of(CodexRunnerCli.class), CodexDirectLaunch.sessions(environment));
	}

	/**
	 * Runs one launch along the route that {@link LaunchRoute#decide(String, String, Set, String[], Map)} gives it,
	 * as {@link #launch(String[], Map, Waiting, Reader, OutputStream, PrintWriter)} runs it.
	 *
	 * @param args        the command and its options
	 * @param environment the launcher's environment
	 * @param waiting     the process's waiting
	 * @param input       the launch's records when it names no {@code --input}
	 * @param output      receives the session's records when the launch names no {@code --output}
	 * @param errors      receives why the launch was refused or did not complete
	 * @param plugin      returns the absolute folder of the plugin that holds the launcher
	 * @param sessions    identifies the Codex session that runs the command
	 * @return the launch's exit status: {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if it is refused
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws InvalidArgumentsException if the launch's options are invalid
	 * @throws IOException               if what the session needs cannot be determined, the launch cannot be
	 *                                   forwarded or bounded, or runner startup, the harness, or bridge I/O fails
	 */
	public static int launch(String[] args, Map<String, String> environment, Waiting waiting, Reader input,
		OutputStream output, PrintWriter errors, Supplier<Path> plugin, DirectLaunch.SessionFinder sessions)
		throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(sessions, "sessions").isNotNull();
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(errors, "errors").isNotNull();
		String[] options = args;
		if (options.length > 0 && options[0].equals("serve"))
			options = Arrays.copyOfRange(options, 1, options.length);
		return switch (LaunchRoute.decide(HARNESS_ID, HARNESS_ID, MARKERS.running(environment), args, environment))
		{
			case LaunchRoute.Forward(Path requests) -> SessionRequestForwarder.forward(HARNESS_ID, options, input,
				requests, waiting.every(REQUEST_POLL), output, errors);
			case LaunchRoute.Refused(String reason) ->
			{
				errors.println(reason);
				yield HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
			}
			case LaunchRoute.Launcher _ ->
			{
				try (TemporaryDirectories cleanup = new TemporaryDirectories())
				{
					List<String> bounded;
					try
					{
						bounded = directArguments(options, environment, waiting, plugin.get(), sessions, cleanup);
					}
					catch (SessionRefusedException e)
					{
						errors.println(e.getMessage());
						yield HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
					}
					yield serveInProcess(bounded.toArray(String[]::new), environment, waiting, input, output, errors);
				}
			}
			case LaunchRoute.Bounded _ -> serveInProcess(args, environment, waiting, input, output, errors);
			case LaunchRoute.InPlace _ ->
			{
				try (TemporaryDirectories cleanup = new TemporaryDirectories())
				{
					List<String> bounded;
					try
					{
						bounded = inPlaceArguments(Path.of(""), args, environment, waiting, cleanup);
					}
					catch (SessionRefusedException e)
					{
						errors.println(e.getMessage());
						yield HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
					}
					yield serveInProcess(bounded.toArray(String[]::new), environment, waiting, input, output, errors);
				}
			}
		};
	}

	/**
	 * Bounds a launch that a Codex session runs, as {@link DirectLaunch#boundArguments(String, String[], Path,
	 * DirectLaunch.SessionFinder, java.util.function.Function, LaunchRoute.NeedsReport, Supplier,
	 * TemporaryDirectories)} bounds it, with what a Codex session needs, as {@link CodexRunner#childNeeds(Map)}
	 * reports it.
	 *
	 * @param args        the launch's options
	 * @param environment the launcher's environment
	 * @param waiting     how the command waits between the attempts of a retried operation
	 * @param plugin      the absolute folder of the plugin that holds the launcher
	 * @param sessions    identifies the Codex session that runs the command
	 * @param cleanup     receives the folder that holds the bound's file, which the session reads when it starts
	 * @return {@code args} followed by the options that bound the session
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws SessionRefusedException   if a check of the launch fails, such as when the new session's project is the
	 *                                   user's home folder or contains it, as
	 *                                   {@link DirectLaunch#refusingHomeProject} decides; the message says why and how
	 *                                   to fix it
	 * @throws InvalidArgumentsException if an option lacks its value, a denied read is not absolute, or the file of
	 *                                   extra permissions is invalid
	 * @throws IOException               if what the session needs cannot be determined, or the bound cannot be
	 *                                   written
	 */
	public static List<String> directArguments(String[] args, Map<String, String> environment, Waiting waiting,
		Path plugin, DirectLaunch.SessionFinder sessions, TemporaryDirectories cleanup) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(plugin, "plugin").isNotNull();
		DirectLaunch.SessionFinder outsideHome = DirectLaunch.refusingHomeProject(sessions, args,
			Path.of("").toAbsolutePath(), environment, Platform.current());
		return DirectLaunch.boundArguments(HARNESS_ID, args, plugin, outsideHome,
			project -> toolScope(project, environment, waiting), () -> CodexRunner.childNeeds(environment),
			() -> CodexDirectLaunch.sandboxed(plugin.resolve(PluginDirectory.BIN).resolve("codex-runner"),
				environment), cleanup);
	}

	/**
	 * Bounds a launch that no harness runs, as {@link LaunchRoute.InPlace#boundArguments} bounds it, with what a
	 * Codex session needs, as {@link CodexRunner#childNeeds(Map)} reports it.
	 *
	 * @param workingDirectory the directory from which the command was invoked
	 * @param args             the command and its options
	 * @param environment      the launcher's environment
	 * @param waiting          how the command waits between the attempts of a retried operation
	 * @param cleanup          receives the folder that holds the bound's file, which the session reads when it
	 *                         starts
	 * @return {@code args} followed by {@value PolicyBoundFile#OPTION} and the bound's file
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws InvalidArgumentsException if an option that bounds the session lacks its value, a denied read is not
	 *                                   absolute, the file of extra permissions is missing or invalid, or the launch
	 *                                   names no {@code --run-root} outside a harness session
	 * @throws IOException               if what the session needs cannot be determined, or the bound cannot be
	 *                                   written
	 */
	public static List<String> inPlaceArguments(Path workingDirectory, String[] args, Map<String, String> environment,
		Waiting waiting, TemporaryDirectories cleanup) throws IOException
	{
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		try (ToolScope tools = toolScope(workingDirectory, environment, waiting))
		{
			return LaunchRoute.InPlace.boundArguments(HARNESS_ID, args, HarnessTemporaryRoot.from(tools),
				tools.json(), () -> CodexRunner.childNeeds(environment), cleanup);
		}
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
	 * @param environment the launcher's environment, which the launch keeps without the harness markers, so that
	 *                    neither the runner nor the new session takes itself for the session that ran the launcher
	 * @param waiting     the process's waiting
	 * @param input       the bridge input
	 * @param output      the bridge output
	 * @param errors      receives why the session was refused or found no capacity
	 * @return the exit status
	 * @throws IOException if runner startup, the harness, or bridge I/O fails
	 */
	private static int serveInProcess(String[] args, Map<String, String> environment, Waiting waiting, Reader input,
		OutputStream output, PrintWriter errors) throws IOException
	{
		args = HarnessRunnerOptions.withArtifact(args, PluginDirectory.of(CodexRunnerCli.class));
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
	 * Refuses verifying an artifact when Codex runs the command outside a run, which it runs outside the
	 * session's sandbox, and the session's sandbox does not let its commands read the artifact, as
	 * {@link DirectLaunch#requireReadable} decides. Any other command runs inside a sandbox that confines what it
	 * reads, such as a run's, or outside every harness session.
	 *
	 * @param args        the command: {@code --verify-artifact} and the artifact
	 * @param environment the launcher's environment
	 * @param errors      receives the reason for a refusal
	 * @param plugin      returns the absolute folder of the plugin that holds the launcher
	 * @param sessions    identifies the Codex session that runs the command
	 * @return {@code true} if the verification is refused, after the reason is written to {@code errors}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code args} is not {@code --verify-artifact} and an artifact
	 * @throws IOException              if a source that identifies the session, or a symbolic link in the artifact's
	 *                                  path, cannot be read
	 */
	public static boolean refusesArtifactRead(String[] args, Map<String, String> environment, PrintWriter errors,
		Supplier<Path> plugin, DirectLaunch.SessionFinder sessions) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (args.length != 2 || !args[0].equals("--verify-artifact"))
		{
			throw new IllegalArgumentException("args must be --verify-artifact and an artifact: " +
				Arrays.toString(args));
		}
		requireThat(environment, "environment").isNotNull();
		requireThat(errors, "errors").isNotNull();
		requireThat(plugin, "plugin").isNotNull();
		requireThat(sessions, "sessions").isNotNull();
		if (!(LaunchRoute.decide(HARNESS_ID, HARNESS_ID, MARKERS.running(environment), args, environment) instanceof
			LaunchRoute.Launcher))
			return false;
		Path pluginFolder = plugin.get();
		try
		{
			DirectLaunch.requireReadable(HARNESS_ID, args[0], Path.of(args[1]), pluginFolder, sessions,
				() -> CodexDirectLaunch.sandboxed(pluginFolder.resolve(PluginDirectory.BIN).resolve("codex-runner"),
					environment));
			return false;
		}
		catch (SessionRefusedException e)
		{
			errors.println(e.getMessage());
			return true;
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
	 * confine the session in a sandbox, as {@link CodexSessionSandbox} decides, any launch whose project is the user's
	 * home folder or contains it, as {@link HarnessRunnerOptions#refusesHomeProject} decides, and any launch from a
	 * Codex sandbox that has network off, whose session could not reach its model. A launch that Codex runs is not
	 * refused for its project here: its project can come from the session's transcript, so {@link #directArguments}
	 * refuses it. Printing the usage and verifying an artifact start no session, so they are never refused for the
	 * platform, the project or the sandbox.
	 *
	 * @param args             the command and its options
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
		requireThat(args, "args").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
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
		// A launch that Codex runs takes its project from the session's transcript, which the launch checks
		boolean projectFromTranscript = LaunchRoute.decide(HARNESS_ID, HARNESS_ID, MARKERS.running(environment), args,
			environment) instanceof LaunchRoute.Launcher;
		return !projectFromTranscript &&
			HarnessRunnerOptions.refusesHomeProject(args, workingDirectory, environment, platform, errors) ||
			ParentSandbox.refusesNetworkOff(HARNESS_ID, environment, errors);
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
		try (ToolScope scope = toolScope(workingDirectory, environment, process.waiting()))
		{
			String agentName = agentName(args);
			int toolOutputTokenLimit = toolOutputTokenLimit(args);
			HarnessRunnerOptions options = options(args, HarnessTemporaryRoot.from(scope));
			Path cat = options.catArtifact(CodexRunner.MANIFEST);
			CodexAgentDefinition agent = loadAgent(cat, agentName);
			ParentPolicy bound = policyBound(scope.json(), options);
			ExtraPermissions extraPermissions = HarnessRunnerOptions.readExtraPermissions(scope.json(),
				options.extraPermissions());
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
					options.gitName(), options.gitEmail(), extraPermissions),
					manager, options, scope.json(), replacer, input, output, (harness, arguments, directory) ->
					requestedRunner(scope, options.artifact(), harness, environment).start(PolicyBoundFile.
						boundArguments(scope.json(), arguments, directory, policy.get()), directory,
						MARKERS.without(environment)));
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
			case CLAUDE_ID -> RequestedRunner.resolved(scope, ClaudeArtifact.execute(environment), List.of());
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

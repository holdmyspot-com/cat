package com.holdmyspot.cat.claude.runner;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.HarnessRunStarter;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.DirectLaunch;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.ProcessTreeStop;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.runner.HarnessMarkers;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.LaunchRoute;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.MainHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;

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
	 * How often a launch inside a run checks the requested session's progress.
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
	 * With {@code --verify-artifact} and an artifact, it verifies the artifact and prints its folder, unless
	 * {@link #refusesArtifactRead} refuses the verification, with the same status.
	 * <p>
	 * Every other launch goes along its route, as {@link #launch(String[], Map, Waiting, Reader, OutputStream,
	 * PrintWriter)} describes, and the launcher exits with the launch's status.
	 * <p>
	 * With {@value ChildNeedsReport#OPTION} and a file, it writes what a Claude child session needs to that file, as
	 * {@link ClaudeRunner#childNeeds(HarnessRunnerScope, Map)} reports it, for the session launcher that asks before
	 * it starts the child, and starts nothing. When it cannot determine them, it prints the reason and exits with status
	 * {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS}.
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
			if (refusesArtifactRead(args, environment, errors(), () -> PluginDirectory.of(ClaudeRunnerCli.class),
				ClaudeDirectLaunch.sessions(environment)))
				System.exit(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			System.out.println(HarnessArtifact.validate(Path.of(args[1]), Path.of(".claude-plugin/plugin.json")));
			return;
		}
		if (args.length == 2 && args[0].equals(ChildNeedsReport.OPTION))
		{
			if (!MARKERS.running(environment).isEmpty())
			{
				errors().println(DirectLaunch.internalOptionRefusal(args).orElseThrow());
				System.exit(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			}
			int status;
			try (HarnessRunnerScope scope = new MainHarnessRunnerScope(environment, Pause::sleeping))
			{
				status = reportChildNeeds(scope, environment, Path.of(args[1]), errors());
			}
			if (status != 0)
				System.exit(status);
			return;
		}
		String[] launch = args;
		ProcessTreeStop.onExit();
		System.exit(HarnessRunnerOptions.refusingInvalidArguments(() -> launch(launch, environment, Pause::sleeping,
			new InputStreamReader(System.in, UTF_8), System.out, errors()), errors()));
	}

	/**
	 * Writes what a Claude child session needs to a file, as
	 * {@link ClaudeRunner#childNeeds(HarnessRunnerScope, Map)} reports it, or refuses when it cannot be determined.
	 *
	 * @param scope       the process scope
	 * @param environment the caller's environment
	 * @param file        the file to write
	 * @param errors      receives the reason, as one line, when the needs cannot be determined
	 * @return {@code 0} if the needs were written, or {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if they
	 *   cannot be determined, in which case no file is written
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be written
	 */
	public static int reportChildNeeds(HarnessRunnerScope scope, Map<String, String> environment, Path file,
		PrintWriter errors) throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(file, "file").isNotNull();
		requireThat(errors, "errors").isNotNull();
		ChildNeedsReport needs;
		try
		{
			needs = ClaudeRunner.childNeeds(scope, environment);
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
	 *   <li>Inside a run whose runner lets its session request sessions, the launch becomes a session request: the
	 *   run's own runner starts the session outside the run's sandbox, and this command returns its transcript and
	 *   receipt.</li>
	 *   <li>Inside a run whose runner lets its session request none, the launch is refused.</li>
	 *   <li>When a harness runs the command, a launch that names an option that only CAT's own starters pass is
	 *   refused.</li>
	 *   <li>When Claude Code runs the command, which it runs outside the session's sandbox, the launch serves its
	 *   session in this process within the bound that {@link #directArguments} gives it.</li>
	 *   <li>When only Codex runs the command, the launch is refused: this plugin cannot read a Codex session's sandbox
	 *   policy, and the message names the Codex plugin's runner instead.</li>
	 *   <li>A launch that names {@value PolicyBoundFile#OPTION}, as CAT's own starters, such as a run's runner, name
	 *   it, serves its session in this process within that bound.</li>
	 *   <li>When no harness runs the command, no harness sandbox confines it, and the launch serves its session in
	 *   this process within the bound that {@link #inPlaceArguments} gives it: exactly what the session needs.</li>
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
			() -> PluginDirectory.of(ClaudeRunnerCli.class), ClaudeDirectLaunch.sessions(environment));
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
	 * @param sessions    identifies the Claude Code session that runs the command
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
		return switch (LaunchRoute.decide(HARNESS_ID, HARNESS_ID, MARKERS.running(environment), args, environment))
		{
			case LaunchRoute.Forward(Path requests) -> SessionRequestForwarder.forward(HARNESS_ID, args, input,
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
					try (HarnessRunnerScope scope = new MainHarnessRunnerScope(environment, waiting))
					{
						bounded = directArguments(scope, args, environment, plugin.get(), sessions, cleanup);
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
					try (HarnessRunnerScope scope = new MainHarnessRunnerScope(environment, waiting))
					{
						bounded = inPlaceArguments(scope, Path.of(""), args, environment, cleanup);
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
	 * Bounds a launch that a Claude Code session runs, as {@link DirectLaunch#boundArguments(String, String[], Path,
	 * DirectLaunch.SessionFinder, java.util.function.Function, LaunchRoute.NeedsReport, Supplier,
	 * TemporaryDirectories)} bounds it, with what a Claude session needs, as
	 * {@link ClaudeRunner#childNeeds(HarnessRunnerScope, Map)} reports it.
	 *
	 * @param scope       the process scope
	 * @param args        the launch's options
	 * @param environment the launcher's environment
	 * @param plugin      the absolute folder of the plugin that holds the launcher
	 * @param sessions    identifies the Claude Code session that runs the command
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
	public static List<String> directArguments(HarnessRunnerScope scope, String[] args,
		Map<String, String> environment, Path plugin, DirectLaunch.SessionFinder sessions,
		TemporaryDirectories cleanup) throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(plugin, "plugin").isNotNull();
		DirectLaunch.SessionFinder outsideHome = DirectLaunch.refusingHomeProject(sessions, args,
			Path.of("").toAbsolutePath(), environment, Platform.current());
		return DirectLaunch.boundArguments(HARNESS_ID, args, plugin, outsideHome, project -> ToolScope.forSession(
				project, environment, SessionHarness.installed(), scope.waiting()), () -> ClaudeRunner.childNeeds(scope,
				environment), () -> ClaudeDirectLaunch.sandboxed(plugin.resolve(PluginDirectory.BIN).
				resolve("claude-runner")), cleanup);
	}

	/**
	 * Bounds a launch that no harness runs, as {@link LaunchRoute.InPlace#boundArguments} bounds it, with what a
	 * Claude session needs, as {@link ClaudeRunner#childNeeds(HarnessRunnerScope, Map)} reports it.
	 *
	 * @param scope            the process scope
	 * @param workingDirectory the directory from which the command was invoked
	 * @param args             the launch's options
	 * @param environment      the launcher's environment
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
	public static List<String> inPlaceArguments(HarnessRunnerScope scope, Path workingDirectory, String[] args,
		Map<String, String> environment, TemporaryDirectories cleanup) throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(environment, "environment").isNotNull();
		try (ToolScope tools = ToolScope.forSession(workingDirectory, environment, SessionHarness.installed(),
			scope.waiting()))
		{
			return LaunchRoute.InPlace.boundArguments(HARNESS_ID, args, HarnessTemporaryRoot.from(tools),
				scope.json(), () -> ClaudeRunner.childNeeds(scope, environment), cleanup);
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
	 * @param args        the command's options
	 * @param environment the launcher's environment
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
		args = HarnessRunnerOptions.withArtifact(args, PluginDirectory.of(ClaudeRunnerCli.class));
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
	 * Refuses verifying an artifact when Claude Code runs the command outside a run, which it runs outside the
	 * session's sandbox, and the session's sandbox does not let its commands read the artifact, as
	 * {@link DirectLaunch#requireReadable} decides. Any other command runs inside a sandbox that confines what it
	 * reads, such as a run's, or outside every harness session.
	 *
	 * @param args        the command: {@code --verify-artifact} and the artifact
	 * @param environment the launcher's environment
	 * @param errors      receives the reason for a refusal
	 * @param plugin      returns the absolute folder of the plugin that holds the launcher
	 * @param sessions    identifies the Claude Code session that runs the command
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
				() -> ClaudeDirectLaunch.sandboxed(pluginFolder.resolve(PluginDirectory.BIN).resolve("claude-runner")));
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
	 * sandbox, as {@link ClaudeSessionSandbox} decides, and any launch whose project is the user's home folder or
	 * contains it, as {@link HarnessRunnerOptions#refusesHomeProject} decides. A launch that Claude Code runs is not
	 * refused for its project here: its project can come from the session's transcript, so {@link #directArguments}
	 * refuses it. Printing the usage and verifying an artifact start no session, so they are never refused for the
	 * platform or the project.
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
		Optional<String> refusal = ClaudeSessionSandbox.refusal(platform);
		if (refusal.isPresent())
		{
			errors.println(refusal.get());
			return true;
		}
		// A launch that Claude Code runs takes its project from the session's transcript, which the launch checks
		if (LaunchRoute.decide(HARNESS_ID, HARNESS_ID, MARKERS.running(environment), args, environment) instanceof
			LaunchRoute.Launcher)
			return false;
		return HarnessRunnerOptions.refusesHomeProject(args, workingDirectory, environment, platform, errors);
	}

	/**
	 * Serves one Claude bridge using caller-provided streams and environment.
	 * <p>
	 * {@code --policy-bound} names the file of the policy that bounds the session's sandbox policy, which the session
	 * launcher writes. Every session starts through the session launcher, so a launch without it is refused before
	 * it takes a capacity slot or creates a run. A session that the bounded session requests is bounded in turn by the
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
		try (ToolScope scope = ToolScope.forSession(workingDirectory, environment, SessionHarness.installed(),
			process.waiting()))
		{
			HarnessRunnerOptions options = options(args, HarnessTemporaryRoot.from(scope));
			ClaudeAgentDefinition agent = loadAgent(options.catArtifact(ClaudeRunner.MANIFEST), agentName(args));
			List<Path> readOnlyEvidence = readOnlyEvidence(args);
			String instructionFilesMode = instructionFilesMode(args);
			ChildPolicy bound = policyBound(scope.json(), options);
			ExtraPermissions extraPermissions = HarnessRunnerOptions.readExtraPermissions(scope.json(),
				options.extraPermissions());
			FileReplacer replacer = FileReplacer.retrying(process.waiting());
			HarnessRunManager manager = HarnessRunManager.openRunRoot(process.fileLocks(), options.runRoot(),
				Clock.systemUTC(), replacer);
			HarnessCapacity capacity = null;
			if (!options.capacityHeld())
				capacity = capacityForMaximum.apply(options.maximumConcurrency());
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
	 * @param capacity the shared harness capacity, or {@code null} if the requester holds the session's slot
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
				options.sessionTranscripts(), options.sessionRequests(), bound, policy::set, options.gitName(),
				options.gitEmail(), extraPermissions);
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
			case HARNESS_ID -> new RequestedRunner(scope, artifact.toAbsolutePath().resolve("bin/claude-runner"),
				List.of());
			case CODEX_ID -> RequestedRunner.resolved(scope, CodexArtifact.execute(environment), List.of("serve"));
			default -> throw new IllegalArgumentException("Unsupported requested harness: " + harness);
		};
	}

	/**
	 * Takes a capacity permit, waiting no longer than the command's {@code --capacity-wait} if it named one.
	 *
	 * @param capacity the shared harness capacity, or {@code null} if the requester holds the session's slot
	 * @param options the parsed bridge options
	 * @return the permit, or {@code null} if {@code capacity} is null
	 * @throws HarnessCapacityWaitExpiredException if the wait passed without a free permit
	 * @throws IOException if a slot cannot be locked or the wait fails
	 */
	private static HarnessCapacity.Permit acquire(HarnessCapacity capacity, HarnessRunnerOptions options)
		throws IOException
	{
		if (capacity == null)
			return null;
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
				"through CAT's session launcher, which names the policy that bounds the session's sandbox with " +
				PolicyBoundFile.OPTION + ", and this launch names none, so CAT does not start the session. Start the " +
				"session from a Claude Code or Codex session in which CAT is installed");
		}
		return PolicyBoundFile.read(json, options.policyBound().toAbsolutePath());
	}
}

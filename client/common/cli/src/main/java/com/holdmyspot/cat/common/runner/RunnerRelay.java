package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.launcher.client.SessionLauncherClient;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs the other harness's runner from a plugin, so that both plugins hold a {@code claude-runner} and a
 * {@code codex-runner} launcher with the same options.
 * <p>
 * The launcher sends each launch along its {@link LaunchRoute}, as the harness's own runner launcher does. Inside a
 * run whose runner lets its session request sessions, it requests a session of its harness from the run's runner.
 * From a command of the plugin's own harness, it asks the session launcher of the harness process that runs the
 * command to start the session, except on native Windows, which has no session launcher. A launch that a session
 * launcher started, a launch from a command that no harness runs, and on native Windows a launch from a command of the
 * plugin's own harness run the runner of the other harness's CAT artifact that the plugin selects instead, which runs
 * that artifact. The launcher refuses a launch from a run that lets its session request no sessions, a launch from
 * a command that only the other harness runs, and any launch from a Codex sandbox that has network off.
 */
public final class RunnerRelay
{
	/**
	 * The variable that names the run's requests directory inside a run that may request sessions.
	 */
	public static final String REQUESTS_VARIABLE = SessionRequestAccess.REQUESTS_VARIABLE;
	/**
	 * How often a launch inside a run checks the requested session's progress.
	 */
	private static final Duration REQUEST_POLL = Duration.ofMillis(100);

	/**
	 * Selects the other harness's CAT artifact.
	 */
	@FunctionalInterface
	public interface ArtifactSelector
	{
		/**
		 * Selects the artifact.
		 *
		 * @return the {@code artifact=} and {@code runner=} lines that name the artifact and its runner launcher
		 * @throws IOException if no artifact of this CAT version can be selected
		 */
		String select() throws IOException;
	}

	/**
	 * Runs a runner command with the caller's standard streams.
	 */
	@FunctionalInterface
	public interface RunnerStarter
	{
		/**
		 * Runs the command and waits for it to end.
		 *
		 * @param command     the runner launcher and its options
		 * @param environment the runner's environment
		 * @return the runner's exit status
		 * @throws IOException if the runner cannot be started or waited for
		 */
		int run(List<String> command, Map<String, String> environment) throws IOException;
	}

	/**
	 * Sends a launch to the session launcher.
	 */
	@FunctionalInterface
	public interface SessionLaunch
	{
		/**
		 * Sends the launch and waits for it to end.
		 *
		 * @param harness the harness whose runner the launcher stands for
		 * @param args    the launch's options
		 * @param input   the launch's records when it names no {@code --input}
		 * @param output  receives the child's records when the launch names no {@code --output}
		 * @param errors  receives why the launch did not complete
		 * @return the launch's exit status
		 * @throws IOException if the launch cannot be sent or followed
		 */
		int launch(String harness, String[] args, Reader input, OutputStream output, PrintWriter errors)
			throws IOException;
	}

	/**
	 * Runs one launch on the current platform, sending a launch that goes to a session launcher to the session
	 * launcher of the process.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param plugin      the harness whose CAT plugin holds the launcher
	 * @param harnesses   the harnesses whose sessions the launcher's environment says run the command, as the plugin
	 *                    reads their markers
	 * @param args        the runner options
	 * @param environment the launcher's environment
	 * @param selector    selects the harness's CAT artifact for a launch that its runner serves
	 * @param starter     runs the artifact's runner for a launch that it serves
	 * @param input       the launch's records when {@code --input} is absent
	 * @param waiting     the process's waiting, which paces checks for the requested session's progress, inside a
	 *                    run or from the session launcher
	 * @param output      receives the session's transcript when {@code --output} is absent
	 * @param errors      receives the reason when the launcher refuses the launch or a requested session does not
	 *                    complete
	 * @return the exit status, as {@link #relay(String, String, Set, String[], Map, Platform, SessionLaunch,
	 *   ArtifactSelector, RunnerStarter, Reader, Pause, OutputStream, PrintWriter)} returns it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the options are invalid
	 * @throws IOException              if no artifact can be selected, the runner cannot be run, or the launch cannot
	 *                                  be forwarded or sent
	 */
	public static int relay(String harness, String plugin, Set<String> harnesses, String[] args,
		Map<String, String> environment, ArtifactSelector selector, RunnerStarter starter, Reader input,
		Waiting waiting, OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		return relay(harness, plugin, harnesses, args, environment, Platform.current(), (launchHarness, launchArgs,
				launchInput, launchOutput, launchErrors) -> SessionLauncherClient.launchFromMain(launchHarness,
				launchArgs, environment, waiting, launchInput, launchOutput, launchErrors), selector, starter, input,
			waiting.every(REQUEST_POLL), output, errors);
	}

	/**
	 * Runs one launch along the route that {@link LaunchRoute#decide(String, String, Set, String[], Map)} gives it. A
	 * launch that goes to a session launcher goes to {@code launcher}, except on native Windows, which has no session
	 * launcher. There, and for a launch that names its policy bound or that no harness runs, the launcher runs the
	 * selected artifact's runner with the caller's options, and that runner serves the launch along the same route.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param plugin      the harness whose CAT plugin holds the launcher
	 * @param harnesses   the harnesses whose sessions the launcher's environment says run the command, as the plugin
	 *                    reads their markers
	 * @param args        the runner options
	 * @param environment the launcher's environment
	 * @param platform    the platform that the launcher runs on
	 * @param launcher    sends a launch to the session launcher
	 * @param selector    selects the harness's CAT artifact for a launch that its runner serves
	 * @param starter     runs the artifact's runner for a launch that it serves
	 * @param input       the launch's records when {@code --input} is absent
	 * @param pause       paces checks for the requested session's progress inside a run
	 * @param output      receives the session's transcript inside a run when {@code --output} is absent
	 * @param errors      receives the reason when the launcher refuses the launch or a requested session does not
	 *                    complete
	 * @return the runner's, the session launcher's or the requested session's exit status, or
	 *   {@link HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if the launch is made from a Codex sandbox that has network
	 *   off, names {@code --artifact} outside a run whose runner lets its session request sessions, or takes a route
	 *   that refuses it. The launcher refuses before it selects an artifact or sends a request
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the options are invalid
	 * @throws IOException              if no artifact can be selected, the runner cannot be run, or the launch cannot
	 *                                  be forwarded or sent
	 */
	public static int relay(String harness, String plugin, Set<String> harnesses, String[] args,
		Map<String, String> environment, Platform platform, SessionLaunch launcher, ArtifactSelector selector,
		RunnerStarter starter, Reader input, Pause pause, OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		requireThat(selector, "selector").isNotNull();
		requireThat(starter, "starter").isNotNull();
		requireThat(pause, "pause").isNotNull();
		if (ParentSandbox.refusesNetworkOff(harness, environment, errors))
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		LaunchRoute route = LaunchRoute.decide(harness, plugin, harnesses, args, environment);
		if (!(route instanceof LaunchRoute.Forward) && HarnessRunnerOptions.refusesOverride(harness, args, errors))
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		return switch (route)
		{
			case LaunchRoute.Forward(Path requests) ->
				SessionRequestForwarder.forward(harness, args, input, requests, pause, output, errors);
			case LaunchRoute.Refused(String reason) ->
			{
				errors.println(reason);
				yield HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
			}
			case LaunchRoute.Launcher _ when !platform.isWindows() ->
				launcher.launch(harness, args, input, output, errors);
			case LaunchRoute.Launcher _, LaunchRoute.Bounded _, LaunchRoute.InPlace _ ->
			{
				// The selected artifact's runner runs the artifact that holds it
				List<Path> locations = RequestedRunner.locations(selector.select());
				List<String> command = new ArrayList<>(List.of(locations.get(1).toString()));
				command.addAll(Arrays.asList(args));
				yield starter.run(command, environment);
			}
		};
	}

	/**
	 * Prevents construction.
	 */
	private RunnerRelay()
	{
	}
}

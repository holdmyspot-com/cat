package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
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
 * From a command of the plugin's own harness, which runs the launcher outside the session's sandbox, it bounds the
 * launch by that session's policy and has the runner of the other harness's CAT artifact that the plugin selects
 * serve the session within that bound. A launch from a command that no harness runs runs that runner with the
 * caller's options. The launcher refuses a launch from a run that lets its session request no sessions, a launch
 * from a command that only the other harness runs, and any launch from a Codex sandbox that has network off.
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
	 * Bounds a launch that a session of the plugin's own harness runs, as {@link DirectLaunch} bounds it.
	 */
	@FunctionalInterface
	public interface LaunchBound
	{
		/**
		 * Bounds the launch by the policy of the session that runs it and what a session of the relayed harness
		 * needs, as its runner reports it.
		 *
		 * @param runner  the runner launcher of the relayed harness's CAT artifact
		 * @param args    the launch's options
		 * @param cleanup receives the folders that the bound's files are in, which the new session reads when it
		 *                starts
		 * @return {@code args} with the options that bound the new session
		 * @throws SessionRefusedException if the launch is refused; the message says why and how to fix it
		 * @throws IOException             if what the new session needs cannot be determined, or the bound cannot
		 *                                 be written
		 */
		List<String> bound(Path runner, String[] args, TemporaryDirectories cleanup) throws IOException;
	}

	/**
	 * Runs one launch on the current platform.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param plugin      the harness whose CAT plugin holds the launcher
	 * @param markers     the markers by which the plugin reads which harnesses' sessions run the command
	 * @param args        the runner options
	 * @param environment the launcher's environment
	 * @param selector    selects the harness's CAT artifact
	 * @param starter     runs the artifact's runner
	 * @param bound       bounds a launch that a session of the plugin's own harness runs
	 * @param input       the launch's records when {@code --input} is absent
	 * @param waiting     the process's waiting, which paces checks for the requested session's progress inside a run
	 * @param output      receives the session's transcript when {@code --output} is absent
	 * @param errors      receives the reason when the launcher refuses the launch or a requested session does not
	 *                    complete
	 * @return the exit status, as {@link #relay(String, String, HarnessMarkers, String[], Map, Platform,
	 *   ArtifactSelector, RunnerStarter, LaunchBound, Reader, Pause, OutputStream, PrintWriter)} returns it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the options are invalid
	 * @throws IOException              if no artifact can be selected, the runner cannot be run, or the launch cannot
	 *                                  be forwarded or bounded
	 */
	public static int relay(String harness, String plugin, HarnessMarkers markers, String[] args,
		Map<String, String> environment, ArtifactSelector selector, RunnerStarter starter, LaunchBound bound,
		Reader input, Waiting waiting, OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(waiting, "waiting").isNotNull();
		return relay(harness, plugin, markers, args, environment, Platform.current(), selector, starter, bound, input,
			waiting.every(REQUEST_POLL), output, errors);
	}

	/**
	 * Runs one launch along the route that {@link LaunchRoute#decide(String, String, Set, String[], Map)} gives it.
	 * When a session of the plugin's own harness runs the command, other than on native Windows, {@code bound} bounds
	 * the launch by that session's policy, and the selected artifact's runner runs the bounded launch with an
	 * environment that names no harness, so that it serves the session within that bound. Otherwise, the selected
	 * artifact's runner runs with the caller's options and serves the launch along the same route; on native Windows,
	 * it refuses every launch from a harness session.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param plugin      the harness whose CAT plugin holds the launcher
	 * @param markers     the markers by which the plugin reads which harnesses' sessions run the command
	 * @param args        the runner options
	 * @param environment the launcher's environment
	 * @param platform    the platform that the launcher runs on
	 * @param selector    selects the harness's CAT artifact
	 * @param starter     runs the artifact's runner
	 * @param bound       bounds a launch that a session of the plugin's own harness runs
	 * @param input       the launch's records when {@code --input} is absent
	 * @param pause       paces checks for the requested session's progress inside a run
	 * @param output      receives the session's transcript inside a run when {@code --output} is absent
	 * @param errors      receives the reason when the launcher refuses the launch or a requested session does not
	 *                    complete
	 * @return the runner's or the requested session's exit status, or
	 *   {@link HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if the launch is made from a Codex sandbox that has network
	 *   off, names {@code --artifact} outside a run whose runner lets its session request sessions, takes a route that
	 *   refuses it, or fails a check of its bound. The launcher refuses before it starts a runner or sends a request
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the options are invalid
	 * @throws IOException              if no artifact can be selected, the runner cannot be run, or the launch cannot
	 *                                  be forwarded or bounded
	 */
	public static int relay(String harness, String plugin, HarnessMarkers markers, String[] args,
		Map<String, String> environment, Platform platform, ArtifactSelector selector, RunnerStarter starter,
		LaunchBound bound, Reader input, Pause pause, OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(markers, "markers").isNotNull();
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(selector, "selector").isNotNull();
		requireThat(starter, "starter").isNotNull();
		requireThat(bound, "bound").isNotNull();
		requireThat(pause, "pause").isNotNull();
		if (ParentSandbox.refusesNetworkOff(harness, environment, errors))
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		LaunchRoute route = LaunchRoute.decide(harness, plugin, markers.running(environment), args, environment);
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
			{
				Path runner = RequestedRunner.locations(selector.select()).get(1);
				try (TemporaryDirectories cleanup = new TemporaryDirectories())
				{
					List<String> bounded;
					try
					{
						bounded = bound.bound(runner, args, cleanup);
					}
					catch (SessionRefusedException e)
					{
						errors.println(e.getMessage());
						yield HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
					}
					List<String> command = new ArrayList<>(List.of(runner.toString()));
					command.addAll(bounded);
					yield starter.run(command, markers.without(environment));
				}
			}
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

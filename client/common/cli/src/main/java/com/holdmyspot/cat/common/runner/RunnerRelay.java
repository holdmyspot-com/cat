package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.launcher.client.SessionLauncherClient;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs the other harness's runner from a plugin, so that both plugins hold a {@code claude-runner} and a
 * {@code codex-runner} launcher with the same options.
 * <p>
 * Outside a run, the launcher asks the session launcher of the harness process that runs it to start a session of its
 * harness, as the harness's own runner launcher does. A launch that a session launcher started, and every launch on
 * native Windows, runs the runner of the other harness's CAT artifact that the plugin selects instead, which runs that
 * artifact. Inside a run whose runner lets its session request sessions, the launcher requests a session of its
 * harness from the run's runner. Either way, the launcher starts nothing from a Codex sandbox that has network off.
 */
public final class RunnerRelay
{
	/**
	 * The variable that names the run's requests directory inside a run that may request sessions.
	 */
	public static final String REQUESTS_VARIABLE = SessionRequestAccess.REQUESTS_VARIABLE;

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
	 * Runs one launch on the current platform, sending a launch outside a run to the session launcher of the process.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param args        the runner options
	 * @param environment the launcher's environment
	 * @param selector    selects the harness's CAT artifact for a launch that runs in place
	 * @param starter     runs the artifact's runner for a launch that runs in place
	 * @param input       the launch's records when {@code --input} is absent
	 * @param pause       paces checks for the requested session's progress, inside a run or from the session
	 *                    launcher
	 * @param output      receives the session's transcript when {@code --output} is absent
	 * @param errors      receives the reason when the launcher refuses the launch or a requested session does not
	 *                    complete
	 * @return the exit status, as {@link #relay(String, String[], Map, Platform, SessionLaunch, ArtifactSelector,
	 *   RunnerStarter, Reader, Pause, OutputStream, PrintWriter)} returns it
	 * @throws IllegalArgumentException if the options are invalid
	 * @throws IOException              if no artifact can be selected, the runner cannot be run, or the launch cannot
	 *                                  be forwarded or sent
	 */
	public static int relay(String harness, String[] args, Map<String, String> environment, ArtifactSelector selector,
		RunnerStarter starter, Reader input, Pause pause, OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		return relay(harness, args, environment, Platform.current(), (launchHarness, launchArgs, launchInput,
				launchOutput, launchErrors) -> SessionLauncherClient.launchFromMain(launchHarness, launchArgs,
				environment, _ -> pause, launchInput, launchOutput, launchErrors), selector, starter, input, pause,
			output, errors);
	}

	/**
	 * Runs one launch.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param args        the runner options
	 * @param environment the launcher's environment
	 * @param platform    the platform that the launcher runs on
	 * @param launcher    sends a launch outside a run to the session launcher, unless the launch runs in place
	 * @param selector    selects the harness's CAT artifact for a launch that runs in place
	 * @param starter     runs the artifact's runner for a launch that runs in place
	 * @param input       the launch's records when {@code --input} is absent
	 * @param pause       paces checks for the requested session's progress inside a run
	 * @param output      receives the session's transcript inside a run when {@code --output} is absent
	 * @param errors      receives the reason when the launcher refuses the launch or a requested session does not
	 *                    complete
	 * @return the runner's or the session launcher's exit status, or
	 *   {@link HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if the launch is made from a Codex sandbox that has network
	 *   off, or if the launch outside a run names {@code --artifact}. The launcher refuses before it selects an
	 *   artifact or sends a request
	 * @throws IllegalArgumentException if the options are invalid
	 * @throws IOException              if no artifact can be selected, the runner cannot be run, or the launch cannot
	 *                                  be forwarded or sent
	 */
	public static int relay(String harness, String[] args, Map<String, String> environment, Platform platform,
		SessionLaunch launcher, ArtifactSelector selector, RunnerStarter starter, Reader input, Pause pause,
		OutputStream output, PrintWriter errors) throws IOException
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
		String requests = environment.get(REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank())
		{
			return SessionRequestForwarder.forward(harness, args, input, Path.of(requests), pause, output, errors);
		}
		if (HarnessRunnerOptions.refusesOverride(harness, args, errors))
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		if (SessionLauncherClient.serves(platform, args))
			return launcher.launch(harness, args, input, output, errors);
		// The selected artifact's runner runs the artifact that holds it
		List<Path> locations = RequestedRunner.locations(selector.select());
		List<String> command = new ArrayList<>(List.of(locations.get(1).toString()));
		command.addAll(Arrays.asList(args));
		return starter.run(command, environment);
	}

	/**
	 * Prevents construction.
	 */
	private RunnerRelay()
	{
	}
}

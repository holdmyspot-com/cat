package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Pause;
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
 * Outside a run, the launcher runs the runner of the other harness's CAT artifact that the plugin selects, with that
 * artifact. Inside a run whose runner lets its session request sessions, it requests a session of its harness from the
 * run's runner instead, as the harness's own runner launcher does.
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
	 * Runs one launch.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param args        the runner options
	 * @param environment the launcher's environment
	 * @param selector    selects the harness's CAT artifact outside a run
	 * @param starter     runs the artifact's runner outside a run
	 * @param input       the launch's records inside a run when {@code --input} is absent
	 * @param pause       paces checks for the requested session's progress inside a run
	 * @param output      receives the session's transcript inside a run when {@code --output} is absent
	 * @param errors      receives the reason when a requested session does not complete
	 * @return the runner's exit status
	 * @throws IllegalArgumentException if the options name {@code --artifact}, or are invalid inside a run
	 * @throws IOException              if no artifact can be selected, the runner cannot be run, or the launch cannot
	 *                                  be forwarded
	 */
	public static int relay(String harness, String[] args, Map<String, String> environment, ArtifactSelector selector,
		RunnerStarter starter, Reader input, Pause pause, OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(selector, "selector").isNotNull();
		requireThat(starter, "starter").isNotNull();
		requireThat(pause, "pause").isNotNull();
		String requests = environment.get(REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank())
		{
			return SessionRequestForwarder.forward(harness, args, input, Path.of(requests), pause, output, errors);
		}
		if (Arrays.asList(args).contains("--artifact"))
		{
			throw new IllegalArgumentException("This launcher runs the " + harness + " CAT artifact that it selects; " +
				"remove --artifact");
		}
		List<Path> locations = RequestedRunner.locations(selector.select());
		List<String> command = new ArrayList<>(List.of(locations.get(1).toString(), "--artifact",
			locations.get(0).toString()));
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

package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.InPlaceBound;
import com.holdmyspot.cat.common.runner.policy.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.policy.RunnerChildNeeds;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Runs the other harness's runner from a plugin, so that both plugins hold a {@code claude-runner} and a
 * {@code codex-runner} launcher with the same options.
 * <p>
 * Outside a run, the launcher runs the runner of the other harness's CAT artifact that the plugin selects, which runs
 * that artifact, in place: inside the sandbox of the harness session whose command runs the launcher. Only the plugin
 * of the session's own harness can read the session's sandbox policy, so the launcher bounds the session that it
 * starts by that policy and by what the other harness's runner says that its session needs, and names the bound to
 * the runner with {@value PolicyBoundFile#OPTION}. A launch whose session needs more than the policy allows is refused
 * with the settings that would allow it. Inside a run whose runner lets its session request sessions, the launcher
 * requests a session of its harness from the run's runner; inside a run that lets its session request none, it
 * refuses the launch. Either way, the launcher starts nothing from a Codex sandbox that has network off.
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
	 * Runs one launch.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param args        the runner options
	 * @param environment the launcher's environment
	 * @param parent      reads the sandbox policy of the harness session whose command runs the launcher
	 * @param selector    selects the harness's CAT artifact for a launch outside a run
	 * @param starter     runs the artifact's runner for a launch outside a run
	 * @param input       the launch's records when {@code --input} is absent
	 * @param waiting     the process's waiting, which paces checks for the requested session's progress inside a run
	 * @param output      receives the session's transcript inside a run when {@code --output} is absent
	 * @param errors      receives the reason when the launcher refuses the launch or a requested session does not
	 *                    complete
	 * @return the runner's exit status, the requested session's exit status inside a run, or
	 *   {@link HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if the launch is refused. The launcher refuses a launch from
	 *   a Codex sandbox that has network off, from a run that lets its session request no sessions, or outside a run
	 *   that names {@code --artifact}, before it selects an artifact or sends a request; and a launch outside a run
	 *   whose session needs more than the parent's policy allows, before the session starts
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the options are invalid
	 * @throws IOException              if no artifact can be selected, the runner cannot be run, what the session
	 *                                  needs cannot be determined, or the launch cannot be forwarded
	 */
	public static int relay(String harness, String[] args, Map<String, String> environment, ParentPolicySource parent,
		ArtifactSelector selector, RunnerStarter starter, Reader input, Waiting waiting, OutputStream output,
		PrintWriter errors) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(parent, "parent").isNotNull();
		requireThat(selector, "selector").isNotNull();
		requireThat(starter, "starter").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		Optional<String> refusal = refusal(harness, args, environment);
		if (refusal.isPresent())
		{
			errors.println(refusal.get());
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		}
		String requests = environment.get(REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank())
		{
			return SessionRequestForwarder.forward(harness, args, input, Path.of(requests),
				waiting.every(REQUEST_POLL), output, errors);
		}
		// The selected artifact's runner runs the artifact that holds it
		Path runner = RequestedRunner.locations(selector.select()).get(1);
		if (Arrays.asList(args).contains(PolicyBoundFile.OPTION))
			return starter.run(command(runner, List.of(args)), environment);
		Path workingDirectory = Path.of("").toAbsolutePath();
		try (ToolScope scope = ToolScope.forSession(workingDirectory, environment, SessionHarness.installed(),
			waiting))
		{
			InPlaceOptions options = InPlaceOptions.parse(args, HarnessTemporaryRoot.from(scope));
			ExtraPermissions extra = HarnessRunnerOptions.readExtraPermissions(scope.json(),
				options.extraPermissions());
			InPlaceBound inPlace = new InPlaceBound(parent, new RunnerChildNeeds((_, arguments, directory) ->
				new RequestedRunner(scope, runner, List.of()).start(arguments, directory, environment)));
			ChildPolicy bound;
			try
			{
				bound = inPlace.bound(harness, options.runRoot(), options.deniedReads(), extra);
			}
			catch (SessionRefusedException e)
			{
				errors.println(e.getMessage());
				return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
			}
			Path directory = Files.createTempDirectory(Files.createDirectories(options.runRoot()), ".policy-bound-");
			try
			{
				return starter.run(command(runner, PolicyBoundFile.boundArguments(scope.json(), List.of(args),
					directory, bound)), environment);
			}
			finally
			{
				TemporaryDirectories.deleteRecursively(directory);
			}
		}
	}

	/**
	 * Returns why the launcher refuses a launch before it selects an artifact or sends a request.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param args        the runner options
	 * @param environment the launcher's environment
	 * @return the reason, or empty if the launch may go on
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	public static Optional<String> refusal(String harness, String[] args, Map<String, String> environment)
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		StringWriter reason = new StringWriter();
		PrintWriter writer = new PrintWriter(reason);
		if (ParentSandbox.refusesNetworkOff(harness, environment, writer))
			return Optional.of(reason.toString().strip());
		String requests = environment.get(REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank())
			return Optional.empty();
		Optional<String> inRun = HarnessEnvironment.inRunRefusal(harness, environment);
		if (inRun.isPresent())
			return inRun;
		if (HarnessRunnerOptions.refusesOverride(harness, args, writer))
			return Optional.of(reason.toString().strip());
		return Optional.empty();
	}

	/**
	 * Returns the command that runs the selected artifact's runner.
	 *
	 * @param runner    the runner launcher
	 * @param arguments the runner's options
	 * @return the command
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static List<String> command(Path runner, List<String> arguments)
	{
		requireThat(runner, "runner").isNotNull();
		requireThat(arguments, "arguments").isNotNull();
		List<String> command = new ArrayList<>(List.of(runner.toString()));
		command.addAll(arguments);
		return command;
	}

	/**
	 * Prevents construction.
	 */
	private RunnerRelay()
	{
	}
}

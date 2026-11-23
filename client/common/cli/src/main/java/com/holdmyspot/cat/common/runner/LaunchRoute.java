package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Where a runner launcher sends a launch, as the environment of the command that runs the launcher decides. Each
 * runner launcher of a plugin, for its own harness or for the other one, decides the route with
 * {@link #decide(String, String, Set, String[], Map)} after its own refusals, and only then starts anything.
 */
public sealed interface LaunchRoute
{
	/**
	 * Why a launch is refused when no harness session runs the command that makes it.
	 */
	String NO_HARNESS_REFUSAL = "CAT cannot start the session: CAT starts sessions only for a Claude Code or Codex " +
		"session, and none runs this command. Run this command from a Claude Code or Codex session.";
	/**
	 * The options that only CAT's own starters pass to a runner launcher. A harness session's launch may not name
	 * them: each one would let the session choose what bounds the new session.
	 */
	List<String> INTERNAL_OPTIONS = List.of(PolicyBoundFile.OPTION, RunnerArguments.CAPACITY_HELD_OPTION,
		ChildNeedsReport.OPTION, RunnerArguments.GIT_NAME_OPTION, RunnerArguments.GIT_EMAIL_OPTION,
		RunnerArguments.CONTINUE_AFTER_FAILED_TURN_OPTION, RunnerArguments.SLOT_ID_OPTION,
		RunnerArguments.RESUME_SLOT_OPTION, RunnerArguments.STARTUP_PERMIT_OPTION,
		RunnerArguments.INHERITED_DENY_READ_OPTION, RunnerArguments.INHERITED_SOURCE_HOME_OPTION);

	/**
	 * Returns the reason for refusing a launch that a harness session runs and that names an option that only CAT's
	 * own starters pass.
	 *
	 * @param args the launch's options
	 * @return the reason, or empty if the launch names none of {@link #INTERNAL_OPTIONS}
	 * @throws NullPointerException if {@code args} is null
	 */
	static Optional<String> internalOptionRefusal(String[] args)
	{
		requireThat(args, "args").isNotNull();
		for (String argument : args)
		{
			if (INTERNAL_OPTIONS.contains(argument))
			{
				return Optional.of("CAT cannot start the session: only CAT itself passes " + argument + ", and a " +
					"harness session's launch may not name it. Run the launcher again without " + argument + ".");
			}
		}
		return Optional.empty();
	}

	/**
	 * The launch becomes a request to the runner of the nested session whose session runs the command, which starts the
	 * session outside the nested session's sandbox.
	 *
	 * @param requests the nested session's requests directory
	 */
	record Forward(Path requests) implements LaunchRoute
	{
		/**
		 * Creates the route.
		 *
		 * @param requests the nested session's requests directory
		 * @throws NullPointerException if {@code requests} is null
		 */
		public Forward
		{
			requireThat(requests, "requests").isNotNull();
		}
	}

	/**
	 * The launch is refused with status {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS}.
	 *
	 * @param reason the message that tells the user why and what to do instead
	 */
	record Refused(String reason) implements LaunchRoute
	{
		/**
		 * Creates the route.
		 *
		 * @param reason the message that tells the user why and what to do instead
		 * @throws NullPointerException     if {@code reason} is null
		 * @throws IllegalArgumentException if {@code reason} is blank
		 */
		public Refused
		{
			requireThat(reason, "reason").isNotBlank();
		}
	}

	/**
	 * A session of the plugin's own harness runs the command outside a nested session. The command, inside the session's
	 * sandbox, packs the launch as a request to the session's {@link RequestServer}, which starts the new session
	 * outside that sandbox, or refuses the launch when no request server serves the session.
	 */
	record Served() implements LaunchRoute
	{
	}

	/**
	 * The launch names the policy that bounds its session with {@value PolicyBoundFile#OPTION}, as CAT's own starters,
	 * a request server and a nested session's runner, name it for the runner that they start, and the runner serves the
	 * session in its own process within that bound.
	 */
	record Bounded() implements LaunchRoute
	{
	}

	/**
	 * Returns where a launch goes. The first of these that applies decides:
	 * <ol>
	 * <li>Inside a nested session whose runner lets its session request sessions, the launch becomes a request to that
	 * runner:
	 *   {@link Forward}.</li>
	 *   <li>Inside a nested session whose runner lets its session request none, the launch is refused, as
	 *   {@link HarnessEnvironment#inNestedSessionRefusal(String, Map)} says: {@link Refused}.</li>
	 *   <li>When a harness runs the command, a launch that names an option that only CAT's own starters pass, as
	 *   {@link #internalOptionRefusal(String[])} lists them, is refused: {@link Refused}.</li>
	 *   <li>When the plugin's own harness runs the command, the launch becomes a request to the session's request
	 *   server: {@link Served}. This holds even when the environment also names the other harness.</li>
	 *   <li>When only the other harness runs the command, the launch is refused, since only that harness's plugin
	 *   holds the request server of its sessions, and the message names the plugin to use instead:
	 *   {@link Refused}.</li>
	 *   <li>When no harness runs the command, a launch that names {@value RunnerArguments#RESUME_OPTION} is refused,
	 *   since nothing outside a harness session or a nested session keeps an interrupted session for a resume: {@link
	 *   Refused}.</li>
	 *   <li>A launch that names {@value PolicyBoundFile#OPTION} serves its session within that bound:
	 *   {@link Bounded}.</li>
	 *   <li>Otherwise no harness session runs the command, as in a terminal or a script, and the launch is refused,
	 *   since only a harness session's sandbox and request server can bound the new session: {@link Refused}.</li>
	 * </ol>
	 *
	 * @param runner      the identifier of the harness whose runner the launcher stands for
	 * @param plugin      the identifier of the harness whose CAT plugin holds the launcher
	 * @param harnesses   the identifiers of the harnesses whose sessions the command's environment says run the
	 *                    command, as the plugin reads their markers
	 * @param args        the launch's options
	 * @param environment the launcher's environment
	 * @return the route
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code runner} or {@code plugin} are blank
	 */
	static LaunchRoute decide(String runner, String plugin, Set<String> harnesses, String[] args,
		Map<String, String> environment)
	{
		requireThat(runner, "runner").isNotBlank();
		requireThat(plugin, "plugin").isNotBlank();
		requireThat(harnesses, "harnesses").isNotNull();
		requireThat(args, "args").isNotNull();
		requireThat(environment, "environment").isNotNull();
		String requests = environment.get(SessionRequestAccess.REQUESTS_VARIABLE);
		if (requests != null && !requests.isBlank())
			return new Forward(Path.of(requests));
		Optional<String> inNestedSession = HarnessEnvironment.inNestedSessionRefusal(runner, environment);
		if (inNestedSession.isPresent())
			return new Refused(inNestedSession.get());
		if (!harnesses.isEmpty())
		{
			Optional<String> internal = internalOptionRefusal(args);
			if (internal.isPresent())
				return new Refused(internal.get());
		}
		if (harnesses.contains(plugin))
			return new Served();
		Optional<String> other = harnesses.stream().sorted().findFirst();
		if (other.isPresent())
		{
			String otherName = RequestServer.productName(other.get());
			return new Refused("CAT cannot start the session: " + otherName + " runs this command, and CAT's " +
				RequestServer.productName(plugin) + " plugin holds no request server for a " + otherName + " session. " +
				"Run the runner of CAT's " + otherName + " plugin instead.");
		}
		if (Arrays.asList(args).contains(RunnerArguments.RESUME_OPTION))
		{
			return new Refused("CAT cannot resume the session: " + RunnerArguments.RESUME_OPTION + " continues an " +
				"interrupted session in the slot that keeps it, and only the request server of the harness session that " +
				"started it, or the runner of the nested session that started it, keeps such a slot. Run the launcher " +
				"from that session.");
		}
		if (Arrays.asList(args).contains(PolicyBoundFile.OPTION))
			return new Bounded();
		return new Refused(NO_HARNESS_REFUSAL);
	}
}

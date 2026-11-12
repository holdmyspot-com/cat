package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.launcher.ChildBound;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Where a runner launcher sends a launch, as the environment of the command that runs the launcher decides. Each
 * runner launcher of a plugin, for its own harness or for the other one, decides the route with
 * {@link #decide(String, String, Set, String[], Map)} after its own refusals, and only then starts anything.
 */
public sealed interface LaunchRoute
{
	/**
	 * The options that only CAT's own starters pass to a runner launcher. A harness session's launch may not name
	 * them: each one would let the session choose what bounds the new session.
	 */
	List<String> INTERNAL_OPTIONS = List.of(PolicyBoundFile.OPTION, RunnerArguments.CAPACITY_HELD_OPTION,
		ChildNeedsReport.OPTION, RunnerArguments.GIT_NAME_OPTION, RunnerArguments.GIT_EMAIL_OPTION,
		RunnerArguments.CONTINUE_AFTER_FAILED_TURN_OPTION, RunnerArguments.RUN_ID_OPTION);

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
	 * The launch becomes a request to the runner of the CAT run whose session runs the command, which starts the
	 * session outside the run's sandbox.
	 *
	 * @param requests the run's requests directory
	 */
	record Forward(Path requests) implements LaunchRoute
	{
		/**
		 * Creates the route.
		 *
		 * @param requests the run's requests directory
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
	 * A session of the plugin's own harness runs the command outside a run. The command, inside the session's
	 * sandbox, packs the launch as a request to the session's {@link RequestServer}, which starts the new session
	 * outside that sandbox, or refuses the launch when no request server serves the session.
	 */
	record Served() implements LaunchRoute
	{
	}

	/**
	 * The launch names the policy that bounds its session with {@value PolicyBoundFile#OPTION}, as CAT's own starters,
	 * such as a run's runner, name it for a runner that no harness session runs, and the runner serves the session in
	 * place within that bound.
	 */
	record Bounded() implements LaunchRoute
	{
	}

	/**
	 * No harness runs the command, so no harness sandbox confines it, and the runner serves the session in place
	 * within the bound that {@link #boundArguments} gives it: exactly what the session needs.
	 */
	record InPlace() implements LaunchRoute
	{
		/**
		 * Bounds a launch that takes this route. The session's parent is unconfined, so the bound gives the session
		 * exactly what it needs: its run folder, the reads that the launch denies it, the writes and hosts that its
		 * harness's runner reports, and the extra permissions that the launch names.
		 *
		 * @param harness       the identifier of the session's harness
		 * @param args          the launch's options
		 * @param temporaryRoot where the run is kept when the launch names no {@code --run-root}
		 * @param json          the document mapper
		 * @param needs         reports what a session of the harness needs
		 * @param cleanup       receives the folder in the run root that holds the bound's file, which the session
		 *                      reads when it starts
		 * @return {@code args} followed by {@value PolicyBoundFile#OPTION} and the bound's file, a launch that takes
		 *   the {@link Bounded} route
		 * @throws NullPointerException      if any of the arguments are null
		 * @throws IllegalArgumentException  if {@code harness} is blank
		 * @throws InvalidArgumentsException if an option that bounds the session lacks its value, a denied read is
		 *                                   not absolute, the file of extra permissions is missing or invalid, or the
		 *                                   launch names no {@code --run-root} outside a harness session
		 * @throws SessionRefusedException   if what the session needs cannot be determined; the message says that
		 *                                   CAT cannot read the session's sandbox policy, and why
		 * @throws IOException               if the bound cannot be written
		 */
		public static List<String> boundArguments(String harness, String[] args, HarnessTemporaryRoot temporaryRoot,
			JsonMapper json, NeedsReport needs, TemporaryDirectories cleanup) throws IOException
		{
			requireThat(harness, "harness").isNotBlank();
			requireThat(args, "args").isNotNull();
			requireThat(temporaryRoot, "temporaryRoot").isNotNull();
			requireThat(json, "json").isNotNull();
			requireThat(needs, "needs").isNotNull();
			requireThat(cleanup, "cleanup").isNotNull();
			InPlaceOptions options = InPlaceOptions.parse(args, temporaryRoot);
			ExtraPermissions extra = HarnessRunnerOptions.readExtraPermissions(json, options.extraPermissions());
			String harnessName = RequestServer.productName(harness);
			ChildBound bound = new ChildBound(unconfined(harnessName), (_, runFolder, deniedReads, _) ->
			{
				ChildNeedsReport report;
				try
				{
					report = needs.report();
				}
				catch (IOException e)
				{
					throw new SessionRefusedException(ChildBound.unreadablePolicy(harnessName, e), e);
				}
				return new ChildNeeds(runFolder, report.writes(), deniedReads, report.hosts());
			});
			ChildPolicy policy = bound.bound(harness, options.runRoot(), options.deniedReads(), extra,
				Optional.empty());
			Path directory = cleanup.add(Files.createTempDirectory(Files.createDirectories(options.runRoot()),
				".policy-bound-"));
			return PolicyBoundFile.boundArguments(json, List.of(args), directory, policy);
		}

		/**
		 * Returns the source of the policy of a parent that no harness sandbox confines.
		 *
		 * @param harnessName the name of the session's harness as its user knows it
		 * @return the source, which reads {@link ParentPolicy#UNSANDBOXED}
		 */
		private static ParentPolicySource unconfined(String harnessName)
		{
			return new ParentPolicySource()
			{
				@Override
				public String harnessName()
				{
					return harnessName;
				}

				@Override
				public String permissionEntries(List<Path> writes, Set<String> hosts)
				{
					// An unconfined parent allows every write and host, so no entry is ever missing
					return "";
				}

				@Override
				public ParentPolicy read(Optional<String> askingThread)
				{
					return ParentPolicy.UNSANDBOXED;
				}
			};
		}
	}

	/**
	 * Reports what a session of a harness needs, as that harness's runner reports it with
	 * {@value ChildNeedsReport#OPTION}.
	 */
	@FunctionalInterface
	interface NeedsReport
	{
		/**
		 * Reports what a session needs.
		 *
		 * @return the report
		 * @throws IOException if what the session needs cannot be determined
		 */
		ChildNeedsReport report() throws IOException;
	}

	/**
	 * Returns where a launch goes. The first of these that applies decides:
	 * <ol>
	 *   <li>Inside a run whose runner lets its session request sessions, the launch becomes a request to that runner:
	 *   {@link Forward}.</li>
	 *   <li>Inside a run whose runner lets its session request none, the launch is refused, as
	 *   {@link HarnessEnvironment#inRunRefusal(String, Map)} says: {@link Refused}.</li>
	 *   <li>When a harness runs the command, a launch that names an option that only CAT's own starters pass, as
	 *   {@link #internalOptionRefusal(String[])} lists them, is refused: {@link Refused}.</li>
	 *   <li>When the plugin's own harness runs the command, the launch becomes a request to the session's request
	 *   server: {@link Served}. This holds even when the environment also names the other harness.</li>
	 *   <li>When only the other harness runs the command, the launch is refused, since only that harness's plugin
	 *   holds the request server of its sessions, and the message names the plugin to use instead:
	 *   {@link Refused}.</li>
	 *   <li>A launch that names {@value PolicyBoundFile#OPTION} serves its session within that bound:
	 *   {@link Bounded}.</li>
	 *   <li>When no harness runs the command, the session starts in place: {@link InPlace}.</li>
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
		Optional<String> inRun = HarnessEnvironment.inRunRefusal(runner, environment);
		if (inRun.isPresent())
			return new Refused(inRun.get());
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
		if (Arrays.asList(args).contains(PolicyBoundFile.OPTION))
			return new Bounded();
		return new InPlace();
	}
}

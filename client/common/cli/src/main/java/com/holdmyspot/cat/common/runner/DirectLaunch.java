package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.runner.launcher.ChildBound;
import com.holdmyspot.cat.common.runner.launcher.ChildNeedsSource;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import com.holdmyspot.cat.common.runner.launcher.RunnerChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Bounds a launch that a harness session runs, for the runner launcher that then starts the new session itself. The
 * launcher runs outside the session's sandbox, and its options, inputs and environment are the session's, because
 * the session's model chose them. So before it starts anything, it checks, in this order:
 * <ol>
 *   <li>that it runs outside the session's sandbox;</li>
 *   <li>which session runs it, from sources that the session's commands cannot change;</li>
 *   <li>the session's sandbox policy, which it refuses to read when the session's commands could change what it reads,
 *   such as the session's transcript or the launcher's own plugin;</li>
 *   <li>that the policy lets the session read every path that the launch reads and write every path that it
 *   writes;</li>
 *   <li>the bound of the new session, which is the intersection of the policy and the new session's needs.</li>
 * </ol>
 * A launch that fails a check is refused with the reason, before the launcher reads a path that the launch names.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class DirectLaunch
{
	/**
	 * The options that only CAT's own starters pass to a runner launcher. A harness session's launch may not name
	 * them: each one would let the session choose what bounds the new session.
	 */
	public static final List<String> INTERNAL_OPTIONS = List.of(PolicyBoundFile.OPTION,
		RunnerArguments.CAPACITY_HELD_OPTION, ChildNeedsReport.OPTION, RunnerArguments.GIT_NAME_OPTION,
		RunnerArguments.GIT_EMAIL_OPTION);
	/**
	 * The options whose values name paths that the launch reads.
	 */
	private static final Set<String> READ_OPTIONS = Set.of("--project", "--input", Conversation.OPTION, "--plugin",
		"--workspace", "--read-only-evidence", ExtraPermissions.OPTION);
	/**
	 * The options whose values name paths that the launch writes.
	 */
	private static final Set<String> WRITE_OPTIONS = Set.of("--output", "--receipt", "--run-root",
		Conversation.OPTION);

	/**
	 * The harness session that runs a runner launcher.
	 *
	 * @param policy       reads the session's sandbox policy
	 * @param transcript   the absolute path of the session's transcript, by which the session was found
	 * @param project      the session's absolute project folder, which the transcript names
	 * @param askingThread the identifier that the session's harness gives the thread that runs the launcher, or empty
	 *                     for the session's main thread
	 */
	public record Session(ParentPolicySource policy, Path transcript, Path project, Optional<String> askingThread)
	{
		/**
		 * Creates a session.
		 *
		 * @param policy       reads the session's sandbox policy
		 * @param transcript   the absolute path of the session's transcript, by which the session was found
		 * @param project      the session's absolute project folder, which the transcript names
		 * @param askingThread the identifier that the session's harness gives the thread that runs the launcher, or
		 *                     empty for the session's main thread
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code transcript} or {@code project} are not absolute
		 */
		public Session
		{
			requireThat(policy, "policy").isNotNull();
			requireThat(transcript, "transcript").isNotNull().isAbsolute();
			requireThat(project, "project").isNotNull().isAbsolute();
			requireThat(askingThread, "askingThread").isNotNull();
		}
	}

	/**
	 * Identifies the harness session that runs a runner launcher.
	 */
	@FunctionalInterface
	public interface SessionFinder
	{
		/**
		 * Returns the session.
		 *
		 * @return the session
		 * @throws IOException   if a source that identifies the session cannot be read
		 * @throws PolicyRefusal if the sources do not identify exactly one session safely
		 */
		Session find() throws IOException, PolicyRefusal;
	}

	/**
	 * Prevents instantiation.
	 */
	private DirectLaunch()
	{
	}

	/**
	 * Refuses a command that a harness session runs outside its sandbox, such as verifying an artifact, when it would
	 * read a path that the session's sandbox does not let the session's commands read. The command checks, as
	 * {@link #boundArguments} does, that it runs outside the session's sandbox, which session runs it, and that
	 * session's policy.
	 *
	 * @param harness   the identifier of the harness whose session runs the command
	 * @param option    the option that names the path, which a refusal names
	 * @param path      the path that the command would read; a relative path resolves against the process's working
	 *                  directory
	 * @param plugin    the absolute folder of the plugin that holds the command's launcher, which the session's
	 *                  commands must not be able to write
	 * @param sessions  identifies the session that runs the command
	 * @param sandboxed returns why the command runs inside the session's sandbox, which a refusal states
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} or {@code option} are blank, or {@code plugin} is not
	 *                                  absolute
	 * @throws SessionRefusedException  if a check fails; the message says why and how to fix it
	 * @throws IOException              if a source that identifies the session, or a symbolic link in the path,
	 *                                  cannot be read
	 */
	public static void requireReadable(String harness, String option, Path path, Path plugin, SessionFinder sessions,
		Supplier<String> sandboxed) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(option, "option").isNotBlank();
		requireThat(path, "path").isNotNull();
		requireThat(plugin, "plugin").isNotNull().isAbsolute();
		requireThat(sessions, "sessions").isNotNull();
		requireThat(sandboxed, "sandboxed").isNotNull();
		requireOutsideSandbox(plugin, sandboxed);
		Session session;
		try
		{
			session = sessions.find();
		}
		catch (PolicyRefusal e)
		{
			throw new SessionRefusedException(e.messageFor(productName(harness)), e);
		}
		ParentPolicySource source = session.policy();
		ChildNeedsSource noNeeds = (_, _, _, _) ->
		{
			throw new AssertionError("A read check asks for no needs");
		};
		ParentPolicy policy = new ChildBound(source, noNeeds).snapshot(session.transcript()).snapshot(plugin).
			parentPolicy(session.askingThread());
		Path absolute = path.toAbsolutePath().normalize();
		if (!policy.mayRead(absolute))
		{
			throw new SessionRefusedException("CAT refuses " + option + ": this " + source.harnessName() +
				" session's sandbox does not let its commands read " + absolute + ". Name a path that the session " +
				"may read.");
		}
	}

	/**
	 * Returns a finder that refuses a launch whose new session would take the user's home folder, or a folder that
	 * contains it, as its project, which {@link HarnessRunnerOptions#refusesHomeProject} refuses. That project is the
	 * folder that the launch's {@code --project} names, resolved against {@code workingDirectory}, or else the project
	 * folder that the session's transcript names, which {@link #boundArguments} gives the new session. The finder
	 * refuses before {@link #boundArguments} writes anything for the new session.
	 *
	 * @param sessions         identifies the session that runs the launcher
	 * @param args             the launch's options
	 * @param workingDirectory the folder against which a relative {@code --project} resolves
	 * @param environment      the launcher's environment
	 * @param platform         the platform that the launcher runs on, whose home variable names the home folder
	 * @return a finder that returns the session that {@code sessions} finds, or throws a
	 *   {@link SessionRefusedException} that gives the reason if the new session's project is refused
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static SessionFinder refusingHomeProject(SessionFinder sessions, String[] args, Path workingDirectory,
		Map<String, String> environment, Platform platform)
	{
		requireThat(sessions, "sessions").isNotNull();
		requireThat(args, "args").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		return () ->
		{
			Session session = sessions.find();
			Path base;
			if (Arrays.asList(args).contains("--project"))
				base = workingDirectory;
			else
				base = session.project();
			Optional<String> refusal = HarnessRunnerOptions.homeProjectRefusal(args, base, environment, platform);
			if (refusal.isPresent())
				throw new SessionRefusedException(refusal.get());
			return session;
		};
	}

	/**
	 * Returns the reason for refusing a launch that a harness session runs and that names an option that only CAT's
	 * own starters pass.
	 *
	 * @param args the launch's options
	 * @return the reason, or empty if the launch names none of {@link #INTERNAL_OPTIONS}
	 * @throws NullPointerException if {@code args} is null
	 */
	public static Optional<String> internalOptionRefusal(String[] args)
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
	 * Bounds a launch that a session of the launcher's own harness runs.
	 *
	 * @param harness   the identifier of the new session's harness
	 * @param args      the launch's options; a relative path in them resolves against the process's working
	 *                  directory, as the runner resolves it
	 * @param plugin    the absolute folder of the plugin that holds the launcher, which the session's commands must not
	 *                  be able to write
	 * @param sessions  identifies the session that runs the launcher
	 * @param scopes    opens the tool scope of the session's project folder, which gives the default run root
	 * @param needs     reports what the new session needs
	 * @param sandboxed returns why the launcher runs inside the session's sandbox, which a refusal states
	 * @param cleanup   receives the folder that holds the bound's file, which the new session reads when it starts
	 * @return {@code args}, followed by {@code --project} and the session's project folder and by {@code --run-root}
	 *   and the run root if they name none, and by {@value PolicyBoundFile#OPTION} and the bound's file
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws IllegalArgumentException  if {@code harness} is blank or {@code plugin} is not absolute
	 * @throws SessionRefusedException   if a check fails; the message says why and how to fix it
	 * @throws InvalidArgumentsException if an option lacks its value, a denied read is not absolute, or the file of
	 *                                   extra permissions is invalid
	 * @throws IOException               if what the new session needs cannot be determined, or the bound cannot be
	 *                                   written
	 */
	public static List<String> boundArguments(String harness, String[] args, Path plugin, SessionFinder sessions,
		Function<Path, ToolScope> scopes, LaunchRoute.NeedsReport needs, Supplier<String> sandboxed,
		TemporaryDirectories cleanup) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(needs, "needs").isNotNull();
		return boundArguments(harness, args, plugin, sessions, scopes, (childHarness, runFolder, deniedReads, policy) ->
		{
			ChildNeedsReport report;
			try
			{
				report = needs.report();
			}
			catch (IOException e)
			{
				throw new SessionRefusedException(ChildBound.unreadablePolicy(productName(harness), e), e);
			}
			return RunnerChildNeeds.needs(childHarness, report, runFolder, deniedReads, policy);
		}, sandboxed, cleanup);
	}

	/**
	 * Bounds a launch that a harness session runs.
	 *
	 * @param harness   the identifier of the new session's harness
	 * @param args      the launch's options; a relative path in them resolves against the process's working
	 *                  directory, as the runner resolves it
	 * @param plugin    the absolute folder of the plugin that holds the launcher, which the session's commands must not
	 *                  be able to write
	 * @param sessions  identifies the session that runs the launcher
	 * @param scopes    opens the tool scope of the session's project folder, which gives the default run root
	 * @param needs     says what the new session needs
	 * @param sandboxed returns why the launcher runs inside the session's sandbox, which a refusal states
	 * @param cleanup   receives the folder that holds the bound's file, which the new session reads when it starts
	 * @return {@code args}, followed by {@code --project} and the session's project folder and by {@code --run-root}
	 *   and the run root if they name none, and by {@value PolicyBoundFile#OPTION} and the bound's file
	 * @throws NullPointerException      if any of the arguments are null
	 * @throws IllegalArgumentException  if {@code harness} is blank or {@code plugin} is not absolute
	 * @throws SessionRefusedException   if a check fails; the message says why and how to fix it
	 * @throws InvalidArgumentsException if an option lacks its value, a denied read is not absolute, or the file of
	 *                                   extra permissions is invalid
	 * @throws IOException               if what the new session needs cannot be determined, or the bound cannot be
	 *                                   written
	 */
	public static List<String> boundArguments(String harness, String[] args, Path plugin, SessionFinder sessions,
		Function<Path, ToolScope> scopes, ChildNeedsSource needs, Supplier<String> sandboxed,
		TemporaryDirectories cleanup) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(args, "args").isNotNull();
		requireThat(plugin, "plugin").isNotNull().isAbsolute();
		requireThat(sessions, "sessions").isNotNull();
		requireThat(scopes, "scopes").isNotNull();
		requireThat(needs, "needs").isNotNull();
		requireThat(sandboxed, "sandboxed").isNotNull();
		requireThat(cleanup, "cleanup").isNotNull();
		requireOutsideSandbox(plugin, sandboxed);
		Session session;
		try
		{
			session = sessions.find();
		}
		catch (PolicyRefusal e)
		{
			throw new SessionRefusedException(e.messageFor(productName(harness)), e);
		}
		ParentPolicySource source = session.policy();
		ChildBound bound = new ChildBound(source, needs).snapshot(session.transcript()).snapshot(plugin);
		ParentPolicy policy = bound.parentPolicy(session.askingThread());
		try (ToolScope tools = scopes.apply(session.project()))
		{
			InPlaceOptions options = InPlaceOptions.parse(args, HarnessTemporaryRoot.from(tools));
			requirePaths(args, session.project(), options.runRoot(), policy, source);
			ExtraPermissions extra = HarnessRunnerOptions.readExtraPermissions(tools.json(),
				options.extraPermissions());
			ChildPolicy child = bound.bound(harness, options.runRoot(), options.deniedReads(), extra,
				session.askingThread());
			Path directory = cleanup.add(Files.createTempDirectory(Files.createDirectories(options.runRoot()),
				".policy-bound-"));
			List<String> arguments = new ArrayList<>(Arrays.asList(args));
			if (!arguments.contains("--project"))
				arguments.addAll(List.of("--project", session.project().toString()));
			if (!arguments.contains("--run-root"))
				arguments.addAll(List.of("--run-root", options.runRoot().toString()));
			return PolicyBoundFile.boundArguments(tools.json(), arguments, directory, child);
		}
	}

	/**
	 * Returns what a session of another harness needs, as that harness's runner reports it when it runs with
	 * {@value ChildNeedsReport#OPTION}. The runner reports into a folder of its own inside the new session's run root,
	 * which the launch has shown that the session may write.
	 *
	 * @param starter starts the runner of the new session's harness
	 * @param cleanup receives the folder that the runner reports into
	 * @return the needs
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static ChildNeedsSource runnerNeeds(RequestedSessionStarter starter, TemporaryDirectories cleanup)
	{
		requireThat(starter, "starter").isNotNull();
		requireThat(cleanup, "cleanup").isNotNull();
		RunnerChildNeeds runner = new RunnerChildNeeds(starter);
		return (harness, runFolder, deniedReads, bound) ->
		{
			Path directory = cleanup.add(Files.createTempDirectory(Files.createDirectories(runFolder),
				".child-needs-"));
			ChildNeeds reported = runner.needs(harness, directory.resolve("run"), deniedReads, bound);
			return new ChildNeeds(runFolder, reported.extraWrites(), reported.readDenials(), reported.hosts());
		};
	}

	/**
	 * Confirms that the launcher runs outside the session's sandbox by creating and deleting a file in its plugin's
	 * folder. The folder belongs to the user, and the policy check that follows refuses a session whose sandbox lets
	 * its commands write it, so a file that can be created there shows that no sandbox confines the launcher.
	 *
	 * @param plugin    the folder of the plugin that holds the launcher
	 * @param sandboxed returns why the launcher runs inside the session's sandbox
	 * @throws SessionRefusedException if the file cannot be created or deleted
	 */
	private static void requireOutsideSandbox(Path plugin, Supplier<String> sandboxed) throws SessionRefusedException
	{
		try
		{
			Path probe = Files.createTempFile(plugin, ".sandbox-probe-", "");
			Files.delete(probe);
		}
		catch (IOException e)
		{
			throw new SessionRefusedException(sandboxed.get(), e);
		}
	}

	/**
	 * Checks that the policy lets the session read every path that the launch reads and write every path that it
	 * writes.
	 *
	 * @param args    the launch's options
	 * @param project the session's project folder, which is the launch's when it names none
	 * @param runRoot the launch's absolute run root
	 * @param policy  the session's policy
	 * @param source  the source of the policy, which names the settings that let the session write a path
	 * @throws SessionRefusedException   if the policy does not allow a path
	 * @throws InvalidArgumentsException if an option lacks its value
	 * @throws IOException               if a symbolic link in a path cannot be read
	 */
	private static void requirePaths(String[] args, Path project, Path runRoot, ParentPolicy policy,
		ParentPolicySource source) throws IOException
	{
		String harnessName = source.harnessName();
		List<NamedPath> reads = new ArrayList<>();
		List<NamedPath> writes = new ArrayList<>();
		boolean namesProject = false;
		for (int index = 0; index < args.length; ++index)
		{
			String option = args[index];
			boolean isRead = READ_OPTIONS.contains(option);
			boolean isWrite = WRITE_OPTIONS.contains(option);
			if (!isRead && !isWrite)
				continue;
			if (index + 1 == args.length || args[index + 1].startsWith("--"))
				throw new InvalidArgumentsException("Missing value for " + option);
			namesProject |= option.equals("--project");
			// Each value up to the next option is one of read-only evidence's paths
			int last = index + 1;
			if (option.equals("--read-only-evidence"))
			{
				while (last + 1 < args.length && !args[last + 1].startsWith("--"))
					++last;
			}
			for (int value = index + 1; value <= last; ++value)
			{
				NamedPath path = new NamedPath(option, Path.of(args[value]).toAbsolutePath().normalize());
				if (isRead)
					reads.add(path);
				if (isWrite && !option.equals("--run-root"))
					writes.add(path);
			}
			index = last;
		}
		if (!namesProject)
			reads.add(new NamedPath("--project", project));
		writes.add(new NamedPath("--run-root", runRoot));
		for (NamedPath read : reads)
		{
			if (!policy.mayRead(read.path()))
			{
				throw new SessionRefusedException("CAT cannot start the session: this " + harnessName + " session's " +
					"sandbox does not let its commands read " + read.path() + ", which " + read.option() + " names. " +
					"Name a path that the session may read.");
			}
		}
		for (NamedPath write : writes)
		{
			if (!policy.mayWrite(write.path()))
			{
				throw new SessionRefusedException("CAT cannot start the session: this " + harnessName + " session's " +
					"sandbox does not let its commands write " + write.path() + ", which " + write.option() + " names. " +
					"Name a path that the session may write, or " + source.permissionEntries(List.of(write.path()),
					Set.of()) + ", then start a new session.");
			}
		}
	}

	/**
	 * Returns the name of a harness as its user knows it.
	 *
	 * @param harness the harness's identifier
	 * @return the product name, or {@code harness} if CAT knows no harness by that identifier
	 */
	static String productName(String harness)
	{
		return Harness.fromCommandName(harness).map(Harness::productName).orElse(harness);
	}

	/**
	 * A path that a launch names.
	 *
	 * @param option the option that names it
	 * @param path   the absolute path
	 */
	private record NamedPath(String option, Path path)
	{
	}
}

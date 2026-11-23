package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.LockFileOpener;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.workspace.GitWorktreeRegistrar;
import com.holdmyspot.cat.common.runner.RequestArchive.LaunchRequest;
import com.holdmyspot.cat.common.runner.launcher.ChildBound;
import com.holdmyspot.cat.common.runner.launcher.ChildNeedsSource;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.ProcessTable;
import com.holdmyspot.cat.common.runner.launcher.RunnerChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code request-server} program of each plugin, which a harness session's agent starts in the background and which
 * runs outside the session's sandbox through the sandbox exception that CAT's install adds for it. It serves the
 * session requests that the session's commands write into {@value #REQUESTS} in the session's CAT session directory, as
 * the nested session's runner serves those of a nested session's commands, and starts each requested session inside its
 * harness's own sandbox, bounded by the intersection of the session's sandbox policy and what the new session needs. It
 * ends once the session's harness process ends, stopping every session that it started with its process tree.
 */
public final class RequestServer
{
	/**
	 * The program's file name in each plugin's {@code bin} folder.
	 */
	public static final String PROGRAM = "request-server";
	/**
	 * The directory, in the session's CAT session directory, into which the session's commands write their requests.
	 */
	public static final String REQUESTS = "requests";
	/**
	 * The file, in the session's CAT session directory, that the server holds locked while it serves the session.
	 */
	public static final String LOCK_FILE = "request-server.lock";
	/**
	 * The directory, in the session's CAT session directory, in which the server answers requests.
	 */
	private static final String RESPONSES = "responses";
	/**
	 * The directory, in the session's CAT session directory, that holds the slots in which the sessions that the
	 * server starts run, each from its start until the server has answered its request. The name is short because
	 * the paths of a session's nested session, which every path of its home and project starts with, recur many times in
	 * what its harness sends the model.
	 */
	private static final String RUNS = "slots";
	/**
	 * How long the server tries to take its lock, which a client takes for a moment when it checks whether a server
	 * serves the session.
	 */
	private static final Duration LOCK_WAIT = Duration.ofSeconds(5);
	/**
	 * How often the server tries to take its lock while another process holds it.
	 */
	private static final Duration LOCK_RETRY = Duration.ofMillis(50);

	/**
	 * The harness session that runs the server, as sources that the session's commands cannot change identify it.
	 *
	 * @param policy       reads the session's sandbox policy
	 * @param transcript   the absolute path of the session's transcript, by which the session was found
	 * @param project      the session's absolute project folder, which the transcript names
	 * @param askingThread the identifier that the session's harness gives the thread that runs the server, or empty
	 *                     for the session's main thread
	 * @param harness      the session's harness process, whose end ends the server
	 */
	public record Session(ParentPolicySource policy, Path transcript, Path project, Optional<String> askingThread,
		ProcessIdentity harness)
	{
		/**
		 * Creates a session.
		 *
		 * @param policy       reads the session's sandbox policy
		 * @param transcript   the absolute path of the session's transcript, by which the session was found
		 * @param project      the session's absolute project folder, which the transcript names
		 * @param askingThread the identifier that the session's harness gives the thread that runs the server, or
		 *                     empty for the session's main thread
		 * @param harness      the session's harness process, whose end ends the server
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code transcript} or {@code project} are not absolute
		 */
		public Session
		{
			requireThat(policy, "policy").isNotNull();
			requireThat(transcript, "transcript").isNotNull().isAbsolute();
			requireThat(project, "project").isNotNull().isAbsolute();
			requireThat(askingThread, "askingThread").isNotNull();
			requireThat(harness, "harness").isNotNull();
		}
	}

	/**
	 * Identifies the harness session that runs the server.
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
	 * Opens the tool scope of a command that a harness session runs, which gives the session's CAT session directory.
	 */
	@FunctionalInterface
	public interface SessionScope
	{
		/**
		 * Opens the scope.
		 *
		 * @return the scope, which the caller closes
		 * @throws IOException if the scope cannot be opened
		 */
		ToolScope open() throws IOException;
	}

	/**
	 * What the plugin that holds the server gives it.
	 *
	 * @param harness           the identifier of the plugin's harness
	 * @param running           the identifiers of the harnesses whose markers the server's environment holds
	 * @param refusal           why this server must not run in its process, such as that the platform cannot run
	 *                          sandboxed sessions or that the process belongs to a nested session's harness session, or
	 *                          empty if it can
	 * @param plugin            the absolute folder of the plugin, which the session's commands must not be able to
	 *                          write
	 * @param sandboxed         returns why the server runs inside the session's sandbox, which a refusal states
	 * @param sessions          identifies the session that runs the server
	 * @param scopes            opens the tool scope of the session's project folder, which gives the session's CAT
	 *                          session directory
	 * @param starter           starts the runner of a requested session's harness, without the harness markers in
	 *                          its environment
	 * @param processes         tells whether the session's harness process still runs
	 * @param pause             paces the checks for requests and for the harness process's end
	 */
	public record Host(String harness, Set<String> running, Optional<String> refusal, Path plugin,
		Supplier<String> sandboxed, SessionFinder sessions, Function<Path, ToolScope> scopes,
		RequestedRunner.Resolver starter, ProcessTable processes, Pause pause)
	{
		/**
		 * Creates what a plugin gives its server.
		 *
		 * @param harness           the identifier of the plugin's harness
		 * @param running           the identifiers of the harnesses whose markers the server's environment holds
		 * @param refusal           why this server must not run in its process, or empty if it can
		 * @param plugin            the absolute folder of the plugin
		 * @param sandboxed         returns why the server runs inside the session's sandbox
		 * @param sessions          identifies the session that runs the server
		 * @param scopes            opens the tool scope of the session's project folder
		 * @param starter           starts the runner of a requested session's harness
		 * @param processes         tells whether the session's harness process still runs
		 * @param pause             paces the checks for requests and for the harness process's end
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code harness} is blank or {@code plugin} is not absolute
		 */
		public Host
		{
			requireThat(harness, "harness").isNotBlank();
			running = Set.copyOf(running);
			requireThat(refusal, "refusal").isNotNull();
			requireThat(plugin, "plugin").isNotNull().isAbsolute();
			requireThat(sandboxed, "sandboxed").isNotNull();
			requireThat(sessions, "sessions").isNotNull();
			requireThat(scopes, "scopes").isNotNull();
			requireThat(starter, "starter").isNotNull();
			requireThat(processes, "processes").isNotNull();
			requireThat(pause, "pause").isNotNull();
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private RequestServer()
	{
	}

	/**
	 * Returns the path of the request server of a plugin.
	 *
	 * @param plugin the plugin's folder
	 * @return the path
	 * @throws NullPointerException if {@code plugin} is null
	 */
	public static Path program(Path plugin)
	{
		requireThat(plugin, "plugin").isNotNull();
		return plugin.resolve(PluginDirectory.BIN).resolve(PROGRAM);
	}

	/**
	 * Returns the directory into which a session's commands write their requests.
	 *
	 * @param session the session's CAT session directory
	 * @return the directory
	 * @throws NullPointerException if {@code session} is null
	 */
	public static Path requests(SessionDirectory session)
	{
		requireThat(session, "session").isNotNull();
		return session.directory().resolve(REQUESTS);
	}

	/**
	 * Returns what a requested session needs, as its harness's runner reports it when it runs with
	 * {@value ChildNeedsReport#OPTION} and the launch's choice of source home and gateway login class, which decide the
	 * files that hold the session's credential. The runner reports into a folder of its own inside the new session's
	 * session folder.
	 *
	 * @param starter        starts the runner of the new session's harness
	 * @param authentication the launch's choice of source home and gateway login class
	 * @param cleanup        receives the folder that the runner reports into
	 * @return the needs
	 * @throws NullPointerException if any of the arguments are null
	 */
	private static ChildNeedsSource runnerNeeds(RequestedRunner.Start starter, AuthenticationOptions authentication,
		TemporaryDirectories cleanup)
	{
		requireThat(starter, "starter").isNotNull();
		requireThat(authentication, "authentication").isNotNull();
		requireThat(cleanup, "cleanup").isNotNull();
		RunnerChildNeeds runner = new RunnerChildNeeds((_, arguments, directory) ->
		{
			List<String> withAuthentication = new ArrayList<>(arguments);
			withAuthentication.addAll(authentication.arguments());
			return starter.run(withAuthentication, directory);
		});
		return (harness, sessionFolder, deniedReads, bound) ->
		{
			Path directory = cleanup.add(Files.createTempDirectory(Files.createDirectories(sessionFolder),
				".child-needs-"));
			ChildNeeds reported = runner.needs(harness, directory.resolve("session"), deniedReads, bound);
			return new ChildNeeds(sessionFolder, reported.extraWrites(), reported.readDenials(), reported.hosts());
		};
	}

	/**
	 * Returns the identity of the harness process that runs a session, among the processes that run the server.
	 *
	 * @param processId the harness process's identifier
	 * @param ancestors the processes that run the server, from its parent up
	 * @return the identity
	 * @throws NullPointerException if {@code ancestors} is null
	 * @throws PolicyRefusal        if no ancestor has {@code processId}, or the operating system does not report when
	 *                              it started
	 */
	public static ProcessIdentity harnessIdentity(long processId, List<ProcessDescription> ancestors)
		throws PolicyRefusal
	{
		requireThat(ancestors, "ancestors").isNotNull();
		for (ProcessDescription ancestor : ancestors)
		{
			if (ancestor.processId() == processId && ancestor.start().isPresent())
				return new ProcessIdentity(processId, ancestor.start().get());
		}
		throw new PolicyRefusal("the operating system reports no start of the harness process " + processId +
			" among the processes that run the request server, so the server cannot tell when the session ends",
			"Start the request server from a command of the session that it serves");
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
	 * Returns the request server's own entries in a CAT session directory, which a request leaves out of the trees
	 * that it carries: they change while the server serves the session, and a nested session among them holds a login.
	 *
	 * @param session the real path of the CAT session directory that holds the requests directory
	 * @return the entries' paths
	 * @throws NullPointerException if {@code session} is null
	 */
	public static Set<Path> ownEntries(Path session)
	{
		requireThat(session, "session").isNotNull();
		return Set.of(session.resolve(REQUESTS), session.resolve(RESPONSES), session.resolve(LOCK_FILE),
			session.resolve(RUNS));
	}

	/**
	 * Indicates whether a request server serves a session. The check takes the server's lock for a moment when no
	 * server holds it, and creates no file.
	 *
	 * @param session the session's CAT session directory
	 * @param scope   the scope whose lock coordinator takes the lock
	 * @return {@code true} if a request server holds the session's lock
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the lock file cannot be opened
	 */
	public static boolean serves(SessionDirectory session, ToolScope scope) throws IOException
	{
		requireThat(session, "session").isNotNull();
		requireThat(scope, "scope").isNotNull();
		Path file = session.directory().resolve(LOCK_FILE);
		Optional<ExclusiveFileLock> lock;
		try
		{
			lock = tryLock(file, scope, path -> FileChannel.open(path, StandardOpenOption.WRITE,
				LinkOption.NOFOLLOW_LINKS));
		}
		catch (NoSuchFileException _)
		{
			// Only a server creates the file
			return false;
		}
		if (lock.isEmpty())
			return true;
		lock.get().close();
		return false;
	}

	/**
	 * Returns the directory into which a command outside a nested session writes the requests of the session that runs
	 * it, once a request server serves that session.
	 *
	 * @param scope the command's tool scope, which gives the session's CAT session directory
	 * @return the directory, or empty if the scope names no session, or no request server serves the session
	 * @throws NullPointerException if {@code scope} is null
	 * @throws IOException          if the session's CAT session directory or its lock file cannot be opened
	 */
	public static Optional<Path> servedRequests(ToolScope scope) throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		Optional<SessionDirectory> directory = scope.sessionDirectory();
		if (directory.isEmpty() || !serves(directory.get(), scope))
			return Optional.empty();
		return Optional.of(requests(directory.get()));
	}

	/**
	 * Returns why a client finds no request server, naming the command that starts one.
	 *
	 * @param plugin the folder of the plugin that holds the client
	 * @return the reason
	 * @throws NullPointerException if {@code plugin} is null
	 */
	public static String missingServer(Path plugin)
	{
		return "No request server serves this session, and outside a nested session every session starts through it. " +
			"Start it in the background once, as a command of its own: " + program(plugin) + ". Then launch again.";
	}

	/**
	 * Returns why a command that a harness session runs outside a nested session cannot have its optimization worktree's
	 * registration changed, because no request server serves the session, naming the command that starts one.
	 *
	 * @param plugin the folder of the plugin that holds the command
	 * @return the reason
	 * @throws NullPointerException if {@code plugin} is null
	 */
	public static String missingWorktreeServer(Path plugin)
	{
		return "No request server serves this session, and the prompt optimizer adds, repairs and removes its " +
			"optimization worktrees through it, outside the session's sandbox. Start it in the background once, as a " +
			"command of its own: " + program(plugin) + ". Then run the command again.";
	}

	/**
	 * Sends a launch that a harness session's command makes outside a nested session to the session's request server,
	 * and waits for the requested session to end, as {@link
	 * SessionRequestForwarder#forward(SessionRequestForwarder.Route, String, String[], Reader, Path, Path, Pause,
	 * OutputStream, PrintWriter)} does. A launch that no request server serves is refused before it changes anything,
	 * naming the command that starts the server.
	 *
	 * @param harness the harness of the requested session
	 * @param args    the launch's options
	 * @param plugin  the folder of the plugin that holds the command
	 * @param scopes  opens the command's tool scope
	 * @param input   the launch's records when neither {@code --input} nor {@code --conversation} is given
	 * @param pause   paces checks for the session's progress
	 * @param output  receives the session's transcript when {@code --output} is absent
	 * @param errors  receives the reason when the launch is refused or the session does not complete
	 * @return the exit status, as the forwarder returns it, or {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if
	 *   no request server serves the session
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the launch's options are invalid
	 * @throws IOException              if the session has no CAT session directory, or the launch cannot be
	 *                                  forwarded
	 */
	public static int forward(String harness, String[] args, Path plugin, SessionScope scopes, Reader input,
		Pause pause, OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(scopes, "scopes").isNotNull();
		requireThat(errors, "errors").isNotNull();
		try (ToolScope tools = scopes.open())
		{
			Optional<Path> requests = servedRequests(tools);
			if (requests.isEmpty())
			{
				errors.println(missingServer(plugin));
				return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
			}
			return SessionRequestForwarder.forward(SessionRequestForwarder.Route.SERVER, harness, args, input,
				requests.get(), HarnessTemporaryRoot.from(tools).defaultResultsFolder(), pause, output, errors);
		}
	}

	/**
	 * Serves the session that runs the server until the session's harness process ends.
	 *
	 * @param args   the program's arguments, of which it takes none
	 * @param host   what the plugin that holds the server gives it
	 * @param errors receives the reason for a refusal, and a line once the server serves the session
	 * @return {@code 0} once the session's harness process has ended, or
	 *   {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if a check fails before the server serves anything
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the session's directories cannot be created or a request cannot be answered
	 */
	public static int serve(String[] args, Host host, PrintWriter errors) throws IOException
	{
		return serve(args, host, errors, Optional.empty());
	}

	/**
	 * Serves the owner session and its native comparison requests when the plugin supports them.
	 *
	 * @param args the server's command arguments
	 * @param host the serving plugin's native services
	 * @param errors receives refusals and lifecycle messages
	 * @param comparison binds a native comparison to the authenticated owner and selected runner
	 * @return the server's exit status
	 * @throws IOException if the server cannot serve the session or finish a request
	 */
	public static int serve(String[] args, Host host, PrintWriter errors, Optional<ComparisonHost> comparison)
		throws IOException
	{
		requireThat(args, "args").isNotNull();
		requireThat(host, "host").isNotNull();
		requireThat(errors, "errors").isNotNull();
		requireThat(comparison, "comparison").isNotNull();
		Optional<String> refusal = refusal(args, host);
		if (refusal.isPresent())
		{
			errors.println(refusal.get());
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		}
		String productName = productName(host.harness());
		try
		{
			requireOutsideSandbox(host.plugin(), host.sandboxed());
		}
		catch (SessionRefusedException e)
		{
			errors.println(e.getMessage());
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		}
		Session session;
		try
		{
			session = host.sessions().find();
		}
		catch (PolicyRefusal e)
		{
			errors.println(e.messageFor(productName));
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		}
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
		     ToolScope tools = host.scopes().apply(session.project()))
		{
			ChildBound bound;
			try
			{
				bound = new ChildBound(session.policy()).
					snapshot(session.transcript()).snapshot(host.plugin());
				bound.parentPolicy(session.askingThread());
			}
			catch (SessionRefusedException e)
			{
				errors.println(e.getMessage());
				return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
			}
			SessionDirectory directory = tools.requireSessionDirectory();
			Optional<ExclusiveFileLock> lock = lock(directory.directory().resolve(LOCK_FILE), tools, host.pause());
			if (lock.isEmpty())
			{
				errors.println("Another request server already serves this " + productName + " session, so " +
					"this one ends. The session's launches go to that server.");
				return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
			}
			try (ExclusiveFileLock _ = lock.get())
			{
				serve(session, directory, bound, host, errors, tools.fileLocks(), cleanup, comparison);
			}
		}
		return 0;
	}

	/**
	 * Supplies the authenticated owner and selected-runner boundary to the harness's comparison service.
	 */
	@FunctionalInterface
	public interface ComparisonHost
	{
		/**
		 * Binds one server instance's authenticated owner session.
		 *
		 * @param session the owner found by the serving harness
		 * @param bound the owner policy reader retaining its earlier readings
		 * @param runners selects the installed native runner
		 * @return the service handling the owner's comparison requests
		 */
		SessionRequestBroker.HookComparisonService open(Session session, ChildBound bound,
			RequestedRunner.Resolver runners);
	}

	/**
	 * Returns why the server refuses to start before it checks the session.
	 *
	 * @param args the program's arguments
	 * @param host what the plugin gives the server
	 * @return the reason, or empty if the server checks the session
	 */
	private static Optional<String> refusal(String[] args, Host host)
	{
		if (args.length > 0)
		{
			return Optional.of("The request server takes no arguments, but it was given " + String.join(" ", args) +
				". Run " + program(host.plugin()) + " as a command of its own.");
		}
		if (host.refusal().isPresent())
			return host.refusal();
		String productName = productName(host.harness());
		if (host.running().contains(host.harness()))
			return Optional.empty();
		Optional<String> other = host.running().stream().findFirst().map(RequestServer::productName);
		if (other.isPresent())
		{
			return Optional.of("This request server belongs to CAT's " + productName + " plugin, but a " +
				other.get() + " session runs it. Run the request server of CAT's " + other.get() + " plugin instead.");
		}
		return Optional.of("The request server serves the " + productName + " session whose agent starts it, " +
			"but no harness session runs it. Start it from that session.");
	}

	/**
	 * Takes the server's lock, retrying while a client holds it for a moment.
	 *
	 * @param file  the lock file
	 * @param tools the scope whose lock coordinator takes the lock
	 * @param pause paces the attempts
	 * @return the lock, or empty if another process still holds it after {@link #LOCK_WAIT}
	 * @throws IOException if the lock file cannot be opened or the wait is cut short
	 */
	private static Optional<ExclusiveFileLock> lock(Path file, ToolScope tools, Pause pause) throws IOException
	{
		long attempts = LOCK_WAIT.toMillis() / LOCK_RETRY.toMillis();
		for (long attempt = 0; attempt < attempts; ++attempt)
		{
			Optional<ExclusiveFileLock> lock = tryLock(file, tools, RequestServer::openLock);
			if (lock.isPresent())
				return lock;
			pause.pause();
		}
		return Optional.empty();
	}

	/**
	 * Tries once to take the server's lock.
	 *
	 * @param file   the lock file
	 * @param tools  the scope whose lock coordinator takes the lock
	 * @param opener opens the lock file
	 * @return the lock, or empty if a process, this one included, holds it
	 * @throws IOException if the lock file cannot be opened
	 */
	private static Optional<ExclusiveFileLock> tryLock(Path file, ToolScope tools, LockFileOpener opener)
		throws IOException
	{
		try
		{
			return tools.fileLocks().tryLock(file, opener);
		}
		catch (OverlappingFileLockException _)
		{
			// Another channel of this process holds the lock
			return Optional.empty();
		}
	}

	/**
	 * Opens the lock file without following a link in its place.
	 *
	 * @param file the lock file
	 * @return the channel
	 * @throws IOException if the file cannot be opened
	 */
	private static FileChannel openLock(Path file) throws IOException
	{
		return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * Serves the session's requests until its harness process ends, then stops every session that the server started
	 * and answers each request that remains with the reason {@code ended}.
	 *
	 * @param session   the session
	 * @param directory the session's CAT session directory
	 * @param bound     bounds each requested session by the session's policy
	 * @param host      what the plugin gives the server
	 * @param errors    receives a line once the server serves the session
	 * @param fileLocks coordinates startup and cleanup locks
	 * @param cleanup removes temporary policy probe directories
	 * @param comparison binds a native hook comparison if the serving plugin supports it
	 * @throws IOException if the request directories cannot be created or a request cannot be answered
	 */
	private static void serve(Session session, SessionDirectory directory, ChildBound bound, Host host,
		PrintWriter errors, FileLocks fileLocks, TemporaryDirectories cleanup, Optional<ComparisonHost> comparison)
		throws IOException
	{
		Path requests = directory(directory.directory().resolve(REQUESTS));
		Path responses = directory(directory.directory().resolve(RESPONSES));
		SessionSlots slots = new SessionSlots(directory.directory().resolve(RUNS), HarnessCapacity.MAXIMUM_CONCURRENCY,
			new ProcessHandleLiveness(), fileLocks);
		ServerSite site = new ServerSite(requests, responses, slots, fileLocks, bound, session.askingThread(), directory,
			new WorktreeService(new GitWorktreeRegistrar()), host.harness(), host.starter(), cleanup,
			comparison.map(service -> service.open(session, bound, host.starter())));
		try (SessionRequestBroker broker = new SessionRequestBroker(site))
		{
			errors.println("The request server serves this " + productName(host.harness()) +
				" session's launches until the session ends.");
			while (host.processes().isAlive(session.harness()))
			{
				broker.scan();
				host.pause().pause();
			}
		}
	}

	/**
	 * Creates a directory, or confirms that one, not a link, already holds its place.
	 *
	 * @param directory the directory
	 * @return {@code directory}
	 * @throws IOException if the directory cannot be created, or something other than a directory holds its place
	 */
	private static Path directory(Path directory) throws IOException
	{
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			Files.createDirectory(directory);
		return directory;
	}

	/**
	 * Confirms that the server runs outside the session's sandbox by creating and deleting a file in its plugin's
	 * folder. The folder belongs to the user, and the policy check that follows refuses a session whose sandbox lets
	 * its commands write it, so a file that can be created there shows that no sandbox confines the server.
	 *
	 * @param plugin    the folder of the plugin that holds the server
	 * @param sandboxed returns why the server runs inside the session's sandbox
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
	 * The site of a session whose request server serves its commands' requests.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 *
	 * @param requests     the directory into which the session's commands write their requests
	 * @param responses    the directory that holds one response directory per request
	 * @param slots        the slots, each a results folder, that the requested sessions take, which the server empties
	 *                     for each session
	 * @param fileLocks    coordinates access to slot startup records
	 * @param bound        bounds each requested session by the session's policy
	 * @param askingThread the thread of the session that runs the server, or empty for its main thread
	 * @param session      the CAT session directory of the session that runs the server, in the project that the
	 *                     session's transcript names
	 * @param worktrees    carries out the session's worktree requests
	 * @param ownerHarness the harness of the session this server serves
	 * @param runners      selects the installed runner for each requested harness
	 * @param cleanup      removes temporary policy probe directories
	 * @param hookComparison the authenticated hook comparison service, when available
	 */
	private record ServerSite(Path requests, Path responses, SessionSlots slots, FileLocks fileLocks, ChildBound bound,
		Optional<String> askingThread, SessionDirectory session, WorktreeService worktrees,
		String ownerHarness, RequestedRunner.Resolver runners, TemporaryDirectories cleanup,
		Optional<SessionRequestBroker.HookComparisonService> hookComparison)
		implements SessionRequestBroker.Site
	{
		/**
		 * Carries out a worktree request on a campaign of the session's own {@code optimizer} directory, once the
		 * session's policy shows that its commands cannot write the repository's configuration or hooks.
		 *
		 * @param request the request
		 * @return the response
		 */
		@Override
		public WorktreeResponse worktree(WorktreeRequest request)
		{
			ParentPolicy policy;
			try
			{
				policy = bound.parentPolicy(askingThread);
			}
			catch (SessionRefusedException e)
			{
				return WorktreeResponse.refused(String.valueOf(e.getMessage()));
			}
			return worktrees.serve(request, session.projectRoot(), session.campaigns(), Optional.of(policy));
		}

		@Override
		public RequestLimits limits()
		{
			// The session packs each request inside its own sandbox, from files that it may read
			return RequestLimits.UNBOUNDED;
		}

		@Override
		public LaunchRequest read(JsonMapper json, Path inputs) throws IOException
		{
			return RequestArchive.readServed(json, inputs);
		}

		@Override
		public Optional<String> refusal(int accepted)
		{
			// The session's own requests have no caller that limits them
			return Optional.empty();
		}

		@Override
		public int acceptedBefore()
		{
			// The session's own requests have no caller that limits them, so nothing counts them
			return 0;
		}

		@Override
		public void accepted(JsonMapper json, int accepted)
		{
			// No caller limits the session's requests, so there is nothing to state
		}

		@Override
		public SessionSlots slots()
		{
			return slots;
		}

		/**
		 * Applies the request's own choices of the session's requests, capacity wait, concurrency, source home and gateway
		 * login class, and bounds the session by the intersection of the session's policy and what the new session needs,
		 * which refuses a session whose credential is in a file that the session's policy denies reading.
		 *
		 * @param request       the request
		 * @param resultsFolder the request's results folder
		 * @return the prepared launch
		 * @throws SessionRefusedException if the session's policy cannot be read safely or does not allow what the new
		 *                                 session needs
		 * @throws IOException             if what the new session needs cannot be determined, or the bound cannot be
		 *                                 written
		 */
		@Override
		public RequestedRunner.Start prepare(LaunchRequest request, Path resultsFolder) throws IOException
		{
			RequestedRunner.Selection selected = runners.select(request.harness());
			List<String> options = new ArrayList<>();
			request.capacityWait().ifPresent(seconds ->
				options.addAll(List.of("--capacity-wait", String.valueOf(seconds))));
			request.maximumConcurrency().ifPresent(maximum ->
				options.addAll(List.of("--max-concurrency", String.valueOf(maximum))));
			if (request.sessionRequests() > 0)
				options.addAll(List.of("--session-requests", String.valueOf(request.sessionRequests())));
			if (request.requestDepth() > 0)
				options.addAll(List.of("--request-depth", String.valueOf(request.requestDepth())));
			options.addAll(request.authentication().arguments());
			ChildPolicy child = bound.bound(request.harness(), resultsFolder, request.deniedReads(),
				request.extraPermissions(), askingThread, runnerNeeds(selected.start(), request.authentication(), cleanup),
				policies ->
				{
					if (!ownerHarness.equals(request.harness()))
						selected.requireTrusted(policies);
				});
			Path folder = Files.createTempDirectory(Files.createDirectories(resultsFolder), ".policy-bound-");
			List<String> added = PolicyBoundFile.boundArguments(JsonMapper.builder().build(), options, folder, child);
			return (base, directory) ->
			{
				List<String> arguments = new ArrayList<>(base);
				arguments.addAll(added);
				return selected.start().run(arguments, directory);
			};
		}

		@Override
		public String closedStatus()
		{
			return "failed";
		}

		@Override
		public String closedReason()
		{
			return "ended";
		}

		@Override
		public String unknownSession(String sessionId) throws IOException
		{
			// Only the session that started a nested session can resume it, so name the session whose server retains it
			Path own = session.directory();
			Path sessions = own.getParent();
			if (sessions != null && Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS))
			{
				List<Path> siblings;
				try (Stream<Path> entries = Files.list(sessions))
				{
					siblings = entries.sorted().toList();
				}
				for (Path sibling : siblings)
				{
					if (sibling.equals(own) || !Files.isDirectory(sibling.resolve(RUNS), LinkOption.NOFOLLOW_LINKS))
						continue;
					SessionSlots other = new SessionSlots(sibling.resolve(RUNS), HarnessCapacity.MAXIMUM_CONCURRENCY,
						new ProcessHandleLiveness());
					if (other.retains(sessionId))
					{
						return "The session " + sessionId + " was interrupted in a nested session that the session " +
							sibling.getFileName() + " started, and only that session can resume it";
					}
				}
			}
			return "No interrupted nested session that this session started records the session " + sessionId + ", so it " +
				"cannot be resumed";
		}

		@Override
		public void taken(Path file) throws IOException
		{
			// The response directory marks the request as taken until the requester removes it with its results, so a
			// request that stayed would be taken again once its response is gone
			Files.deleteIfExists(file);
		}
	}
}

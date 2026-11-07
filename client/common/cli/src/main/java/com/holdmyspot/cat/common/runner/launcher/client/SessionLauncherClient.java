package com.holdmyspot.cat.common.runner.launcher.client;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Request;
import com.holdmyspot.cat.common.runner.launcher.client.RequestExchange.Destinations;
import com.holdmyspot.cat.common.runner.launcher.client.RequestExchange.Outcome;
import com.holdmyspot.cat.common.runner.launcher.client.RequestExchange.Sink;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts a session by asking the session launcher of the harness process that runs this command, which runs outside
 * the command's sandbox, to start it.
 * <p>
 * A runner launcher that a sandboxed session runs cannot start a harness itself. When its own plugin's harness runs the
 * command, outside a run, and except on native Windows, it sends its launch to the launcher as a session-level
 * request instead, as its {@link com.holdmyspot.cat.common.runner.LaunchRoute} decides: it packs the launch's project,
 * plugins, workspace and evidence as the session sees them, proves with a holder process that the request comes from
 * the launcher's own session, and follows the child's records until the launcher answers. The launcher starts the
 * child's runner with {@value #POLICY_BOUND_OPTION}, and a launch that names that option runs in place.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class SessionLauncherClient
{
	/**
	 * The option with which a session launcher starts a child's runner, naming the file of the child's sandbox
	 * policy. A launch that names it runs in place instead of becoming a request.
	 */
	public static final String POLICY_BOUND_OPTION = "--policy-bound";
	/**
	 * The product names that the no-launcher message uses when no harness session variable names the harness that
	 * runs the command.
	 */
	private static final String ANY_PRODUCT = "Claude Code or Codex";
	/**
	 * Reads the project's Git identity.
	 */
	private final GitIdentity git;
	/**
	 * The identifier that each harness gives the thread that runs the command, by harness identifier.
	 */
	private final Map<String, String> askingThreads;
	/**
	 * Sends the requests.
	 */
	private final RequestExchange exchange;
	/**
	 * Reads the files of extra permissions.
	 */
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates a client.
	 *
	 * @param channel        the channel to the launcher
	 * @param sessionProduct the product name of the harness that runs the command, as its users know it, which the
	 *                       message names when no launcher serves the session
	 * @param holders        starts the holder of each message
	 * @param git            reads the project's Git identity
	 * @param clock          the time source for the waits
	 * @param pause          paces the checks for the launcher's progress
	 * @param askingThreads  the identifier that each harness gives the thread that runs the command, by harness
	 *                       identifier, which a launch through that harness's runner sends as its asking thread
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code sessionProduct} is blank
	 */
	public SessionLauncherClient(SessionLauncherChannel channel, String sessionProduct, HolderStarter holders,
		GitIdentity git, Clock clock, Pause pause, Map<String, String> askingThreads)
	{
		requireThat(channel, "channel").isNotNull();
		requireThat(sessionProduct, "sessionProduct").isNotBlank();
		requireThat(holders, "holders").isNotNull();
		requireThat(git, "git").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(pause, "pause").isNotNull();
		requireThat(askingThreads, "askingThreads").isNotNull();
		this.git = git;
		this.askingThreads = Map.copyOf(askingThreads);
		this.exchange = new RequestExchange(channel, sessionProduct, holders, clock, pause, json);
	}

	/**
	 * Returns the client that serves a process: it sends requests to the launchers below the user's home directory,
	 * starts real holder processes and reads Git's configuration.
	 *
	 * @param environment the process environment
	 * @param waiting     paces the client's checks of the launcher's files
	 * @return the client
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the user's home directory cannot be found
	 */
	public static SessionLauncherClient production(Map<String, String> environment, Waiting waiting)
		throws IOException
	{
		return production(environment, waiting, GitIdentity.fromGit());
	}

	/**
	 * Returns the client that serves a process, as {@link #production(Map, Waiting)} does, but reading the project's
	 * Git identity with the given reader instead of Git's configuration.
	 *
	 * @param environment the process environment
	 * @param waiting     paces the client's checks of the launcher's files
	 * @param git         reads the project's Git identity
	 * @return the client
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the user's home directory cannot be found
	 */
	public static SessionLauncherClient production(Map<String, String> environment, Waiting waiting, GitIdentity git)
		throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(git, "git").isNotNull();
		Path home = Platform.current().homeDirectory(environment);
		return new SessionLauncherClient(new SessionLauncherChannel(home), sessionProduct(environment),
			HolderStarter.processes(), git, Clock.systemUTC(), waiting.every(SessionLauncherChannel.INBOX_POLL),
			askingThreads(environment, SessionHarness.installed()));
	}

	/**
	 * Returns the identifier that each harness gives the thread that runs the command: the value of the harness's
	 * {@link SessionHarness#sessionVariable() session variable}.
	 *
	 * @param environment the process environment
	 * @param harnesses   the harnesses to look for
	 * @return the identifier of the thread, by the identifier of each harness whose session variable is set and not
	 *         blank
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Map<String, String> askingThreads(Map<String, String> environment, List<SessionHarness> harnesses)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(harnesses, "harnesses").isNotNull();
		Map<String, String> threads = new HashMap<>();
		for (SessionHarness harness : harnesses)
		{
			String thread = environment.get(harness.sessionVariable());
			if (thread != null && !thread.isBlank())
				threads.put(harness.id(), thread);
		}
		return threads;
	}

	/**
	 * Returns the product name of the harness whose session runs the command.
	 *
	 * @param environment the process environment
	 * @return the name of the harness whose session variable is set, or both names if none is
	 */
	private static String sessionProduct(Map<String, String> environment)
	{
		for (SessionHarness harness : SessionHarness.installed())
		{
			String session = environment.get(harness.sessionVariable());
			if (session != null && !session.isBlank())
				return Harness.fromCommandName(harness.id()).map(Harness::productName).orElse(harness.id());
		}
		return ANY_PRODUCT;
	}

	/**
	 * Sends a runner launcher's launch to the session launcher with the process's own client, and waits for it to
	 * end.
	 *
	 * @param harness     the harness whose runner the launcher stands for
	 * @param args        the launch's options
	 * @param environment the process environment
	 * @param waiting     the process's waiting, which blocks the calling thread
	 * @param input       the launch's records when it names no {@code --input}
	 * @param output      receives the child's records when the launch names no {@code --output}
	 * @param errors      receives why the launch did not complete
	 * @return the launch's exit status, as {@link #launch} returns it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the launch's options are invalid
	 * @throws IOException              if the request cannot be sent or followed
	 */
	public static int launchFromMain(String harness, String[] args, Map<String, String> environment,
		Waiting waiting, Reader input, OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		Path workingDirectory = Path.of("").toAbsolutePath();
		try (MainJvmScope process = new MainJvmScope(environment, waiting);
			ToolScope scope = ToolScope.forSession(workingDirectory, environment, SessionHarness.installed(),
				process.waiting()))
		{
			return production(environment, waiting).launch(harness, args, workingDirectory, HarnessTemporaryRoot.from(scope),
				input, output, errors);
		}
	}

	/**
	 * Returns why the session launcher would refuse every session at once, without sending a request or starting a
	 * session.
	 *
	 * @return the message that names the reason and the user's remedy, if no launcher serves the session or the
	 *   launchers' state cannot be read; empty if a launcher may answer a request
	 * @throws IOException if the launchers' state cannot be listed for another reason
	 */
	public Optional<String> refusal() throws IOException
	{
		return exchange.refusal().map(Outcome::message);
	}

	/**
	 * Sends a runner launcher's launch to the session launcher and waits for it to end. A launch that names
	 * {@code --artifact} is refused, as the runner launcher refuses it. The child's records go to {@code --output},
	 * or to {@code output}; its receipt goes to {@code --receipt}; with {@code --keep-run}, its run is copied into
	 * the run root, as {@code <run root>/<harness>/<run id>}, without links and without the child's isolated home. A
	 * file of the run that cannot be copied ends the copy; the launch then reports the file, the error and the
	 * incomplete run after the session's own outcome.
	 *
	 * @param harness          the harness whose runner the launcher stands for
	 * @param args             the launch's options
	 * @param workingDirectory the absolute directory against which relative paths resolve
	 * @param temporaryRoot    where the run is kept when the launch names no {@code --run-root}
	 * @param input            the launch's records when it names no {@code --input}
	 * @param output           receives the child's records when the launch names no {@code --output}
	 * @param errors           receives why the launch did not complete
	 * @return {@code 0} if the child completed; {@value HarnessRunnerOptions#REFUSED_LAUNCH_STATUS} if the launch was
	 *   refused, no launcher serves the session, the launchers' state cannot be read, or the launcher refused the
	 *   request; {@code 75} if the child found no capacity; {@code 1} if the launcher stopped, the child failed, or
	 *   the child completed but its kept run could not be copied in full. When the session ended with another status,
	 *   a failed copy leaves that status unchanged, and its message follows the status's reason.
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if the launch's options are invalid
	 * @throws IOException              if the request cannot be sent or followed, or its records or receipt cannot
	 *                                  be written
	 */
	public int launch(String harness, String[] args, Path workingDirectory, HarnessTemporaryRoot temporaryRoot,
		Reader input, OutputStream output, PrintWriter errors) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(args, "args").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(errors, "errors").isNotNull();
		if (HarnessRunnerOptions.refusesOverride(harness, args, errors))
			return HarnessRunnerOptions.REFUSED_LAUNCH_STATUS;
		LaunchOptions options = LaunchOptions.parse(args, workingDirectory);
		if (options.receipt.isPresent() && (!options.keepRun || options.output.isEmpty()))
			throw new InvalidArgumentsException("--receipt requires --keep-run and --output");
		Optional<Outcome> refusal = exchange.refusal();
		if (refusal.isPresent())
			return report(refusal.get(), errors);
		Request request = request(harness, options, options.plugins, false);
		Optional<Path> runRoot = Optional.empty();
		if (options.keepRun)
			runRoot = Optional.of(options.runRoot.orElseGet(temporaryRoot::defaultRunRoot));
		Destinations destinations = new Destinations(runRoot, options.receipt);
		Optional<Reader> turns = Optional.of(input).filter(_ -> options.input.isEmpty());
		Outcome outcome;
		if (options.output.isPresent())
		{
			Path file = options.output.get();
			Path parent = file.getParent();
			if (parent != null)
				Files.createDirectories(parent);
			try (OutputStream records = Files.newOutputStream(file))
			{
				outcome = exchange.exchange(request, turns, sink(records), destinations);
			}
		}
		else
			outcome = exchange.exchange(request, turns, sink(output), destinations);
		return report(outcome, errors);
	}

	/**
	 * Returns the bridge through which the prompt optimizer runs its sessions as requests to the session launcher.
	 * The optimizer streams each turn after the replies to the one before, holds each session's capacity slot itself,
	 * and keeps every run. It names the CAT artifact that a trial installs with {@code --artifact}: unless that is
	 * {@code ownArtifact}, which the launcher's runner installs as CAT anyway, the request carries it as its first
	 * plugin. An interrupt of the thread that serves the bridge ends the request, which stops the child. A session that
	 * no launcher serves, or that the launcher refuses, ends with a {@link LaunchRefusedException}.
	 *
	 * @param harness     the harness whose runner the bridge stands for
	 * @param ownArtifact the CAT artifact that holds the bridge
	 * @return the bridge
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	public Bridge bridge(String harness, Path ownArtifact)
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(ownArtifact, "ownArtifact").isNotNull();
		return (arguments, input, output) ->
		{
			LaunchOptions options = LaunchOptions.parse(arguments.toArray(String[]::new),
				Path.of("").toAbsolutePath());
			if (options.runRoot.isEmpty() || !options.keepRun)
				throw new IllegalArgumentException("The optimizer's sessions name --run-root and --keep-run");
			List<Path> plugins = new ArrayList<>();
			if (options.installCat && options.artifact.isPresent() &&
				!isSame(options.artifact.get(), ownArtifact))
				plugins.add(options.artifact.get());
			plugins.addAll(options.plugins);
			Request request = request(harness, options, plugins, true);
			Optional<Outcome> refusal = exchange.refusal();
			if (refusal.isPresent())
				throw new LaunchRefusedException(refusal.get().message());
			Outcome outcome = exchange.exchange(request, Optional.of(input), sink(output),
				new Destinations(options.runRoot, Optional.empty()));
			if (outcome.exitStatus() == SessionLauncherChannel.Status.REFUSED.exitStatus())
				throw new LaunchRefusedException(outcome.message());
			if (outcome.exitStatus() != 0)
				throw new IOException(outcome.message());
		};
	}

	/**
	 * Indicates whether two paths name the same file.
	 *
	 * @param first  a path
	 * @param second another path
	 * @return {@code true} if both exist and are one file, or neither exists and their normalized forms are equal
	 * @throws IOException if the files cannot be compared
	 */
	private static boolean isSame(Path first, Path second) throws IOException
	{
		if (Files.exists(first) && Files.exists(second))
			return Files.isSameFile(first, second);
		return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
	}

	/**
	 * Builds the request of a launch.
	 *
	 * @param harness      the child's harness, whose runner the launch stands for; the request names the thread that
	 *                     this harness gives the command as the asking thread
	 * @param options      the launch's options
	 * @param plugins      the plugins that the request carries
	 * @param capacityHeld indicates whether the sender holds the child's capacity slot
	 * @return the request
	 * @throws IllegalArgumentException if the options do not form a valid request, or name a file of extra
	 *                                  permissions that is missing or invalid
	 * @throws IOException              if the project's Git identity cannot be read
	 */
	private Request request(String harness, LaunchOptions options, List<Path> plugins, boolean capacityHeld)
		throws IOException
	{
		return new Request(harness, options.model, options.effort, options.agent, options.input, options.project,
			plugins, options.workspace, options.readOnlyEvidence, options.installCat, options.sessionTranscripts,
			options.keepRun, options.sessionRequests, options.requestDepth, options.capacityWait,
			options.maximumConcurrency, capacityHeld, options.instructionFiles, options.toolOutputTokenLimit,
			options.deniedReads, HarnessRunnerOptions.readExtraPermissions(json, options.extraPermissions.orElse(null)),
			git.name(options.project), git.email(options.project),
			Optional.ofNullable(askingThreads.get(harness)));
	}

	/**
	 * Prints why a launch did not complete.
	 *
	 * @param outcome how the launch ended
	 * @param errors  receives the reason
	 * @return the launch's exit status
	 */
	private static int report(Outcome outcome, PrintWriter errors)
	{
		if (!outcome.message().isEmpty())
		{
			errors.println(outcome.message());
			errors.flush();
		}
		return outcome.exitStatus();
	}

	/**
	 * Returns a sink that writes each record as a UTF-8 line.
	 *
	 * @param output the stream
	 * @return the sink
	 */
	private static Sink sink(OutputStream output)
	{
		return line ->
		{
			output.write((line + "\n").getBytes(UTF_8));
			output.flush();
		};
	}

	/**
	 * Returns a sink that writes each record as a line.
	 *
	 * @param output the writer
	 * @return the sink
	 */
	private static Sink sink(Writer output)
	{
		return line ->
		{
			output.write(line + "\n");
			output.flush();
		};
	}
}

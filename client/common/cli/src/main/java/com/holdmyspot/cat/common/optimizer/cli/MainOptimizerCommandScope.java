package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.InstalledCopies;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.execution.CapCalibrator;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramEvaluation;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibration;
import com.holdmyspot.cat.common.optimizer.workspace.Forks;
import com.holdmyspot.cat.common.optimizer.workspace.GitForks;
import com.holdmyspot.cat.common.optimizer.workspace.GitWorktreeRegistrar;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRefusedException;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.CheckProgramClient;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessMarkers;
import com.holdmyspot.cat.common.runner.HarnessSandbox;
import com.holdmyspot.cat.common.runner.LaunchRoute;
import com.holdmyspot.cat.common.runner.NestedSessionChannel;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.RetainedNestedSessionClient;
import com.holdmyspot.cat.common.runner.RetainedTrialRequest;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;
import com.holdmyspot.cat.common.runner.SandboxCheckClient;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.WorktreeRequestClient;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import io.github.cowwoc.pouch10.core.LazyReference;
import io.github.cowwoc.pouch10.core.Reference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The production {@link OptimizerCommandScope} that both harnesses share: real Git forks, the real cap calibration,
 * the system clock, a real process for the harness's version, the JSON mapper and the CAT session directory of the host
 * session that the process environment names. Each harness's scope adds the sessions and the host interrupt signal
 * that it resolves from the process environment.
 */
public abstract class MainOptimizerCommandScope extends AbstractOptimizerCommandScope
{
	/**
	 * The launcher's name in the runtime image's {@code bin} directory.
	 */
	private static final String LAUNCHER = "prompt-optimizer";
	/**
	 * The time allowed for broker observations before a spent-allowance command keeps its original refusal.
	 */
	private static final Duration RETAINED_TRIAL_PREFLIGHT_WAIT = Duration.ofSeconds(5);
	private final Harness harness;
	private final HarnessSandbox trialSandbox;
	private final SessionHarness sessionHarness;
	private final HarnessMarkers markers;
	private final Map<String, String> environment;
	private final Path configurationDirectory;
	private final Path directory;
	private final List<Path> loaderConfiguration;
	/**
	 * The command's choice of the source home and gateway login class, which every session that the scope's ports
	 * start receives.
	 */
	private final AuthenticationOptions authentication;
	private final Reference<RandomGenerator> random = LazyReference.create(RandomGenerator::getDefault);
	private final Reference<FileReplacer> fileReplacer;
	private final Object forksLock = new Object();
	/**
	 * The forks, which the first call to {@link #forks()} creates; guarded by {@code forksLock}.
	 */
	private Forks forks;

	/**
	 * Creates an open scope.
	 *
	 * @param harness                the harness that runs the launcher
	 * @param trialSandbox           decides whether {@code harness} sandboxes the campaign's trials on a platform
	 * @param sessionHarness         the parts of the harness's CAT session directories
	 * @param markers                reads which harnesses' sessions run a command from its environment
	 * @param environment            the process environment, whose session variable names the host session
	 * @param configurationDirectory the host harness's configuration directory
	 * @param directory              the launcher's working directory, below the project
	 * @param loaderConfiguration    the files, relative to the project, that decide which prompt files the harness
	 *                               loads
	 * @param waiting                how the process waits between the attempts of a retried operation
	 * @param authentication         the command's choice of the source home and gateway login class, which every
	 *                               session that the scope's ports start receives
	 * @throws NullPointerException     if any argument is null
	 */
	protected MainOptimizerCommandScope(Harness harness, HarnessSandbox trialSandbox, SessionHarness sessionHarness,
		HarnessMarkers markers, Map<String, String> environment, Path configurationDirectory, Path directory,
		List<Path> loaderConfiguration, Waiting waiting, AuthenticationOptions authentication)
	{
		super(new MainJvmScope(environment, waiting));
		this.fileReplacer = LazyReference.create(() -> FileReplacer.retrying(waiting));
		requireThat(harness, "harness").isNotNull();
		requireThat(trialSandbox, "trialSandbox").isNotNull();
		requireThat(sessionHarness, "sessionHarness").isNotNull();
		requireThat(markers, "markers").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(configurationDirectory, "configurationDirectory").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(loaderConfiguration, "loaderConfiguration").isNotNull();
		this.harness = harness;
		this.trialSandbox = trialSandbox;
		this.sessionHarness = sessionHarness;
		this.markers = markers;
		this.environment = Map.copyOf(environment);
		this.configurationDirectory = configurationDirectory;
		this.directory = directory;
		this.loaderConfiguration = List.copyOf(loaderConfiguration);
		requireThat(authentication, "authentication").isNotNull();
		this.authentication = authentication;
	}

	/**
	 * Returns the command's choice of the source home and gateway login class, which every session that the scope's
	 * ports start receives.
	 *
	 * @return the choice
	 */
	@Override
	protected final AuthenticationOptions authentication()
	{
		return authentication;
	}



	@Override
	public final FileReplacer fileReplacer()
	{
		ensureOpen();
		return fileReplacer.getValue();
	}

	@Override
	public final Path directory()
	{
		ensureOpen();
		return directory;
	}

	/**
	 * Returns the {@code prompt-optimizer} launcher of the runtime image that runs this command: the launcher in the
	 * {@code bin} directory beside the image's Java home, with the {@code .cmd} suffix on Windows.
	 *
	 * @return the launcher's absolute path
	 * @throws IllegalStateException if this scope is closed
	 */
	@Override
	public final Path launcher()
	{
		ensureOpen();
		String name = LAUNCHER;
		if (Platform.current().isWindows())
			name += ".cmd";
		return PluginDirectory.of(MainOptimizerCommandScope.class).resolve("bin").resolve(name);
	}

	/**
	 * Returns the installed evaluator's already resolved native configuration directory.
	 *
	 * @return the path that the native evaluator uses for its configuration
	 */
	final Path evaluatorConfigurationDirectory()
	{
		ensureOpen();
		return configurationDirectory;
	}

	@Override
	protected final SessionPort evaluatorInspection(Path project, Path results)
	{
		ensureOpen();
		RunnerSessions.Settings settings = new RunnerSessions.Settings(this, plugin(), results, List::of, true,
			environment, configurationDirectory, HarnessCapacity.unlimited(), clock(), fileReplacer(),
			Optional.of(ProjectRuleLoader.rulesDirectory(home())), project, authentication);
		return evaluatorSessions(settings, (_, _, _) ->
		{
			throw new IOException("Metadata inspection cannot start a session");
		});
	}

	@Override
	protected final Optional<String> hookComparisonRefusal(Harness evaluator)
	{
		ensureOpen();
		if (evaluator == Harness.CODEX)
			return Optional.of("CAT could not verify native Codex approval for the exact startup hooks in the " +
				"staged project. The comparison stopped before executing the hooks.");
		return Optional.empty();
	}

	@Override
	final Optional<HookCheck.PeerComparator> peerHookComparison(Harness evaluator)
	{
		ensureOpen();
		if (harness != Harness.CLAUDE || evaluator != Harness.CODEX || !Platform.current().isLinux())
			return Optional.empty();
		return Optional.of(request ->
		{
			try (WorktreeRequestClient.Channel owner = worktreeChannel().orElseThrow(() ->
				new IOException("The campaign owner has no request broker for the Codex hook comparison")))
			{
				if (!(owner instanceof ServerChannel))
					throw new IOException("Codex hook comparison requires the top-level Claude Code request server");
				return request.compare(json(), owner, waiting().every(SessionRequestForwarder.RESPONSE_POLL));
			}
		});
	}

	@Override
	protected final SessionPort peerTrialSessions(Harness evaluator, EvaluatorMetadata metadata) throws IOException
	{
		ensureOpen();
		if (evaluator == harness || metadata.harness() != evaluator)
			throw new IOException("The trial metadata does not identify the requested peer evaluator");
		Path artifact = peerEvaluatorArtifact(evaluator);
		try (WorktreeRequestClient.Channel owner = worktreeChannel().orElseThrow(() ->
			new IOException("The campaign owner has no request broker for the evaluator")))
		{
			SessionRequestForwarder.Route route;
			if (owner instanceof ServerChannel)
				route = SessionRequestForwarder.Route.SERVER;
			else
				route = SessionRequestForwarder.Route.RUN;
			return new PeerTrialSessions(this, artifact, metadata, route, owner.requests(), hostProject(),
				resultsFolder(), this::heldOutDirectories, evaluatorCapacity(evaluator), retainedTrials());
		}
	}

	/**
	 * Creates the installed evaluator's trial adapter using the caller's trusted owner channel.
	 *
	 * @param launch the origin and results of the owner-selected trial
	 * @return an adapter that launches from this installation
	 */
	final SessionPort evaluatorTrialSessions(PeerTrialProtocol.Launch launch)
	{
		ensureOpen();
		RunnerSessions.Settings settings = new RunnerSessions.Settings(this, plugin(), launch.results(),
			launch::heldOut, true, environment, configurationDirectory, evaluatorCapacity(harness), clock(),
			fileReplacer(), Optional.of(ProjectRuleLoader.rulesDirectory(home())), launch.project(), authentication);
		Bridge bridge = RunnerSessions.evaluatorBridge(settings, harness.commandName(), launch.route(), launch.requests());
		return evaluatorSessions(settings, bridge);
	}

	/**
	 * Finds the shared capacity and provider hold of the given native evaluator.
	 *
	 * @param evaluator the harness whose capacity is shared
	 * @return its capacity ledger
	 */
	private HarnessCapacity evaluatorCapacity(Harness evaluator)
	{
		return HarnessCapacity.forHome(fileLocks(), home(), evaluator.commandName(), HarnessCapacity.MAXIMUM_CONCURRENCY,
			waiting());
	}

	/**
	 * Creates the installed harness's adapter.
	 *
	 * @param settings the evaluator configuration
	 * @param bridge   the adapter's launch bridge
	 * @return the native optimizer adapter
	 */
	protected abstract SessionPort evaluatorSessions(RunnerSessions.Settings settings, Bridge bridge);

	/**
	 * Reads the policies of the authenticated owner that starts this optimizer command, when the command runs outside
	 * the owner's sandbox and so can run check programs itself.
	 *
	 * @return the initial and current policy readings used to bound its check programs, or empty if the command runs
	 *   inside the owner's sandbox, where a check program's own sandbox cannot start and the receiver that serves the
	 *   owner's requests runs the programs instead
	 * @throws IOException   if the owner or its policy cannot be read
	 * @throws PolicyRefusal if the owner or its policy cannot be authenticated
	 */
	protected abstract Optional<List<ParentPolicy>> unsandboxedCheckProgramPolicies() throws IOException, PolicyRefusal;

	@Override
	public final CheckProgramEvaluation checkPrograms()
	{
		return (test, assertionIds, testCase, record, runs) ->
		{
			ensureOpen();
			try
			{
				List<Path> heldOut = new ArrayList<>(heldOutDirectories());
				Path heldOutResults = RunnerSessions.Settings.heldOutResultsFolder(resultsFolder());
				heldOut.add(Files.createDirectories(heldOutResults).toRealPath());
				Optional<List<ParentPolicy>> policies = unsandboxedCheckProgramPolicies();
				if (policies.isEmpty())
				{
					CheckProgramClient client = new CheckProgramClient(this::worktreeChannel, json(),
						waiting().every(SessionRequestForwarder.RESPONSE_POLL));
					return CheckProgramEvaluation.requested(client, json(), temporaryDirectory(), heldOut).run(test,
						assertionIds, testCase, record, runs);
				}
				CheckProgram.Launch launch = CheckProgram.ownerLaunch(Platform.current(), environment, policies.get(),
					heldOut);
				return CheckProgramEvaluation.isolated(launch, json(), temporaryDirectory()).run(test, assertionIds,
					testCase, record, runs);
			}
			catch (PolicyRefusal e)
			{
				throw new IOException(e.messageFor(harness.productName()), e);
			}
		};
	}

	/**
	 * Returns the ID of the host session that the harness's session variable names.
	 *
	 * @return the ID, without surrounding whitespace
	 * @throws CommandRefusedException if the session variable is not set
	 */
	private String hostSessionId() throws CommandRefusedException
	{
		String sessionId = environment.get(sessionHarness.sessionVariable());
		if (sessionId == null || sessionId.isBlank())
		{
			throw new CommandRefusedException("A campaign belongs to the " + harness.commandName() + " session that " +
				"runs it, but " + sessionHarness.sessionVariable() + " is not set. Run this command from a tool shell " +
				"of a " + harness.commandName() + " session.");
		}
		return sessionId.strip();
	}

	/**
	 * Resolves the CAT session directory of the host session that the harness's session variable names, without
	 * creating it.
	 *
	 * @param projectRoot the project root
	 * @return the directory, which may not exist
	 * @throws CommandRefusedException if the session variable is not set
	 * @throws IOException if the session variable does not hold a session ID
	 */
	@Override
	protected final SessionDirectory resolveSessionDirectory(Path projectRoot)
		throws CommandRefusedException, IOException
	{
		return SessionDirectory.resolve(projectRoot, hostSessionId(), json());
	}

	/**
	 * Opens the CAT session directory of the host session that the harness's session variable names.
	 *
	 * @param projectRoot the project root
	 * @return the directory
	 * @throws CommandRefusedException if the session variable is not set
	 * @throws IOException if the session variable does not hold a session ID, or the directory cannot be created
	 */
	@Override
	protected final SessionDirectory openSessionDirectory(Path projectRoot)
		throws CommandRefusedException, IOException
	{
		return SessionDirectory.open(projectRoot, hostSessionId(), sessionHarness, configurationDirectory,
			clock().instant(), json());
	}

	@Override
	protected final Harness harness()
	{
		ensureOpen();
		return harness;
	}

	@Override
	protected final HarnessSandbox trialSandbox()
	{
		ensureOpen();
		return trialSandbox;
	}

	/**
	 * Returns what asks the session that runs this command, by the route that carries its worktree requests, whether
	 * it runs its commands in a sandbox. A route that refuses the command's requests, or a session that no request
	 * server serves, refuses every launch on its own, so it adds no reason here.
	 *
	 * @return what says why the session cannot start trials
	 */
	@Override
	protected final OwnerSandbox ownerSandbox()
	{
		ensureOpen();
		return () ->
		{
			try
			{
				return new SandboxCheckClient(this::worktreeChannel, json(),
					waiting().every(SessionRequestForwarder.RESPONSE_POLL)).refusal();
			}
			catch (WorktreeRefusedException _)
			{
				return Optional.empty();
			}
		};
	}

	/**
	 * Returns why this command is refused when no harness session runs it: the environment holds no marker of Claude
	 * Code or Codex, as in a terminal. A session variable without its harness's marker does not make a harness run
	 * the command.
	 *
	 * @return the message that tells the user to run the command from a Claude Code or Codex session, or empty if a
	 *   harness session runs the command
	 * @throws IllegalStateException if this scope is closed
	 */
	@Override
	protected final Optional<String> noHarnessRefusal()
	{
		ensureOpen();
		if (!markers.running(environment).isEmpty())
			return Optional.empty();
		return Optional.of("The prompt optimizer runs only from a Claude Code or Codex session, and no harness " +
			"session runs this command. Run it from a Claude Code or Codex session.");
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Inside a nested session whose runner lets its session request sessions, the command's sessions are requests to
	 * that runner, so the command is also refused when the nested session's limits, as its runner states them, allow no
	 * further session.
	 */
	@Override
	protected final Optional<String> nestedSessionRefusal()
	{
		ensureOpen();
		Optional<String> refusal = HarnessEnvironment.nestedSessionRefusal(RunnerSessions.COMMAND, environment);
		if (refusal.isPresent())
			return refusal;
		if (!(launchRoute() instanceof LaunchRoute.Forward(Path requests)))
			return Optional.empty();
		try
		{
			return SessionRequestAccess.furtherRequestRefusal(requests).
				map(reason -> SessionRequestAccess.refusalMessage(RunnerSessions.COMMAND, reason));
		}
		catch (IOException _)
		{
			// The nested session's runner still refuses each session that its limits do not allow, which pauses the campaign
			return Optional.empty();
		}
	}

	@Override
	protected final Optional<RetainedTrialLookup> retainedTrialPreflight() throws IOException
	{
		ensureOpen();
		if (!(launchRoute() instanceof LaunchRoute.Forward(Path requests)))
			return Optional.empty();
		Optional<String> refusal = SessionRequestAccess.furtherRequestRefusal(requests);
		if (refusal.isEmpty() || refusal.get().equals(SessionRequestAccess.NO_REQUESTS_REASON))
			return Optional.empty();
		Instant deadline = clock().instant().plus(RETAINED_TRIAL_PREFLIGHT_WAIT);
		Pause pause = waiting().every(SessionRequestForwarder.RESPONSE_POLL);
		RetainedNestedSessionClient client = retainedTrials(() ->
		{
			checkRetainedTrialPreflightDeadline(deadline);
			pause.pause();
		});
		return Optional.of(requestId ->
		{
			checkRetainedTrialPreflightDeadline(deadline);
			RetainedTrialResponse response = client.request(RetainedTrialRequest.status(requestId));
			checkRetainedTrialPreflightDeadline(deadline);
			return response;
		});
	}

	/**
	 * Enforces the preflight deadline also when the route is still being served.
	 *
	 * @param deadline the deadline shared by all status probes
	 * @throws IOException if the deadline has passed
	 */
	private void checkRetainedTrialPreflightDeadline(Instant deadline) throws IOException
	{
		if (!clock().instant().isBefore(deadline))
			throw new IOException("The retained trial status preflight timed out");
	}

	/**
	 * Returns the route shared by nested launch preflight and worktree requests.
	 *
	 * @return the launch route
	 */
	private LaunchRoute launchRoute()
	{
		String id = harness.commandName();
		return LaunchRoute.decide(id, id, markers.running(environment), new String[0], environment);
	}

	/**
	 * Returns why every session would be refused, but only when this command's sessions go to the session's request
	 * server, as their bridge decides from the environment: when this scope's harness runs the command outside a nested
	 * session. Such sessions are refused while no request server serves the host session. On every other route no
	 * request server needs to serve the host session: inside a nested session the nested session's runner serves it,
	 * and a route that refuses its sessions refuses each of them, with its reason, when it starts.
	 *
	 * @return the message that names the command that starts the request server, or empty if a request server serves
	 *   the host session or the sessions do not go to a request server
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException           if the host session's CAT session directory cannot be opened
	 */
	@Override
	protected final Optional<String> launchRefusal() throws IOException
	{
		ensureOpen();
		return switch (launchRoute())
		{
			case LaunchRoute.Served _ ->
			{
				try (ToolScope tools = ToolScope.forSession(directory, environment, SessionHarness.installed(),
					waiting()))
				{
					if (RequestServer.servedRequests(tools).isPresent())
						yield Optional.empty();
					yield Optional.of(RequestServer.missingServer(plugin(), installedCopies().find()));
				}
			}
			case LaunchRoute.Forward _, LaunchRoute.Refused _, LaunchRoute.Bounded _ ->
				Optional.empty();
		};
	}

	/**
	 * Returns why this command's worktree operations would be refused because no request server serves the host
	 * session, when the operations go to the session's request server: when this scope's harness runs the command
	 * outside a nested session.
	 *
	 * @return the message that names the command that starts the request server, or empty if a request server serves
	 *   the host session or the operations do not go to a request server
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException           if the host session's CAT session directory cannot be opened
	 */
	@Override
	protected final Optional<String> worktreeRefusal() throws IOException
	{
		ensureOpen();
		if (!(launchRoute() instanceof LaunchRoute.Served))
			return Optional.empty();
		try (ToolScope tools = ToolScope.forSession(directory, environment, SessionHarness.installed(), waiting()))
		{
			if (RequestServer.servedRequests(tools).isPresent())
				return Optional.empty();
			return Optional.of(RequestServer.missingWorktreeServer(plugin(), installedCopies().find()));
		}
	}

	/**
	 * Opens the channel that this command's worktree requests go through, by the route that decides its launches: the
	 * nested session's runner inside a nested session and the host session's request server when this scope's harness
	 * runs the command outside a nested session. A command that no harness session runs is refused before it reaches
	 * this channel.
	 *
	 * @return the channel, or empty if the command carries out the operations itself
	 * @throws WorktreeRefusedException if the route refuses the command's requests, or no request server serves the
	 *                                  host session
	 * @throws IOException              if the host session's CAT session directory cannot be opened
	 */
	private Optional<WorktreeRequestClient.Channel> worktreeChannel() throws IOException
	{
		return switch (launchRoute())
		{
			case LaunchRoute.Forward(Path requests) -> Optional.of(new NestedSessionChannel(requests));
			case LaunchRoute.Refused(String reason) -> throw new WorktreeRefusedException(reason);
			case LaunchRoute.Served _ ->
			{
				try (ToolScope tools = ToolScope.forSession(directory, environment, SessionHarness.installed(),
					waiting()))
				{
					Optional<Path> requests = RequestServer.servedRequests(tools);
					if (requests.isEmpty())
						throw new WorktreeRefusedException(RequestServer.missingWorktreeServer(plugin(),
							installedCopies().find()));
					yield Optional.of(new ServerChannel(requests.get()));
				}
			}
			case LaunchRoute.Bounded _ -> Optional.empty();
		};
	}

	/**
	 * Creates the client for retained trial operations on the same authenticated route used for worktrees.
	 *
	 * @return the client for this invoking session
	 */
	protected final RetainedNestedSessionClient retainedTrials()
	{
		return retainedTrials(waiting().every(SessionRequestForwarder.RESPONSE_POLL));
	}

	/**
	 * Creates a retained trial client with a caller-supplied response wait.
	 *
	 * @param pause the response wait
	 * @return the client for this invoking session
	 */
	private RetainedNestedSessionClient retainedTrials(Pause pause)
	{
		return new RetainedNestedSessionClient(this::worktreeChannel, json(), pause);
	}

	/**
	 * Returns the folder of the CAT plugin that runs this command.
	 *
	 * @return the plugin's folder
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract Path plugin();

	/**
	 * Returns what finds the installed copies of CAT in the host harness's configuration.
	 *
	 * @return what finds the installed copies
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract InstalledCopies installedCopies();

	@Override
	protected final RandomGenerator random()
	{
		ensureOpen();
		return random.getValue();
	}

	@Override
	protected final HarnessVersion.Launcher harnessVersionLauncher()
	{
		ensureOpen();
		return HarnessVersion.process(executableFiles());
	}

	@Override
	protected final List<Path> loaderConfiguration()
	{
		ensureOpen();
		return loaderConfiguration;
	}

	@Override
	protected final Forks forks()
	{
		ensureOpen();
		synchronized (forksLock)
		{
			if (forks == null)
			{
				forks = new GitForks(fileReplacer.getValue(), new WorktreeRequestClient(this::worktreeChannel,
					new GitWorktreeRegistrar(), json(), waiting().every(SessionRequestForwarder.RESPONSE_POLL)));
			}
			return forks;
		}
	}

	@Override
	protected final CapCalibrator calibrator()
	{
		ensureOpen();
		return CapCalibration::calibrate;
	}

	/**
	 * The channel to the request server of the host session, which keeps each response until its requester removes
	 * it.
	 */
	private final class ServerChannel implements WorktreeRequestClient.Channel
	{
		private final Path requests;

		/**
		 * Creates a channel.
		 *
		 * @param requests the server's requests directory
		 */
		private ServerChannel(Path requests)
		{
			this.requests = requests;
		}

		@Override
		public Path requests()
		{
			return requests;
		}

		@Override
		public boolean isServed() throws IOException
		{
			try (ToolScope tools = ToolScope.forSession(directory, environment, SessionHarness.installed(),
				waiting()))
			{
				Optional<SessionDirectory> session = tools.sessionDirectory();
				return session.isPresent() && RequestServer.serves(session.get(), tools);
			}
		}

		@Override
		public boolean removesResponses()
		{
			return true;
		}

		@Override
		public void close()
		{
		}
	}
}

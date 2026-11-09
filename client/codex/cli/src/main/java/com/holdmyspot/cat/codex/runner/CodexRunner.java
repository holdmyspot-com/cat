package com.holdmyspot.cat.codex.runner;

import static com.holdmyspot.cat.codex.CodexPluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.codex.installer.CodexIntegration;
import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.codex.optimizer.InstalledPlugins;
import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.HarnessCommand;
import com.holdmyspot.cat.common.runner.HarnessCredentials;
import com.holdmyspot.cat.common.runner.HarnessDependencyCache;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessProcessStarter;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunLock;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessProjectRepository;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.AbstractHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.MainHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.PluginManifest;
import com.holdmyspot.cat.common.runner.SessionCommandLimits;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestInstructions;
import com.holdmyspot.cat.common.runner.SetupCommands;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Creates an isolated Codex configuration and starts one persistent Codex CLI process, optionally with CAT.
 */
public final class CodexRunner
{
	private static final Set<String> AUTHENTICATION_VARIABLES = Set.of("CODEX_API_KEY", "OPENAI_API_KEY");
	/**
	 * The variable that names the Codex home, which holds Codex's login, configuration and state.
	 */
	private static final String CODEX_HOME = "CODEX_HOME";
	/**
	 * The variable in which Codex names the thread whose tool shell runs a command.
	 */
	private static final String THREAD_ID = "CODEX_THREAD_ID";
	/**
	 * The name of the file in the Codex home that holds Codex's login.
	 */
	private static final String LOGIN = "auth.json";
	/**
	 * The path of a Codex plugin's manifest, relative to the plugin directory.
	 */
	static final Path MANIFEST = Path.of(".codex-plugin/plugin.json");
	/**
	 * CAT as Codex identifies an installed plugin: its name and its marketplace's.
	 */
	private static final String CAT_PLUGIN_ID = CodexMarketplace.CAT + "@" + CodexMarketplace.CAT;
	/**
	 * The text of Codex's failure to start a thread when bubblewrap cannot set up the sandbox's loopback interface.
	 */
	private static final String SANDBOX_LOOPBACK_FAILURE = "bwrap: loopback: Failed RTM_NEWADDR";
	private static final String SANDBOX_UID_MAP_FAILURE = "bwrap: setting up uid map: Permission denied";
	private static final int MAXIMUM_COMMAND_OUTPUT_BYTES = 1_048_576;
	private final Waiting waiting;

	/**
	 * Creates a Codex artifact runner. Each run obtains executable-file services from its owned scope.
	 *
	 * @param waiting how each run waits between the attempts of a retried operation
	 * @throws NullPointerException if {@code waiting} is null
	 */
	public CodexRunner(Waiting waiting)
	{
		requireThat(waiting, "waiting").isNotNull();
		this.waiting = waiting;
	}

	/**
	 * Starts a runner whose validator may read, but not modify, completed executor evidence.
	 *
	 * @param artifact the complete Codex artifact that the session installs as CAT and whose launcher the session
	 *   requests sessions through
	 * @param project the validator project
	 * @param workspace the optional directory whose files are copied into the project on top of {@code project}
	 * @param trialPlugins the trial plugins, each installed into the isolated home from the marketplace that bears its
	 *   manifest's name, beside CAT when {@code installCat} is set, with the user's approvals of its hooks
	 * @param manager the run manager
	 * @param executable the Codex executable
	 * @param environment the scrubbed isolated environment
	 * @param model the Codex model name
	 * @param effort the reasoning effort of each turn
	 * @param installCat whether CAT is installed in the isolated home
	 * @param agent the optional direct CAT agent definition, whose model and effort replace {@code model} and
	 *   {@code effort}
	 * @param readOnlyEvidence the completed evidence files and directories that the session may read but not change
	 * @param deniedReads the existing files and directories that the session must not read, besides the records of
	 *   its own run
	 * @param toolOutputTokenLimit the most tokens of each tool output that the session keeps, or {@code 0} for
	 *   Codex's default
	 * @param sessionTranscripts whether the session's threads are persistent, and the run copies their rollouts into its
	 *   session transcripts directory when the session closes; otherwise the threads are ephemeral and Codex keeps no
	 *   rollout
	 * @param sessionRequests how many sessions the session may request over its run, or {@code 0} if it may request
	 *   none
	 * @param bound the policy that bounds the session's sandbox: the session gets its run directory, the dependency
	 *   caches and its model host only within it
	 * @param policyListener receives the session's sandbox policy before its Codex process starts
	 * @param gitName the Git user name of the copied project, or {@code null} to read {@code project}'s
	 * @param gitEmail the Git user email of the copied project, or {@code null} to read {@code project}'s
	 * @param extraPermissions the writes and hosts that the session's commands need beyond what Codex needs
	 * @return the live owned Codex run session
	 * @throws SessionRefusedException if the session's sandbox would be wider than its policy or cannot confine it
	 * @throws IOException             if validation, copying, or process startup fails
	 */
	HarnessRunSession start(Path artifact, Path project, Path workspace, List<Path> trialPlugins,
		HarnessRunManager manager, String executable, Map<String, String> environment, String model, String effort,
		boolean installCat, CodexAgentDefinition agent, List<Path> readOnlyEvidence, List<Path> deniedReads,
		int toolOutputTokenLimit, boolean sessionTranscripts, int sessionRequests, ParentPolicy bound,
		Consumer<ChildPolicy> policyListener, String gitName, String gitEmail, ExtraPermissions extraPermissions)
		throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(trialPlugins, "trialPlugins").isNotNull();
		requireThat(manager, "manager").isNotNull();
		requireThat(executable, "executable").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		requireThat(model, "model").isNotBlank();
		requireThat(effort, "effort").isNotBlank();
		requireThat(toolOutputTokenLimit, "toolOutputTokenLimit").isNotNegative();
		requireThat(sessionRequests, "sessionRequests").isNotNegative();
		requireThat(bound, "bound").isNotNull();
		requireThat(policyListener, "policyListener").isNotNull();
		requireThat(extraPermissions, "extraPermissions").isNotNull();
		if (agent != null && !installCat)
			throw new IOException("A direct CAT agent requires --install-cat");
		artifact = HarnessArtifact.validate(artifact, Path.of(".codex-plugin/plugin.json"));
		HarnessRun run = manager.create(HARNESS_ID);
		HarnessRunLock lock = null;
		AbstractHarnessRunnerScope scope = new MainHarnessRunnerScope(environment, waiting);
		ExecutableFiles executables = scope.executableFiles();
		try
		{
			manager.copyProject(project, run);
			if (workspace != null)
				manager.copyWorkspace(workspace, run);
			manager.snapshotProject(run);
			HarnessProjectRepository.initialize(project, run.directory().resolve("project"), run.directory(), environment,
				executables, gitName, gitEmail);
			Path isolatedHome = run.directory().resolve("home");
			Path temporaryDirectory = Files.createDirectories(isolatedHome.resolve("tmp"));
			Path hostHome = CodexInstallation.resolveHome(environment);
			HarnessCredentials.copyAllowedFiles(hostHome, isolatedHome, Set.of(LOGIN));
			Path isolatedConfiguration = configuration(isolatedHome);
			HarnessDependencyCaches caches = HarnessDependencyCache.verify(environment);
			SessionRequestAccess.Shell shell = SessionRequestAccess.Shell.POSIX;
			if (Platform.current().isWindows())
				shell = SessionRequestAccess.Shell.POWERSHELL;
			SessionRequestAccess requestAccess = SessionRequestAccess.allowing(run, artifact, sessionRequests,
				Platform.current(), shell).orElse(null);
			// A runner that a Codex thread starts gives the session the multi-agent state that the thread runs with
			CodexParentMultiAgentState parentState = CodexParentMultiAgentState.read(scope.json(), hostHome,
				environment.get(THREAD_ID));
			// The session's commands may reach only the host that its own Codex sends model requests to
			String modelHost = CodexConnectionConfiguration.modelHost(configuration(hostHome), environment,
				isolatedHome.resolve(LOGIN));
			ChildPolicy policy = ChildPolicy.intersect(bound, new ChildNeeds(run.directory().toAbsolutePath(),
				caches.directories(), List.of(), Set.of(modelHost)).with(extraPermissions));
			CodexConnectionConfiguration.copy(configuration(hostHome), isolatedConfiguration,
				run.directory().resolve("project"), caches,
				temporaryDirectory, toolOutputTokenLimit, requestAccess, Platform.current(), parentState, policy,
				extraPermissions);
			if (!Platform.current().isWindows())
			{
				CodexConnectionConfiguration.requireNarrowingLayers(CodexConnectionConfiguration.SYSTEM_LAYER,
					CodexConnectionConfiguration.MANAGED_LAYER, policy);
			}
			policyListener.accept(policy);
			boolean pluginsInstalled = installCat || !trialPlugins.isEmpty();
			List<String> appServer = appServerCommand(executable, pluginsInstalled);
			SessionSandbox sandbox = sessionSandbox(isolatedConfiguration, manager, run, readOnlyEvidence, deniedReads,
				Platform.current(), environment, requestAccess, appServer);
			Map<String, String> isolatedEnvironment = processEnvironment(environment, caches, isolatedHome,
				run.directory(), temporaryDirectory, Platform.current());
			Path worktree = run.directory().resolve("project");
			requireNetworkProxy(arguments -> commandOutput(executable, arguments, worktree, isolatedEnvironment,
				executables, manager, run), featureFlags(pluginsInstalled), policy.hosts());
			installPlugins(executable, artifact, installCat, trialPlugins, hostHome, isolatedHome, isolatedEnvironment,
				executables, FileReplacer.retrying(waiting), (setup, step) -> HarnessCommand.execute(setup, worktree,
					isolatedEnvironment, executables, manager, run, step));
			List<String> command = sandbox.command();
			manager.initialize(run, artifact, project, "codex_app_server", command);
			lock = manager.acquireLock(run);
			HarnessSessionTranscripts transcripts = null;
			if (sessionTranscripts)
				transcripts = CodexProcess.rollouts(isolatedHome, run.sessionTranscripts());
			CodexProcess process = new CodexProcess(HarnessProcessStarter.start(command, worktree,
				isolatedEnvironment, executables), scope, installCat, transcripts);
			try
			{
				manager.recordHarnessProcess(run, process.pid(), process.processStart());
				String selectedModel = model;
				String agentInstructions = "";
				String selectedEffort = effort;
				if (agent != null)
				{
					selectedModel = agent.model();
					agentInstructions = agent.developerInstructions();
					selectedEffort = agent.effort();
				}
				Path requestLauncher = null;
				if (requestAccess != null)
					requestLauncher = requestAccess.launcher();
				try
				{
					process.initialize(worktree, selectedModel, developerInstructions(manager.root().toRealPath(),
						worktree, isolatedHome, agentInstructions, requestLauncher, sessionRequests), selectedEffort,
						sandbox.profile());
				}
				catch (IOException e)
				{
					throw refusalOf(e);
				}
				return new HarnessRunSession(run, process, scope, lock);
			}
			catch (IOException e)
			{
				try
				{
					process.close();
				}
				catch (IOException closeFailure)
				{
					e.addSuppressed(closeFailure);
				}
				throw e;
			}
		}
		catch (IOException e)
		{
			if (lock != null)
			{
				Exception failure = close(lock);
				if (failure != null)
					e.addSuppressed(failure);
			}
			scope.close();
			manager.fail(run, e.getMessage());
			throw e;
		}
	}

	/**
	 * Returns the environment of a run's app-server. It holds the caller's generic operating-system variables, its
	 * Codex authentication variables and the shared dependency caches, names the run's isolated home as the Codex home,
	 * and stops Git from finding a repository above the run directory. On Linux, it also names the run's temporary
	 * directory as {@code TMPDIR}, the directory that the run's configuration gives the session's shell commands.
	 *
	 * @param environment        the caller's environment
	 * @param caches             the shared dependency caches
	 * @param isolatedHome       the run's isolated home
	 * @param runDirectory       the run's directory
	 * @param temporaryDirectory the run's temporary directory, which the run's configuration names as the shell
	 *                           commands' {@code TMPDIR}
	 * @param platform           the platform that the app-server runs on
	 * @return the environment, which the caller may change
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code runDirectory} is relative
	 */
	public static Map<String, String> processEnvironment(Map<String, String> environment,
		HarnessDependencyCaches caches, Path isolatedHome, Path runDirectory, Path temporaryDirectory,
		Platform platform)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(caches, "caches").isNotNull();
		requireThat(isolatedHome, "isolatedHome").isNotNull();
		requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
		requireThat(platform, "platform").isNotNull();
		Map<String, String> isolatedEnvironment = new HashMap<>(HarnessEnvironment.scrub(environment));
		HarnessEnvironment.copyAllowed(environment, isolatedEnvironment, AUTHENTICATION_VARIABLES);
		// Each run gets an isolated HOME, but packages belong in the parent's explicitly writable shared caches so
		// independent trials do not redownload the same immutable registry artifacts.
		isolatedEnvironment.putAll(caches.environment());
		isolatedEnvironment.put(CODEX_HOME, isolatedHome.toString());
		HarnessEnvironment.preventGitDiscoveryAbove(isolatedEnvironment, runDirectory);
		// The session's commands send their sessions to this run's runner
		isolatedEnvironment.put(HarnessEnvironment.RUN_VARIABLE, HarnessEnvironment.RUN_VALUE);
		if (platform.isLinux())
		{
			// Codex's Linux sandbox creates an empty directory for each protected path that a shell command's sandbox
			// mounts over, and records it in a file under TMPDIR so that no other sandbox removes it while the command
			// still needs it. The filesystem helpers that the app-server starts for a thread inherit the app-server's
			// TMPDIR. Without the shell commands' TMPDIR, a helper that a sibling thread's start launched records in a
			// different file, removes a running command's directory and makes that command fail before it starts.
			isolatedEnvironment.put("TMPDIR", temporaryDirectory.toString());
		}
		return isolatedEnvironment;
	}

	/**
	 * Returns what a Codex child session needs beyond its run folder, as a request server asks for it before it
	 * starts the child: the shared dependency caches, which the child writes; the host that the child's Codex sends
	 * its model requests to, which its commands reach; and the caller's login file, which the child's isolated home
	 * copies when it exists.
	 *
	 * @param environment the caller's environment
	 * @return the needs
	 * @throws NullPointerException if {@code environment} is null
	 * @throws IOException          if a cache cannot be used, Codex's home cannot be found, or its configuration or
	 *                              login cannot be read
	 */
	public static ChildNeedsReport childNeeds(Map<String, String> environment) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		HarnessDependencyCaches caches = HarnessDependencyCache.verify(environment);
		Path hostHome = CodexInstallation.resolveHome(environment);
		Path login = hostHome.resolve(LOGIN).toAbsolutePath();
		String modelHost = CodexConnectionConfiguration.modelHost(configuration(hostHome), environment, login);
		List<Path> logins = new ArrayList<>();
		if (Files.isRegularFile(login))
			logins.add(login);
		return new ChildNeedsReport(caches.directories(), Set.of(modelHost), logins);
	}

	/**
	 * Returns the developer instructions of a session: a note that the denied reads under the run root cover neither
	 * the session's project nor its home, followed by the agent's contract, if any. Codex lists the run root among the
	 * denied reads in the model's instructions, and without the note models answered that their own project was
	 * denied without trying to read it.
	 *
	 * @param root the run root, as the denied reads name it
	 * @param project the session's project, which is its working directory
	 * @param home the session's Codex home
	 * @param agentInstructions the agent's contract, or an empty string for a session without an agent
	 * @return the developer instructions
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String developerInstructions(Path root, Path project, Path home, String agentInstructions)
	{
		return developerInstructions(root, project, home, agentInstructions, null, 0);
	}

	/**
	 * Returns the developer instructions of a session as
	 * {@link #developerInstructions(Path, Path, Path, String)} does, except that the instructions of a session that may
	 * request sessions tell it how, between the note and the agent's contract.
	 *
	 * @param root              the run root, as the denied reads name it
	 * @param project           the session's project, which is its working directory
	 * @param home              the session's Codex home
	 * @param agentInstructions the agent's contract, or an empty string for a session without an agent
	 * @param requestLauncher   the absolute request-session launcher path, or {@code null} when requests are disabled
	 * @param sessionRequests   how many sessions the session may request; {@code 0} adds no instructions
	 * @return the developer instructions
	 * @throws NullPointerException     if any required argument is null
	 * @throws IllegalArgumentException if {@code sessionRequests} is negative, or requests are enabled and
	 *   {@code requestLauncher} is relative
	 */
	public static String developerInstructions(Path root, Path project, Path home, String agentInstructions,
		Path requestLauncher, int sessionRequests)
	{
		requireThat(sessionRequests, "sessionRequests").isNotNegative();
		requireThat(root, "root").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(home, "home").isNotNull();
		requireThat(agentInstructions, "agentInstructions").isNotNull();
		String note = "The denied reads protect other sessions' projects in " + root + ". They do not apply to your " +
			"project " + project + ", your working directory, or to anything under it, nor to " + home + ": you have " +
			"full read and write access to both. Your project's files are not in your context; read one with a shell " +
			"command whenever a task needs it.";
		StringJoiner instructions = new StringJoiner("\n\n").add(note);
		if (sessionRequests > 0)
			instructions.add(SessionRequestInstructions.text(sessionRequests, requestLauncher,
				Platform.current().isWindows()));
		if (!agentInstructions.isEmpty())
			instructions.add(agentInstructions);
		return instructions.toString();
	}

	/**
	 * Adds to a session's configuration the permission profile that its thread runs under, as
	 * {@link #addSessionProfile(Path, HarnessRunManager, HarnessRun, List, List, Platform, Map, SessionRequestAccess)}
	 * does, and returns it with the command that starts the session's app-server.
	 * <p>
	 * When the caller's {@code PATH} has bubblewrap, that command runs the app-server under bubblewrap, as
	 * {@link #withHiddenDirectories(List, Path, List, List, List)} describes, which hides from the app-server each
	 * directory that {@link #hiddenDirectories(HarnessRunManager, List, List, Platform, Map)} returns, and reopens
	 * inside it the session's project and home and its requests directory, if any, for writing and the session's
	 * read-only evidence for reading only. The permission profile then names neither those directories nor the paths
	 * under them, so its size does not grow with the number of runs or files beside the evidence. Otherwise, and when
	 * it hides nothing, the command is the app-server command itself.
	 * <p>
	 * A Linux session whose {@code readOnlyEvidence} lies inside a directory that it is denied, such as the run root,
	 * cannot start without that bubblewrap, so the method refuses it before it changes the configuration.
	 *
	 * @param configuration    the session's isolated Codex configuration, which holds the CAT runner profile
	 * @param manager          the run manager
	 * @param run              the session's run
	 * @param readOnlyEvidence the completed evidence directories and files that the caller lets the session read
	 * @param deniedReads      the files and directories that the caller denies the session reading
	 * @param platform         the platform that the session runs on
	 * @param environment      the caller's environment, which names the user's home, harness directories and
	 *                         {@code PATH}
	 * @param requestAccess    the session's access to requested sessions, or {@code null} if it may request none
	 * @param appServer        the command that starts the app-server
	 * @return the session's sandbox
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} are null
	 * @throws IOException          if the session runs on Linux, its {@code readOnlyEvidence} lies inside a directory
	 *                              that it is denied and the caller's {@code PATH} has no bubblewrap; if an evidence
	 *                              path does not exist, a denied directory cannot be listed, or the configuration
	 *                              cannot be updated
	 */
	public static SessionSandbox sessionSandbox(Path configuration, HarnessRunManager manager, HarnessRun run,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Platform platform, Map<String, String> environment,
		SessionRequestAccess requestAccess, List<String> appServer) throws IOException
	{
		requireThat(appServer, "appServer").isNotEmpty();
		requireBubblewrapForEvidence(manager, readOnlyEvidence, deniedReads, platform, environment);
		String profile = addSessionProfile(configuration, manager, run, readOnlyEvidence, deniedReads, platform,
			environment, requestAccess);
		Optional<Path> bubblewrap = bubblewrap(environment);
		if (bubblewrap.isEmpty())
			return new SessionSandbox(profile, appServer);
		List<Path> writable = new ArrayList<>(List.of(run.directory().resolve("project"),
			run.directory().resolve("home")));
		if (requestAccess != null)
			writable.add(requestAccess.requests());
		List<String> command = withHiddenDirectories(appServer, bubblewrap.get(),
			hiddenDirectories(manager, sessionReadOnly(readOnlyEvidence, requestAccess), deniedReads, platform,
				environment), writable, sessionReadOnly(readOnlyEvidence, requestAccess));
		return new SessionSandbox(profile, command);
	}

	/**
	 * Refuses a Linux session that reads evidence inside a directory that it is denied when the caller's {@code PATH}
	 * has no bubblewrap. The Linux sandbox of Codex hides read-only paths inside a denied directory, so only the
	 * bubblewrap around the app-server can show the session its evidence while it hides the rest of that directory.
	 * Denying each other path under the directory instead makes the permission profile of a session beside thousands of
	 * runs too long for Codex's sandbox helper, which then fails to start the session's thread.
	 *
	 * @param manager          the run manager
	 * @param readOnlyEvidence the completed evidence directories and files that the caller lets the session read
	 * @param deniedReads      the files and directories that the caller denies the session reading
	 * @param platform         the platform that the session runs on
	 * @param environment      the caller's environment, whose {@code PATH} names bubblewrap
	 * @throws IOException if the session must be refused
	 */
	private static void requireBubblewrapForEvidence(HarnessRunManager manager, List<Path> readOnlyEvidence,
		List<Path> deniedReads, Platform platform, Map<String, String> environment) throws IOException
	{
		requireThat(manager, "manager").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		if (!platform.isLinux() || bubblewrap(environment).isPresent())
			return;
		List<Path> holding = deniedDirectoriesHolding(manager, readOnlyEvidence, deniedReads);
		if (holding.isEmpty())
			return;
		String path = environment.getOrDefault("PATH", "");
		String searched;
		if (path.isBlank())
			searched = "the PATH is empty";
		else
			searched = "none of the PATH directories " + path + " holds an executable bwrap file";
		throw new IOException("On Linux, a Codex session that reads evidence needs bubblewrap (bwrap) to let it read " +
			readOnlyEvidence + " while it hides the rest of " + holding + ", but " + searched + ". Install your " +
			"distribution's bubblewrap package, for example with \"sudo apt install bubblewrap\" or \"sudo dnf install " +
			"bubblewrap\", then start the session again.");
	}

	/**
	 * Adds to a session's configuration the permission profile that its thread runs under: the session may read but
	 * not change {@code readOnlyEvidence}, as {@link #sessionReadOnly(List, SessionRequestAccess)} extends it, and may
	 * not read the paths that {@link #sessionDeniedReads(HarnessRunManager, HarnessRun, List, List, Platform, Map,
	 * SessionRequestAccess)} returns.
	 *
	 * @param configuration    the session's isolated Codex configuration, which holds the CAT runner profile
	 * @param manager          the run manager
	 * @param run              the session's run
	 * @param readOnlyEvidence the completed evidence directories and files that the caller lets the session read
	 * @param deniedReads      the files and directories that the caller denies the session reading
	 * @param platform         the platform that the session runs on
	 * @param environment      the caller's environment, which names the user's home and harness directories
	 * @param requestAccess    the session's access to requested sessions, or {@code null} if it may request none
	 * @return the profile that the thread names, or an empty string if the thread uses the workspace-write sandbox
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} are null
	 * @throws IOException          if an evidence path does not exist, a denied directory cannot be listed, or the
	 *                              configuration cannot be updated
	 */
	public static String addSessionProfile(Path configuration, HarnessRunManager manager, HarnessRun run,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Platform platform, Map<String, String> environment,
		SessionRequestAccess requestAccess) throws IOException
	{
		requireThat(configuration, "configuration").isNotNull();
		List<Path> sessionReadOnly = sessionReadOnly(readOnlyEvidence, requestAccess);
		return CodexConnectionConfiguration.addSessionProfile(configuration, sessionReadOnly,
			sessionDeniedReads(manager, run, sessionReadOnly, deniedReads, platform, environment, requestAccess));
	}

	/**
	 * Returns the completed files and directories that a session may read but not change: the caller's read-only
	 * evidence, and the run's responses directory when the session may request sessions, so that a link that the
	 * session plants there cannot redirect the runner's answers.
	 *
	 * @param readOnlyEvidence the completed evidence directories and files that the caller lets the session read
	 * @param requestAccess    the session's access to requested sessions, or {@code null} if it may request none
	 * @return the files and directories
	 * @throws NullPointerException if {@code readOnlyEvidence} is null
	 */
	public static List<Path> sessionReadOnly(List<Path> readOnlyEvidence, SessionRequestAccess requestAccess)
	{
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		List<Path> result = new ArrayList<>(readOnlyEvidence);
		if (requestAccess != null)
			result.add(requestAccess.responses());
		return List.copyOf(result);
	}

	/**
	 * Returns the files and directories that a session of a run must not read: those that the caller denies, every file
	 * and directory under the run root except the session's project, home and evidence, the directory where Codex logs
	 * the session's events, and every harness's login and configuration files in the session's home and in the user's
	 * home, as {@link SessionCommandLimits#harnessFiles(Map, Platform)} names them, except two files in the session's
	 * own Codex home: its {@code config.toml}, which holds no login and from which CAT's {@code read-part} command reads
	 * the session's tool output limit, and its {@code auth.json}, the copy of the user's login that the runner puts
	 * there, which a Codex process that the session starts from a command, such as the prompt optimizer's sessions,
	 * reads. The session therefore cannot read the runner's records of its own run or of any other run, the workspace
	 * files of any run, any login but that copy, such as the user's own Codex and Claude logins, or the user's
	 * configuration. The method creates the session log directory, because the sandbox skips a denied path that does
	 * not exist yet; for the same reason, it leaves out a login or configuration file that does not exist.
	 * <p>
	 * The result names the run root and each directory that the caller denies rather than each path under them, which
	 * also denies the runs that start after the session. It omits a path inside another directory that it names, except
	 * the session log directory and the denied files inside the session's home. Every platform's sandbox still lets the
	 * session read and write its project and home inside a denied directory, such as the project that holds the run
	 * root, but the Linux sandbox would also hide read-only evidence there. The result therefore omits each directory
	 * that {@link #hiddenDirectories(HarnessRunManager, List, List, Platform, Map)} returns, which the app-server's
	 * bubblewrap hides instead, and the paths under it. Where the caller's {@code PATH} has no bubblewrap, a Linux
	 * session is instead denied each other file and directory under a denied directory that holds any of
	 * {@code readOnlyEvidence} by its own path. The runner starts no such session with the caller's evidence, as
	 * {@link #sessionSandbox(Path, HarnessRunManager, HarnessRun, List, List, Platform, Map, SessionRequestAccess,
	 * List)} describes, so this listing covers only the run's own responses directory, which lies inside the run root.
	 *
	 * @param manager the run manager
	 * @param run the session's run
	 * @param readOnlyEvidence the completed evidence directories and files that the session reads
	 * @param deniedReads the files and directories that the caller denies the session reading
	 * @param platform the platform that the session runs on
	 * @param environment the caller's environment, which names the user's home and harness directories
	 * @return the paths to deny, each once
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if a denied directory cannot be listed or the session log directory cannot be created
	 */
	public static List<Path> sessionDeniedReads(HarnessRunManager manager, HarnessRun run,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Platform platform, Map<String, String> environment)
		throws IOException
	{
		return sessionDeniedReads(manager, run, readOnlyEvidence, deniedReads, platform, environment, null);
	}

	/**
	 * Returns the files and directories that a session of a run must not read, as
	 * {@link #sessionDeniedReads(HarnessRunManager, HarnessRun, List, List, Platform, Map)} does, except that a Linux
	 * session that is denied each path under a directory by its own path also keeps the run's requests directory when
	 * it may request sessions. The responses directory is among {@code readOnlyEvidence}, as
	 * {@link #sessionReadOnly(List, SessionRequestAccess)} returns it.
	 *
	 * @param manager the run manager
	 * @param run the session's run
	 * @param readOnlyEvidence the completed evidence directories and files that the session reads
	 * @param deniedReads the files and directories that the caller denies the session reading
	 * @param platform the platform that the session runs on
	 * @param environment the caller's environment, which names the user's home and harness directories
	 * @param requestAccess the session's access to requested sessions, or {@code null} if it may request none
	 * @return the paths to deny, each once
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} are null
	 * @throws IOException if a denied directory cannot be listed or the session log directory cannot be created
	 */
	public static List<Path> sessionDeniedReads(HarnessRunManager manager, HarnessRun run,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Platform platform, Map<String, String> environment,
		SessionRequestAccess requestAccess) throws IOException
	{
		requireThat(manager, "manager").isNotNull();
		requireThat(run, "run").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		Path home = run.directory().resolve("home");
		List<Path> accessible = new ArrayList<>(readOnlyEvidence);
		accessible.add(run.directory().resolve("project"));
		accessible.add(home);
		if (requestAccess != null)
			accessible.add(requestAccess.requests());
		Set<Path> result = new LinkedHashSet<>();
		List<Path> hidden = hiddenDirectories(manager, readOnlyEvidence, deniedReads, platform, environment);
		for (Path directory : deniedDirectories(manager, deniedReads))
		{
			if (hidden.stream().anyMatch(directory::startsWith))
				continue;
			if (platform.isLinux() && HarnessRunManager.containsAny(directory, readOnlyEvidence))
				result.addAll(HarnessRunManager.deniedReads(directory, accessible));
			else
			{
				// Codex lists every denied path in the model's instructions and omits the rest of a long list, and the
				// omission led trial agents to refuse reading their own project
				result.add(directory);
			}
		}
		// The Linux sandbox fails to start when it must hide a directory inside one that it already hides, and the
		// outer directory hides it anyway
		Set<Path> named = Set.copyOf(result);
		result.removeIf(path -> named.stream().anyMatch(other -> !other.equals(path) && path.startsWith(other)));
		// The session's events are the same record as the run's artifacts, kept by Codex instead of the runner
		result.add(Files.createDirectories(home.resolve("sessions")));
		// The Codex process reads the logins and configuration itself; the sandbox confines only the commands that the
		// session runs
		Map<String, String> commandEnvironment = new HashMap<>(SessionCommandLimits.homeEnvironment(home, platform));
		commandEnvironment.put(CODEX_HOME, home.toString());
		List<Path> harnessFiles = new ArrayList<>(SessionCommandLimits.harnessFiles(environment, platform));
		harnessFiles.addAll(SessionCommandLimits.harnessFiles(commandEnvironment, platform));
		// CAT's read-part command reads the session's tool output limit from the session's configuration, and a Codex
		// process that a command starts, such as a prompt optimizer's session, reads the session's own login
		Set<Path> readable = Set.of(configuration(home),
			home.resolve(LOGIN).toAbsolutePath().normalize());
		for (Path file : harnessFiles)
		{
			if (readable.contains(file))
				continue;
			if (Files.exists(file, NOFOLLOW_LINKS))
				result.add(file);
		}
		return List.copyOf(result);
	}

	/**
	 * Returns the directories that the bubblewrap around a Linux session's app-server hides: each directory that the
	 * caller denies, and the run root, that holds read-only evidence, except one inside another. The Linux sandbox of
	 * Codex hides read-only paths inside a directory that a permission profile denies, so such a directory cannot be
	 * denied in the profile. Denying each other path under it instead made the profile of a judge that ran beside
	 * thousands of runs too long for Codex to pass to its sandbox helper, which then failed to start the judge's
	 * thread.
	 *
	 * @param manager          the run manager
	 * @param readOnlyEvidence the completed evidence directories and files that the session reads
	 * @param deniedReads      the files and directories that the caller denies the session reading
	 * @param platform         the platform that the session runs on
	 * @param environment      the caller's environment, whose {@code PATH} names bubblewrap
	 * @return the directories, absolute and normalized, or none if the session does not run on Linux or the
	 *   {@code PATH} has no bubblewrap
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static List<Path> hiddenDirectories(HarnessRunManager manager, List<Path> readOnlyEvidence,
		List<Path> deniedReads, Platform platform, Map<String, String> environment)
	{
		requireThat(manager, "manager").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		if (!platform.isLinux() || bubblewrap(environment).isEmpty())
			return List.of();
		List<Path> holding = deniedDirectoriesHolding(manager, readOnlyEvidence, deniedReads);
		// Bubblewrap cannot mount over a directory inside one that it already replaced with an empty one
		return holding.stream().
			filter(path -> holding.stream().noneMatch(other -> !other.equals(path) && path.startsWith(other))).
			distinct().
			toList();
	}

	/**
	 * Returns the directories that a session must not read as a whole, as
	 * {@link #deniedDirectories(HarnessRunManager, List)} lists them, that hold any of its read-only paths.
	 *
	 * @param manager     the run manager
	 * @param readOnly    the files and directories that the session may read but not change
	 * @param deniedReads the files and directories that the caller denies the session reading
	 * @return the directories, absolute and normalized, in the order of
	 *   {@link #deniedDirectories(HarnessRunManager, List)}
	 */
	private static List<Path> deniedDirectoriesHolding(HarnessRunManager manager, List<Path> readOnly,
		List<Path> deniedReads)
	{
		List<Path> holding = new ArrayList<>();
		for (Path directory : deniedDirectories(manager, deniedReads))
		{
			if (HarnessRunManager.containsAny(directory, readOnly))
				holding.add(directory);
		}
		return holding;
	}

	/**
	 * Returns the directories that a session must not read as a whole: those that the caller denies, followed by the
	 * run root.
	 *
	 * @param manager     the run manager
	 * @param deniedReads the files and directories that the caller denies the session reading
	 * @return the directories, absolute and normalized
	 */
	private static List<Path> deniedDirectories(HarnessRunManager manager, List<Path> deniedReads)
	{
		List<Path> directories = new ArrayList<>();
		for (Path denied : deniedReads)
			directories.add(denied.toAbsolutePath().normalize());
		directories.add(manager.root().toAbsolutePath().normalize());
		return directories;
	}

	/**
	 * Installs CAT, when requested, and each trial plugin into an isolated Codex home, each from a marketplace of its
	 * own, with the user's approvals of its hooks. Plugin hooks are untrusted in every fresh home, so the approvals are
	 * copied from the user's configuration; Codex rejects an approval whose hook's normalized declaration differs.
	 * <p>
	 * Installing CAT also writes into the isolated home the entries that CAT's installer writes for the installed
	 * plugin version: the execution-policy rules for its installer and request server, and the hash of each of its
	 * hooks in {@code config.toml}, which replaces a copied approval that the version's hooks no longer match. The
	 * installer's other entries, which cover the user's sandbox mode, shell startup files, capacity locks and the other
	 * harness's installation, are left out: the run's own sandbox policy covers its files, and the run shares neither
	 * locks nor an installation with the user's.
	 *
	 * @param executable the Codex executable
	 * @param artifact the complete Codex artifact, which is installed as CAT
	 * @param installCat {@code true} to install CAT
	 * @param trialPlugins the trial plugins, each installed under its manifest's name
	 * @param hostHome the user's Codex home, whose configuration holds the hook approvals
	 * @param isolatedHome the isolated Codex home
	 * @param isolatedEnvironment the isolated environment, which seeding CAT's curated plugin cache runs in
	 * @param executables runs Git while seeding CAT's curated plugin cache
	 * @param replacer replaces the rules file and {@code config.toml}
	 * @param commands runs the commands that add each marketplace and each plugin
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code executable} is blank
	 * @throws IOException if a plugin's manifest cannot be read, a configuration or marketplace cannot be written, CAT's
	 *   agents cannot be copied, a command fails, or Codex did not install exactly one copy of CAT
	 */
	public static void installPlugins(String executable, Path artifact, boolean installCat, List<Path> trialPlugins,
		Path hostHome, Path isolatedHome, Map<String, String> isolatedEnvironment, ExecutableFiles executables,
		FileReplacer replacer, SetupCommands commands) throws IOException
	{
		requireThat(executable, "executable").isNotBlank();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(trialPlugins, "trialPlugins").isNotNull();
		requireThat(hostHome, "hostHome").isNotNull();
		requireThat(isolatedHome, "isolatedHome").isNotNull();
		requireThat(isolatedEnvironment, "isolatedEnvironment").isNotNull();
		requireThat(executables, "executables").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(commands, "commands").isNotNull();
		Path hostConfiguration = configuration(hostHome);
		Path isolatedConfiguration = configuration(isolatedHome);
		List<String> names = new ArrayList<>();
		for (Path trialPlugin : trialPlugins)
		{
			String name = PluginManifest.name(trialPlugin, MANIFEST);
			names.add(name);
			CodexConnectionConfiguration.copyTrustedHooks(hostConfiguration, isolatedConfiguration, name);
		}
		if (installCat)
		{
			CodexConnectionConfiguration.copyTrustedCatHooks(hostConfiguration, isolatedConfiguration);
			CodexCuratedPluginCache.seed(hostHome, isolatedHome, isolatedEnvironment, executables);
			// Codex resolves custom agents from CODEX_HOME/agents, not the installed plugin. Copy the definitions from
			// this run's artifact so delegated agents receive the same CAT contract as the primary isolated process,
			// rather than an unrelated version from the host's global Codex home.
			CodexAgents.install(artifact, isolatedHome);
			Path marketplace = CodexMarketplace.create(artifact, new CodexInstallation(isolatedHome.toAbsolutePath()));
			List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace);
			commands.execute(pluginCommands.get(0), "marketplace-add");
			commands.execute(pluginCommands.get(1), "plugin-add");
			installPermissions(isolatedHome, replacer);
		}
		for (int i = 0; i < trialPlugins.size(); ++i)
		{
			String name = names.get(i);
			Path marketplace = CodexMarketplace.create(trialPlugins.get(i), name, name, isolatedHome);
			List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace, name);
			// Each plugin's steps are named after it, so that the run's log tells the plugins apart
			commands.execute(pluginCommands.get(0), "trial-plugin-" + name + "-marketplace-add");
			commands.execute(pluginCommands.get(1), "trial-plugin-" + name + "-add");
		}
	}

	/**
	 * Writes the entries that CAT's installer writes for the CAT version that Codex installed into the isolated home.
	 *
	 * @param isolatedHome the isolated Codex home
	 * @param replacer replaces the rules file and {@code config.toml}
	 * @throws IOException if Codex did not install exactly one copy of CAT, or an entry cannot be written
	 */
	private static void installPermissions(Path isolatedHome, FileReplacer replacer) throws IOException
	{
		Path home = isolatedHome.toAbsolutePath().normalize();
		List<Path> copies = InstalledPlugins.of(home, CAT_PLUGIN_ID);
		if (copies.size() != 1)
		{
			throw new IOException("Codex did not install exactly one copy of " + CAT_PLUGIN_ID + " into " + home +
				": " + copies);
		}
		new CodexIntegration().installPermissions(home, copies.get(0).resolve(PluginDirectory.INSTALLER), replacer);
	}

	/**
	 * Returns the command that starts the app-server. With a plugin installed, the app-server enables hooks so the
	 * plugin's hooks run; without one, the app-server disables plugins so no installed plugin affects the run.
	 *
	 * @param executable the Codex executable
	 * @param pluginsInstalled {@code true} if CAT or a trial plugin is installed into the isolated home
	 * @return the app-server command
	 */
	public static List<String> appServerCommand(String executable, boolean pluginsInstalled)
	{
		requireThat(executable, "executable").isNotBlank();
		List<String> command = new ArrayList<>(List.of(executable, "app-server", "--stdio"));
		command.addAll(featureFlags(pluginsInstalled));
		return List.copyOf(command);
	}

	/**
	 * Returns the options that turn Codex features on or off for a session's app-server.
	 *
	 * @param pluginsInstalled {@code true} if CAT or a trial plugin is installed into the isolated home
	 * @return the options
	 */
	private static List<String> featureFlags(boolean pluginsInstalled)
	{
		if (pluginsInstalled)
			return List.of("--enable", "hooks");
		return List.of("--disable", "plugins");
	}

	/**
	 * Refuses a session whose commands may reach some hosts unless {@code codex features list}, run with the session's
	 * Codex home and its app-server's feature options, shows Codex's network proxy on. Only that proxy limits the
	 * commands to the profile's hosts, and Codex silently ignores a feature that it no longer knows, so without the
	 * check a Codex release that renames or removes the feature would let the commands reach every host. A session
	 * whose commands may reach no host needs no proxy: its network is off.
	 *
	 * @param codex        runs the session's Codex
	 * @param featureFlags the app-server's feature options
	 * @param hosts        the hosts that the session's commands may reach
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if Codex's network proxy is not on
	 * @throws IOException             if Codex cannot be run
	 */
	public static void requireNetworkProxy(CodexOutput codex, List<String> featureFlags, Set<String> hosts)
		throws IOException
	{
		requireThat(codex, "codex").isNotNull();
		requireThat(featureFlags, "featureFlags").isNotNull();
		requireThat(hosts, "hosts").isNotNull();
		if (hosts.isEmpty())
			return;
		List<String> arguments = new ArrayList<>(List.of("features", "list"));
		arguments.addAll(featureFlags);
		for (String row : codex.read(arguments).lines().toList())
		{
			List<String> columns = List.of(row.strip().split("\\s+"));
			if (columns.getFirst().equals(CodexConnectionConfiguration.NETWORK_PROXY_FEATURE) &&
				columns.getLast().equals("true"))
			{
				return;
			}
		}
		String version = codex.read(List.of("--version")).lines().findFirst().orElse("").strip();
		if (version.startsWith("codex-cli "))
			version = version.substring("codex-cli ".length());
		throw new SessionRefusedException("Codex " + version + " no longer limits the session's network to " +
			String.join(", ", new TreeSet<>(hosts)) + " (`features.network_proxy` is not available), so CAT does not " +
			"start the trial.");
	}

	/**
	 * Runs the session's Codex and returns what it prints, keeping its output among the run's artifacts.
	 *
	 * @param executable  the Codex executable
	 * @param arguments   the arguments
	 * @param worktree    the session's project, which Codex runs in
	 * @param environment the session's environment
	 * @param executables runs Codex
	 * @param manager     the run manager
	 * @param run         the session's run
	 * @return the stdout
	 * @throws IOException if Codex fails or its output cannot be kept or read
	 */
	private static String commandOutput(String executable, List<String> arguments, Path worktree,
		Map<String, String> environment, ExecutableFiles executables, HarnessRunManager manager, HarnessRun run)
		throws IOException
	{
		List<String> command = new ArrayList<>(List.of(executable));
		command.addAll(arguments);
		String name = ("codex-" + String.join("-", arguments)).replaceAll("[^a-z0-9]+", "-").
			replaceAll("^-|-$", "");
		HarnessCommand.execute(command, worktree, environment, executables, manager, run, name);
		return BoundedInput.readUtf8(run.directory().resolve("artifacts").resolve(name + ".stdout"),
			MAXIMUM_COMMAND_OUTPUT_BYTES, "Codex output");
	}

	/**
	 * Returns the failure to start a session's app-server or thread as a refusal when bubblewrap cannot start on the
	 * system, as on stock Ubuntu 24.04, whose AppArmor keeps unprivileged processes from creating namespaces: either
	 * bubblewrap could not set up the loopback interface of the sandbox's network namespace, or it could not map the
	 * user into a new user namespace. Neither Codex's sandbox nor the bubblewrap around the app-server could then
	 * confine the session, so it must not run.
	 *
	 * @param failure the failure to start the thread
	 * @return the refusal, or {@code failure} itself if the sandbox did not cause it
	 * @throws NullPointerException if {@code failure} is null
	 */
	public static IOException refusalOf(IOException failure)
	{
		requireThat(failure, "failure").isNotNull();
		String message = failure.getMessage();
		if (message == null || !message.contains(SANDBOX_LOOPBACK_FAILURE) &&
			!message.contains(SANDBOX_UID_MAP_FAILURE))
		{
			return failure;
		}
		return new SessionRefusedException("CAT does not start the session: bubblewrap could not start its sandbox (" +
			message + "). On Ubuntu 24.04 and later, AppArmor keeps bubblewrap from creating the namespaces that the " +
			"sandbox needs; let bubblewrap create user namespaces, for example with an AppArmor profile for bwrap, " +
			"then start the session again.", failure);
	}

	/**
	 * Returns a command that runs another command under bubblewrap, which replaces each of several directories with an
	 * empty one and mounts back inside it each path that the command may write or read. The command then sees nothing
	 * else of those directories, however many files they hold.
	 *
	 * @param command    the command to run
	 * @param bubblewrap the bubblewrap executable
	 * @param hidden     the existing directories to replace with empty ones, none inside another
	 * @param writable   the existing paths that the command may read and write; those outside every hidden directory
	 *                   are left as they are
	 * @param readOnly   the existing paths that the command may read but not change; those outside every hidden
	 *                   directory are left as they are
	 * @return the command that runs {@code command}, or {@code command} itself if {@code hidden} is empty
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the real path of a hidden directory or of a path inside one cannot be read
	 */
	public static List<String> withHiddenDirectories(List<String> command, Path bubblewrap, List<Path> hidden,
		List<Path> writable, List<Path> readOnly) throws IOException
	{
		requireThat(command, "command").isNotEmpty();
		requireThat(bubblewrap, "bubblewrap").isNotNull();
		requireThat(hidden, "hidden").isNotNull();
		requireThat(writable, "writable").isNotNull();
		requireThat(readOnly, "readOnly").isNotNull();
		if (hidden.isEmpty())
			return command;
		List<String> wrapped = new ArrayList<>(List.of(bubblewrap.toString(), "--dev-bind", "/", "/"));
		// The paths are compared in their real form, so that a directory reached through a symbolic link, such as
		// /var/folders/... on macOS, which leads to /private/var/folders/..., still holds the session's real paths
		List<Path> hiddenReal = new ArrayList<>();
		for (Path directory : hidden)
		{
			Path real = directory.toRealPath();
			hiddenReal.add(real);
			wrapped.addAll(List.of("--tmpfs", real.toString()));
		}
		// A path is mounted after the paths that contain it, so that a read-only path inside a writable one stays
		// read-only
		Map<Path, String> reopened = new TreeMap<>(Comparator.comparingInt(Path::getNameCount).
			thenComparing(Comparator.naturalOrder()));
		for (Path path : writable)
			reopened.put(path.toRealPath(), "--bind");
		for (Path path : readOnly)
			reopened.put(path.toRealPath(), "--ro-bind");
		for (Entry<Path, String> path : reopened.entrySet())
		{
			if (hiddenReal.stream().noneMatch(path.getKey()::startsWith))
				continue;
			String name = path.getKey().toString();
			wrapped.addAll(List.of(path.getValue(), name, name));
		}
		// The command would otherwise write files into the empty directories, which vanish when it ends
		for (Path directory : hiddenReal)
			wrapped.addAll(List.of("--remount-ro", directory.toString()));
		// Codex dies when bubblewrap does, so stopping the recorded harness process also stops Codex
		wrapped.addAll(List.of("--die-with-parent", "--"));
		wrapped.addAll(command);
		return wrapped;
	}

	/**
	 * Returns the first executable {@code bwrap} file in the directories of the caller's {@code PATH}.
	 *
	 * @param environment the caller's environment
	 * @return the bubblewrap executable, or empty if the {@code PATH} has none
	 * @throws NullPointerException if {@code environment} is null
	 */
	public static Optional<Path> bubblewrap(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		String path = environment.getOrDefault("PATH", "");
		for (String directory : path.split(File.pathSeparator))
		{
			if (directory.isEmpty())
				continue;
			Path candidate = Path.of(directory, "bwrap");
			if (Files.isRegularFile(candidate) && Files.isExecutable(candidate))
				return Optional.of(candidate);
		}
		return Optional.empty();
	}

	/**
	 * Returns the isolated setup commands that install CAT from this run's marketplace artifact.
	 *
	 * @param executable the Codex executable
	 * @param marketplace the run-local marketplace directory
	 * @return the ordered marketplace-add and plugin-add commands
	 */
	public static List<List<String>> pluginSetupCommands(String executable, Path marketplace)
	{
		return pluginSetupCommands(executable, marketplace, CodexMarketplace.CAT);
	}

	/**
	 * Returns the isolated setup commands that install a plugin from its own run-local marketplace, which bears the
	 * plugin's name.
	 *
	 * @param executable the Codex executable
	 * @param marketplace the run-local marketplace directory
	 * @param name the plugin's manifest name
	 * @return the ordered marketplace-add and plugin-add commands
	 */
	public static List<List<String>> pluginSetupCommands(String executable, Path marketplace, String name)
	{
		requireThat(executable, "executable").isNotBlank();
		requireThat(marketplace, "marketplace").isNotNull();
		requireThat(name, "name").isNotBlank();
		return List.of(
			List.of(executable, "plugin", "marketplace", "add", marketplace.toString()),
			List.of(executable, "plugin", "add", name + "@" + name));
	}

	/**
	 * Returns Codex's configuration file.
	 *
	 * @param home a Codex home directory
	 * @return the configuration file in {@code home}
	 */
	private static Path configuration(Path home)
	{
		return new CodexInstallation(home.toAbsolutePath()).configuration();
	}

	/**
	 * Closes a lock while preserving the original startup failure.
	 *
	 * @param lock the acquired lock
	 * @return an exception representing lock closure failure, or {@code null}
	 */
	private static Exception close(HarnessRunLock lock)
	{
		assert lock != null;
		try
		{
			lock.close();
			return null;
		}
		catch (IOException e)
		{
			return e;
		}
	}

	/**
	 * Indicates whether the isolated home needs a copied credential file.
	 *
	 * @param environment the parent process environment
	 * @return {@code true} if no usable Codex authentication variable is present
	 */
	public static boolean requiresCredentialFiles(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		return AUTHENTICATION_VARIABLES.stream().map(environment::get).noneMatch(value ->
			value != null && !value.isBlank());
	}

	/**
	 * The sandbox of a session: the permission profile that its thread names and the command that starts its
	 * app-server.
	 *
	 * @param profile the profile that the thread names, or an empty string if the thread uses the workspace-write
	 *                sandbox
	 * @param command the command that starts the app-server
	 */
	public record SessionSandbox(String profile, List<String> command)
	{
		/**
		 * Creates a session's sandbox.
		 *
		 * @param profile the profile that the thread names, or an empty string if the thread uses the workspace-write
		 *                sandbox
		 * @param command the command that starts the app-server
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code command} is empty
		 */
		public SessionSandbox
		{
			requireThat(profile, "profile").isNotNull();
			requireThat(command, "command").isNotEmpty();
			command = List.copyOf(command);
		}
	}

	/**
	 * Runs a session's Codex.
	 */
	@FunctionalInterface
	public interface CodexOutput
	{
		/**
		 * Runs the session's Codex.
		 *
		 * @param arguments the arguments, without the executable
		 * @return the stdout
		 * @throws IOException if Codex fails
		 */
		String read(List<String> arguments) throws IOException;
	}
}

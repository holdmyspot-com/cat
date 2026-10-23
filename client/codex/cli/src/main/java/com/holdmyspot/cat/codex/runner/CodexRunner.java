package com.holdmyspot.cat.codex.runner;

import static com.holdmyspot.cat.codex.CodexPluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
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
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.PluginManifest;
import com.holdmyspot.cat.common.runner.SessionCommandLimits;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestInstructions;
import com.holdmyspot.cat.common.runner.SetupCommands;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

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
	 * The name of Codex's configuration file in the Codex home.
	 */
	private static final String CONFIGURATION = "config.toml";
	/**
	 * The path of a Codex plugin's manifest, relative to the plugin directory.
	 */
	private static final Path MANIFEST = Path.of(".codex-plugin/plugin.json");
	/**
	 * The system's shared temporary directory, which the app-server replaces with a private one.
	 */
	private static final Path TMP = Path.of("/tmp");
	private final ExecutableFiles executables;
	private final ProcessStarter processes;

	/**
	 * Starts the app-server process.
	 */
	@FunctionalInterface
	interface ProcessStarter
	{
		/**
		 * Starts a process.
		 *
		 * @param command the executable and its arguments
		 * @param workingDirectory the process's working directory
		 * @param environment the process's complete environment
		 * @return the started process
		 * @throws IOException if the process cannot start
		 */
		Process start(List<String> command, Path workingDirectory, Map<String, String> environment)
			throws IOException;
	}

	/**
	 * Creates a Codex artifact runner.
	 *
	 * @param executables runs the app-server's and the setup commands' files
	 * @throws NullPointerException if {@code executables} is null
	 */
	public CodexRunner(ExecutableFiles executables)
	{
		this(executables, (command, workingDirectory, environment) ->
			HarnessProcessStarter.start(command, workingDirectory, environment, executables));
	}

	/**
	 * Creates a Codex artifact runner that starts the app-server through {@code processes}.
	 *
	 * @param executables runs the setup commands' files
	 * @param processes   starts the app-server process
	 * @throws NullPointerException if any of the arguments are null
	 */
	CodexRunner(ExecutableFiles executables, ProcessStarter processes)
	{
		requireThat(executables, "executables").isNotNull();
		requireThat(processes, "processes").isNotNull();
		this.executables = executables;
		this.processes = processes;
	}

	/**
	 * Starts one persistent Codex app-server process in an owned run directory.
	 *
	 * @param artifact the complete Codex artifact
	 * @param project the source project to copy
	 * @param manager the run manager
	 * @param executable the Codex executable
	 * @param environment the scrubbed isolated environment
	 * @return the live owned Codex run session
	 * @throws IOException if validation, copying, or process startup fails
	 */
	public HarnessRunSession start(Path artifact, Path project, HarnessRunManager manager, String executable,
		Map<String, String> environment) throws IOException
	{
		return start(artifact, project, manager, executable, environment, "", false);
	}

	/**
	 * Starts one persistent Codex app-server process in an owned run directory.
	 *
	 * @param artifact the complete Codex artifact
	 * @param project the source project to copy
	 * @param manager the run manager
	 * @param executable the Codex executable
	 * @param environment the scrubbed isolated environment
	 * @param model the optional Codex model name
	 * @param installCat whether CAT is installed in the isolated home
	 * @return the live owned Codex run session
	 * @throws IOException if validation, copying, or process startup fails
	 */
	public HarnessRunSession start(Path artifact, Path project, HarnessRunManager manager, String executable,
		Map<String, String> environment, String model, boolean installCat) throws IOException
	{
		return start(artifact, project, null, null, manager, executable, environment, model, "", installCat, null,
			List.of(), List.of(), 0, false, 0);
	}

	/**
	 * Starts one persistent Codex app-server process in an owned run directory with an optional direct CAT agent.
	 *
	 * @param artifact the complete Codex artifact
	 * @param project the source project to copy
	 * @param manager the run manager
	 * @param executable the Codex executable
	 * @param environment the scrubbed isolated environment
	 * @param model the optional Codex model name
	 * @param effort the optional reasoning effort of each turn
	 * @param installCat whether CAT is installed in the isolated home
	 * @param agent the optional direct CAT agent definition, whose model and effort replace {@code model} and
	 *   {@code effort}
	 * @return the live owned Codex run session
	 * @throws IOException if validation, copying, or process startup fails
	 */
	HarnessRunSession start(Path artifact, Path project, HarnessRunManager manager, String executable,
		Map<String, String> environment, String model, String effort, boolean installCat, CodexAgentDefinition agent)
		throws IOException
	{
		return start(artifact, project, null, List.of(), manager, executable, environment, model, effort, installCat,
			agent, List.of(), List.of(), 0, false, 0);
	}

	/**
	 * Starts a runner whose validator may read, but not modify, completed executor evidence.
	 *
	 * @param artifact the complete Codex artifact
	 * @param project the validator project
	 * @param workspace the optional directory whose files are copied into the project on top of {@code project}
	 * @param trialPlugins the trial plugins, each installed into the isolated home from the marketplace that bears its
	 *   manifest's name, beside CAT when {@code installCat} is set, with the user's approvals of its hooks
	 * @param manager the run manager
	 * @param executable the Codex executable
	 * @param environment the scrubbed isolated environment
	 * @param model the optional Codex model name
	 * @param effort the optional reasoning effort of each turn
	 * @param installCat whether CAT is installed in the isolated home
	 * @param agent the optional direct CAT agent definition, whose model and effort replace {@code model} and
	 *   {@code effort}
	 * @param readOnlyEvidence the optional completed evidence directory
	 * @param deniedReads the existing files and directories that the session must not read, besides the records of
	 *   its own run
	 * @param toolOutputTokenLimit the most tokens of each tool output that the session keeps, or {@code 0} for
	 *   Codex's default
	 * @param sessionTranscripts whether the session's threads are persistent, and the run copies their rollouts into its
	 *   session transcripts directory when the session closes; otherwise the threads are ephemeral and Codex keeps no
	 *   rollout
	 * @param sessionRequests how many sessions the session may request over its run, or {@code 0} if it may request
	 *   none
	 * @return the live owned Codex run session
	 * @throws IOException if validation, copying, or process startup fails
	 */
	HarnessRunSession start(Path artifact, Path project, Path workspace, List<Path> trialPlugins,
		HarnessRunManager manager, String executable, Map<String, String> environment, String model, String effort,
		boolean installCat, CodexAgentDefinition agent, List<Path> readOnlyEvidence, List<Path> deniedReads,
		int toolOutputTokenLimit, boolean sessionTranscripts, int sessionRequests) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(trialPlugins, "trialPlugins").isNotNull();
		requireThat(manager, "manager").isNotNull();
		requireThat(executable, "executable").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		requireThat(model, "model").isNotNull();
		requireThat(effort, "effort").isNotNull();
		requireThat(toolOutputTokenLimit, "toolOutputTokenLimit").isNotNegative();
		requireThat(sessionRequests, "sessionRequests").isNotNegative();
		if (agent != null && !installCat)
			throw new IOException("A direct CAT agent requires --install-cat");
		artifact = HarnessArtifact.validate(artifact, Path.of(".codex-plugin/plugin.json"));
		HarnessRun run = manager.create(HARNESS_ID);
		HarnessRunLock lock = null;
		try
		{
			manager.copyProject(project, run);
			if (workspace != null)
				manager.copyWorkspace(workspace, run);
			manager.snapshotProject(run);
			HarnessProjectRepository.initialize(run.directory().resolve("project"), run.directory(), environment,
				executables);
			Path isolatedHome = run.directory().resolve("home");
			Path temporaryDirectory = Files.createDirectories(isolatedHome.resolve("tmp"));
			Path hostHome = codexHome(environment);
			HarnessCredentials.copyAllowedFiles(hostHome, isolatedHome, Set.of("auth.json"));
			Path isolatedConfiguration = isolatedHome.resolve(CONFIGURATION);
			HarnessDependencyCaches caches = HarnessDependencyCache.verify(environment);
			SessionRequestAccess requestAccess = SessionRequestAccess.allowing(run, artifact, sessionRequests,
				Platform.current()).orElse(null);
			CodexConnectionConfiguration.copy(hostHome.resolve(CONFIGURATION), isolatedConfiguration, caches,
				temporaryDirectory, toolOutputTokenLimit, requestAccess, Platform.current());
			List<Path> sessionReadOnly = sessionReadOnly(readOnlyEvidence, requestAccess);
			String permissionProfile = CodexConnectionConfiguration.addSessionProfile(isolatedConfiguration,
				sessionReadOnly, sessionDeniedReads(manager, run, sessionReadOnly, deniedReads, Platform.current(),
					environment, requestAccess));
			Map<String, String> isolatedEnvironment = processEnvironment(environment, caches, isolatedHome,
				run.directory());
			Path worktree = run.directory().resolve("project");
			installPlugins(executable, artifact, installCat, trialPlugins, hostHome, isolatedHome, isolatedEnvironment,
				executables, (setup, step) -> HarnessCommand.execute(setup, worktree, isolatedEnvironment, executables,
					manager, run, step));
			List<String> appServer = appServerCommand(executable, installCat || !trialPlugins.isEmpty());
			List<String> command = bubblewrap(environment).
				map(bubblewrap -> withPrivateTmp(appServer, bubblewrap, run.directory())).
				orElse(appServer);
			manager.initialize(run, artifact, project, "codex_app_server", command);
			AbstractHarnessRunnerScope scope = new MainHarnessRunnerScope(environment);
			lock = manager.acquireLock(run);
			HarnessSessionTranscripts transcripts = null;
			if (sessionTranscripts)
				transcripts = CodexProcess.rollouts(isolatedHome, run.sessionTranscripts());
			CodexProcess process = new CodexProcess(processes.start(command, worktree, isolatedEnvironment), scope,
				installCat, transcripts);
			try
			{
				manager.recordHarnessProcess(run, process.pid(), process.startedAt());
				String selectedModel = model;
				String agentInstructions = "";
				String selectedEffort = effort;
				if (agent != null)
				{
					selectedModel = agent.model();
					agentInstructions = agent.developerInstructions();
					selectedEffort = agent.effort();
				}
				process.initialize(worktree, selectedModel, developerInstructions(manager.root().toRealPath(), worktree,
					isolatedHome, agentInstructions, sessionRequests), selectedEffort, permissionProfile);
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
			manager.fail(run, e.getMessage());
			throw e;
		}
	}

	/**
	 * Returns the environment of a run's app-server. It holds the caller's generic operating-system variables, its
	 * Codex authentication variables and the shared dependency caches, names the run's isolated home as the Codex home,
	 * and stops Git from finding a repository above the run directory.
	 *
	 * @param environment  the caller's environment
	 * @param caches       the shared dependency caches
	 * @param isolatedHome the run's isolated home
	 * @param runDirectory the run's directory
	 * @return the environment, which the caller may change
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code runDirectory} is relative
	 */
	public static Map<String, String> processEnvironment(Map<String, String> environment,
		HarnessDependencyCaches caches, Path isolatedHome, Path runDirectory)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(caches, "caches").isNotNull();
		requireThat(isolatedHome, "isolatedHome").isNotNull();
		Map<String, String> isolatedEnvironment = new HashMap<>(HarnessEnvironment.scrub(environment));
		HarnessEnvironment.copyAllowed(environment, isolatedEnvironment, AUTHENTICATION_VARIABLES);
		// Each run gets an isolated HOME, but packages belong in the parent's explicitly writable shared caches so
		// independent trials do not redownload the same immutable registry artifacts.
		isolatedEnvironment.putAll(caches.environment());
		isolatedEnvironment.put(CODEX_HOME, isolatedHome.toString());
		HarnessEnvironment.preventGitDiscoveryAbove(isolatedEnvironment, runDirectory);
		return isolatedEnvironment;
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
		return developerInstructions(root, project, home, agentInstructions, 0);
	}

	/**
	 * Returns the developer instructions of a session as
	 * {@link #developerInstructions(Path, Path, Path, String)} does, except that the instructions of a session that may
	 * request sessions tell it how, between the note and the agent's contract.
	 *
	 * @param root the run root, as the denied reads name it
	 * @param project the session's project, which is its working directory
	 * @param home the session's Codex home
	 * @param agentInstructions the agent's contract, or an empty string for a session without an agent
	 * @param sessionRequests how many sessions the session may request; {@code 0} adds no instructions
	 * @return the developer instructions
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code sessionRequests} is negative
	 */
	public static String developerInstructions(Path root, Path project, Path home, String agentInstructions,
		int sessionRequests)
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
			instructions.add(SessionRequestInstructions.text(sessionRequests));
		if (!agentInstructions.isEmpty())
			instructions.add(agentInstructions);
		return instructions.toString();
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
	 * home, as {@link SessionCommandLimits#harnessFiles(Map, Platform)} names them, except the session's own
	 * {@code config.toml}, which holds no login and from which CAT's {@code read-part} command reads the session's tool
	 * output limit. The session therefore cannot read the runner's records of its own run or of any other run, the
	 * workspace files of any run, or a login or the user's configuration. The method creates the session log
	 * directory, because the sandbox skips a denied path that does not exist yet; for the same reason, it leaves out a
	 * login or configuration file that does not exist.
	 * <p>
	 * The result names the run root and each directory that the caller denies rather than each path under them, which
	 * also denies the runs that start after the session. It omits a path inside another directory that it names, except
	 * the session log directory and the login inside the session's home. Every platform's sandbox still lets the
	 * session read and write its project and home inside a denied directory, such as the project that holds the run
	 * root, but the Linux sandbox would also hide read-only evidence there, so a Linux session with evidence under a
	 * denied directory is denied each other file and directory under it by its own path instead.
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
		List<Path> directories = new ArrayList<>(deniedReads);
		directories.add(manager.root());
		for (Path denied : directories)
		{
			Path directory = denied.toAbsolutePath().normalize();
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
		for (Path file : harnessFiles)
		{
			// CAT's read-part command reads the session's tool output limit from the session's configuration
			if (file.equals(home.resolve(CONFIGURATION).toAbsolutePath().normalize()))
				continue;
			if (Files.exists(file, NOFOLLOW_LINKS))
				result.add(file);
		}
		return List.copyOf(result);
	}

	/**
	 * Installs CAT, when requested, and each trial plugin into an isolated Codex home, each from a marketplace of its
	 * own, with the user's approvals of its hooks. Plugin hooks are untrusted in every fresh home, so the approvals are
	 * copied from the user's configuration; Codex rejects an approval whose hook's normalized declaration differs.
	 *
	 * @param executable the Codex executable
	 * @param artifact the complete Codex artifact, which is installed as CAT
	 * @param installCat {@code true} to install CAT
	 * @param trialPlugins the trial plugins, each installed under its manifest's name
	 * @param hostHome the user's Codex home, whose configuration holds the hook approvals
	 * @param isolatedHome the isolated Codex home
	 * @param isolatedEnvironment the isolated environment, which seeding CAT's curated plugin cache runs in
	 * @param executables runs Git while seeding CAT's curated plugin cache
	 * @param commands runs the commands that add each marketplace and each plugin
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code executable} is blank
	 * @throws IOException if a plugin's manifest cannot be read, a configuration or marketplace cannot be written, CAT's
	 *   agents cannot be copied or a command fails
	 */
	public static void installPlugins(String executable, Path artifact, boolean installCat, List<Path> trialPlugins,
		Path hostHome, Path isolatedHome, Map<String, String> isolatedEnvironment, ExecutableFiles executables,
		SetupCommands commands) throws IOException
	{
		requireThat(executable, "executable").isNotBlank();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(trialPlugins, "trialPlugins").isNotNull();
		requireThat(hostHome, "hostHome").isNotNull();
		requireThat(isolatedHome, "isolatedHome").isNotNull();
		requireThat(isolatedEnvironment, "isolatedEnvironment").isNotNull();
		requireThat(executables, "executables").isNotNull();
		requireThat(commands, "commands").isNotNull();
		Path hostConfiguration = hostHome.resolve(CONFIGURATION);
		Path isolatedConfiguration = isolatedHome.resolve(CONFIGURATION);
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
			Path marketplace = CodexMarketplace.create(artifact, isolatedHome);
			List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace);
			commands.execute(pluginCommands.get(0), "marketplace-add");
			commands.execute(pluginCommands.get(1), "plugin-add");
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
		if (pluginsInstalled)
			return List.of(executable, "app-server", "--stdio", "--enable", "hooks");
		return List.of(executable, "app-server", "--stdio", "--disable", "plugins");
	}

	/**
	 * Returns a command that runs another command under bubblewrap with a private, empty {@code /tmp}. A Codex sandbox
	 * hides the directory {@code /tmp/codex-daemon-<uid>} from the commands it runs, and the sandbox helper of an
	 * app-server started there refuses to start without that directory. The app-server cannot then start a thread that
	 * denies reads, so a runner started from a sandboxed Codex session could not start its session.
	 *
	 * @param command the command to run
	 * @param bubblewrap the bubblewrap executable
	 * @param runDirectory the run's directory, which stays visible when it is below {@code /tmp}
	 * @return the command that runs {@code command} with a private {@code /tmp}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static List<String> withPrivateTmp(List<String> command, Path bubblewrap, Path runDirectory)
	{
		requireThat(command, "command").isNotEmpty();
		requireThat(bubblewrap, "bubblewrap").isNotNull();
		requireThat(runDirectory, "runDirectory").isNotNull();
		List<String> wrapped = new ArrayList<>(List.of(bubblewrap.toString(), "--dev-bind", "/", "/", "--tmpfs",
			TMP.toString()));
		Path absoluteRun = runDirectory.toAbsolutePath().normalize();
		if (absoluteRun.startsWith(TMP.toAbsolutePath()))
			wrapped.addAll(List.of("--bind", absoluteRun.toString(), absoluteRun.toString()));
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
	 * Resolves the host Codex configuration directory without copying arbitrary home state.
	 *
	 * @param environment the caller environment
	 * @return the host Codex configuration directory
	 */
	private static Path codexHome(Map<String, String> environment)
	{
		assert environment != null;
		String configured = environment.get(CODEX_HOME);
		if (configured != null && !configured.isBlank())
			return Path.of(configured);
		String home = environment.get(Platform.current().homeVariable());
		if (home == null || home.isBlank())
			return Path.of(".codex").toAbsolutePath();
		return Path.of(home).resolve(".codex");
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
}

package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.cat.codex.CodexPluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.runner.HarnessArtifact;
import io.github.cowwoc.cat.common.runner.HarnessCommand;
import io.github.cowwoc.cat.common.runner.HarnessCredentials;
import io.github.cowwoc.cat.common.runner.HarnessDependencyCache;
import io.github.cowwoc.cat.common.runner.HarnessEnvironment;
import io.github.cowwoc.cat.common.runner.HarnessProcessStarter;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunLock;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessProjectRepository;
import io.github.cowwoc.cat.common.runner.HarnessRunSession;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.MainHarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.PluginManifest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Creates an isolated Codex configuration and starts one persistent Codex CLI process, optionally with CAT.
 */
public final class CodexRunner
{
	/**
	 * The directory, beside a run's transcript in its {@code artifacts} directory, that keeps the rollouts of the
	 * session's threads after the run's Codex home is deleted. A rollout records what a thread's model received.
	 */
	public static final String ROLLOUTS = "rollouts";
	private static final Set<String> AUTHENTICATION_VARIABLES = Set.of("CODEX_API_KEY", "OPENAI_API_KEY");
	/**
	 * The path of a Codex plugin's manifest, relative to the plugin directory.
	 */
	private static final Path MANIFEST = Path.of(".codex-plugin/plugin.json");
	/**
	 * The system's shared temporary directory, which the app-server replaces with a private one.
	 */
	private static final Path TMP = Path.of("/tmp");
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
	 */
	public CodexRunner()
	{
		this(HarnessProcessStarter::start);
	}

	/**
	 * Creates a Codex artifact runner that starts the app-server through {@code processes}.
	 *
	 * @param processes starts the app-server process
	 * @throws NullPointerException if {@code processes} is null
	 */
	CodexRunner(ProcessStarter processes)
	{
		requireThat(processes, "processes").isNotNull();
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
			List.of(), List.of(), 0);
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
		return start(artifact, project, null, null, manager, executable, environment, model, effort, installCat, agent,
			List.of(), List.of(), 0);
	}

	/**
	 * Starts a runner whose validator may read, but not modify, completed executor evidence.
	 *
	 * @param artifact the complete Codex artifact
	 * @param project the validator project
	 * @param fixtures the optional directory whose files are copied into the project on top of {@code project}
	 * @param plugin the optional plugin under test, which is installed into the isolated home from the marketplace
	 *   that bears its manifest's name, beside CAT when {@code installCat} is set, with the user's approvals of its
	 *   hooks
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
	 * @return the live owned Codex run session
	 * @throws IOException if validation, copying, or process startup fails
	 */
	HarnessRunSession start(Path artifact, Path project, Path fixtures, Path plugin, HarnessRunManager manager,
		String executable,
		Map<String, String> environment, String model, String effort, boolean installCat, CodexAgentDefinition agent,
		List<Path> readOnlyEvidence, List<Path> deniedReads, int toolOutputTokenLimit) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(manager, "manager").isNotNull();
		requireThat(executable, "executable").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		requireThat(model, "model").isNotNull();
		requireThat(effort, "effort").isNotNull();
		requireThat(toolOutputTokenLimit, "toolOutputTokenLimit").isNotNegative();
		if (agent != null && !installCat)
			throw new IOException("A direct CAT agent requires --install-cat");
		artifact = HarnessArtifact.validate(artifact, Path.of(".codex-plugin/plugin.json"));
		HarnessRun run = manager.create(HARNESS_ID);
		HarnessRunLock lock = null;
		try
		{
			manager.copyProject(project, run);
			if (fixtures != null)
				manager.copyFixtures(fixtures, run);
			manager.snapshotProject(run);
			HarnessProjectRepository.initialize(run.directory().resolve("project"), run.directory(), environment);
			Path isolatedHome = run.directory().resolve("home");
			Path temporaryDirectory = Files.createDirectories(isolatedHome.resolve("tmp"));
			Path hostHome = codexHome(environment);
			HarnessCredentials.copyAllowedFiles(hostHome, isolatedHome, Set.of("auth.json"));
			Path isolatedConfiguration = isolatedHome.resolve("config.toml");
			Path npmCache = HarnessDependencyCache.verifyNpm(environment);
			CodexConnectionConfiguration.copy(hostHome.resolve("config.toml"), isolatedConfiguration, npmCache,
				temporaryDirectory, toolOutputTokenLimit, Platform.current());
			String permissionProfile = CodexConnectionConfiguration.addSessionProfile(isolatedConfiguration,
				readOnlyEvidence, sessionDeniedReads(manager, run, deniedReads));
			String pluginName = "";
			if (plugin != null)
			{
				pluginName = PluginManifest.name(plugin, MANIFEST);
				// Plugin hooks are untrusted in every fresh runner home; reuse the user's approvals of the plugin under
				// test's hooks as for CAT's below
				CodexConnectionConfiguration.copyTrustedHooks(hostHome.resolve("config.toml"), isolatedConfiguration,
					pluginName);
			}
			if (installCat)
			{
				// Plugin hooks are untrusted in every fresh runner home. Reuse only the user's existing approvals for
				// CAT's hooks; Codex rejects each if its normalized declaration differs, while preserving the
				// workspace-write sandbox for the actual run.
				CodexConnectionConfiguration.copyTrustedCatHooks(hostHome.resolve("config.toml"),
					isolatedConfiguration);
			}
			Map<String, String> isolatedEnvironment = new HashMap<>(HarnessEnvironment.scrub(environment));
			HarnessEnvironment.copyAllowed(environment, isolatedEnvironment, AUTHENTICATION_VARIABLES);
			// Each run gets an isolated HOME, but packages belong in the parent's explicitly writable shared npm cache so
			// independent trials do not redownload the same immutable registry artifacts.
			isolatedEnvironment.put("NPM_CONFIG_CACHE", npmCache.toString());
			isolatedEnvironment.put("CODEX_HOME", isolatedHome.toString());
			HarnessEnvironment.preventGitDiscoveryAbove(isolatedEnvironment, run.directory());
			Path worktree = run.directory().resolve("project");
			if (installCat)
			{
				CodexCuratedPluginCache.seed(hostHome, isolatedHome, isolatedEnvironment);
				// Codex resolves custom agents from CODEX_HOME/agents, not the installed plugin. Copy the definitions from
				// this run's artifact so delegated agents receive the same CAT contract as the primary isolated process,
				// rather than an unrelated version from the host's global Codex home.
				CodexAgents.install(artifact, isolatedHome);
				Path marketplace = CodexMarketplace.create(artifact, isolatedHome);
				List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace);
				HarnessCommand.execute(pluginCommands.get(0), worktree, isolatedEnvironment, manager, run, "marketplace-add");
				HarnessCommand.execute(pluginCommands.get(1), worktree, isolatedEnvironment, manager, run, "plugin-add");
			}
			if (plugin != null)
			{
				Path marketplace = CodexMarketplace.create(plugin, pluginName, pluginName, isolatedHome);
				List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace, pluginName);
				HarnessCommand.execute(pluginCommands.get(0), worktree, isolatedEnvironment, manager, run,
					"plugin-under-test-marketplace-add");
				HarnessCommand.execute(pluginCommands.get(1), worktree, isolatedEnvironment, manager, run,
					"plugin-under-test-add");
			}
			List<String> appServer = appServerCommand(executable, installCat || plugin != null);
			List<String> command = bubblewrap(environment).
				map(bubblewrap -> withPrivateTmp(appServer, bubblewrap, run.directory())).
				orElse(appServer);
			manager.initialize(run, artifact, project, "codex_app_server", command);
			HarnessRunnerScope scope = new MainHarnessRunnerScope();
			lock = manager.acquireLock(run);
			CodexProcess process = new CodexProcess(processes.start(command, worktree, isolatedEnvironment), scope,
				installCat, isolatedHome, run.directory().resolve("artifacts").resolve(ROLLOUTS));
			try
			{
				manager.recordHarnessProcess(run, process.pid(), process.startedAt());
				String selectedModel = model;
				String developerInstructions = "";
				String selectedEffort = effort;
				if (agent != null)
				{
					selectedModel = agent.model();
					developerInstructions = agent.developerInstructions();
					selectedEffort = agent.effort();
				}
				process.initialize(worktree, selectedModel, developerInstructions, selectedEffort, permissionProfile);
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
	 * Returns the files and directories that a session of a run must not read: those that the caller denies, the
	 * runner's records of the run, and the directory where Codex logs the session's events. The method creates that
	 * directory, because the sandbox skips a denied path that does not exist yet.
	 *
	 * @param manager the run manager
	 * @param run the session's run
	 * @param deniedReads the files and directories that the caller denies the session reading
	 * @return the paths to deny
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the session log directory cannot be created
	 */
	public static List<Path> sessionDeniedReads(HarnessRunManager manager, HarnessRun run, List<Path> deniedReads)
		throws IOException
	{
		requireThat(manager, "manager").isNotNull();
		requireThat(run, "run").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		List<Path> result = new ArrayList<>(deniedReads);
		result.addAll(manager.runnerRecords(run));
		// The session's events are the same record as the run's artifacts, kept by Codex instead of the runner
		result.add(Files.createDirectories(run.directory().resolve("home").resolve("sessions")));
		return result;
	}

	/**
	 * Returns the command that starts the app-server. With a plugin installed, the app-server enables hooks so the
	 * plugin's hooks run; without one, the app-server disables plugins so no installed plugin affects the run.
	 *
	 * @param executable the Codex executable
	 * @param pluginsInstalled {@code true} if CAT or a plugin under test is installed into the isolated home
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
		String configured = environment.get("CODEX_HOME");
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
	 * Determines whether the isolated home needs a copied credential file.
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

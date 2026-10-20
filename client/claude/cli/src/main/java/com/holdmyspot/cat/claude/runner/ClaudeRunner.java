package com.holdmyspot.cat.claude.runner;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import com.holdmyspot.cat.claude.ClaudeConfiguration;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ProjectRuleLoader;
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
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.MainHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.PluginManifest;
import com.holdmyspot.cat.common.runner.SetupCommands;
import com.holdmyspot.cat.common.runner.UserRuleTree;

import java.io.IOException;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates an isolated Claude configuration and starts one persistent Claude CLI process, optionally with CAT.
 */
public final class ClaudeRunner
{
	private static final Set<String> AUTHENTICATION_VARIABLES = Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
		"CLAUDE_CODE_OAUTH_TOKEN");
	/**
	 * The path of a Claude plugin's manifest, relative to the plugin directory.
	 */
	private static final Path MANIFEST = Path.of(".claude-plugin/plugin.json");

	/**
	 * Resolves the launch configuration Bubblewrap inspects beside one run's Claude temporary directory.
	 *
	 * @param claudeTemporaryDirectory the run-owned Claude temporary directory
	 * @return the required launch configuration path
	 */
	public static Path launchConfigurationPath(Path claudeTemporaryDirectory)
	{
		requireThat(claudeTemporaryDirectory, "claudeTemporaryDirectory").isNotNull();
		Path parent = claudeTemporaryDirectory.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IllegalArgumentException("Claude temporary directory has no parent: " + claudeTemporaryDirectory);
		return parent.resolve(".claude/launch.json");
	}

	/**
	 * Creates a Claude artifact runner.
	 */
	public ClaudeRunner()
	{
	}

	/**
	 * Starts one persistent Claude stream-json process in an owned run directory.
	 *
	 * @param artifact the complete Claude artifact
	 * @param project the source project to copy
	 * @param manager the run manager
	 * @param executable the Claude executable
	 * @param environment the scrubbed isolated environment
	 * @return the live owned Claude run session
	 * @throws IOException if validation, copying, or process startup fails
	 */
	public HarnessRunSession start(Path artifact, Path project, HarnessRunManager manager, String executable,
		Map<String, String> environment) throws IOException
	{
		return start(artifact, project, manager, executable, environment, "", false);
	}

	/**
	 * Starts one persistent Claude stream-json process in an owned run directory.
	 *
	 * @param artifact the complete Claude artifact
	 * @param project the source project to copy
	 * @param manager the run manager
	 * @param executable the Claude executable
	 * @param environment the scrubbed isolated environment
	 * @param model the optional Claude model name
	 * @param installCat whether CAT is installed in the isolated home
	 * @return the live owned Claude run session
	 * @throws IOException if validation, copying, or process startup fails
	 */
	public HarnessRunSession start(Path artifact, Path project, HarnessRunManager manager, String executable,
		Map<String, String> environment, String model, boolean installCat) throws IOException
	{
		return start(artifact, project, null, List.of(), manager, executable, environment, model, "", null, installCat,
			true, List.of(), List.of());
	}

	/**
	 * Starts a validator with completed executor evidence mounted outside its writable workspace.
	 *
	 * @param artifact the complete Claude artifact
	 * @param project the validator project
	 * @param workspace the optional directory whose files are copied into the project on top of {@code project}
	 * @param trialPlugins the trial plugins, each installed into the isolated home from the marketplace that bears its
	 *   manifest's name, beside CAT when {@code installCat} is set
	 * @param manager the run manager
	 * @param executable the Claude executable
	 * @param environment the isolated environment
	 * @param model the requested model
	 * @param effort the requested reasoning effort
	 * @param agent the optional CAT agent selected for the session
	 * @param installCat whether CAT is installed
	 * @param includeUserRules whether CAT copies the parent user's rules into the isolated home
	 * @param readOnlyEvidence executor evidence exposed through added directories
	 * @param deniedReads the files and directories that the session must not read
	 * @return the live run session
	 * @throws IOException if startup fails
	 */
	HarnessRunSession start(Path artifact, Path project, Path workspace, List<Path> trialPlugins,
		HarnessRunManager manager, String executable, Map<String, String> environment, String model, String effort,
		ClaudeAgentDefinition agent, boolean installCat, boolean includeUserRules, List<Path> readOnlyEvidence,
		List<Path> deniedReads) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(trialPlugins, "trialPlugins").isNotNull();
		requireThat(manager, "manager").isNotNull();
		requireThat(executable, "executable").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		requireThat(model, "model").isNotNull();
		requireThat(effort, "effort").isNotNull();
		if (agent != null && !installCat)
			throw new IOException("A direct CAT agent requires --install-cat");
		HarnessArtifact.validate(artifact, MANIFEST);
		HarnessRun run = manager.create(HARNESS_ID);
		HarnessRunLock lock = null;
		HarnessRunnerScope scope = new MainHarnessRunnerScope();
		try
		{
			ClaudeMarketplace.writeEmptyMcpConfiguration(run.directory().getParent().getParent());
			manager.copyProject(project, run);
			if (workspace != null)
				manager.copyWorkspace(workspace, run);
			manager.snapshotProject(run);
			HarnessProjectRepository.initialize(run.directory().resolve("project"), run.directory(), environment);
			Path hostConfiguration = claudeConfigDirectory(environment);
			Path claudeTemporaryDirectory = run.directory().resolve("claude");
			ClaudeMarketplace.writeEmptyLaunchConfiguration(launchConfigurationPath(claudeTemporaryDirectory));
			// The run manager places every project, home, and Claude temporary directory beneath CAT's isolated work root.
			// This keeps concurrent runs separate without diverting CAT-owned state to the shared system temporary directory.
			Path isolatedProcessHome = run.directory().resolve("home");
			Path isolatedConfiguration = isolatedProcessHome.resolve(".claude");
			if (installCat && includeUserRules)
				UserRuleTree.copy(userRulesRoot(environment), ProjectRuleLoader.rulesDirectory(isolatedProcessHome));
			Map<String, String> isolatedEnvironment = new HashMap<>(HarnessEnvironment.scrub(environment));
			ClaudeProcessEnvironment.removeUnavailablePathEntries(isolatedEnvironment);
			ClaudeConnectionSettings.copyProcessEnvironment(environment, isolatedEnvironment);
			// Each run gets an isolated HOME, but packages belong in the parent's explicitly writable shared caches so
			// independent trials do not redownload the same immutable registry artifacts.
			HarnessDependencyCaches caches = HarnessDependencyCache.verify(environment);
			isolatedEnvironment.putAll(caches.environment());
			// The Claude CLI's subprocess scrubbing can make Bubblewrap bind absent host paths after it makes its root
			// read-only, preventing Bash commands from starting.
			//WORKAROUND: https://github.com/anthropics/claude-code/issues/84563
			// isolatedEnvironment.put("CLAUDE_CODE_SUBPROCESS_ENV_SCRUB", "1");
			ClaudeConnectionSettings.copy(scope, hostConfiguration.resolve("settings.json"), isolatedEnvironment);
			if (requiresCredentialFiles(isolatedEnvironment))
			{
				HarnessCredentials.copyAllowedFiles(hostConfiguration, isolatedConfiguration, Set.of(".credentials.json"));
			}
			ClaudeConnectionSettings.writeIsolatedSettings(scope, hostConfiguration.resolve("settings.json"),
				isolatedConfiguration.resolve("settings.json"), claudeTemporaryDirectory, isolatedProcessHome,
				caches.directories(), run.directory(), sessionDeniedReads(manager, run, readOnlyEvidence, deniedReads),
				Platform.current());
			HarnessEnvironment.setHomeDirectory(isolatedEnvironment, isolatedProcessHome);
			isolatedEnvironment.put("CLAUDE_CONFIG_DIR", isolatedConfiguration.toString());
			// The turn boundary in ClaudeProcess depends on Claude's session_state_changed lines. Set it after
			// copying the caller's settings so that no inherited value turns it off.
			ClaudeProcessEnvironment.requestSessionStateEvents(isolatedEnvironment);
			Path worktree = run.directory().resolve("project");
			// The copied project already has its owned baseline. A second initial commit
			// would be empty, so Git would reject it before Claude can start.
			HarnessEnvironment.preventGitDiscoveryAbove(isolatedEnvironment, run.directory());
			List<Path> plugins = installPlugins(executable, artifact, installCat, trialPlugins, isolatedConfiguration,
				(command, step) -> HarnessCommand.execute(command, worktree, isolatedEnvironment, manager, run, step));
			Path hookResults = createHookResultsDirectory(isolatedConfiguration, run);
			String selectedModel = model;
			String selectedEffort = effort;
			String selectedAgent = "";
			if (agent != null)
			{
				selectedModel = agent.model();
				selectedEffort = agent.effort();
				selectedAgent = agent.name();
			}
			List<String> command = command(executable, selectedModel, selectedEffort, selectedAgent, plugins, hookResults,
				readOnlyEvidence);
			manager.initialize(run, artifact, project, "claude_stream_json", command);
			lock = manager.acquireLock(run);
			ClaudeProcess process = new ClaudeProcess(scope, HarnessProcessStarter.start(command, worktree,
				isolatedEnvironment));
			try
			{
				manager.recordHarnessProcess(run, process.pid(), process.startedAt());
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
	 * Returns the files and directories that a session of a run must not read: those that the caller denies, every
	 * file and directory under the run root except the session's project, home, Claude temporary directory and
	 * evidence, and the login that the runner copied into the session's home. The session therefore cannot read the
	 * runner's records of its own run or of any other run, the workspace files of any run, or the login. A denied
	 * directory that holds the session's project, home, Claude temporary directory or evidence, such as the project
	 * that holds the run root, is replaced by every file and directory under it except those and the directories that
	 * lead to them.
	 *
	 * @param manager the run manager
	 * @param run the session's run
	 * @param readOnlyEvidence the completed evidence directories and files that the session reads
	 * @param deniedReads the files and directories that the caller denies the session reading
	 * @return the paths to deny, each once
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the run root or a denied directory cannot be listed
	 */
	public static List<Path> sessionDeniedReads(HarnessRunManager manager, HarnessRun run,
		List<Path> readOnlyEvidence, List<Path> deniedReads) throws IOException
	{
		requireThat(manager, "manager").isNotNull();
		requireThat(run, "run").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		Path home = run.directory().resolve("home");
		List<Path> accessible = new ArrayList<>(readOnlyEvidence);
		accessible.add(run.directory().resolve("project"));
		accessible.add(home);
		accessible.add(run.directory().resolve("claude"));
		Set<Path> result = new LinkedHashSet<>();
		for (Path denied : deniedReads)
		{
			Path directory = denied.toAbsolutePath().normalize();
			// Denying a directory that holds the session's own files would deny those files too
			if (HarnessRunManager.containsAny(directory, accessible))
				result.addAll(HarnessRunManager.deniedReads(directory, accessible));
			else
				result.add(denied);
		}
		result.addAll(manager.deniedReads(accessible));
		// The Claude process reads the login itself; the denial confines only the session's tools and commands
		Path login = home.resolve(".claude").resolve(".credentials.json");
		if (Files.exists(login, NOFOLLOW_LINKS))
			result.add(login);
		return List.copyOf(result);
	}

	/**
	 * Creates the run-owned directory Claude may read for spilled hook context.
	 *
	 * @param isolatedHome the isolated Claude configuration directory
	 * @param run the owned Claude run
	 * @return the directory containing spilled hook context
	 * @throws IOException if the directory or its narrowly scoped Claude path alias cannot be created
	 */
	public static Path createHookResultsDirectory(Path isolatedHome, HarnessRun run) throws IOException
	{
		assert isolatedHome != null;
		assert run != null;
		Path hookResults = Files.createDirectories(isolatedHome.resolve("projects"));
		Path worktree = run.directory().resolve("project").toAbsolutePath().normalize();
		Path canonicalProjectDirectory = Files.createDirectories(hookResults.resolve(claudeProjectDirectory(worktree)));
		Path harnessDirectory = run.directory().toAbsolutePath().normalize().getParent();
		Path runRoot = harnessDirectory.getParent();
		Path reportedParent = Files.createDirectories(hookResults.resolve(claudeProjectDirectory(runRoot)).
			resolve(harnessDirectory.getFileName()));
		Path reportedProjectDirectory = reportedParent.resolve(run.id() + "-project");
		if (Files.exists(reportedProjectDirectory, NOFOLLOW_LINKS))
			throw new IOException("Claude spill-context alias already exists: " + reportedProjectDirectory);
		// Claude writes the canonicalized path but reports the runner's <harness> directory uncanonicalized
		// to a worktree subagent. This symlink exposes only that run's already-authorized hook-results directory at the
		// reported location; it neither grants access to the isolated home nor changes a model-supplied path.
		Files.createSymbolicLink(reportedProjectDirectory, reportedParent.relativize(canonicalProjectDirectory));
		return hookResults;
	}

	/**
	 * Renders the path component Claude uses beneath its {@code projects} directory for a worktree.
	 *
	 * @param worktree an absolute normalized worktree path
	 * @return the corresponding Claude project-directory name
	 */
	private static String claudeProjectDirectory(Path worktree)
	{
		assert worktree != null;
		return worktree.toString().replaceAll("[^A-Za-z0-9_-]", "-");
	}

	/**
	 * Resolves the host Claude configuration directory without copying arbitrary home state.
	 *
	 * @param environment the caller environment
	 * @return the host Claude configuration directory
	 */
	private static Path claudeConfigDirectory(Map<String, String> environment)
	{
		assert environment != null;
		String home = environment.get(Platform.current().homeVariable());
		Path homeDirectory;
		// Retain the runner's working-directory fallback for optional host settings.
		if (home == null || home.isBlank())
			homeDirectory = Path.of("").toAbsolutePath();
		else
			homeDirectory = Path.of(home);
		return ClaudeConfiguration.resolveDirectory(homeDirectory, environment.get("CLAUDE_CONFIG_DIR"));
	}

	/**
	 * Derives the non-repository CAT user-rule directory from the parent process's harness home directory.
	 *
	 * @param environment the parent process environment
	 * @return the CAT user-rule directory
	 * @throws IOException if the harness home directory is unavailable
	 */
	private static Path userRulesRoot(Map<String, String> environment) throws IOException
	{
		Path userHome = Platform.current().homeDirectory(environment).toAbsolutePath().normalize();
		return ProjectRuleLoader.rulesDirectory(userHome);
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
	 * @return {@code true} if no usable Claude authentication variable is present
	 */
	public static boolean requiresCredentialFiles(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		return AUTHENTICATION_VARIABLES.stream().map(environment::get).noneMatch(value ->
			value != null && !value.isBlank());
	}

	/**
	 * Builds a Claude command with immutable evidence directories.
	 *
	 * @param executable the Claude executable
	 * @param model the requested model
	 * @param marketplace the optional marketplace
	 * @param installCat whether CAT is installed
	 * @param hookResults the hook context directory
	 * @return the complete harness command
	 */
	public static List<String> command(String executable, String model, Path marketplace, boolean installCat,
		Path hookResults)
	{
		return command(executable, model, "", "", marketplace, installCat, hookResults, List.of());
	}

	/**
	 * Builds a Claude command with immutable evidence directories.
	 *
	 * @param executable the Claude executable
	 * @param model the requested model
	 * @param marketplace the optional marketplace
	 * @param installCat whether CAT is installed
	 * @param hookResults the hook context directory
	 * @param readOnlyEvidence executor evidence exposed through added directories
	 * @return the complete harness command
	 */
	public static List<String> command(String executable, String model, Path marketplace, boolean installCat,
		Path hookResults, List<Path> readOnlyEvidence)
	{
		return command(executable, model, "", "", marketplace, installCat, hookResults, readOnlyEvidence);
	}

	/**
	 * Builds a Claude command with an explicit profile, optional direct agent, and immutable evidence directories.
	 *
	 * @param executable the Claude executable
	 * @param model the requested model
	 * @param effort the requested reasoning effort
	 * @param agent the optional direct CAT agent name
	 * @param marketplace the optional marketplace
	 * @param installCat whether CAT is installed
	 * @param hookResults the hook context directory
	 * @param readOnlyEvidence executor evidence exposed through added directories
	 * @return the complete harness command
	 */
	public static List<String> command(String executable, String model, String effort, String agent, Path marketplace,
		boolean installCat, Path hookResults, List<Path> readOnlyEvidence)
	{
		List<Path> plugins = List.of();
		if (installCat)
		{
			requireThat(marketplace, "marketplace").isNotNull();
			plugins = List.of(marketplace.resolve("plugins/cat"));
		}
		return command(executable, model, effort, agent, plugins, hookResults, readOnlyEvidence);
	}

	/**
	 * Returns the commands that add a local plugin marketplace and install the plugin that bears its name.
	 *
	 * @param executable the Claude executable
	 * @param marketplace the local marketplace root
	 * @param name the plugin's manifest name, which also names its marketplace
	 * @return the marketplace and install commands, in execution order
	 * @throws NullPointerException if {@code marketplace} is null
	 * @throws IllegalArgumentException if {@code executable} or {@code name} are blank
	 */
	public static List<List<String>> pluginSetupCommands(String executable, Path marketplace, String name)
	{
		requireThat(executable, "executable").isNotBlank();
		requireThat(marketplace, "marketplace").isNotNull();
		requireThat(name, "name").isNotBlank();
		return List.of(
			List.of(executable, "plugin", "marketplace", "add", marketplace.toString()),
			List.of(executable, "plugin", "install", name + "@" + name));
	}

	/**
	 * Installs CAT, when requested, and each trial plugin into an isolated Claude configuration, each from a
	 * marketplace of its own. Claude Code runs an installed plugin's hooks without asking for approval, so no approval
	 * is copied.
	 *
	 * @param executable the Claude executable
	 * @param artifact the complete Claude artifact, which is installed as CAT
	 * @param installCat {@code true} to install CAT
	 * @param trialPlugins the trial plugins, each installed under its manifest's name
	 * @param isolatedConfiguration the isolated Claude configuration directory
	 * @param commands runs the commands that add each marketplace and install each plugin
	 * @return the directory of each installed plugin, CAT first
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code executable} is blank
	 * @throws IOException if a plugin's manifest cannot be read, a marketplace cannot be created or a command fails
	 */
	public static List<Path> installPlugins(String executable, Path artifact, boolean installCat,
		List<Path> trialPlugins, Path isolatedConfiguration, SetupCommands commands) throws IOException
	{
		requireThat(executable, "executable").isNotBlank();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(trialPlugins, "trialPlugins").isNotNull();
		requireThat(isolatedConfiguration, "isolatedConfiguration").isNotNull();
		requireThat(commands, "commands").isNotNull();
		List<Path> plugins = new ArrayList<>();
		if (installCat)
		{
			Path marketplace = ClaudeMarketplace.create(artifact, isolatedConfiguration);
			List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace, "cat");
			commands.execute(pluginCommands.get(0), "marketplace-add");
			commands.execute(pluginCommands.get(1), "plugin-install");
			plugins.add(marketplace.resolve("plugins/cat"));
		}
		for (Path trialPlugin : trialPlugins)
		{
			String name = PluginManifest.name(trialPlugin, MANIFEST);
			Path marketplace = ClaudeMarketplace.create(trialPlugin, name, name, isolatedConfiguration);
			List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace, name);
			// Each plugin's steps are named after it, so that the run's log tells the plugins apart
			commands.execute(pluginCommands.get(0), "trial-plugin-" + name + "-marketplace-add");
			commands.execute(pluginCommands.get(1), "trial-plugin-" + name + "-install");
			plugins.add(marketplace.resolve("plugins/" + name));
		}
		return plugins;
	}

	/**
	 * Builds a Claude command with an explicit profile, optional direct agent, installed plugins and immutable evidence
	 * directories.
	 *
	 * @param executable the Claude executable
	 * @param model the requested model
	 * @param effort the requested reasoning effort
	 * @param agent the optional direct CAT agent name
	 * @param plugins the directories of the plugins installed in the isolated home
	 * @param hookResults the hook context directory
	 * @param readOnlyEvidence executor evidence exposed through added directories
	 * @return the complete harness command
	 */
	public static List<String> command(String executable, String model, String effort, String agent, List<Path> plugins,
		Path hookResults, List<Path> readOnlyEvidence)
	{
		requireThat(executable, "executable").isNotBlank();
		requireThat(model, "model").isNotNull();
		requireThat(effort, "effort").isNotNull();
		requireThat(agent, "agent").isNotNull();
		requireThat(plugins, "plugins").isNotNull();
		requireThat(hookResults, "hookResults").isNotNull();
		List<String> command = new ArrayList<>(List.of(executable, "--print", "--input-format", "stream-json",
			"--output-format", "stream-json", "--permission-mode", "acceptEdits", "--verbose",
			"--include-partial-messages", "--no-session-persistence"));
		for (Path plugin : plugins)
		{
			command.add("--add-dir");
			command.add(plugin.toString());
		}
		if (!plugins.isEmpty())
		{
			// Claude writes spilled hook context under this run-owned directory. Allowing it lets the required
			// SessionStart instruction read it without granting the isolated home or disabling sandboxing.
			command.add("--add-dir");
			command.add(hookResults.toString());
		}
		for (Path evidence : evidenceDirectories(readOnlyEvidence))
		{
			// Claude accepts directories here. Mounting a result file's parent preserves the validator's read access without
			// copying evidence or granting it write access to the executor's retained state.
			command.add("--add-dir");
			command.add(evidence.toString());
		}
		if (!model.isBlank())
		{
			command.add("--model");
			command.add(model);
		}
		if (!effort.isBlank())
		{
			command.add("--effort");
			command.add(effort);
		}
		if (!agent.isBlank())
		{
			command.add("--agent");
			command.add(agent);
		}
		return command;
	}


	/**
	 * Converts immutable evidence files to the narrowest directory accepted by Claude's {@code --add-dir} option.
	 *
	 * @param evidence completed executor evidence paths
	 * @return distinct evidence directories in declaration order
	 */
	private static List<Path> evidenceDirectories(List<Path> evidence)
	{
		assert evidence != null;
		Set<Path> directories = new LinkedHashSet<>();
		for (Path item : evidence)
		{
			Path directory = item;
			if (!Files.isDirectory(item))
				directory = item.getParent();
			if (directory == null)
				throw new IllegalArgumentException("Evidence file has no containing directory: " + item);
			directories.add(directory);
		}
		return List.copyOf(directories);
	}
}

package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.cat.claude.ClaudePluginInfo.HARNESS_ID;

import io.github.cowwoc.cat.claude.ClaudeConfiguration;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.ProjectRuleLoader;
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
import io.github.cowwoc.cat.common.runner.UserRuleTree;

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
		return start(artifact, project, null, null, manager, executable, environment, model, "", installCat, true, null,
			List.of(), List.of());
	}

	/**
	 * Starts a validator with completed executor evidence mounted outside its writable workspace.
	 *
	 * @param artifact the complete Claude artifact
	 * @param project the validator project
	 * @param fixtures the optional directory whose files are copied into the project on top of {@code project}
	 * @param plugin the optional plugin under test, which is installed into the isolated home from the marketplace
	 *   that bears its manifest's name, beside CAT when {@code installCat} is set
	 * @param manager the run manager
	 * @param executable the Claude executable
	 * @param environment the isolated environment
	 * @param model the requested model
	 * @param effort the requested reasoning effort
	 * @param installCat whether CAT is installed
	 * @param includeUserRules whether CAT copies the parent user's rules into the isolated home
	 * @param agent the optional CAT agent selected for the session
	 * @param readOnlyEvidence executor evidence exposed through added directories
	 * @param deniedReads the files and directories that the session must not read
	 * @return the live run session
	 * @throws IOException if startup fails
	 */
	HarnessRunSession start(Path artifact, Path project, Path fixtures, Path plugin, HarnessRunManager manager,
		String executable, Map<String, String> environment, String model, String effort, boolean installCat,
		boolean includeUserRules, ClaudeAgentDefinition agent, List<Path> readOnlyEvidence, List<Path> deniedReads)
		throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
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
			if (fixtures != null)
				manager.copyFixtures(fixtures, run);
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
			// Each run gets an isolated HOME, but packages belong in the parent's explicitly writable shared npm cache so
			// independent trials do not redownload the same immutable registry artifacts.
			HarnessDependencyCache.configureNpm(environment, isolatedEnvironment);
			// The Claude CLI's subprocess scrubbing can make Bubblewrap bind absent host paths after it makes its root
			// read-only, preventing Bash commands from starting.
			//WORKAROUND: https://github.com/anthropics/claude-code/issues/84563
			// isolatedEnvironment.put("CLAUDE_CODE_SUBPROCESS_ENV_SCRUB", "1");
			ClaudeConnectionSettings.copy(scope, hostConfiguration.resolve("settings.json"), isolatedEnvironment);
			ClaudeConnectionSettings.writeIsolatedSettings(scope, hostConfiguration.resolve("settings.json"),
				isolatedConfiguration.resolve("settings.json"), claudeTemporaryDirectory, isolatedProcessHome, deniedReads);
			if (requiresCredentialFiles(isolatedEnvironment))
			{
				HarnessCredentials.copyAllowedFiles(hostConfiguration, isolatedConfiguration, Set.of(".credentials.json"));
			}
			isolatedEnvironment.put("HOME", isolatedProcessHome.toString());
			isolatedEnvironment.put("CLAUDE_CONFIG_DIR", isolatedConfiguration.toString());
			// The turn boundary in ClaudeProcess depends on Claude's session_state_changed lines. Set it after
			// copying the caller's settings so that no inherited value turns it off.
			ClaudeProcessEnvironment.requestSessionStateEvents(isolatedEnvironment);
			Path worktree = run.directory().resolve("project");
			// The copied project already has its owned baseline. A second initial commit
			// would be empty, so Git would reject it before Claude can start.
			HarnessEnvironment.preventGitDiscoveryAbove(isolatedEnvironment, run.directory());
			List<Path> plugins = new ArrayList<>();
			if (installCat)
			{
				Path marketplace = ClaudeMarketplace.create(artifact, isolatedConfiguration);
				List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace, "cat");
				HarnessCommand.execute(pluginCommands.get(0), worktree, isolatedEnvironment, manager, run,
					"marketplace-add");
				HarnessCommand.execute(pluginCommands.get(1), worktree, isolatedEnvironment, manager, run, "plugin-install");
				plugins.add(marketplace.resolve("plugins/cat"));
			}
			if (plugin != null)
			{
				String name = PluginManifest.name(plugin, MANIFEST);
				Path marketplace = ClaudeMarketplace.create(plugin, name, name, isolatedConfiguration);
				List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace, name);
				HarnessCommand.execute(pluginCommands.get(0), worktree, isolatedEnvironment, manager, run,
					"plugin-under-test-marketplace-add");
				HarnessCommand.execute(pluginCommands.get(1), worktree, isolatedEnvironment, manager, run,
					"plugin-under-test-install");
				plugins.add(marketplace.resolve("plugins/" + name));
			}
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
		String home = environment.get("HOME");
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
		String home = environment.get("HOME");
		if (home == null || home.isBlank())
			throw new IOException("Claude user rules require the parent HOME");
		Path userHome = Path.of(home.strip()).toAbsolutePath().normalize();
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
	 * Determines whether the isolated home needs a copied credential file.
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

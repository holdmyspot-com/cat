package com.holdmyspot.cat.claude.runner;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import com.holdmyspot.cat.claude.ClaudeConfiguration;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ProjectRuleLoader;
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
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessProjectRepository;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.AbstractHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.MainHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.PluginManifest;
import com.holdmyspot.cat.common.runner.SessionCommandLimits;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestInstructions;
import com.holdmyspot.cat.common.runner.SetupCommands;
import com.holdmyspot.cat.common.runner.UserRuleTree;
import com.holdmyspot.cat.common.runner.HarnessEventListener;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
	static final Path MANIFEST = Path.of(".claude-plugin/plugin.json");
	/**
	 * The name of the file in Claude's configuration directory that holds Claude's login.
	 */
	private static final String LOGIN = ".credentials.json";
	private final Waiting waiting;

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
	 * Creates a Claude artifact runner. Each run obtains executable-file services from its owned scope.
	 *
	 * @param waiting how each run waits between the attempts of a retried operation
	 * @throws NullPointerException if {@code waiting} is null
	 */
	public ClaudeRunner(Waiting waiting)
	{
		requireThat(waiting, "waiting").isNotNull();
		this.waiting = waiting;
	}

	/**
	 * Starts a validator with completed executor evidence mounted outside its writable workspace.
	 *
	 * @param artifact the complete Claude artifact that the session installs as CAT and whose launcher the session
	 *   requests sessions through
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
	 * @param installCat whether CAT is installed, along with a copy of the parent user's rules in the isolated home
	 * @param readOnlyEvidence executor evidence exposed through added directories
	 * @param deniedReads the files and directories that the session must not read
	 * @param instructionFilesMode the choice of which instruction files load, such as {@code claude-md}, or an empty
	 *   string to keep the parent's choice
	 * @param sessionTranscripts whether Claude persists its session and the run copies the session's transcripts, its
	 *   subagents' included, into the run's session transcripts directory when the session closes
	 * @param sessionRequests how many sessions the session may request; with any, the run gets its requests and
	 *   responses directories, and the session is told how to request one
	 * @param bound the policy that bounds the session's sandbox policy, or {@code null} if nothing bounds it
	 * @param policyListener receives the session's sandbox policy before its Claude Code process starts
	 * @param gitName the Git user name of the copied project, or {@code null} to read {@code project}'s
	 * @param gitEmail the Git user email of the copied project, or {@code null} to read {@code project}'s
	 * @return the live run session
	 * @throws SessionRefusedException if the session's sandbox would not confine it as its policy requires: its
	 *   bound does not let it write what Claude Code writes, or a settings file that Claude Code also loads would
	 *   widen it
	 * @throws IOException if startup fails
	 */
	HarnessRunSession start(Path artifact, Path project, Path workspace, List<Path> trialPlugins,
		HarnessRunManager manager, String executable, Map<String, String> environment, String model, String effort,
		ClaudeAgentDefinition agent, boolean installCat, List<Path> readOnlyEvidence,
		List<Path> deniedReads, String instructionFilesMode, boolean sessionTranscripts, int sessionRequests,
		ChildPolicy bound, Consumer<ChildPolicy> policyListener, String gitName, String gitEmail)
		throws IOException
	{
		requireThat(policyListener, "policyListener").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(trialPlugins, "trialPlugins").isNotNull();
		requireThat(manager, "manager").isNotNull();
		requireThat(executable, "executable").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		requireThat(model, "model").isNotBlank();
		requireThat(effort, "effort").isNotBlank();
		requireThat(instructionFilesMode, "instructionFilesMode").isNotNull();
		if (agent != null && !installCat)
			throw new IOException("A direct CAT agent requires --install-cat");
		Path artifactRoot = HarnessArtifact.validate(artifact, MANIFEST);
		HarnessRun run = manager.create(HARNESS_ID);
		HarnessRunLock lock = null;
		AbstractHarnessRunnerScope scope = new MainHarnessRunnerScope(environment, waiting);
		ExecutableFiles executables = scope.executableFiles();
		try
		{
			ClaudeMarketplace.writeEmptyMcpConfiguration(run.directory().getParent().getParent());
			manager.copyProject(project, run);
			if (workspace != null)
				manager.copyWorkspace(workspace, run);
			manager.snapshotProject(run);
			HarnessProjectRepository.initialize(project, run.directory().resolve("project"), run.directory(), environment,
				executables, gitName, gitEmail);
			Path hostConfiguration = claudeConfigDirectory(environment);
			Path claudeTemporaryDirectory = run.directory().resolve("claude");
			ClaudeMarketplace.writeEmptyLaunchConfiguration(launchConfigurationPath(claudeTemporaryDirectory));
			// The run manager places every project, home, and Claude temporary directory beneath CAT's isolated work root.
			// This keeps concurrent runs separate without diverting CAT-owned state to the shared system temporary directory.
			Path isolatedProcessHome = run.directory().resolve("home");
			Path isolatedConfiguration = isolatedProcessHome.resolve(".claude");
			if (installCat)
				UserRuleTree.copy(userRulesRoot(environment), ProjectRuleLoader.rulesDirectory(isolatedProcessHome));
			// Each run gets an isolated HOME, but packages belong in the parent's explicitly writable shared caches so
			// independent trials do not redownload the same immutable registry artifacts.
			HarnessDependencyCaches caches = HarnessDependencyCache.verify(environment);
			SessionRequestAccess requestAccess = SessionRequestAccess.allowing(run, artifactRoot, sessionRequests,
				Platform.current(), SessionRequestAccess.Shell.POSIX).orElse(null);
			Map<String, String> isolatedEnvironment = processEnvironment(scope, environment, caches,
				hostConfiguration.resolve("settings.json"), isolatedProcessHome, requestAccess);
			if (requiresCredentialFiles(isolatedEnvironment))
			{
				HarnessCredentials.copyAllowedFiles(hostConfiguration, isolatedConfiguration, Set.of(LOGIN));
			}
			Path worktree = run.directory().resolve("project");
			ChildPolicy policy = ClaudeConnectionSettings.writeIsolatedSettings(scope,
				hostConfiguration.resolve("settings.json"), isolatedConfiguration.resolve("settings.json"),
				claudeTemporaryDirectory, isolatedProcessHome, caches.directories(), run.directory(),
				sessionDeniedReads(manager, run, readOnlyEvidence, deniedReads, environment, requestAccess),
				instructionFilesMode, requestAccess, worktree, bound,
				ClaudeConnectionSettings.modelHost(isolatedEnvironment));
			requireNoWidening(scope, Platform.current(), worktree, policy, isolatedProcessHome);
			policyListener.accept(policy);
			// The copied project already has its owned baseline. A second initial commit
			// would be empty, so Git would reject it before Claude can start.
			HarnessEnvironment.preventGitDiscoveryAbove(isolatedEnvironment, run.directory());
			List<Path> plugins = installPlugins(executable, artifact, installCat, trialPlugins, isolatedConfiguration,
				(command, step) -> HarnessCommand.execute(command, worktree, isolatedEnvironment, executables, manager,
					run, step));
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
			Path requestLauncher = null;
			if (requestAccess != null)
				requestLauncher = requestAccess.launcher();
			List<String> command = command(executable, selectedModel, selectedEffort, selectedAgent, plugins, hookResults,
				readOnlyEvidence, sessionTranscripts, requestLauncher, sessionRequests);
			manager.initialize(run, artifact, project, "claude_stream_json", command);
			lock = manager.acquireLock(run);
			Path projectDirectory = null;
			HarnessSessionTranscripts transcripts = null;
			if (sessionTranscripts)
			{
				// Claude names the root session's transcript after the session, below the directory it names for the worktree
				projectDirectory = hookResults.resolve(claudeProjectDirectory(worktree.toAbsolutePath().normalize()));
				transcripts = new HarnessSessionTranscripts(hookResults, run.sessionTranscripts(),
					name -> name.endsWith(".jsonl"));
			}
			SandboxWatch watch = new SandboxWatch(HarnessProcessStarter.start(command, worktree, isolatedEnvironment,
				executables), scope.json());
			ClaudeProcess process = new ClaudeProcess(scope, watch.process(), projectDirectory, transcripts);
			try
			{
				manager.recordHarnessProcess(run, process.pid(), process.startedAt());
				return new HarnessRunSession(run, watch.guard(process), scope, lock);
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
	 * Refuses a session if a settings file that Claude Code loads beside the session's own would widen the session's
	 * sandbox beyond its policy: a managed settings file, or the project's shared or local settings.
	 *
	 * @param scope    services owned for the run
	 * @param platform the platform that the session runs on
	 * @param project  the session's project
	 * @param policy   the session's sandbox policy
	 * @param home     the session's home directory
	 * @throws SessionRefusedException if a settings file would widen the session
	 * @throws IOException             if a settings file cannot be read
	 */
	private static void requireNoWidening(AbstractHarnessRunnerScope scope, Platform platform, Path project,
		ChildPolicy policy, Path home) throws IOException
	{
		List<Path> files = new ArrayList<>(ClaudeConnectionSettings.managedSettings(platform));
		files.add(project.resolve(".claude/settings.json"));
		files.add(project.resolve(".claude/settings.local.json"));
		Optional<String> widening = ClaudeConnectionSettings.findWidening(scope.json(), files, policy, home);
		if (widening.isPresent())
			throw new SessionRefusedException(widening.get());
	}

	/**
	 * Returns the environment of a run's Claude process. It holds the caller's generic operating-system variables, its
	 * Claude connection variables and its settings for starting subagents, each from its environment or else its Claude
	 * settings, so that the session starts subagents the way the caller does, and the shared dependency caches;
	 * the run's home as the home directory and its {@code .claude} as Claude's configuration directory; and the
	 * request for Claude's session state events, which the turn boundary depends on. CAT's hooks then find Claude's
	 * configuration directory through the home directory, as {@code .claude} in it.
	 * <p>
	 * The environment never sets {@code CLAUDE_CODE_SUBPROCESS_ENV_SCRUB}, so Claude Code passes its credentials and
	 * its configuration directory's variable to the commands and hooks that it starts, and keeps protecting the
	 * project's {@code .git/worktrees} at the lower priority that the session's settings grant overrides. The caller's
	 * value of the variable is not inherited, because the environment copies only generic operating-system variables,
	 * Claude's connection variables and its subagent settings from the caller's environment.
	 *
	 * @param scope        services shared for the runner process lifetime
	 * @param environment  the caller's environment
	 * @param caches       the shared dependency caches
	 * @param hostSettings the caller's Claude settings file
	 * @param processHome   the run's isolated home
	 * @param requestAccess the session's access to requested sessions, whose variables the environment adds, or
	 *                      {@code null} if the session may request none
	 * @return the environment, which the caller may change
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} are null
	 * @throws IOException          if the settings file cannot be read
	 */
	public static Map<String, String> processEnvironment(HarnessRunnerScope scope, Map<String, String> environment,
		HarnessDependencyCaches caches, Path hostSettings, Path processHome, SessionRequestAccess requestAccess)
		throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(caches, "caches").isNotNull();
		requireThat(hostSettings, "hostSettings").isNotNull();
		requireThat(processHome, "processHome").isNotNull();
		Map<String, String> isolatedEnvironment = new HashMap<>(HarnessEnvironment.scrub(environment));
		ClaudeProcessEnvironment.removeUnavailablePathEntries(isolatedEnvironment);
		ClaudeConnectionSettings.copyProcessEnvironment(environment, isolatedEnvironment);
		isolatedEnvironment.putAll(caches.environment());
		ClaudeConnectionSettings.copy(scope, hostSettings, isolatedEnvironment);
		HarnessEnvironment.setHomeDirectory(isolatedEnvironment, processHome);
		isolatedEnvironment.put("CLAUDE_CONFIG_DIR", processHome.resolve(".claude").toString());
		// The turn boundary in ClaudeProcess depends on Claude's session_state_changed lines. Set it after
		// copying the caller's settings so that no inherited value turns it off.
		ClaudeProcessEnvironment.requestSessionStateEvents(isolatedEnvironment);
		// Claude Code passes these to the commands that it starts
		if (requestAccess != null)
			isolatedEnvironment.putAll(requestAccess.environment());
		return isolatedEnvironment;
	}

	/**
	 * Returns the files and directories that a session of a run must not read: those that the caller denies, every
	 * file and directory under the run root except the session's project, home, Claude temporary directory and
	 * evidence, and every harness's login and configuration files in the session's home and in the user's home, as
	 * {@link SessionCommandLimits#harnessFiles(Map, Platform)} names them, except the session's own login: the
	 * {@code .credentials.json} in the Claude configuration directory of the session's home, the copy of the user's
	 * login that the runner puts there when the caller's environment holds no Claude authentication variable, which a
	 * Claude process that the session starts from a command, such as the prompt optimizer's sessions, reads. The
	 * session therefore cannot read the runner's records of its own run or of any other run, the workspace files of any
	 * run, any login but that copy, such as the user's own Claude and Codex logins, or a configuration that would let a
	 * harness started from a command reach the model provider. A denied directory that holds the
	 * session's project, home, Claude temporary directory or evidence, such as the project that holds the run root, is
	 * replaced by every file and directory under it except those and the directories that lead to them.
	 * <p>
	 * A login or configuration file that does not exist when the session starts is left out, because Claude's sandbox
	 * creates an empty file at a missing denied path, which would change the user's harness configuration; the
	 * session's own settings file, which the runner writes before the session starts, is always included.
	 *
	 * @param manager          the run manager
	 * @param run              the session's run
	 * @param readOnlyEvidence the completed evidence directories and files that the session reads
	 * @param deniedReads      the files and directories that the caller denies the session reading
	 * @param environment      the caller's environment, which names the user's home and harness directories
	 * @return the paths to deny, each once
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the run root or a denied directory cannot be listed
	 */
	public static List<Path> sessionDeniedReads(HarnessRunManager manager, HarnessRun run,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Map<String, String> environment) throws IOException
	{
		return sessionDeniedReads(manager, run, readOnlyEvidence, deniedReads, environment, null);
	}

	/**
	 * Returns the files and directories that a session of a run must not read, as
	 * {@link #sessionDeniedReads(HarnessRunManager, HarnessRun, List, List, Map)} does, except that the run's requests
	 * and responses directories stay readable when the session may request sessions.
	 *
	 * @param manager          the run manager
	 * @param run              the session's run
	 * @param readOnlyEvidence the completed evidence directories and files that the session reads
	 * @param deniedReads      the files and directories that the caller denies the session reading
	 * @param environment      the caller's environment, which names the user's home and harness directories
	 * @param requestAccess    the session's access to requested sessions, or {@code null} if it may request none
	 * @return the paths to deny, each once
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} are null
	 * @throws IOException          if the run root or a denied directory cannot be listed
	 */
	public static List<Path> sessionDeniedReads(HarnessRunManager manager, HarnessRun run,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Map<String, String> environment,
		SessionRequestAccess requestAccess) throws IOException
	{
		requireThat(manager, "manager").isNotNull();
		requireThat(run, "run").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		requireThat(environment, "environment").isNotNull();
		Path home = run.directory().resolve("home");
		List<Path> accessible = new ArrayList<>(readOnlyEvidence);
		accessible.add(run.directory().resolve("project"));
		accessible.add(home);
		accessible.add(run.directory().resolve("claude"));
		if (requestAccess != null)
		{
			accessible.add(requestAccess.requests());
			accessible.add(requestAccess.responses());
		}
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
		// The Claude process reads the logins and settings itself; the denial confines only the session's tools and
		// commands
		Platform platform = Platform.current();
		List<Path> harnessFiles = new ArrayList<>(SessionCommandLimits.harnessFiles(environment, platform));
		harnessFiles.addAll(SessionCommandLimits.harnessFiles(SessionCommandLimits.homeEnvironment(home, platform),
			platform));
		// A Claude process that a command starts, such as a prompt optimizer's session, reads the session's own login
		Path ownLogin = home.resolve(".claude").resolve(LOGIN).toAbsolutePath().normalize();
		for (Path file : harnessFiles)
		{
			if (!file.equals(ownLogin) && Files.exists(file, NOFOLLOW_LINKS))
				result.add(file);
		}
		result.add(home.resolve(".claude").resolve("settings.json"));
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
	public static String claudeProjectDirectory(Path worktree)
	{
		assert worktree != null;
		String path = worktree.toString();
		int prefixLimit = 200;
		StringBuilder directory = new StringBuilder();
		// Claude Code 2.1.317 replaces UTF-16 code units, so an astral character contributes two hyphens.
		for (int index = 0; index < Math.min(path.length(), prefixLimit); ++index)
		{
			char character = path.charAt(index);
			boolean asciiAlphanumeric = (character >= 'a' && character <= 'z') ||
				(character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9');
			if (asciiAlphanumeric)
				directory.append(character);
			else
				directory.append('-');
		}
		if (path.length() > prefixLimit)
		{
			// Claude hashes the original path, and Java's hashCode has the same signed 32-bit UTF-16 arithmetic.
			long hashMagnitude = Math.abs((long) path.hashCode());
			directory.append('-').append(Long.toString(hashMagnitude, 36));
		}
		return directory.toString();
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
		return command(executable, model, effort, agent, plugins, hookResults, readOnlyEvidence, false);
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
	 * @param sessionTranscripts whether Claude persists its session, which is what writes the transcripts that the run
	 *   copies; otherwise Claude keeps no session
	 * @return the complete harness command
	 */
	public static List<String> command(String executable, String model, String effort, String agent, List<Path> plugins,
		Path hookResults, List<Path> readOnlyEvidence, boolean sessionTranscripts)
	{
		return command(executable, model, effort, agent, plugins, hookResults, readOnlyEvidence, sessionTranscripts,
			null, 0);
	}

	/**
	 * Builds a Claude command as
	 * {@link #command(String, String, String, String, List, Path, List, boolean)} does, except that a session that may
	 * request sessions is told how in its appended system prompt.
	 *
	 * @param executable         the Claude executable
	 * @param model              the requested model
	 * @param effort             the requested reasoning effort; {@code none} starts Claude Code with extended thinking
	 *   turned off ({@code --thinking disabled}) and no {@code --effort}, because Claude Code ignores an
	 *   {@code --effort} it does not list and applies its default effort instead
	 * @param agent              the optional direct CAT agent name
	 * @param plugins            the directories of the plugins installed in the isolated home
	 * @param hookResults        the hook context directory
	 * @param readOnlyEvidence   executor evidence exposed through added directories
	 * @param sessionTranscripts whether Claude persists its session, which is what writes the transcripts that the run
	 *   copies; otherwise Claude keeps no session
	 * @param requestLauncher    the absolute request-session launcher path, or {@code null} when requests are disabled
	 * @param sessionRequests    how many sessions the session may request; {@code 0} adds no instructions
	 * @return the complete harness command
	 * @throws NullPointerException     if any required argument is null
	 * @throws IllegalArgumentException if {@code executable}, {@code model} or {@code effort} is blank,
	 *   {@code sessionRequests} is negative, or requests are enabled and {@code requestLauncher} is relative
	 */
	public static List<String> command(String executable, String model, String effort, String agent, List<Path> plugins,
		Path hookResults, List<Path> readOnlyEvidence, boolean sessionTranscripts, Path requestLauncher,
		int sessionRequests)
	{
		requireThat(sessionRequests, "sessionRequests").isNotNegative();
		requireThat(executable, "executable").isNotBlank();
		requireThat(model, "model").isNotBlank();
		requireThat(effort, "effort").isNotBlank();
		requireThat(agent, "agent").isNotNull();
		requireThat(plugins, "plugins").isNotNull();
		requireThat(hookResults, "hookResults").isNotNull();
		List<String> command = new ArrayList<>(List.of(executable, "--print", "--input-format", "stream-json",
			"--output-format", "stream-json", "--permission-mode", "acceptEdits",
			"--verbose", "--include-partial-messages"));
		if (!sessionTranscripts)
			command.add("--no-session-persistence");
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
		command.add("--model");
		command.add(model);
		if (effort.equals(HarnessRunnerOptions.NO_REASONING))
		{
			command.add("--thinking");
			command.add("disabled");
		}
		else
		{
			command.add("--effort");
			command.add(effort);
		}
		if (!agent.isBlank())
		{
			command.add("--agent");
			command.add(agent);
		}
		if (sessionRequests > 0)
		{
			command.add("--append-system-prompt");
			command.add(SessionRequestInstructions.text(sessionRequests, requestLauncher, false));
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

	/**
	 * Watches a Claude Code process for the signs that its sandbox is not confining the session, in its
	 * {@code stream-json} output and its standard error, so that CAT refuses the session rather than letting its
	 * commands run unconfined.
	 * <p>
	 * When the sandbox that the settings require cannot start, Claude Code makes no model request, writes one
	 * {@code result} event whose {@code startup_failure_reason} is {@code sandbox_required_unavailable} and whose
	 * {@code errors} give the reason, and exits. When it runs the session without its sandbox, it writes a
	 * {@code Sandbox disabled:} notice to its standard error, or, if the sandbox is turned off, asks to approve a
	 * command that writes outside the session's working directories, which a {@code system} event of subtype
	 * {@code permission_denied} reports; a sandboxed command is never asked about. Either of the latter means that the
	 * requirement stopped working, so the watch stops the process tree at once. Either way, the session's next turn
	 * is refused with Claude Code's reason.
	 * <p>
	 * The process that {@link #process()} returns is the watched process, which the session reads its output and
	 * standard error from, and the harness process that {@link #guard(HarnessProcess)} returns refuses the session's
	 * turns.
	 */
	public static final class SandboxWatch
	{
		/**
		 * The {@code startup_failure_reason} of the {@code result} event that Claude Code writes when its required
		 * sandbox cannot start.
		 */
		private static final String SANDBOX_UNAVAILABLE = "sandbox_required_unavailable";
		/**
		 * The text that starts Claude Code's reason, in that event's {@code errors}, when its required sandbox cannot
		 * start.
		 */
		private static final String UNAVAILABLE_PREFIX = "Sandbox required but unavailable: ";
		/**
		 * The text that starts Claude Code's advice, at the end of that reason, to let commands run without the
		 * sandbox, which CAT never does.
		 */
		private static final String UNSANDBOXED_ADVICE = " Set sandbox.failIfUnavailable=false";
		/**
		 * The subtype of the {@code system} event that reports a tool use that Claude Code denied.
		 */
		private static final String PERMISSION_DENIED = "permission_denied";
		/**
		 * The text of a denied command's message when Claude Code asked to approve a command because it writes
		 * outside the session's working directories, which it asks only about a command that no sandbox confines.
		 */
		private static final String UNCONFINED_COMMAND = "is outside the working directories for this session";
		/**
		 * The text that starts Claude Code's standard error notice when it runs the session without its sandbox.
		 */
		private static final String DISABLED_NOTICE = "Sandbox disabled: ";
		/**
		 * Where a refused session's user learns what Claude Code's sandbox needs.
		 */
		private static final String SANDBOX_GUIDE = "See the Claude Code sandbox requirements in CAT's installation " +
			"guide";
		/**
		 * The longest line that is checked; the rest of a longer line is ignored.
		 */
		private static final int MAXIMUM_LINE_BYTES = 65_536;
		/**
		 * How long a session whose process ended waits for the rest of the process's output and standard error.
		 */
		private static final Duration OUTPUT_WAIT = Duration.ofSeconds(5);
		private final Process process;
		private final JsonMapper json;
		private final Process watched;
		private final CountDownLatch streamsEnded = new CountDownLatch(2);
		private volatile String refusal;

		/**
		 * Creates a watch.
		 *
		 * @param process a Claude Code process whose output and standard error nothing has read yet
		 * @param json    the JSON mapper
		 * @throws NullPointerException if any of the arguments are null
		 */
		public SandboxWatch(Process process, JsonMapper json)
		{
			requireThat(process, "process").isNotNull();
			requireThat(json, "json").isNotNull();
			this.process = process;
			this.json = json;
			this.watched = new WatchedProcess(process, new ScanningStream(process.getInputStream(), this::checkEvent),
				new ScanningStream(process.getErrorStream(), this::checkError));
		}

		/**
		 * Returns the watched process, which reads the process's output and standard error through the watch.
		 *
		 * @return the process
		 */
		public Process process()
		{
			return watched;
		}

		/**
		 * Returns a harness process that refuses a session once the watch has seen a sign.
		 *
		 * @param session the session's harness process, which reads {@link #process()}
		 * @return the harness process
		 * @throws NullPointerException if {@code session} is null
		 */
		public HarnessProcess guard(HarnessProcess session)
		{
			requireThat(session, "session").isNotNull();
			return new GuardedProcess(session);
		}

		/**
		 * Checks one line of the process's {@code stream-json} output for an event that shows that the sandbox is not
		 * confining the session.
		 *
		 * @param line the line, without its line terminator
		 */
		private void checkEvent(String line)
		{
			if (refusal != null || !line.contains(SANDBOX_UNAVAILABLE) && !line.contains(PERMISSION_DENIED))
				return;
			JsonNode event;
			try
			{
				event = json.readTree(line);
			}
			catch (JacksonException _)
			{
				// The session's reader reports output that is not an event
				return;
			}
			String type = event.path("type").asString("");
			if (type.equals("result") && event.path("startup_failure_reason").asString("").equals(SANDBOX_UNAVAILABLE))
			{
				String reason = event.path("errors").path(0).asString("");
				if (reason.startsWith(UNAVAILABLE_PREFIX))
					reason = reason.substring(UNAVAILABLE_PREFIX.length());
				int advice = reason.indexOf(UNSANDBOXED_ADVICE);
				if (advice >= 0)
					reason = reason.substring(0, advice);
				refusal = "Claude Code cannot start the session's sandbox, which the session requires: " +
					reason.strip() + " " + SANDBOX_GUIDE + ".";
				return;
			}
			String message = event.path("message").asString("");
			if (type.equals("system") && event.path("subtype").asString("").equals(PERMISSION_DENIED) &&
				event.path("tool_name").asString("").equals("Bash") && message.contains(UNCONFINED_COMMAND))
			{
				stop("Claude Code asked to approve a command that its sandbox would confine, so the session's commands " +
					"run without the sandbox, which the session's settings forbid, and CAT stopped it: " + message.strip() +
					" " + SANDBOX_GUIDE + ".");
			}
		}

		/**
		 * Checks one line of the process's standard error for the notice that the session runs without its sandbox.
		 *
		 * @param line the line, without its line terminator
		 */
		private void checkError(String line)
		{
			if (refusal != null)
				return;
			int disabled = line.indexOf(DISABLED_NOTICE);
			if (disabled >= 0)
			{
				stop("Claude Code ran the session without its sandbox, which the session's settings forbid, so CAT " +
					"stopped it: " + line.substring(disabled + DISABLED_NOTICE.length()).strip() + " " + SANDBOX_GUIDE +
					".");
			}
		}

		/**
		 * Records a refusal and stops the process tree.
		 *
		 * @param reason the refusal
		 */
		private void stop(String reason)
		{
			refusal = reason;
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
		}

		/**
		 * Returns the refusal that the watch has seen.
		 *
		 * @param processAlive {@code true} if the session's process is still running; otherwise this method first waits
		 *                     a bounded time for the rest of its output and standard error
		 * @return the refusal, or {@code null} if the watch has seen none
		 */
		private String refusal(boolean processAlive)
		{
			if (!processAlive && refusal == null)
			{
				try
				{
					if (!streamsEnded.await(OUTPUT_WAIT.toMillis(), TimeUnit.MILLISECONDS))
						return refusal;
				}
				catch (InterruptedException _)
				{
					Thread.currentThread().interrupt();
				}
			}
			return refusal;
		}

		/**
		 * One stream of the watched process, which checks each line as it is read.
		 */
		private final class ScanningStream extends FilterInputStream
		{
			private final ByteArrayOutputStream line = new ByteArrayOutputStream();
			private final Consumer<String> checker;
			private final AtomicBoolean ended = new AtomicBoolean();

			/**
			 * Creates a stream.
			 *
			 * @param input   the process's stream
			 * @param checker checks each line of the stream
			 */
			ScanningStream(InputStream input, Consumer<String> checker)
			{
				super(input);
				this.checker = checker;
			}

			@Override
			public int read() throws IOException
			{
				int value = super.read();
				if (value < 0)
					ended();
				else
					accept((byte) value);
				return value;
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException
			{
				int count = super.read(buffer, offset, length);
				if (count < 0)
					ended();
				for (int index = 0; index < count; ++index)
					accept(buffer[offset + index]);
				return count;
			}

			@Override
			public void close() throws IOException
			{
				try
				{
					super.close();
				}
				finally
				{
					ended();
				}
			}

			/**
			 * Adds a byte to the current line, and checks the line when the byte ends it.
			 *
			 * @param value the byte
			 */
			private void accept(byte value)
			{
				if (value == '\n')
				{
					checkLine();
					return;
				}
				if (line.size() < MAXIMUM_LINE_BYTES)
					line.write(value);
			}

			/**
			 * Checks the current line and starts the next one.
			 */
			private void checkLine()
			{
				checker.accept(line.toString(UTF_8));
				line.reset();
			}

			/**
			 * Checks the last line and records that the stream ended.
			 */
			private void ended()
			{
				if (!ended.compareAndSet(false, true))
					return;
				if (line.size() > 0)
					checkLine();
				streamsEnded.countDown();
			}
		}

		/**
		 * A harness process that refuses the session once the watch has seen a sign.
		 */
		private final class GuardedProcess implements HarnessProcess
		{
			private final HarnessProcess session;

			/**
			 * Creates a harness process.
			 *
			 * @param session the session's harness process
			 */
			GuardedProcess(HarnessProcess session)
			{
				this.session = session;
			}

			@Override
			public HarnessProcessResult sendTurn(String request) throws IOException
			{
				return sendTurn(request, _ ->
				{
				});
			}

			@Override
			public HarnessProcessResult sendTurn(String request, HarnessEventListener listener) throws IOException
			{
				requireNoRefusal();
				HarnessProcessResult result;
				try
				{
					result = session.sendTurn(request, listener);
				}
				catch (IOException e)
				{
					String seen = refusal(session.isAlive());
					if (seen == null)
						throw e;
					SessionRefusedException refused = new SessionRefusedException(seen);
					refused.addSuppressed(e);
					throw refused;
				}
				requireNoRefusal();
				return result;
			}

			/**
			 * Refuses the session if the watch has seen a sign.
			 *
			 * @throws SessionRefusedException if the watch has seen a sign
			 */
			private void requireNoRefusal() throws SessionRefusedException
			{
				String seen = refusal(session.isAlive());
				if (seen != null)
					throw new SessionRefusedException(seen);
			}

			@Override
			public void steer(String request) throws IOException
			{
				session.steer(request);
			}

			@Override
			public void interrupt() throws IOException
			{
				session.interrupt();
			}

			@Override
			public Optional<Path> sessionTranscript()
			{
				return session.sessionTranscript();
			}

			/**
			 * {@inheritDoc}
			 * <p>
			 * A session whose process ended after a sign counts as alive, so that its next turn reports the refusal
			 * rather than the ended process.
			 */
			@Override
			public boolean isAlive()
			{
				return session.isAlive() || refusal(false) != null;
			}

			@Override
			public void close() throws IOException
			{
				session.close();
			}
		}
	}

	/**
	 * A process whose output and standard error are read through streams other than its own.
	 */
	private static final class WatchedProcess extends Process
	{
		private final Process process;
		private final InputStream output;
		private final InputStream standardError;

		/**
		 * Creates a process.
		 *
		 * @param process       the process
		 * @param output        the stream that its output is read through
		 * @param standardError the stream that its standard error is read through
		 */
		WatchedProcess(Process process, InputStream output, InputStream standardError)
		{
			this.process = process;
			this.output = output;
			this.standardError = standardError;
		}

		@Override
		public OutputStream getOutputStream()
		{
			return process.getOutputStream();
		}

		@Override
		public InputStream getInputStream()
		{
			return output;
		}

		@Override
		public InputStream getErrorStream()
		{
			return standardError;
		}

		@Override
		public int waitFor() throws InterruptedException
		{
			return process.waitFor();
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
		{
			return process.waitFor(timeout, unit);
		}

		@Override
		public int exitValue()
		{
			return process.exitValue();
		}

		@Override
		public void destroy()
		{
			process.destroy();
		}

		@Override
		public Process destroyForcibly()
		{
			process.destroyForcibly();
			return this;
		}

		@Override
		public boolean supportsNormalTermination()
		{
			return process.supportsNormalTermination();
		}

		@Override
		public boolean isAlive()
		{
			return process.isAlive();
		}

		@Override
		public long pid()
		{
			return process.pid();
		}

		@Override
		public CompletableFuture<Process> onExit()
		{
			return process.onExit().thenApply(_ -> this);
		}

		@Override
		public ProcessHandle toHandle()
		{
			return process.toHandle();
		}

		@Override
		public ProcessHandle.Info info()
		{
			return process.info();
		}

		@Override
		public Stream<ProcessHandle> children()
		{
			return process.children();
		}

		@Override
		public Stream<ProcessHandle> descendants()
		{
			return process.descendants();
		}
	}
}

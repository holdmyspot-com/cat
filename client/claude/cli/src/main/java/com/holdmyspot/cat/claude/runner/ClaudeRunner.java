package com.holdmyspot.cat.claude.runner;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import com.holdmyspot.cat.claude.ClaudeConfiguration;
import com.holdmyspot.cat.claude.installer.Integration;
import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.claude.optimizer.InstalledPlugins;
import com.holdmyspot.cat.claude.runner.ClaudeSessionSandbox.ProbeResult;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.runner.AbstractHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.HarnessCommand;
import com.holdmyspot.cat.common.runner.HarnessDependencyCache;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessEventListener;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessProcessStarter;
import com.holdmyspot.cat.common.runner.HarnessProcessTermination;
import com.holdmyspot.cat.common.runner.HarnessProjectRepository;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.LiveNestedSession;
import com.holdmyspot.cat.common.runner.MainHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionLock;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.PluginManifest;
import com.holdmyspot.cat.common.runner.SessionCommandLimits;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SetupCommands;
import com.holdmyspot.cat.common.runner.TurnMode;
import com.holdmyspot.cat.common.runner.UserRuleTree;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.SeatbeltProfile;
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
	/**
	 * The variable with which a caller asks Claude Code to remove credentials and other settings from its commands'
	 * environment. No session sets it: it would also take Git, SSH, cloud and tool settings from every command, and the
	 * session's Claude process holds no token for it to remove.
	 */
	private static final String SCRUB_VARIABLE = "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB";
	/**
	 * The path of a Claude plugin's manifest, relative to the plugin directory.
	 */
	static final Path MANIFEST = Path.of(".claude-plugin/plugin.json");
	private final Waiting waiting;

	/**
	 * Resolves the launch configuration Bubblewrap inspects beside one nested session's Claude temporary directory.
	 *
	 * @param claudeTemporaryDirectory the session-owned Claude temporary directory
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
	 * Creates a Claude artifact runner. Each nested session obtains executable-file services from its owned scope.
	 *
	 * @param waiting how each nested session waits between the attempts of a retried operation
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
	 * @param manager the nested-session manager
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
	 * @param sessionTranscripts whether the nested session copies the session's transcripts, its subagents' included,
	 *   into the nested session's harness session transcripts directory when the session closes
	 * @param sessionRequests how many sessions the session may request; with any, the nested session gets its requests
	 *   and responses directories
	 * @param bound the policy that bounds the session's sandbox policy, or {@code null} if nothing bounds it
	 * @param policyListener receives the session's sandbox policy before its Claude Code process starts
	 * @param gitName the Git user name of the copied project, or {@code null} to read {@code project}'s
	 * @param gitEmail the Git user email of the copied project, or {@code null} to read {@code project}'s
	 * @param extraPermissions the writes and hosts that the session's commands need beyond what Claude Code needs
	 * @param role the session's role, which decides its tools, its prompt cache's lifetime and whether it runs inside a
	 *   sandbox, or {@code null} to keep Claude Code's defaults
	 * @param resumedSession the session that the nested session of {@code manager}'s slot records and that the runner
	 *   continues, or {@code null} to start a session in a new nested session. A resumed nested session keeps its
	 *   project, its installed plugins and its session store; the runner writes its settings again under the current
	 *   bound, runs the session under {@code model} and {@code effort} whatever {@code agent} names, and records no end
	 *   if the session cannot start.
	 * @param authentication the credential that the session takes from the launch's source home, which a resumed
	 *   session takes from the resuming launch's source home
	 * @return the live nested session
	 * @throws SessionRefusedException if the session's sandbox would not confine it as its policy requires: its
	 *   bound does not let it write what Claude Code writes outside the session's session folder, which the bound always
	 *   lets it write, or a settings file that Claude Code also loads would widen it; or if the session runs without a
	 *   sandbox and its tools include one beyond {@link ClaudeSessionSandbox#READ_ONLY_TOOLS}; or if the session runs
	 *   inside a sandbox that Claude Code could not start, before the nested session is created or resumed, as
	 *   {@link ClaudeSessionSandbox#startRefusal} decides
	 * @throws IOException if startup fails
	 */
	@SuppressWarnings("PMD.CloseResource")
	public LiveNestedSession start(Path artifact, Path project, Path workspace, List<Path> trialPlugins,
		NestedSessionManager manager, String executable, Map<String, String> environment, String model, String effort,
		ClaudeAgentDefinition agent, boolean installCat, List<Path> readOnlyEvidence,
		List<Path> deniedReads, String instructionFilesMode, boolean sessionTranscripts, int sessionRequests,
		ChildPolicy bound, Consumer<ChildPolicy> policyListener, String gitName, String gitEmail,
		ExtraPermissions extraPermissions, ClaudeSessionRole role, String resumedSession,
		ClaudeAuthentication authentication) throws IOException
	{
		requireThat(policyListener, "policyListener").isNotNull();
		requireThat(authentication, "authentication").isNotNull();
		requireThat(extraPermissions, "extraPermissions").isNotNull();
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
		// Before the nested session is created or resumed, so that a refused launch leaves nothing behind
		if (sandboxed(role))
			requireSandboxStart(project, environment);
		Path artifactRoot = HarnessArtifact.validate(artifact, MANIFEST);
		boolean resumes = resumedSession != null;
		NestedSession nestedSession;
		if (resumes)
			nestedSession = manager.resume(HARNESS_ID, resumedSession);
		else
			nestedSession = manager.create(HARNESS_ID);
		NestedSessionLock lock = null;
		AbstractHarnessRunnerScope scope = new MainHarnessRunnerScope(environment, waiting);
		ExecutableFiles executables = scope.executableFiles();
		try
		{
			// The empty files keep Claude Code's sandbox from mounting a file that the session's tools could not find
			if (sandboxed(role))
				ClaudeMarketplace.writeEmptyMcpConfiguration(nestedSession.directory().getParent().getParent());
			// A resumed nested session keeps the project that its start copied, as its session left it
			if (!resumes)
			{
				manager.copyProject(project, nestedSession);
				if (workspace != null)
					manager.copyWorkspace(workspace, nestedSession);
				manager.snapshotProject(nestedSession);
				HarnessProjectRepository.initialize(project, nestedSession.directory().resolve("project"),
					nestedSession.directory(), environment, executables, gitName, gitEmail);
			}
			Path hostConfiguration = claudeConfigDirectory(environment, Platform.current());
			Path claudeTemporaryDirectory = nestedSession.directory().resolve("claude");
			if (sandboxed(role))
				ClaudeMarketplace.writeEmptyLaunchConfiguration(launchConfigurationPath(claudeTemporaryDirectory));
			// The nested-session manager places every project, home, and Claude temporary directory beneath CAT's isolated
			// work root. This keeps concurrent nested sessions separate without diverting CAT-owned state to the shared
			// system temporary directory.
			Path isolatedProcessHome = nestedSession.directory().resolve("home");
			Path isolatedConfiguration = isolatedProcessHome.resolve(".claude");
			if (installCat && !resumes)
				UserRuleTree.copy(userRulesRoot(environment), ProjectRuleLoader.rulesDirectory(isolatedProcessHome));
			// Each nested session gets an isolated HOME, but packages belong in the parent's explicitly writable shared
			// caches so independent trials do not redownload the same immutable registry artifacts.
			HarnessDependencyCaches caches = HarnessDependencyCache.verify(environment);
			SessionRequestAccess requestAccess = SessionRequestAccess.allowing(nestedSession, artifactRoot, sessionRequests,
				Platform.current(), SessionRequestAccess.Shell.POSIX).orElse(null);
			Map<String, String> isolatedEnvironment = processEnvironment(scope, environment, caches,
				hostConfiguration.resolve("settings.json"), isolatedProcessHome, requestAccess, authentication);
			Path worktree = nestedSession.directory().resolve("project");
			ChildPolicy policy = ClaudeConnectionSettings.writeIsolatedSettings(scope,
				hostConfiguration.resolve("settings.json"), isolatedConfiguration.resolve("settings.json"),
				claudeTemporaryDirectory, isolatedProcessHome, caches.directories(), nestedSession.directory(),
				sessionDeniedReads(manager, nestedSession, readOnlyEvidence, deniedReads, environment, requestAccess),
				readOnlyEvidence, instructionFilesMode, requestAccess, worktree, nestedSession.directory(), bound,
				extraPermissions, authentication.modelHost(), promptCacheTtl(role), sandboxed(role), tools(role),
				authentication);
			requireNoWidening(scope, Platform.current(), worktree, policy, isolatedProcessHome);
			policyListener.accept(policy);
			// The copied project already has its owned baseline. A second initial commit
			// would be empty, so Git would reject it before Claude can start.
			HarnessEnvironment.preventGitDiscoveryAbove(isolatedEnvironment, nestedSession.directory());
			List<Path> plugins;
			if (resumes)
			{
				plugins = installedPlugins(installCat, trialPlugins, isolatedConfiguration, isolatedEnvironment,
					FileReplacer.retrying(waiting), scope.json());
			}
			else
			{
				plugins = installPlugins(executable, artifact, installCat, trialPlugins, isolatedConfiguration,
					isolatedEnvironment, FileReplacer.retrying(waiting), scope.json(),
					(command, step) -> HarnessCommand.execute(command, worktree, isolatedEnvironment, executables, manager,
						nestedSession, step));
			}
			// The first plugin is CAT's when the nested session installs it, and its launchers are the ones that the
			// session's requested sessions may read
			Path catPlugin = null;
			if (installCat)
				catPlugin = plugins.getFirst();
			Path hookResults = createHookResultsDirectory(isolatedConfiguration, nestedSession);
			String selectedModel = model;
			String selectedEffort = effort;
			String selectedAgent = "";
			if (agent != null)
			{
				// A resumed session runs under the model and effort of the launch that resumes it
				if (!resumes)
				{
					selectedModel = agent.model();
					selectedEffort = agent.effort();
				}
				selectedAgent = agent.name();
			}
			List<String> command = command(executable, selectedModel, selectedEffort, selectedAgent, plugins, hookResults,
				readOnlyEvidence, tools(role));
			if (resumes)
				command = resumed(command, resumedSession);
			manager.initialize(nestedSession, artifact, project, "claude_stream_json", command,
				authentication.evidence());
			lock = manager.acquireLock(nestedSession);
			Path projectDirectory = null;
			HarnessSessionTranscripts transcripts = null;
			if (sessionTranscripts)
			{
				// Claude names the root session's transcript after the session, below the directory it names for the worktree
				projectDirectory = hookResults.resolve(claudeProjectDirectory(worktree.toAbsolutePath().normalize()));
				transcripts = new HarnessSessionTranscripts(hookResults, nestedSession.sessionTranscripts(),
					name -> name.endsWith(".jsonl"));
			}
			// A session without a sandbox has none whose failure to confine the watch could report
			SandboxWatch watch = null;
			ProcessOwnership child = new ProcessOwnership();
			ClaudeProcess process = null;
			boolean identityRecorded = false;
			manager.markHarnessStarting(nestedSession);
			try
			{
				// Written last, so that the token rests in the slot only while the Claude process can need it
				authentication.writeKeyFile(isolatedConfiguration);
				child.start(command, worktree, isolatedEnvironment, executables);
				if (sandboxed(role))
				{
					watch = new SandboxWatch(child.process(), scope.json());
					process = new ClaudeProcess(scope, watch.process(), projectDirectory, transcripts);
				}
				else
					process = new ClaudeProcess(scope, child.process(), projectDirectory, transcripts);
				manager.recordHarnessProcess(nestedSession, process.pid(), process.processStart());
				identityRecorded = true;
				HarnessProcess session = process;
				if (watch != null)
					session = watch.guard(process);
				LiveNestedSession live = new LiveNestedSession(nestedSession, removingKeyFile(session, isolatedConfiguration),
					scope, lock, catPlugin, policy.asParentPolicy());
				child.release();
				return live;
			}
			catch (IOException | RuntimeException e)
			{
				cleanupLaunchFailure(child, process, manager, nestedSession, identityRecorded, e);
				deleteKeyFile(isolatedConfiguration, e);
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
			// A resumed nested session whose session could not start stays retained for a later launch
			if (!resumes)
				manager.fail(nestedSession, e.getMessage());
			throw e;
		}
	}

	/**
	 * Refuses a launch whose Claude Code sandbox would not start, once, before the session's Claude process exists. The
	 * check reads the {@code PATH} and the temporary folder from the environment that the Claude process gets, the
	 * {@code TMPDIR} and {@code sandbox.bwrapPath} of the settings files that Claude Code loads beside the session's own,
	 * and runs the program that tries to start a sandbox, only if every cheaper check passed.
	 *
	 * @param project     the project whose settings files Claude Code loads in the session
	 * @param environment the caller's environment
	 * @throws SessionRefusedException if the sandbox would not start, as
	 *   {@link ClaudeSessionSandbox#startRefusal} decides
	 * @throws IOException             if a settings file cannot be read
	 */
	private void requireSandboxStart(Path project, Map<String, String> environment) throws IOException
	{
		Platform platform = Platform.current();
		Map<String, String> processEnvironment = ClaudeProcessEnvironment.baseEnvironment(environment);
		String path = processEnvironment.getOrDefault("PATH", "");
		Path temporaryFolder = ClaudeSessionSandbox.temporaryFolder(processEnvironment);
		Path sandboxExecutable = SeatbeltProfile.SANDBOX_EXECUTABLE;
		try (AbstractHarnessRunnerScope scope = new MainHarnessRunnerScope(environment, waiting))
		{
			// From the lowest precedence to the highest: the project's files, which the managed files override
			List<Path> files = new ArrayList<>();
			files.add(project.resolve(".claude/settings.json"));
			files.add(project.resolve(".claude/settings.local.json"));
			files.addAll(ClaudeConnectionSettings.managedSettings(platform));
			ClaudeSandboxSettings settings = ClaudeSandboxSettings.read(scope.json(), files);
			Optional<String> refusal = ClaudeSessionSandbox.startRefusal(platform, path, temporaryFolder,
				settings.temporaryFolder(), settings.bwrapPath(), sandboxExecutable, null, null);
			if (refusal.isEmpty())
			{
				IOException temporaryFileFailure = null;
				if (platform.isLinux())
					temporaryFileFailure = SandboxProbe.fileCreationFailure(temporaryFolder).orElse(null);
				ProbeResult probe = null;
				Optional<List<String>> command = ClaudeSessionSandbox.probeCommand(platform, path, settings.bwrapPath(),
					sandboxExecutable);
				if (temporaryFileFailure == null && command.isPresent())
				{
					probe = SandboxProbe.run(command.get(), Path.of("").toAbsolutePath(), processEnvironment,
						scope.executableFiles());
				}
				refusal = ClaudeSessionSandbox.startRefusal(platform, path, temporaryFolder, settings.temporaryFolder(),
					settings.bwrapPath(), sandboxExecutable, temporaryFileFailure, probe);
			}
			if (refusal.isPresent())
				throw new SessionRefusedException(refusal.get());
		}
	}

	/**
	 * Returns a session's harness process whose close also deletes the session's key file, once the session's Claude
	 * process has exited and before the caller retains or empties the session's slot, so that the token rests in the
	 * slot only while the Claude process runs.
	 *
	 * @param session      the session's harness process
	 * @param claudeFolder the session's Claude folder, which holds its key file
	 * @return the harness process
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static HarnessProcess removingKeyFile(HarnessProcess session, Path claudeFolder)
	{
		requireThat(session, "session").isNotNull();
		requireThat(claudeFolder, "claudeFolder").isNotNull();
		return new KeyFileRemoval(session, claudeFolder);
	}

	/**
	 * Deletes a session's key file after its launch failed.
	 *
	 * @param claudeFolder the session's Claude folder
	 * @param primary      the launch failure, which receives a failure to delete the file
	 */
	private static void deleteKeyFile(Path claudeFolder, Throwable primary)
	{
		try
		{
			Files.deleteIfExists(ClaudeAuthentication.keyFile(claudeFolder));
		}
		catch (IOException e)
		{
			primary.addSuppressed(e);
		}
	}

	/**
	 * A session's harness process whose close also deletes the session's key file.
	 */
	private static final class KeyFileRemoval implements HarnessProcess
	{
		private final HarnessProcess session;
		private final Path keyFile;

		/**
		 * Creates a harness process.
		 *
		 * @param session      the session's harness process
		 * @param claudeFolder the session's Claude folder
		 */
		private KeyFileRemoval(HarnessProcess session, Path claudeFolder)
		{
			this.session = session;
			this.keyFile = ClaudeAuthentication.keyFile(claudeFolder);
		}

		@Override
		public void prepareTurn()
		{
			session.prepareTurn();
		}

		@Override
		public HarnessProcessResult sendTurn(String request) throws IOException
		{
			return session.sendTurn(request);
		}

		@Override
		public HarnessProcessResult sendTurn(String request, HarnessEventListener listener) throws IOException
		{
			return session.sendTurn(request, listener);
		}

		@Override
		public HarnessProcessResult sendTurn(String request, HarnessEventListener listener, TurnMode mode)
			throws IOException
		{
			return session.sendTurn(request, listener, mode);
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

		@Override
		public Optional<String> harnessSessionId()
		{
			return session.harnessSessionId();
		}

		@Override
		public boolean isAlive()
		{
			return session.isAlive();
		}

		/**
		 * Closes the session's harness process, which ends its Claude process, then deletes the key file, even if the
		 * close failed.
		 *
		 * @throws IOException if the close fails or the file cannot be deleted
		 */
		@Override
		public void close() throws IOException
		{
			try
			{
				session.close();
			}
			catch (IOException | RuntimeException e)
			{
				deleteKeyFile(keyFile.getParent(), e);
				throw e;
			}
			Files.deleteIfExists(keyFile);
		}
	}

	/**
	 * Returns the directory of each plugin that a resumed nested session's start installed, and merges CAT's installer
	 * entries into the isolated configuration's {@code settings.json} again, which the resumed nested session's settings
	 * replaced.
	 *
	 * @param installCat {@code true} if the nested session's start installed CAT
	 * @param trialPlugins the trial plugins that the nested session's start installed
	 * @param isolatedConfiguration the isolated Claude configuration directory
	 * @param environment the environment of the nested session's Claude process
	 * @param replacer replaces {@code settings.json}
	 * @param json parses Claude Code's install records
	 * @return the directory of each installed plugin, CAT first
	 * @throws IOException if a plugin's manifest cannot be read, Claude Code's install records cannot be read or
	 *   {@code settings.json} cannot be changed
	 */
	private static List<Path> installedPlugins(boolean installCat, List<Path> trialPlugins,
		Path isolatedConfiguration, Map<String, String> environment, FileReplacer replacer, JsonMapper json)
		throws IOException
	{
		List<Path> plugins = new ArrayList<>();
		if (installCat)
		{
			Path plugin = isolatedConfiguration.resolve("plugins/cat-marketplace/plugins/cat");
			installPermissions(isolatedConfiguration, environment, plugin, replacer, json);
			writeRunnerMarker(new ClaudeInstallation(isolatedConfiguration.toAbsolutePath().normalize()));
			plugins.add(plugin);
		}
		for (Path trialPlugin : trialPlugins)
		{
			String name = PluginManifest.name(trialPlugin, MANIFEST);
			plugins.add(isolatedConfiguration.resolve("plugins/" + name + "-marketplace/plugins/" + name));
		}
		return plugins;
	}

	/**
	 * Closes a failed harness launch and clears the starting marker only after child exit is confirmed.
	 *
	 * @param child the raw process returned by the operating system, if spawned
	 * @param wrapper the process wrapper, if constructed
	 * @param manager the nested-session manager
	 * @param nestedSession the nested session being launched
	 * @param identityRecorded whether the manifest already contains the child identity
	 * @param primary the launch failure
	 */
	@SuppressWarnings("PMD.CloseResource")
	private static void cleanupLaunchFailure(ProcessOwnership child, ClaudeProcess wrapper, NestedSessionManager manager,
		NestedSession nestedSession, boolean identityRecorded, Throwable primary)
	{
		Process rawProcess = child.processOrNull();
		if (rawProcess != null)
		{
			try
			{
				HarnessProcessTermination.terminate(rawProcess, "claude");
				if (rawProcess.isAlive())
					throw new IOException("Claude child remained alive after launch cleanup");
			}
			catch (IOException | RuntimeException e)
			{
				primary.addSuppressed(e);
			}
		}
		if (wrapper != null && (rawProcess == null || !rawProcess.isAlive()))
		{
			try
			{
				wrapper.close();
			}
			catch (IOException e)
			{
				primary.addSuppressed(e);
			}
		}
		if (!identityRecorded && (rawProcess == null || !rawProcess.isAlive()))
		{
			try
			{
				manager.recordNoHarnessProcess(nestedSession);
			}
			catch (IOException e)
			{
				primary.addSuppressed(e);
			}
		}
	}

	/**
	 * Owns a raw child until its durable identity and wrapper have been accepted by the nested session.
	 */
	private static final class ProcessOwnership
	{
		private Process process;

		/**
		 * Starts and retains the raw child.
		 *
		 * @param command the command
		 * @param worktree the working directory
		 * @param environment the child environment
		 * @param executables the process launcher
		 * @throws IOException if the child cannot be started
		 */
		private void start(List<String> command, Path worktree, Map<String, String> environment,
			ExecutableFiles executables) throws IOException
		{
			process = HarnessProcessStarter.start(command, worktree, environment, executables);
		}

		/**
		 * Returns the retained child.
		 *
		 * @return the raw child process
		 */
		private Process process()
		{
			return process;
		}

		/**
		 * Returns the child if launch cleanup still owns it.
		 *
		 * @return the raw child, or null if ownership transferred
		 */
		private Process processOrNull()
		{
			return process;
		}

		/**
		 * Transfers ownership to the returned harness session.
		 */
		private void release()
		{
			process = null;
		}
	}

	/**
	 * Refuses a session if a settings file that Claude Code loads beside the session's own would widen the session's
	 * sandbox beyond its policy: a managed settings file, or the project's shared or local settings.
	 *
	 * @param scope    services owned for the nested session
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
	 * Returns the environment of a nested session's Claude process. It holds the caller's generic operating-system
	 * variables and its settings for starting subagents, each from its environment or else its Claude settings, so that
	 * the session starts subagents the way the caller does, and the shared dependency caches; the nested session's home
	 * as the home directory and its {@code .claude} as Claude's configuration directory; the variables of the source
	 * home's authentication, as {@link ClaudeAuthentication#environment()} lists them; and the request for Claude's
	 * session state events, which the turn boundary depends on.
	 * <p>
	 * The session's credential comes only from the source home: the environment holds none of the caller's values of
	 * {@link ClaudeAuthentication#CREDENTIAL_VARIABLES}, from its environment or its Claude settings, and none of its
	 * {@code CLAUDE_CODE_SUBPROCESS_ENV_SCRUB} or {@code CAT_HOST_LOGIN}. It names no login store, so the session's
	 * Claude Code keeps any login that {@code /login} stores in the session's own {@code .claude} folder and never reads,
	 * refreshes or replaces the user's. The environment holds no token: a token that the source home selects reaches
	 * Claude Code only through the session's key file, so the commands and hooks that Claude Code starts inherit no
	 * credential, and no request to remove credentials from them. Claude Code keeps the project's
	 * {@code .git/worktrees} read-only in its sandbox whether or not that is asked, so the session's commands register no
	 * worktree; the prompt optimizer's registration operations run outside the sandbox.
	 *
	 * @param scope          services shared for the runner process lifetime
	 * @param environment    the caller's environment
	 * @param caches         the shared dependency caches
	 * @param hostSettings   the caller's Claude settings file
	 * @param processHome    the nested session's isolated home
	 * @param requestAccess  the session's access to requested sessions, whose variables the environment adds, or
	 *                       {@code null} if the session may request none
	 * @param authentication the credential that the session takes from the launch's source home
	 * @return the environment, which the caller may change
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} are null
	 * @throws IOException          if the settings file cannot be read
	 */
	public static Map<String, String> processEnvironment(HarnessRunnerScope scope, Map<String, String> environment,
		HarnessDependencyCaches caches, Path hostSettings, Path processHome, SessionRequestAccess requestAccess,
		ClaudeAuthentication authentication) throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(caches, "caches").isNotNull();
		requireThat(hostSettings, "hostSettings").isNotNull();
		requireThat(processHome, "processHome").isNotNull();
		requireThat(authentication, "authentication").isNotNull();
		Map<String, String> isolatedEnvironment = ClaudeProcessEnvironment.baseEnvironment(environment);
		ClaudeConnectionSettings.copyProcessEnvironment(environment, isolatedEnvironment);
		isolatedEnvironment.putAll(caches.environment());
		ClaudeConnectionSettings.copy(scope.json(), hostSettings, isolatedEnvironment);
		// A credential that the caller's environment held would otherwise outrank the source home's, or bill another
		// login than the one that the user's own Claude Code in the source home uses
		isolatedEnvironment.keySet().removeAll(ClaudeAuthentication.CREDENTIAL_VARIABLES);
		isolatedEnvironment.remove(SCRUB_VARIABLE);
		HarnessEnvironment.setHomeDirectory(isolatedEnvironment, processHome);
		isolatedEnvironment.put("CLAUDE_CONFIG_DIR", processHome.resolve(".claude").toString());
		isolatedEnvironment.putAll(authentication.environment());
		// The turn boundary in ClaudeProcess depends on Claude's session_state_changed lines. Set it after
		// copying the caller's settings so that no inherited value turns it off.
		ClaudeProcessEnvironment.requestSessionStateEvents(isolatedEnvironment);
		// Set after the caller's settings too, so that the session's commands send their sessions to this nested session's
		// runner
		isolatedEnvironment.put(HarnessEnvironment.NESTED_SESSION_VARIABLE, HarnessEnvironment.NESTED_SESSION_VALUE);
		// Claude Code passes these to the commands that it starts
		if (requestAccess != null)
			isolatedEnvironment.putAll(requestAccess.environment());
		return isolatedEnvironment;
	}

	/**
	 * Returns what a Claude child session needs beyond its session folder, as a request server asks for it before it
	 * starts the child: the shared dependency caches, which the child writes, the host of the model provider that the
	 * child's Claude Code connects to, which its commands reach, and the source files that hold the child's credential,
	 * which the requesting session must be allowed to read.
	 *
	 * @param scope          services shared for the runner process lifetime
	 * @param environment    the caller's environment
	 * @param authentication the credential that the child takes from the launch's source home
	 * @return the needs
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a cache cannot be used
	 */
	public static ChildNeedsReport childNeeds(HarnessRunnerScope scope, Map<String, String> environment,
		ClaudeAuthentication authentication) throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(authentication, "authentication").isNotNull();
		HarnessDependencyCaches caches = HarnessDependencyCache.verify(environment);
		return new ChildNeedsReport(caches.directories(), Set.of(authentication.modelHost()),
			authentication.sourceFiles());
	}

	/**
	 * Returns the files and directories that a session of a nested session must not read: those that the caller denies,
	 * every file and directory under the results folder except the session's project, link-targets directory, home,
	 * Claude temporary directory and evidence, and every harness's login and configuration files in the session's home
	 * and in the user's home, as {@link SessionCommandLimits#harnessFiles(Map, Platform)} names them. The runner puts no
	 * login file in the session's home: the session's Claude Code takes its credential from the source home, whose files
	 * that hold a secret the caller denies the session through {@code deniedReads}, as
	 * {@link ClaudeAuthentication#withDeniedReads} adds them. No command receives a credential in its environment, so a
	 * Claude process that a command starts directly has none. The session therefore cannot read the runner's records of
	 * its own nested
	 * session or of any other nested session, the workspace files of any nested session, any login file, such as the
	 * user's own Claude and Codex logins, or a configuration that would let a harness started from a command reach the
	 * model provider. A denied directory that holds the session's project, home, Claude temporary directory, link-targets
	 * directory or evidence, such as the project that holds the results folder, is replaced by every file and directory
	 * under it except those and the directories that lead to them. The link-targets directory holds the read-only copies
	 * that the project's links to targets outside it lead to, so the session reads it; the session's settings do not let
	 * it write it.
	 * <p>
	 * A login or configuration file that does not exist when the session starts is left out, because Claude's sandbox
	 * creates an empty file at a missing denied path, which would change the user's harness configuration; the
	 * session's own settings file and its key file, which the runner writes before the session starts, are always
	 * included.
	 *
	 * @param manager          the nested-session manager
	 * @param nestedSession    the session's nested session
	 * @param readOnlyEvidence the completed evidence directories and files that the session reads
	 * @param deniedReads      the files and directories that the caller denies the session reading
	 * @param environment      the caller's environment, which names the user's home and harness directories
	 * @return the paths to deny, each once
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the results folder or a denied directory cannot be listed
	 */
	public static List<Path> sessionDeniedReads(NestedSessionManager manager, NestedSession nestedSession,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Map<String, String> environment) throws IOException
	{
		return sessionDeniedReads(manager, nestedSession, readOnlyEvidence, deniedReads, environment, null);
	}

	/**
	 * Returns the files and directories that a session of a nested session must not read, as
	 * {@link #sessionDeniedReads(NestedSessionManager, NestedSession, List, List, Map)} does, except that the nested
	 * session's requests and responses directories stay readable when the session may request sessions.
	 *
	 * @param manager          the nested-session manager
	 * @param nestedSession    the session's nested session
	 * @param readOnlyEvidence the completed evidence directories and files that the session reads
	 * @param deniedReads      the files and directories that the caller denies the session reading
	 * @param environment      the caller's environment, which names the user's home and harness directories
	 * @param requestAccess    the session's access to requested sessions, or {@code null} if it may request none
	 * @return the paths to deny, each once
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} are null
	 * @throws IOException          if the results folder or a denied directory cannot be listed
	 */
	public static List<Path> sessionDeniedReads(NestedSessionManager manager, NestedSession nestedSession,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Map<String, String> environment,
		SessionRequestAccess requestAccess) throws IOException
	{
		requireThat(manager, "manager").isNotNull();
		requireThat(nestedSession, "nestedSession").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		requireThat(environment, "environment").isNotNull();
		Path home = nestedSession.directory().resolve("home");
		List<Path> accessible = new ArrayList<>(readOnlyEvidence);
		accessible.add(nestedSession.directory().resolve("project"));
		accessible.add(NestedSessionManager.linkTargets(nestedSession.directory()));
		accessible.add(home);
		accessible.add(nestedSession.directory().resolve("claude"));
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
			if (NestedSessionManager.containsAny(directory, accessible))
				result.addAll(NestedSessionManager.deniedReads(directory, accessible));
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
		for (Path file : harnessFiles)
		{
			if (Files.exists(file, NOFOLLOW_LINKS))
				result.add(file);
		}
		result.add(home.resolve(".claude").resolve("settings.json"));
		result.add(ClaudeAuthentication.keyFile(home.resolve(".claude")));
		return List.copyOf(result);
	}

	/**
	 * Creates the session-owned directory Claude may read for spilled hook context. A resumed nested session finds the
	 * alias that its first start created and keeps it.
	 *
	 * @param isolatedHome the isolated Claude configuration directory
	 * @param nestedSession the owned Claude run
	 * @return the directory containing spilled hook context
	 * @throws IOException if the directory or its narrowly scoped Claude path alias cannot be created, or if the
	 *   alias's location holds anything other than this session's alias
	 */
	public static Path createHookResultsDirectory(Path isolatedHome, NestedSession nestedSession) throws IOException
	{
		assert isolatedHome != null;
		assert nestedSession != null;
		Path hookResults = Files.createDirectories(isolatedHome.resolve("projects"));
		Path worktree = nestedSession.directory().resolve("project").toAbsolutePath().normalize();
		Path canonicalProjectDirectory = Files.createDirectories(hookResults.resolve(claudeProjectDirectory(worktree)));
		Path harnessDirectory = nestedSession.directory().toAbsolutePath().normalize().getParent();
		Path resultsFolder = harnessDirectory.getParent();
		Path reportedParent = Files.createDirectories(hookResults.resolve(claudeProjectDirectory(resultsFolder)).
			resolve(harnessDirectory.getFileName()));
		Path reportedProjectDirectory = reportedParent.resolve(nestedSession.id() + "-project");
		Path aliasTarget = reportedParent.relativize(canonicalProjectDirectory);
		if (Files.exists(reportedProjectDirectory, NOFOLLOW_LINKS))
		{
			// A resumed nested session starts in the home that its first start prepared, so it finds its own alias
			if (Files.isSymbolicLink(reportedProjectDirectory) &&
				Files.readSymbolicLink(reportedProjectDirectory).equals(aliasTarget))
			{
				return hookResults;
			}
			throw new IOException("Claude spill-context alias already exists: " + reportedProjectDirectory);
		}
		// Claude writes the canonicalized path but reports the runner's <harness> directory uncanonicalized
		// to a worktree subagent. This symlink exposes only that nested session's already-authorized hook-results directory
		// at the reported location; it neither grants access to the isolated home nor changes a model-supplied path.
		Files.createSymbolicLink(reportedProjectDirectory, aliasTarget);
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
	 * @param platform    the platform whose home variable names the caller's home directory
	 * @return the host Claude configuration directory
	 */
	private static Path claudeConfigDirectory(Map<String, String> environment, Platform platform)
	{
		assert environment != null;
		assert platform != null;
		return ClaudeConfiguration.resolveDirectory(homeDirectory(environment, platform),
			environment.get("CLAUDE_CONFIG_DIR"));
	}

	/**
	 * Returns the caller's home directory.
	 *
	 * @param environment the caller environment
	 * @param platform    the platform whose home variable names the caller's home directory
	 * @return the directory that the home variable names, or else the working directory
	 */
	private static Path homeDirectory(Map<String, String> environment, Platform platform)
	{
		assert environment != null;
		assert platform != null;
		String home = environment.get(platform.homeVariable());
		// Retain the runner's working-directory fallback for optional host settings.
		if (home == null || home.isBlank())
			return Path.of("").toAbsolutePath();
		return Path.of(home);
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
	private static Exception close(NestedSessionLock lock)
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
	 * <p>
	 * Installing CAT also merges into the isolated configuration's {@code settings.json} the entries that CAT's
	 * installer writes for an installed plugin: the sandbox exclusions and permissions for its installer and request
	 * server. They name the copy of CAT in the nested session's marketplace, and each copy that Claude Code's install
	 * records name for CAT. The installer's other entries, which cover the user's shell startup files, the capacity locks
	 * and the other harness's installation, are left out: the nested session's own sandbox policy covers its files, and
	 * the nested session shares neither locks nor an installation with the user's.
	 *
	 * @param executable the Claude executable
	 * @param artifact the complete Claude artifact, which is installed as CAT
	 * @param installCat {@code true} to install CAT
	 * @param trialPlugins the trial plugins, each installed under its manifest's name
	 * @param isolatedConfiguration the isolated Claude configuration directory
	 * @param environment the environment of the nested session's Claude process, which CAT's installer records as the
	 *   harness environment
	 * @param replacer replaces {@code settings.json}
	 * @param json parses Claude Code's install records
	 * @param commands runs the commands that add each marketplace and install each plugin
	 * @return the directory of each installed plugin, CAT first
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code executable} is blank
	 * @throws IOException if a plugin's manifest cannot be read, a marketplace cannot be created, a command fails,
	 *   Claude Code's install records cannot be read or {@code settings.json} cannot be changed
	 */
	public static List<Path> installPlugins(String executable, Path artifact, boolean installCat,
		List<Path> trialPlugins, Path isolatedConfiguration, Map<String, String> environment, FileReplacer replacer,
		JsonMapper json, SetupCommands commands) throws IOException
	{
		requireThat(executable, "executable").isNotBlank();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(trialPlugins, "trialPlugins").isNotNull();
		requireThat(isolatedConfiguration, "isolatedConfiguration").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(commands, "commands").isNotNull();
		List<Path> plugins = new ArrayList<>();
		if (installCat)
		{
			Path marketplace = ClaudeMarketplace.create(artifact, isolatedConfiguration);
			List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace, "cat");
			commands.execute(pluginCommands.get(0), "marketplace-add");
			commands.execute(pluginCommands.get(1), "plugin-install");
			Path plugin = marketplace.resolve("plugins/cat");
			installPermissions(isolatedConfiguration, environment, plugin, replacer, json);
			writeRunnerMarker(new ClaudeInstallation(isolatedConfiguration.toAbsolutePath().normalize()));
			plugins.add(plugin);
		}
		for (Path trialPlugin : trialPlugins)
		{
			String name = PluginManifest.name(trialPlugin, MANIFEST);
			Path marketplace = ClaudeMarketplace.create(trialPlugin, name, name, isolatedConfiguration);
			List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace, name);
			// Each plugin's steps are named after it, so that the nested session's log tells the plugins apart
			commands.execute(pluginCommands.get(0), "trial-plugin-" + name + "-marketplace-add");
			commands.execute(pluginCommands.get(1), "trial-plugin-" + name + "-install");
			plugins.add(marketplace.resolve("plugins/" + name));
		}
		return plugins;
	}

	/**
	 * Records that the runner, not CAT's installer, installed CAT into the isolated configuration. The installation has
	 * no installer policy, so its installer reports the installed version and changes nothing.
	 *
	 * @param installation the isolated installation
	 * @throws IOException if the marker cannot be written
	 */
	private static void writeRunnerMarker(ClaudeInstallation installation) throws IOException
	{
		Path marker = installation.runnerMarker();
		Files.createDirectories(marker.getParent());
		Files.writeString(marker, "");
	}

	/**
	 * Writes the entries that CAT's installer writes into {@code settings.json} for each installed copy of CAT: the copy
	 * in the nested session's marketplace and each copy that Claude Code's install records name for CAT.
	 *
	 * @param isolatedConfiguration the isolated Claude configuration directory
	 * @param environment the environment of the nested session's Claude process, which CAT's installer records as the
	 *   harness environment
	 * @param marketplacePlugin the copy of CAT in the nested session's marketplace
	 * @param replacer replaces {@code settings.json}
	 * @param json parses Claude Code's install records
	 * @throws IOException if the install records cannot be read or {@code settings.json} cannot be changed
	 */
	private static void installPermissions(Path isolatedConfiguration, Map<String, String> environment,
		Path marketplacePlugin, FileReplacer replacer, JsonMapper json) throws IOException
	{
		Path configuration = isolatedConfiguration.toAbsolutePath().normalize();
		Set<Path> copies = new LinkedHashSet<>();
		copies.add(marketplacePlugin.toAbsolutePath().normalize());
		copies.addAll(InstalledPlugins.of(configuration, "cat@cat", json));
		Integration integration = new Integration();
		for (Path copy : copies)
		{
			integration.installPermissions(environment, configuration, copy.resolve(PluginDirectory.INSTALLER), replacer,
				entries ->
				{
					// The nested session owns and removes this entire isolated configuration.
				});
		}
	}

	/**
	 * Builds a Claude command with an explicit profile, optional direct agent, installed plugins and immutable evidence
	 * directories. The session keeps its Claude Code session in the nested session's home, as every nested session's
	 * does, so that the session can continue after its runner ends.
	 *
	 * @param executable       the Claude executable
	 * @param model            the requested model
	 * @param effort           the requested reasoning effort; {@code none} starts Claude Code with extended thinking
	 *   turned off ({@code --thinking disabled}) and no {@code --effort}, because Claude Code ignores an
	 *   {@code --effort} it does not list and applies its default effort instead
	 * @param agent            the optional direct CAT agent name
	 * @param plugins          the directories of the plugins installed in the isolated home
	 * @param hookResults      the hook context directory
	 * @param readOnlyEvidence executor evidence exposed through added directories
	 * @return the complete harness command
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code executable}, {@code model} or {@code effort} is blank
	 */
	public static List<String> command(String executable, String model, String effort, String agent, List<Path> plugins,
		Path hookResults, List<Path> readOnlyEvidence)
	{
		return command(executable, model, effort, agent, plugins, hookResults, readOnlyEvidence, List.of());
	}

	/**
	 * Builds a Claude command as {@link #command(String, String, String, String, List, Path, List)} does, except that
	 * the session's model is offered only {@code tools}.
	 *
	 * @param executable       the Claude executable
	 * @param model            the requested model
	 * @param effort           the requested reasoning effort
	 * @param agent            the optional direct CAT agent name
	 * @param plugins          the directories of the plugins installed in the isolated home
	 * @param hookResults      the hook context directory
	 * @param readOnlyEvidence executor evidence exposed through added directories
	 * @param tools            the only tools that the session's model is offered, or none to keep every tool. The
	 *   option {@code --tools} removes the other tools' definitions from the model's requests, which
	 *   {@code --allowedTools} does not
	 * @return the complete harness command
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code executable}, {@code model} or {@code effort} is blank
	 */
	public static List<String> command(String executable, String model, String effort, String agent, List<Path> plugins,
		Path hookResults, List<Path> readOnlyEvidence, List<String> tools)
	{
		requireThat(tools, "tools").isNotNull();
		requireThat(executable, "executable").isNotBlank();
		requireThat(model, "model").isNotBlank();
		requireThat(effort, "effort").isNotBlank();
		requireThat(agent, "agent").isNotNull();
		requireThat(plugins, "plugins").isNotNull();
		requireThat(hookResults, "hookResults").isNotNull();
		List<String> command = new ArrayList<>(List.of(executable, "--print", "--input-format", "stream-json",
			"--output-format", "stream-json", "--permission-mode", "acceptEdits",
			"--verbose", "--include-partial-messages"));
		// Every nested session keeps its session in the nested session's home, so that the session can continue after its
		// runner ends. A runner launcher that the session's commands run also finds the session's sandbox policy from its
		// transcript.
		for (Path plugin : plugins)
		{
			command.add("--add-dir");
			command.add(plugin.toString());
		}
		if (!plugins.isEmpty())
		{
			// Claude writes spilled hook context under this session-owned directory. Allowing it lets the required
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
		if (!tools.isEmpty())
		{
			command.add("--tools");
			command.add(String.join(",", tools));
		}
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
		return command;
	}

	/**
	 * Returns a harness command that continues a recorded session instead of starting one. Claude Code keeps a resumed
	 * session's identifier.
	 *
	 * @param command   the harness command
	 * @param sessionId the session that the command continues
	 * @return the command that resumes the session
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code sessionId} is blank
	 */
	public static List<String> resumed(List<String> command, String sessionId)
	{
		requireThat(command, "command").isNotNull();
		requireThat(sessionId, "sessionId").isNotBlank();
		List<String> resumed = new ArrayList<>(command);
		resumed.add("--resume");
		resumed.add(sessionId);
		return resumed;
	}


	/**
	 * Returns the tools that a session's model is offered.
	 *
	 * @param role the session's role, or {@code null} if it has none
	 * @return the tool names, empty to keep every tool
	 */
	private static List<String> tools(ClaudeSessionRole role)
	{
		if (role == null)
			return List.of();
		return role.tools();
	}

	/**
	 * Indicates whether Claude Code confines a session's commands in a sandbox.
	 *
	 * @param role the session's role, or {@code null} if it has none
	 * @return {@code true} if it does, which is also the case for a session with no role
	 */
	private static boolean sandboxed(ClaudeSessionRole role)
	{
		return role == null || role.sandboxed();
	}

	/**
	 * Returns the lifetime of a session's prompt cache in the notation of Claude Code's {@code promptCacheTtl}
	 * setting.
	 *
	 * @param role the session's role, or {@code null} if it has none
	 * @return the lifetime, or an empty string to keep Claude Code's default
	 */
	private static String promptCacheTtl(ClaudeSessionRole role)
	{
		if (role == null)
			return "";
		return role.promptCacheTtl();
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
	 * {@code permission_denied} reports; a sandboxed command is not asked about, because the runner refuses settings
	 * whose denied paths are too many for the sandbox to apply, which make Claude Code ask about every command. Either
	 * of the latter means that the requirement stopped working, so the watch stops the process tree at once. Either
	 * way, the session's next turn is refused with Claude Code's reason.
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
		 * Stops the process tree, then records a refusal, so that no caller sees the refusal while the process still
		 * runs.
		 *
		 * @param reason the refusal
		 */
		private void stop(String reason)
		{
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
			refusal = reason;
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
			public void prepareTurn()
			{
				session.prepareTurn();
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
				return sendTurn(request, listener, TurnMode.STANDARD);
			}

			@Override
			public HarnessProcessResult sendTurn(String request, HarnessEventListener listener, TurnMode mode)
				throws IOException
			{
				requireNoRefusal();
				HarnessProcessResult result;
				try
				{
					result = session.sendTurn(request, listener, mode);
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

			@Override
			public Optional<String> harnessSessionId()
			{
				return session.harnessSessionId();
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

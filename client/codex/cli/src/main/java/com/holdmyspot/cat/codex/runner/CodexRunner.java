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
import com.holdmyspot.cat.common.runner.AbstractHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.HarnessArtifact;
import com.holdmyspot.cat.common.runner.HarnessCommand;
import com.holdmyspot.cat.common.runner.HarnessCredentials;
import com.holdmyspot.cat.common.runner.HarnessDependencyCache;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessProcessStarter;
import com.holdmyspot.cat.common.runner.HarnessProcessTermination;
import com.holdmyspot.cat.common.runner.HarnessProjectRepository;
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
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import tools.jackson.databind.JsonNode;

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
	 * Creates a Codex artifact runner. Each nested session obtains executable-file services from its owned scope.
	 *
	 * @param waiting how each nested session waits between the attempts of a retried operation
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
	 * @param manager the nested-session manager
	 * @param executable the Codex executable
	 * @param environment the scrubbed isolated environment
	 * @param model the Codex model name
	 * @param effort the reasoning effort of each turn
	 * @param installCat whether CAT is installed in the isolated home
	 * @param agent the optional direct CAT agent definition, whose model and effort replace {@code model} and
	 *   {@code effort}
	 * @param readOnlyEvidence the completed evidence files and directories that the session may read but not change
	 * @param deniedReads the existing files and directories that the session must not read, besides the records of
	 *   its own nested session
	 * @param toolOutputTokenLimit the most tokens of each tool output that the session keeps, or {@code 0} for
	 *   Codex's default
	 * @param sessionTranscripts whether the nested session copies its threads' rollouts into its session transcripts
	 *   directory when the session closes. Codex keeps every thread in the nested session's home either way, so that the
	 *   nested session's harness session can continue after its runner ends
	 * @param sessionRequests how many sessions the session may request over its nested session, or {@code 0} if it may
	 *   request none
	 * @param bound the policy that bounds the session's sandbox: the session gets its session folder, the dependency
	 *   caches and its model host only within it
	 * @param policyListener receives the session's sandbox policy before its Codex process starts
	 * @param gitName the Git user name of the copied project, or {@code null} to read {@code project}'s
	 * @param gitEmail the Git user email of the copied project, or {@code null} to read {@code project}'s
	 * @param extraPermissions the writes and hosts that the session's commands need beyond what Codex needs
	 * @param resumedSession the thread that the nested session of {@code manager}'s slot records and that the runner
	 *   continues, or {@code null} to start a thread in a new nested session. A resumed nested session keeps its project
	 *   and its installed plugins, and its thread keeps the developer instructions and agent contract that its start gave
	 *   it, but runs under {@code model} and {@code effort} and the sandbox that {@code bound} allows now.
	 * @return the live owned Codex run session
	 * @throws SessionRefusedException if the session's sandbox would be wider than its policy or cannot confine it
	 * @throws IOException             if validation, copying, or process startup fails
	 */
	@SuppressWarnings("PMD.CloseResource")
	LiveNestedSession start(Path artifact, Path project, Path workspace, List<TrialPluginInput> trialPlugins,
		NestedSessionManager manager, String executable, Map<String, String> environment, String model, String effort,
		boolean installCat, CodexAgentDefinition agent, List<Path> readOnlyEvidence, List<Path> deniedReads,
		int toolOutputTokenLimit, boolean sessionTranscripts, int sessionRequests, ParentPolicy bound,
		Consumer<ChildPolicy> policyListener, String gitName, String gitEmail, ExtraPermissions extraPermissions,
		String resumedSession) throws IOException
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
			// A resumed nested session keeps the project that its start copied, as its thread left it
			if (!resumes)
			{
				manager.copyProject(project, nestedSession);
				if (workspace != null)
					manager.copyWorkspace(workspace, nestedSession);
				manager.snapshotProject(nestedSession);
				HarnessProjectRepository.initialize(project, nestedSession.directory().resolve("project"),
					nestedSession.directory(), environment, executables, gitName, gitEmail);
			}
			Path isolatedHome = nestedSession.directory().resolve("home");
			Path temporaryDirectory = Files.createDirectories(isolatedHome.resolve("tmp"));
			Path hostHome = CodexInstallation.resolveHome(environment);
			HarnessCredentials.copyAllowedFiles(hostHome, isolatedHome, Set.of(LOGIN));
			Path isolatedConfiguration = configuration(isolatedHome);
			HarnessDependencyCaches caches = HarnessDependencyCache.verify(environment);
			SessionRequestAccess.Shell shell = SessionRequestAccess.Shell.POSIX;
			if (Platform.current().isWindows())
				shell = SessionRequestAccess.Shell.POWERSHELL;
			SessionRequestAccess requestAccess = SessionRequestAccess.allowing(nestedSession, artifact, sessionRequests,
				Platform.current(), shell).orElse(null);
			// A runner that a Codex thread starts gives the session the multi-agent state that the thread runs with
			CodexParentMultiAgentState parentState = CodexParentMultiAgentState.read(scope.json(), hostHome,
				environment.get(THREAD_ID));
			// The session's commands may reach only the host that its own Codex sends model requests to
			String modelHost = CodexConnectionConfiguration.modelHost(configuration(hostHome), environment,
				isolatedHome.resolve(LOGIN));
			ChildPolicy policy = ChildPolicy.intersect(bound, new ChildNeeds(nestedSession.directory().toAbsolutePath(),
				caches.directories(), List.of(), Set.of(modelHost)).with(extraPermissions));
			boolean pluginsInstalled = installCat || !trialPlugins.isEmpty();
			List<String> appServer = appServerCommand(executable, pluginsInstalled);
			Path worktree = nestedSession.directory().resolve("project");
			FileReplacer replacer = FileReplacer.retrying(waiting);
			// An interrupted resume must leave the installed plugin and trust records available for another attempt.
			Path stagedConfiguration = Files.createTempFile(isolatedHome, ".config-", ".toml");
			SessionSandbox sandbox;
			try
			{
				Path retainedConfiguration = null;
				if (resumes)
					retainedConfiguration = isolatedConfiguration;
				CodexConnectionConfiguration.copy(configuration(hostHome), stagedConfiguration, worktree, caches,
					temporaryDirectory, toolOutputTokenLimit, requestAccess, Platform.current(), parentState, policy,
					extraPermissions, retainedConfiguration);
				if (!Platform.current().isWindows())
				{
					CodexConnectionConfiguration.requireNarrowingLayers(CodexConnectionConfiguration.SYSTEM_LAYER,
						CodexConnectionConfiguration.MANAGED_LAYER, policy);
				}
				policyListener.accept(policy);
				sandbox = sessionSandbox(stagedConfiguration, manager, nestedSession, readOnlyEvidence, deniedReads,
					Platform.current(), environment, requestAccess, appServer, policy);
				replacer.replace(stagedConfiguration, isolatedConfiguration);
			}
			finally
			{
				Files.deleteIfExists(stagedConfiguration);
			}
			Map<String, String> isolatedEnvironment = processEnvironment(environment, caches, isolatedHome,
				nestedSession.directory(), temporaryDirectory, Platform.current());
			requireNetworkProxy(arguments -> commandOutput(executable, arguments, worktree, isolatedEnvironment,
				executables, manager, nestedSession), featureFlags(pluginsInstalled), policy.hosts());
			// A resumed nested session keeps the plugins that its start installed in its home
			Path hookBindings = nestedSession.directory().resolve(CodexPluginHooks.BINDINGS_FILE);
			Map<String, String> boundHooks;
			if (resumes)
			{
				boundHooks = CodexPluginHooks.resumeBindings(scope.json(), hookBindings,
					CodexConnectionConfiguration.configuredHookApprovals(isolatedConfiguration), installCat);
			}
			else
			{
				installPlugins(executable, artifact, installCat, trialPlugins.stream().map(TrialPluginInput::path).toList(),
					hostHome, isolatedHome,
					isolatedEnvironment, executables, replacer, (setup, step) ->
						HarnessCommand.execute(setup, worktree, isolatedEnvironment, executables, manager, nestedSession, step));
				boundHooks = copyBoundTrialHooks(trialPlugins, hostHome, isolatedHome, project, worktree, temporaryDirectory,
					executable, bound, environment, scope);
				if (!boundHooks.isEmpty())
					CodexPluginHooks.saveBindings(scope.json(), hookBindings, boundHooks);
			}
			// The requested sessions of the session may read the CAT version that Codex installed, whose launchers the
			// session's commands run
			Path catPlugin = null;
			if (installCat)
				catPlugin = installedCat(isolatedHome);
			List<String> command = sandbox.command();
			if (!boundHooks.isEmpty())
				command = protectHookBindings(command, hookBindings, bound, environment);
			manager.initialize(nestedSession, artifact, project, "codex_app_server", command);
			lock = manager.acquireLock(nestedSession);
			HarnessSessionTranscripts transcripts = null;
			if (sessionTranscripts)
				transcripts = CodexProcess.rollouts(isolatedHome, nestedSession.sessionTranscripts());
			manager.markHarnessStarting(nestedSession);
			ProcessOwnership child = new ProcessOwnership();
			CodexProcess process = null;
			boolean identityRecorded = false;
			try
			{
				child.start(command, worktree, isolatedEnvironment, executables);
				process = new CodexProcess(child.process(), scope, installCat, transcripts);
				manager.recordHarnessProcess(nestedSession, process.pid(), process.processStart());
				identityRecorded = true;
				String selectedModel = model;
				String agentInstructions = "";
				String selectedEffort = effort;
				// A resumed thread runs under the model and effort of the launch that resumes it
				if (agent != null && !resumes)
				{
					selectedModel = agent.model();
					agentInstructions = agent.developerInstructions();
					selectedEffort = agent.effort();
				}
				try
				{
					// A resumed thread keeps the developer instructions that its start gave it
					String instructions = "";
					if (!resumes)
					{
						instructions = developerInstructions(manager.root().toRealPath(), worktree, isolatedHome,
							agentInstructions);
					}
					process.initialize(worktree, selectedModel, instructions, selectedEffort, sandbox.profile(),
					resumedSession, boundHooks);
				}
				catch (IOException e)
				{
					throw refusalOf(e);
				}
				LiveNestedSession session = new LiveNestedSession(nestedSession, process, scope, lock, catPlugin);
				child.release();
				return session;
			}
			catch (IOException | RuntimeException e)
			{
				cleanupLaunchFailure(child, process, manager, nestedSession, identityRecorded, e);
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
			// A resumed nested session whose thread could not continue stays retained for a later launch
			if (!resumes)
				manager.fail(nestedSession, e.getMessage());
			throw e;
		}
	}

	/**
	 * Keeps the runner's trial hook bindings read-only in the native process and its descendants.
	 *
	 * @param command the app-server command with any existing outer sandbox
	 * @param file the runner-owned bindings file
	 * @param policy the authenticated parent policy
	 * @param environment the launcher's environment
	 * @return the command with the existing or a new sandbox protecting the file
	 * @throws IOException if the bindings cannot be protected
	 */
	private static List<String> protectHookBindings(List<String> command, Path file, ParentPolicy policy,
		Map<String, String> environment) throws IOException
	{
		Path wrapper = bubblewrap(environment).orElseThrow(() ->
			new IOException("Codex cannot protect the retained trial's plugin hook bindings without bubblewrap"));
		try
		{
			policy.requireTrusted(wrapper.toRealPath());
		}
		catch (PolicyRefusal e)
		{
			throw new IOException("Codex cannot protect trial hook bindings with an untrusted sandbox", e);
		}
		String bindings = file.toRealPath().toString();
		List<String> rootMount = List.of(wrapper.toString(), "--dev-bind", "/", "/");
		List<String> protectedCommand = new ArrayList<>(rootMount);
		protectedCommand.addAll(List.of("--ro-bind", bindings, bindings));
		if (command.size() >= rootMount.size() && command.subList(0, rootMount.size()).equals(rootMount))
		{
			protectedCommand.addAll(command.subList(rootMount.size(), command.size()));
		}
		else
		{
			protectedCommand.addAll(List.of("--die-with-parent", "--"));
			protectedCommand.addAll(command);
		}
		return protectedCommand;
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
	private static void cleanupLaunchFailure(ProcessOwnership child, CodexProcess wrapper, NestedSessionManager manager,
		NestedSession nestedSession, boolean identityRecorded, Throwable primary)
	{
		Process rawProcess = child.processOrNull();
		if (rawProcess != null)
		{
			try
			{
				HarnessProcessTermination.terminate(rawProcess, "codex");
				if (rawProcess.isAlive())
					throw new IOException("Codex child remained alive after launch cleanup");
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
	 * Returns the environment of a nested session's app-server. It holds the caller's generic operating-system variables,
	 * its Codex authentication variables and the shared dependency caches, names the nested session's isolated home as
	 * the Codex home, and stops Git from finding a repository above the session folder. On Linux, it also names the
	 * nested session's temporary directory as {@code TMPDIR}, the directory that the nested session's configuration gives
	 * the session's shell commands.
	 *
	 * @param environment        the caller's environment
	 * @param caches             the shared dependency caches
	 * @param isolatedHome       the nested session's isolated home
	 * @param sessionFolder      the nested session's directory
	 * @param temporaryDirectory the nested session's temporary directory, which the nested session's configuration names
	 *                           as the shell commands' {@code TMPDIR}
	 * @param platform           the platform that the app-server runs on
	 * @return the environment, which the caller may change
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code sessionFolder} is relative
	 */
	public static Map<String, String> processEnvironment(Map<String, String> environment,
		HarnessDependencyCaches caches, Path isolatedHome, Path sessionFolder, Path temporaryDirectory,
		Platform platform)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(caches, "caches").isNotNull();
		requireThat(isolatedHome, "isolatedHome").isNotNull();
		requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
		requireThat(platform, "platform").isNotNull();
		Map<String, String> isolatedEnvironment = new HashMap<>(HarnessEnvironment.scrub(environment));
		HarnessEnvironment.copyAllowed(environment, isolatedEnvironment, AUTHENTICATION_VARIABLES);
		// Each nested session gets an isolated HOME, but packages belong in the parent's explicitly writable shared caches
		// so independent trials do not redownload the same immutable registry artifacts.
		isolatedEnvironment.putAll(caches.environment());
		isolatedEnvironment.put(CODEX_HOME, isolatedHome.toString());
		HarnessEnvironment.preventGitDiscoveryAbove(isolatedEnvironment, sessionFolder);
		// The session's commands send their sessions to this nested session's runner
		isolatedEnvironment.put(HarnessEnvironment.NESTED_SESSION_VARIABLE, HarnessEnvironment.NESTED_SESSION_VALUE);
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
	 * Returns what a Codex child session needs beyond its session folder, as a request server asks for it before it
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
	 * Returns the developer instructions of a session: a note that the denied reads under the results folder cover
	 * neither the session's project nor its home, followed by the agent's contract, if any. Codex lists the results
	 * folder among the denied reads in the model's instructions, and without the note models answered that their own
	 * project was denied without trying to read it.
	 *
	 * @param root the results folder, as the denied reads name it
	 * @param project the session's project, which is its working directory
	 * @param home the session's Codex home
	 * @param agentInstructions the agent's contract, or an empty string for a session without an agent
	 * @return the developer instructions
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static String developerInstructions(Path root, Path project, Path home, String agentInstructions)
	{
		requireThat(root, "root").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(home, "home").isNotNull();
		requireThat(agentInstructions, "agentInstructions").isNotNull();
		String note = "The denied reads protect other sessions' projects in " + root + ". They do not apply to your " +
			"project " + project + ", your working directory, or to anything under it, nor to " + home + ": you have " +
			"full read and write access to both. Your project's files are not in your context; read one with a shell " +
			"command whenever a task needs it.";
		StringJoiner instructions = new StringJoiner("\n\n").add(note);
		if (!agentInstructions.isEmpty())
			instructions.add(agentInstructions);
		return instructions.toString();
	}

	/**
	 * Adds to a session's configuration the permission profile that its thread runs under, as
	 * {@link #addSessionProfile(Path, NestedSessionManager, NestedSession, List, List, Platform, Map,
	 * SessionRequestAccess)} does, and returns it with the command that starts the session's app-server.
	 * <p>
	 * When the caller's {@code PATH} has bubblewrap, that command runs the app-server under bubblewrap, as
	 * {@link #withHiddenDirectories(List, Path, List, List, List)} describes, which hides from the app-server each
	 * directory that {@link #hiddenDirectories(NestedSessionManager, List, List, Platform, Map)} returns, and reopens
	 * inside it the session's project and home and its requests directory, if any, for writing and the session's
	 * read-only evidence for reading only. The profile does not enumerate files beside the evidence, so its size
	 * does not grow with the number of runs or unrelated files. Otherwise, and when it hides nothing, the command
	 * is the app-server command itself.
	 * <p>
	 * A Linux session whose {@code readOnlyEvidence} lies inside a directory that it is denied, such as the results
	 * folder, cannot start without that bubblewrap, so the method refuses it before it changes the configuration.
	 *
	 * @param configuration    the session's isolated Codex configuration, which holds the CAT runner profile
	 * @param manager          the nested-session manager
	 * @param nestedSession    the session's nested session
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
	public static SessionSandbox sessionSandbox(Path configuration, NestedSessionManager manager,
		NestedSession nestedSession,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Platform platform, Map<String, String> environment,
		SessionRequestAccess requestAccess, List<String> appServer) throws IOException
	{
		return sessionSandbox(configuration, manager, nestedSession, readOnlyEvidence, deniedReads, platform,
			environment, requestAccess, appServer, null);
	}

	/**
	 * Prepares an app-server command and its thread profile together. If a Linux app-server already masks a
	 * directory, the thread profile relies on that mask and retains the policy's other read denials. The default
	 * runner profile still enforces the full policy for commands outside the app-server.
	 *
	 * @param configuration the isolated Codex configuration that contains the runner profile
	 * @param manager the nested-session manager
	 * @param nestedSession the session's nested session
	 * @param readOnlyEvidence the caller's permitted read-only evidence
	 * @param deniedReads the paths that the caller denies the session reading
	 * @param platform the session's platform
	 * @param environment the caller's environment
	 * @param requestAccess the session's request access, or {@code null} if it may request none
	 * @param appServer the command that starts the app-server
	 * @param policy the policy used to generate the runner profile, or {@code null} to use that profile as written
	 * @return the sandbox command and its matching thread profile
	 * @throws IOException if the sandbox cannot be prepared
	 */
	public static SessionSandbox sessionSandbox(Path configuration, NestedSessionManager manager,
		NestedSession nestedSession,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Platform platform, Map<String, String> environment,
		SessionRequestAccess requestAccess, List<String> appServer, ChildPolicy policy) throws IOException
	{
		requireThat(appServer, "appServer").isNotEmpty();
		requireBubblewrapForEvidence(manager, readOnlyEvidence, deniedReads, platform, environment);
		List<Path> readOnly = sessionReadOnly(nestedSession, readOnlyEvidence, requestAccess);
		List<Path> denied = sessionDeniedReads(manager, nestedSession, readOnly, deniedReads, platform, environment,
			requestAccess);
		Optional<Path> bubblewrap = bubblewrap(environment);
		if (bubblewrap.isEmpty())
			return new SessionSandbox(CodexConnectionConfiguration.addSessionProfile(configuration, readOnly, denied),
				appServer);
		List<Path> writable = new ArrayList<>(List.of(nestedSession.directory().resolve("project"),
			nestedSession.directory().resolve("home")));
		if (requestAccess != null)
			writable.add(requestAccess.requests());
		OuterSandbox outer = outerSandbox(appServer, bubblewrap.get(),
			hiddenDirectories(manager, readOnly, deniedReads, platform, environment), writable, readOnly);
		String runner = CodexConnectionConfiguration.addMountedRunnerProfile(configuration, outer.masked(),
			outer.reopened(), policy);
		String profile = CodexConnectionConfiguration.addSessionProfile(configuration, readOnly, denied, runner);
		return new SessionSandbox(profile, outer.command());
	}

	/**
	 * Refuses a Linux session that reads evidence inside a directory that it is denied when the caller's {@code PATH}
	 * has no bubblewrap. The Linux sandbox of Codex hides read-only paths inside a denied directory, so only the
	 * bubblewrap around the app-server can show the session its evidence while it hides the rest of that directory.
	 * Denying each other path under the directory instead makes the permission profile of a session beside thousands of
	 * nested sessions too long for Codex's sandbox helper, which then fails to start the session's thread.
	 *
	 * @param manager          the nested-session manager
	 * @param readOnlyEvidence the completed evidence directories and files that the caller lets the session read
	 * @param deniedReads      the files and directories that the caller denies the session reading
	 * @param platform         the platform that the session runs on
	 * @param environment      the caller's environment, whose {@code PATH} names bubblewrap
	 * @throws IOException if the session must be refused
	 */
	private static void requireBubblewrapForEvidence(NestedSessionManager manager, List<Path> readOnlyEvidence,
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
	 * not change {@code readOnlyEvidence}, as {@link #sessionReadOnly(NestedSession, List, SessionRequestAccess)} extends
	 * it, and may not read the paths that {@link #sessionDeniedReads(NestedSessionManager, NestedSession, List, List,
	 * Platform, Map, SessionRequestAccess)} returns.
	 *
	 * @param configuration    the session's isolated Codex configuration, which holds the CAT runner profile
	 * @param manager          the nested-session manager
	 * @param nestedSession    the session's nested session
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
	public static String addSessionProfile(Path configuration, NestedSessionManager manager, NestedSession nestedSession,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Platform platform, Map<String, String> environment,
		SessionRequestAccess requestAccess) throws IOException
	{
		requireThat(configuration, "configuration").isNotNull();
		List<Path> sessionReadOnly = sessionReadOnly(nestedSession, readOnlyEvidence, requestAccess);
		return CodexConnectionConfiguration.addSessionProfile(configuration, sessionReadOnly,
			sessionDeniedReads(manager, nestedSession, sessionReadOnly, deniedReads, platform, environment, requestAccess));
	}

	/**
	 * Returns the completed files and directories that a session may read but not change: the caller's read-only
	 * evidence, the nested session's link-targets directory if the nested session has one, which holds the copies that
	 * the project's links to targets outside it lead to, and the nested session's responses directory when the session
	 * may request sessions, so that a link that the session plants there cannot redirect the runner's answers.
	 *
	 * @param nestedSession    the session's nested session
	 * @param readOnlyEvidence the completed evidence directories and files that the caller lets the session read
	 * @param requestAccess    the session's access to requested sessions, or {@code null} if it may request none
	 * @return the files and directories
	 * @throws NullPointerException if {@code nestedSession} or {@code readOnlyEvidence} are null
	 */
	public static List<Path> sessionReadOnly(NestedSession nestedSession, List<Path> readOnlyEvidence,
		SessionRequestAccess requestAccess)
	{
		requireThat(nestedSession, "nestedSession").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		List<Path> result = new ArrayList<>(readOnlyEvidence);
		Path linkTargets = NestedSessionManager.linkTargets(nestedSession.directory());
		if (Files.isDirectory(linkTargets))
			result.add(linkTargets);
		if (requestAccess != null)
			result.add(requestAccess.responses());
		return List.copyOf(result);
	}

	/**
	 * Returns the files and directories that a session of a nested session must not read: those that the caller denies,
	 * every file and directory under the results folder except the session's project, home, link-targets directory and
	 * evidence, the directory where Codex logs the session's events unless the session may request sessions, and every
	 * harness's login and configuration files in the session's home and in the user's home, as {@link
	 * SessionCommandLimits#harnessFiles(Map, Platform)} names them, except two files in the session's own Codex home: its
	 * {@code config.toml}, which holds no login and from which CAT's {@code read-part} command reads the session's tool
	 * output limit, and its {@code auth.json}, the copy of the user's login that the runner puts there, which a Codex
	 * process that the session starts from a command, such as the prompt optimizer's sessions, reads. The session
	 * therefore cannot read the runner's records of its own nested session or of any other nested session, the workspace
	 * files of any nested session, any login but that copy, such as the user's own Codex and Claude logins, or the user's
	 * configuration. The method creates the session log directory, because the sandbox skips a denied path that does not
	 * exist yet; for the same reason, it leaves out a login or configuration file that does not exist.
	 * <p>
	 * A session that may request sessions is not denied the session log directory, because the commands that it
	 * runs include the prompt optimizer's {@code nestedSession}, which reads the session's rollout there to learn that
	 * the user interrupted the session's turn. A session that may request none runs no such command, so it stays denied
	 * the record of its own events. Another Codex home holds each other session's log, so the results folder's denial
	 * keeps those hidden.
	 * <p>
	 * The result names the results folder and each directory that the caller denies rather than each path under them,
	 * which also denies the nested sessions that start after the session. It omits a path inside another directory that
	 * it names, except the session log directory and the denied files inside the session's home. Every platform's sandbox
	 * still lets the session read and write its project and home inside a denied directory, such as the project that
	 * holds the results folder, but the Linux sandbox would also hide read-only evidence there. The result therefore
	 * omits each directory that {@link #hiddenDirectories(NestedSessionManager, List, List, Platform, Map)} returns,
	 * which the app-server's bubblewrap hides instead, and the paths under it. Where the caller's {@code PATH} has no
	 * bubblewrap, a Linux session is instead denied each other file and directory under a denied directory that holds any
	 * of {@code readOnlyEvidence} by its own path. The runner starts no such session with the caller's evidence, as
	 * {@link #sessionSandbox(Path, NestedSessionManager, NestedSession, List, List, Platform, Map, SessionRequestAccess,
	 * List)} describes, so this listing covers only the nested session's own link-targets and responses directories,
	 * which lie inside the results folder.
	 *
	 * @param manager the nested-session manager
	 * @param nestedSession the session's nested session
	 * @param readOnlyEvidence the completed evidence directories and files that the session reads
	 * @param deniedReads the files and directories that the caller denies the session reading
	 * @param platform the platform that the session runs on
	 * @param environment the caller's environment, which names the user's home and harness directories
	 * @return the paths to deny, each once
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if a denied directory cannot be listed or the session log directory cannot be created
	 */
	public static List<Path> sessionDeniedReads(NestedSessionManager manager, NestedSession nestedSession,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Platform platform, Map<String, String> environment)
		throws IOException
	{
		return sessionDeniedReads(manager, nestedSession, readOnlyEvidence, deniedReads, platform, environment, null);
	}

	/**
	 * Returns the files and directories that a session of a nested session must not read, as
	 * {@link #sessionDeniedReads(NestedSessionManager, NestedSession, List, List, Platform, Map)} does, except that a
	 * Linux session that is denied each path under a directory by its own path also keeps the nested session's requests
	 * directory when it may request sessions. The nested session's link-targets directory and its responses directory are
	 * among {@code readOnlyEvidence}, as {@link #sessionReadOnly(NestedSession, List, SessionRequestAccess)} returns
	 * them.
	 *
	 * @param manager the nested-session manager
	 * @param nestedSession the session's nested session
	 * @param readOnlyEvidence the completed evidence directories and files that the session reads
	 * @param deniedReads the files and directories that the caller denies the session reading
	 * @param platform the platform that the session runs on
	 * @param environment the caller's environment, which names the user's home and harness directories
	 * @param requestAccess the session's access to requested sessions, or {@code null} if it may request none
	 * @return the paths to deny, each once
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} are null
	 * @throws IOException if a denied directory cannot be listed or the session log directory cannot be created
	 */
	public static List<Path> sessionDeniedReads(NestedSessionManager manager, NestedSession nestedSession,
		List<Path> readOnlyEvidence, List<Path> deniedReads, Platform platform, Map<String, String> environment,
		SessionRequestAccess requestAccess) throws IOException
	{
		requireThat(manager, "manager").isNotNull();
		requireThat(nestedSession, "nestedSession").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		Path home = nestedSession.directory().resolve("home");
		List<Path> accessible = new ArrayList<>(readOnlyEvidence);
		accessible.add(nestedSession.directory().resolve("project"));
		accessible.add(home);
		if (requestAccess != null)
			accessible.add(requestAccess.requests());
		Set<Path> result = new LinkedHashSet<>();
		List<Path> hidden = hiddenDirectories(manager, readOnlyEvidence, deniedReads, platform, environment);
		for (Path directory : deniedDirectories(manager, deniedReads))
		{
			if (hidden.stream().anyMatch(directory::startsWith))
				continue;
			if (platform.isLinux() && NestedSessionManager.containsAny(directory, readOnlyEvidence))
				result.addAll(NestedSessionManager.deniedReads(directory, accessible));
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
		// The session's events are the same record as the nested session's artifacts, kept by Codex instead of the runner.
		// A session that may request sessions runs the prompt optimizer's commands, whose run command reads the session's
		// rollout here to learn that the user interrupted the session's turn
		Path sessionLogs = Files.createDirectories(home.resolve("sessions"));
		if (requestAccess == null || !requestAccess.allowsRequests())
			result.add(sessionLogs);
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
	 * caller denies, and the results folder, that holds read-only evidence, except one inside another. The Linux
	 * sandbox of Codex hides read-only paths inside a directory that a permission profile denies, so such a directory
	 * cannot be denied in the profile. Denying each other path under it instead made the profile of a judge that ran
	 * beside thousands of nested sessions too long for Codex to pass to its sandbox helper, which then failed to start
	 * the judge's thread.
	 *
	 * @param manager          the nested-session manager
	 * @param readOnlyEvidence the completed evidence directories and files that the session reads
	 * @param deniedReads      the files and directories that the caller denies the session reading
	 * @param platform         the platform that the session runs on
	 * @param environment      the caller's environment, whose {@code PATH} names bubblewrap
	 * @return the directories, absolute and normalized, or none if the session does not run on Linux or the
	 *   {@code PATH} has no bubblewrap
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static List<Path> hiddenDirectories(NestedSessionManager manager, List<Path> readOnlyEvidence,
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
	 * {@link #deniedDirectories(NestedSessionManager, List)} lists them, that hold any of its read-only paths.
	 *
	 * @param manager     the nested-session manager
	 * @param readOnly    the files and directories that the session may read but not change
	 * @param deniedReads the files and directories that the caller denies the session reading
	 * @return the directories, absolute and normalized, in the order of
	 *   {@link #deniedDirectories(NestedSessionManager, List)}
	 */
	private static List<Path> deniedDirectoriesHolding(NestedSessionManager manager, List<Path> readOnly,
		List<Path> deniedReads)
	{
		List<Path> holding = new ArrayList<>();
		for (Path directory : deniedDirectories(manager, deniedReads))
		{
			if (NestedSessionManager.containsAny(directory, readOnly))
				holding.add(directory);
		}
		return holding;
	}

	/**
	 * Returns the directories that a session must not read as a whole: those that the caller denies, followed by the
	 * results folder.
	 *
	 * @param manager     the nested-session manager
	 * @param deniedReads the files and directories that the caller denies the session reading
	 * @return the directories, absolute and normalized
	 */
	private static List<Path> deniedDirectories(NestedSessionManager manager, List<Path> deniedReads)
	{
		List<Path> directories = new ArrayList<>();
		for (Path denied : deniedReads)
			directories.add(denied.toAbsolutePath().normalize());
		directories.add(manager.root().toAbsolutePath().normalize());
		return directories;
	}

	/**
	 * Installs CAT, when requested, and each trial plugin into an isolated Codex home, each from a marketplace of its
	 * own. Ordinary trial plugins start without inherited hook approvals. The native binding step verifies their
	 * approvals after installation.
	 * <p>
	 * Installing CAT also writes into the isolated home the entries that CAT's installer writes for the installed
	 * plugin version: the execution-policy rules for its installer and request server, and the hash of each of its
	 * hooks in {@code config.toml}, which replaces a copied approval that the version's hooks no longer match. The
	 * installer's other entries, which cover the user's sandbox mode, shell startup files, capacity locks and the other
	 * harness's installation, are left out: the nested session's own sandbox policy covers its files, and the nested
	 * session shares neither locks nor an installation with the user's.
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
		}
		if (installCat)
		{
			CodexConnectionConfiguration.copyTrustedCatHooks(hostConfiguration, isolatedConfiguration);
			CodexCuratedPluginCache.seed(hostHome, isolatedHome, isolatedEnvironment, executables);
			// Codex resolves custom agents from CODEX_HOME/agents, not the installed plugin. Copy the definitions from
			// this nested session's artifact so delegated agents receive the same CAT contract as the primary isolated
			// process, rather than an unrelated version from the host's global Codex home.
			CodexAgents.install(artifact, isolatedHome);
			Path marketplace = CodexMarketplace.create(artifact, new CodexInstallation(isolatedHome.toAbsolutePath()));
			List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace);
			commands.execute(pluginCommands.get(0), "marketplace-add");
			commands.execute(pluginCommands.get(1), "plugin-add");
			installPermissions(isolatedHome, isolatedEnvironment, replacer);
		}
		for (int i = 0; i < trialPlugins.size(); ++i)
		{
			String name = names.get(i);
			Path marketplace = CodexMarketplace.create(trialPlugins.get(i), name, name, isolatedHome);
			List<List<String>> pluginCommands = pluginSetupCommands(executable, marketplace, name);
			// Each plugin's steps are named after it, so that the nested session's log tells the plugins apart
			commands.execute(pluginCommands.get(0), "trial-plugin-" + name + "-marketplace-add");
			commands.execute(pluginCommands.get(1), "trial-plugin-" + name + "-add");
		}
	}

	/**
	 * Transfers approvals only between exact original and trial installations observed by native Codex.
	 *
	 * @param inputs the installed trial copies with their original IDs, when known
	 * @param hostHome the host Codex home
	 * @param isolatedHome the trial Codex home
	 * @param project the source project
	 * @param worktree the trial project
	 * @param scratch the trial's writable temporary directory
	 * @param executable the native Codex executable name
	 * @param policy the authenticated parent policy
	 * @param environment the launcher environment
	 * @param scope the runner's process services
	 * @return the transferred keys and full hashes, to verify before creating a thread
	 * @throws IOException if an approval source or native observation cannot be verified
	 */
	private static Map<String, String> copyBoundTrialHooks(List<TrialPluginInput> inputs, Path hostHome,
		Path isolatedHome, Path project, Path worktree, Path scratch, String executable, ParentPolicy policy,
		Map<String, String> environment, AbstractHarnessRunnerScope scope) throws IOException
	{
		TrialHookEvidence evidence = inspectTrialHooks(inputs, hostHome, isolatedHome, project, worktree, scratch,
			executable, policy, environment, Optional.empty(), scope);
		Map<String, String> hashes = new LinkedHashMap<>();
		for (CodexPluginHooks.Binding binding : evidence.bindings())
			hashes.put(binding.trialKey(), binding.hash());
		CodexConnectionConfiguration.copyBoundHooks(configuration(isolatedHome), hashes);
		return hashes;
	}

	/**
	 * Resolves the exact native hook records that an installed trial can inherit from the owner's approved plugins.
	 *
	 * @param inputs the installed trial copies with their original IDs, when known
	 * @param hostHome the host Codex home
	 * @param isolatedHome the trial Codex home with the plugins installed
	 * @param project the original project, as opened by the owner
	 * @param worktree the copied trial project
	 * @param scratch the caller's writable temporary directory
	 * @param executable the native Codex executable name
	 * @param policy the authenticated parent policy
	 * @param environment the launcher environment
	 * @param sandboxExecutable bubblewrap selected by the caller, or empty to find it in the launcher environment
	 * @param scope services for the native process
	 * @return the exact bound pairs, with their native records
	 * @throws IOException if a protected source or native observation cannot be verified
	 */
	static TrialHookEvidence inspectTrialHooks(List<TrialPluginInput> inputs, Path hostHome,
		Path isolatedHome, Path project, Path worktree, Path scratch, String executable, ParentPolicy policy,
		Map<String, String> environment, Optional<Path> sandboxExecutable, HarnessRunnerScope scope) throws IOException
	{
		List<TrialPluginInput> installed = inputs.stream().filter(input -> input.installedId().isPresent()).toList();
		if (installed.isEmpty() || !Platform.current().isLinux())
			return TrialHookEvidence.EMPTY;
		Optional<Path> sandbox = sandboxExecutable.or(() -> bubblewrap(environment));
		if (sandbox.isEmpty())
			return TrialHookEvidence.EMPTY;
		Path configuration = configuration(hostHome);
		if (!policy.mayRead(configuration) || !Files.isRegularFile(configuration))
		{
			return TrialHookEvidence.EMPTY;
		}
		try
		{
			policy.requireTrusted(configuration);
		}
		catch (PolicyRefusal _)
		{
			// A mutable approval file provides no authority to inherit approvals.
			return TrialHookEvidence.EMPTY;
		}
		Map<String, String> approvals = CodexConnectionConfiguration.configuredHookApprovals(configuration);
		List<TrialPluginInput> eligible = installed.stream().filter(input -> approvals.keySet().stream().anyMatch(
			key -> key.startsWith(input.installedId().orElseThrow() + ":"))).toList();
		if (eligible.isEmpty())
			return TrialHookEvidence.EMPTY;
		Path nativeExecutable = Path.of(Platform.current().findProgram(executable, environment)).toRealPath();
		Path bubblewrap = sandbox.orElseThrow().toRealPath();
		CodexHookInspection.requireReadableExecutables(policy, bubblewrap, nativeExecutable);
		try
		{
			policy.requireTrusted(nativeExecutable);
			policy.requireTrusted(bubblewrap);
			Map<String, List<Path>> sourceRoots = new LinkedHashMap<>();
			for (TrialPluginInput input : eligible)
			{
				String id = input.installedId().orElseThrow();
				List<Path> roots = pluginRoots(hostHome, id);
				for (Path root : roots)
				{
					if (!policy.mayRead(root))
						throw new IOException("The owner cannot read the Codex plugin used for hook approval: " + root);
					policy.requireTrustedTree(root);
				}
				sourceRoots.put(id, roots);
			}
			Map<String, JsonNode> sourceRecords = CodexHookInspection.read(hostHome, project.toRealPath(), scratch,
				bubblewrap, nativeExecutable, policy, List.of(), scope);
			Map<String, JsonNode> trialRecords = CodexHookInspection.readTrial(isolatedHome, worktree.toRealPath(),
				scratch, bubblewrap, nativeExecutable, policy, featureFlags(true), scope);
			List<CodexPluginHooks.Binding> matched = new ArrayList<>();
			for (TrialPluginInput input : eligible)
			{
				String declared = input.installedId().orElseThrow();
				String name = PluginManifest.name(input.path(), MANIFEST);
				if (!declared.startsWith(name + "@"))
					continue;
				String trialId = name + "@" + name;
				matched.addAll(CodexPluginHooks.bind(sourceRecords, trialRecords,
					new CodexPluginHooks.Installation(declared, sourceRoots.get(declared)),
					new CodexPluginHooks.Installation(trialId, pluginRoots(isolatedHome, trialId)), approvals));
			}
			return new TrialHookEvidence(matched, sourceRecords);
		}
		catch (PolicyRefusal e)
		{
			throw new IOException("Codex could not verify a protected source for trial hook approval", e);
		}
	}

	/**
	 * Native observations and the approvals that bind their exact hook keys.
	 *
	 * @param bindings the exact original and isolated bindings
	 * @param original the native records from the original installation
	 */
	record TrialHookEvidence(List<CodexPluginHooks.Binding> bindings, Map<String, JsonNode> original)
	{
		private static final TrialHookEvidence EMPTY = new TrialHookEvidence(List.of(), Map.of());

		/**
		 * Creates one native observation.
		 *
		 * @param bindings the approved pairs
		 * @param original the original native records
		 */
		TrialHookEvidence
		{
			bindings = List.copyOf(bindings);
			original = Map.copyOf(original);
		}
	}

	/**
	 * Resolves the canonical roots that one exact native plugin registration owns.
	 *
	 * @param home the native home
	 * @param id the exact plugin and marketplace identity
	 * @return the installed cache roots
	 * @throws IOException if an installed cache root cannot be resolved
	 */
	static List<Path> pluginRoots(Path home, String id) throws IOException
	{
		List<Path> result = new ArrayList<>();
		for (Path candidate : InstalledPlugins.of(home, id))
			result.add(candidate.toRealPath());
		return result;
	}

	/**
	 * Writes the entries that CAT's installer writes for the CAT version that Codex installed into the isolated home.
	 *
	 * @param isolatedHome the isolated Codex home
	 * @param isolatedEnvironment the isolated Codex process's environment, which CAT's installer records as the harness
	 *   environment
	 * @param replacer replaces the rules file and {@code config.toml}
	 * @throws IOException if Codex did not install exactly one copy of CAT, or an entry cannot be written
	 */
	private static void installPermissions(Path isolatedHome, Map<String, String> isolatedEnvironment,
		FileReplacer replacer) throws IOException
	{
		Path home = isolatedHome.toAbsolutePath().normalize();
		new CodexIntegration().installPermissions(isolatedEnvironment, home,
			installedCat(home).resolve(PluginDirectory.INSTALLER), replacer, entries ->
			{
				// The nested session owns and removes this entire isolated configuration.
			});
	}

	/**
	 * Returns the directory of the CAT version that Codex installed into an isolated home, whose launchers the
	 * session's commands run.
	 *
	 * @param isolatedHome the isolated Codex home
	 * @return the absolute directory of the version
	 * @throws NullPointerException if {@code isolatedHome} is null
	 * @throws IOException          if the home's configuration or plugin cache cannot be read, or Codex did not install
	 *                              exactly one copy of CAT
	 */
	public static Path installedCat(Path isolatedHome) throws IOException
	{
		requireThat(isolatedHome, "isolatedHome").isNotNull();
		Path home = isolatedHome.toAbsolutePath().normalize();
		List<Path> copies = InstalledPlugins.of(home, CAT_PLUGIN_ID);
		if (copies.size() != 1)
		{
			throw new IOException("Codex did not install exactly one copy of " + CAT_PLUGIN_ID + " into " + home +
				": " + copies);
		}
		return copies.getFirst();
	}

	/**
	 * Returns the command that starts the app-server. With a plugin installed, the app-server enables hooks so the
	 * plugin's hooks run; without one, the app-server disables plugins so no installed plugin affects the nested session.
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
	static List<String> featureFlags(boolean pluginsInstalled)
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
	 * Runs the session's Codex and returns what it prints, keeping its output among the nested session's artifacts.
	 *
	 * @param executable    the Codex executable
	 * @param arguments     the arguments
	 * @param worktree      the session's project, which Codex runs in
	 * @param environment   the session's environment
	 * @param executables   runs Codex
	 * @param manager       the nested-session manager
	 * @param nestedSession the session's nested session
	 * @return the stdout
	 * @throws IOException if Codex fails or its output cannot be kept or read
	 */
	private static String commandOutput(String executable, List<String> arguments, Path worktree,
		Map<String, String> environment, ExecutableFiles executables, NestedSessionManager manager,
		NestedSession nestedSession) throws IOException
	{
		List<String> command = new ArrayList<>(List.of(executable));
		command.addAll(arguments);
		String name = ("codex-" + String.join("-", arguments)).replaceAll("[^a-z0-9]+", "-").
			replaceAll("^-|-$", "");
		HarnessCommand.execute(command, worktree, environment, executables, manager, nestedSession, name);
		return BoundedInput.readUtf8(nestedSession.directory().resolve("artifacts").resolve(name + ".stdout"),
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
		return outerSandbox(command, bubblewrap, hidden, writable, readOnly).command();
	}

	/**
	 * Builds the app-server command and records the paths that its mounts use.
	 *
	 * @param command the app-server command
	 * @param bubblewrap the bubblewrap executable
	 * @param hidden the directories to mask
	 * @param writable the directories that may stay writable
	 * @param readOnly the evidence that may stay readable
	 * @return the outer sandbox
	 * @throws IOException if the mount paths cannot be resolved
	 */
	private static OuterSandbox outerSandbox(List<String> command, Path bubblewrap, List<Path> hidden,
		List<Path> writable, List<Path> readOnly) throws IOException
	{
		requireThat(command, "command").isNotEmpty();
		requireThat(bubblewrap, "bubblewrap").isNotNull();
		requireThat(hidden, "hidden").isNotNull();
		requireThat(writable, "writable").isNotNull();
		requireThat(readOnly, "readOnly").isNotNull();
		if (hidden.isEmpty())
			return new OuterSandbox(command, List.of(), List.of());
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
		return new OuterSandbox(wrapped, hiddenReal, List.copyOf(reopened.keySet()));
	}

	/**
	 * The app-server's filesystem wrapper.
	 *
	 * @param command the app-server command with any required mounts
	 * @param masked the real destinations of its empty-directory mounts
	 * @param reopened the real destinations of its writable and read-only paths
	 */
	private record OuterSandbox(List<String> command, List<Path> masked, List<Path> reopened)
	{
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
	 * Returns the isolated setup commands that install CAT from this nested session's marketplace artifact.
	 *
	 * @param executable the Codex executable
	 * @param marketplace the session-local marketplace directory
	 * @return the ordered marketplace-add and plugin-add commands
	 */
	public static List<List<String>> pluginSetupCommands(String executable, Path marketplace)
	{
		return pluginSetupCommands(executable, marketplace, CodexMarketplace.CAT);
	}

	/**
	 * Returns the isolated setup commands that install a plugin from its own session-local marketplace, which bears the
	 * plugin's name.
	 *
	 * @param executable the Codex executable
	 * @param marketplace the session-local marketplace directory
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

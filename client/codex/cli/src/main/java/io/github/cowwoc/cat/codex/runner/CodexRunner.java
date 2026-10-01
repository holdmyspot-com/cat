package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HarnessArtifact;
import io.github.cowwoc.cat.common.runner.HarnessCommand;
import io.github.cowwoc.cat.common.runner.HarnessCredentials;
import io.github.cowwoc.cat.common.runner.HarnessDependencyCache;
import io.github.cowwoc.cat.common.runner.HarnessEnvironment;
import io.github.cowwoc.cat.common.runner.HarnessProcessStarter;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunLock;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunSession;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates an isolated Codex configuration and starts one persistent Codex CLI process, optionally with CAT.
 */
public final class CodexRunner
{
	private static final Set<String> AUTHENTICATION_VARIABLES = Set.of("CODEX_API_KEY", "OPENAI_API_KEY");

	/**
	 * Creates a Codex artifact runner.
	 */
	public CodexRunner()
	{
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
		return start(artifact, project, manager, executable, environment, model, installCat, null, List.of());
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
	 * @param installCat whether CAT is installed in the isolated home
	 * @param agent the optional direct CAT agent definition
	 * @return the live owned Codex run session
	 * @throws IOException if validation, copying, or process startup fails
	 */
	HarnessRunSession start(Path artifact, Path project, HarnessRunManager manager, String executable,
		Map<String, String> environment, String model, boolean installCat, CodexAgentDefinition agent) throws IOException
	{
		return start(artifact, project, manager, executable, environment, model, installCat, agent, List.of());
	}

	/**
	 * Starts a runner whose validator may read, but not modify, completed executor evidence.
	 *
	 * @param artifact the complete Codex artifact
	 * @param project the validator project
	 * @param manager the run manager
	 * @param executable the Codex executable
	 * @param environment the scrubbed isolated environment
	 * @param model the optional Codex model name
	 * @param installCat whether CAT is installed in the isolated home
	 * @param agent the optional direct CAT agent definition
	 * @param readOnlyEvidence the optional completed evidence directory
	 * @return the live owned Codex run session
	 * @throws IOException if validation, copying, or process startup fails
	 */
	HarnessRunSession start(Path artifact, Path project, HarnessRunManager manager, String executable,
		Map<String, String> environment, String model, boolean installCat, CodexAgentDefinition agent,
		List<Path> readOnlyEvidence) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(manager, "manager").isNotNull();
		requireThat(executable, "executable").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		requireThat(model, "model").isNotNull();
		if (agent != null && !installCat)
			throw new IOException("A direct CAT agent requires --install-cat");
		artifact = HarnessArtifact.validate(artifact, Path.of(".codex-plugin/plugin.json"));
		HarnessRun run = manager.create("codex");
		HarnessRunLock lock = null;
		try
		{
			manager.copyProject(project, run);
			Path isolatedHome = run.directory().resolve("home");
			Path temporaryDirectory = Files.createDirectories(isolatedHome.resolve("tmp"));
			Path hostHome = codexHome(environment);
			HarnessCredentials.copyAllowedFiles(hostHome, isolatedHome, Set.of("auth.json"));
			Path isolatedConfiguration = isolatedHome.resolve("config.toml");
			Path npmCache = HarnessDependencyCache.verifyNpm(environment);
			CodexConnectionConfiguration.copy(hostHome.resolve("config.toml"), isolatedConfiguration, npmCache,
				temporaryDirectory);
			if (!readOnlyEvidence.isEmpty())
				CodexConnectionConfiguration.addValidatorProfile(isolatedConfiguration, readOnlyEvidence);
			if (installCat)
			{
				// Plugin hooks are untrusted in every fresh runner home and app-server has no approval operation. Reuse
				// only the user's existing approvals for CAT's stable context hooks; Codex rejects each if its normalized
				// declaration differs, while preserving the workspace-write sandbox for the actual run.
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
			List<String> command = new ArrayList<>(List.of(executable, "app-server", "--stdio"));
			if (installCat)
				command.addAll(List.of("--enable", "hooks"));
			else
				command.addAll(List.of("--disable", "plugins"));
			manager.initialize(run, artifact, project, "codex-app-server", command);
			HarnessRunnerScope scope = new HarnessRunnerScope();
			lock = manager.acquireLock(run);
			CodexProcess process = new CodexProcess(HarnessProcessStarter.start(command, worktree,
				isolatedEnvironment), scope, installCat);
			try
			{
				manager.recordHarnessProcess(run, process.pid(), process.startedAt());
				String selectedModel = model;
				String developerInstructions = "";
				String effort = "";
				if (agent != null)
				{
					selectedModel = agent.model();
					developerInstructions = agent.developerInstructions();
					effort = agent.effort();
				}
				String permissionProfile = "";
				if (!readOnlyEvidence.isEmpty())
					permissionProfile = "cat-validator";
				process.initialize(worktree, selectedModel, developerInstructions, effort, permissionProfile);
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
	 * Returns the isolated setup commands that install CAT from this run's marketplace artifact.
	 *
	 * @param executable the Codex executable
	 * @param marketplace the run-local marketplace directory
	 * @return the ordered marketplace-add and plugin-add commands
	 */
	static List<List<String>> pluginSetupCommands(String executable, Path marketplace)
	{
		requireThat(executable, "executable").isNotBlank();
		requireThat(marketplace, "marketplace").isNotNull();
		return List.of(
			List.of(executable, "plugin", "marketplace", "add", marketplace.toString()),
			List.of(executable, "plugin", "add", "cat@cat"));
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
		String home = environment.get("HOME");
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
	static boolean requiresCredentialFiles(Map<String, String> environment)
	{
		requireThat(environment, "environment").isNotNull();
		return AUTHENTICATION_VARIABLES.stream().map(environment::get).noneMatch(value ->
			value != null && !value.isBlank());
	}
}

package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.cat.codex.CodexPluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.runner.CodexRunner;
import io.github.cowwoc.cat.codex.runner.CodexRunnerCli;
import io.github.cowwoc.cat.common.optimizer.adapter.Bridge;
import io.github.cowwoc.cat.common.optimizer.adapter.HarnessEvents;
import io.github.cowwoc.cat.common.optimizer.adapter.PluginFiles;
import io.github.cowwoc.cat.common.optimizer.adapter.PromptSources;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.codex.secrets.CodexSessionsAccess;
import io.github.cowwoc.cat.codex.secrets.CodexSessionsSecrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs nested sessions through the Codex runner.
 * <p>
 * The runner's bridge sends the request's model as {@code thread/start}'s {@code model} and its reasoning effort as
 * {@code turn/start}'s {@code effort}.
 * <p>
 * The Codex runner has no option for read-only evidence, so {@link SessionRequest#readOnly()}
 * is not passed to it. A judge reads the evidence by path, and only file permissions protect it.
 * <p>
 * Codex can shorten a tool's output before its model receives it. The runner keeps each thread's rollout, which
 * records what the thread's model received, in the {@value CodexRunner#ROLLOUTS} directory beside the transcript, and
 * the tool results that the agents received are read from there.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexSessions extends RunnerSessions
{
	static
	{
		CodexSessionsSecrets.setAccess(new CodexSessionsAccess()
		{
			@Override
			public CodexSessions newCodexSessions(RunnerSessions.Settings settings, Bridge bridge)
			{
				return new CodexSessions(settings, bridge);
			}
		});
	}

	/**
	 * The version of the environment that Codex sessions run in. Increment it in every release that changes what a
	 * session's model receives or how its actions take effect, and register the new version with the hash of the
	 * configuration and permission profile that the runner writes.
	 */
	private static final int SESSION_ENVIRONMENT_VERSION = 4;
	/**
	 * The version of the judge that Codex sessions run. Increment it in every release that changes the judge's role
	 * profile, prompt, rubric, decision rule or result schema, or the evidence section rendered for the same harness
	 * events, and register the new version with the hash of the judge profile and the rendered evidence section.
	 */
	private static final int JUDGE_VERSION = 8;

	/**
	 * Creates a port that runs sessions through the real Codex runner.
	 *
	 * @param settings how the runner is configured
	 * @throws NullPointerException if {@code settings} is null
	 */
	public CodexSessions(Settings settings)
	{
		this(settings, productionBridge(settings));
	}

	/**
	 * Creates a port.
	 *
	 * @param settings how the runner is configured
	 * @param bridge runs the runner's bridge
	 * @throws NullPointerException if any argument is null
	 */
	CodexSessions(Settings settings, Bridge bridge)
	{
		super(HARNESS_ID, settings, bridge);
	}

	@Override
	public int sessionEnvironmentVersion()
	{
		return SESSION_ENVIRONMENT_VERSION;
	}

	@Override
	public int judgeVersion()
	{
		return JUDGE_VERSION;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Codex reports the exit code of each command that it executes.
	 */
	@Override
	public boolean reportsExitStatus()
	{
		return true;
	}

	@Override
	public HostLogin hostLogin() throws IOException
	{
		return Credentials.classify(settings().configurationDirectory(), settings().environment(),
			JsonMapper.builder().build());
	}

	@Override
	public List<String> unapprovedHooks(Path plugin) throws IOException
	{
		return CodexHookApprovals.unapproved(plugin, settings().configurationDirectory(),
			JsonMapper.builder().build());
	}

	@Override
	public List<Path> installedPlugins(String name) throws IOException
	{
		return InstalledPlugins.of(settings().configurationDirectory(), name);
	}

	@Override
	protected HarnessEvents newEvents(JsonMapper json)
	{
		return new AppServerEvents(json);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The results are read from the rollouts that the runner kept beside the transcript.
	 */
	@Override
	protected SessionEvidence readReceived(SessionEvidence activity, Path transcript, JsonMapper json)
		throws IOException
	{
		requireThat(activity, "activity").isNotNull();
		requireThat(transcript, "transcript").isNotNull();
		requireThat(json, "json").isNotNull();
		List<String> received = RolloutToolResults.read(transcript.resolveSibling(CodexRunner.ROLLOUTS), json);
		return new SessionEvidence(activity.entries(), received);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Codex reads {@code AGENTS.override.md} at the project root if it exists, and {@code AGENTS.md} otherwise.
	 */
	@Override
	protected List<DerivedSource> instructionFiles(Path project) throws IOException
	{
		requireThat(project, "project").isNotNull();
		for (String name : List.of("AGENTS.override.md", "AGENTS.md"))
		{
			if (Files.isRegularFile(project.resolve(name)))
				return List.of(new DerivedSource(name, "Codex reads " + name + " at the project root in every session"));
		}
		return List.of();
	}

	@Override
	protected PromptSources newPromptSources(JsonMapper json, Map<String, Path> directories)
	{
		return new AppServerPromptSources(json, directories);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Codex registers the hooks of the project's {@code .codex/hooks.json}.
	 */
	@Override
	protected List<Path> projectHookFiles()
	{
		return List.of(Path.of(".codex/hooks.json"));
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Codex registers the hooks of each hooks file that the manifest's {@code hooks} property names and of each
	 * declaration that the property holds inline, or of {@code hooks/hooks.json} if the manifest declares no hooks.
	 */
	@Override
	protected List<HookDeclaration> pluginHooks(PluginFiles plugin, JsonMapper json) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(json, "json").isNotNull();
		List<HookDeclaration> declarations = new ArrayList<>();
		for (CodexHookApprovals.HookSource source : CodexHookApprovals.sources(plugin::resolve, json))
			declarations.add(new HookDeclaration(source.file(), source.declaration().path("hooks")));
		return declarations;
	}

	@Override
	protected List<String> readOnlyArguments(List<Path> evidence)
	{
		return List.of();
	}

	/**
	 * Creates the bridge that runs the real runner.
	 *
	 * @param settings how the runner is configured
	 * @return the bridge
	 */
	private static Bridge productionBridge(Settings settings)
	{
		requireThat(settings, "settings").isNotNull();
		return (arguments, input, output) -> CodexRunnerCli.serve(arguments.toArray(String[]::new), input, output,
			settings.environment(), _ -> HarnessCapacity.unlimited());
	}
}

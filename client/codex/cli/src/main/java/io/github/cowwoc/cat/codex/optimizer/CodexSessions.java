package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.cat.codex.CodexPluginInfo.HARNESS_ID;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.runner.CodexRunnerCli;
import io.github.cowwoc.cat.common.optimizer.adapter.Bridge;
import io.github.cowwoc.cat.common.optimizer.adapter.HarnessEvents;
import io.github.cowwoc.cat.common.optimizer.adapter.PromptSources;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.nio.file.Path;
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
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexSessions extends RunnerSessions
{
	/**
	 * The version of the environment that Codex sessions run in. Increment it in every release that changes what a
	 * session's model receives or how its actions take effect, and register the new version with the hash of the
	 * configuration and permission profile that the runner writes.
	 */
	private static final int SESSION_ENVIRONMENT_VERSION = 1;

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
	public HostLogin hostLogin() throws IOException
	{
		return Credentials.classify(settings().configurationDirectory(), settings().environment(),
			JsonMapper.builder().build());
	}

	@Override
	protected HarnessEvents newEvents(JsonMapper json)
	{
		return new AppServerEvents(json);
	}

	@Override
	protected PromptSources newPromptSources(JsonMapper json, Map<String, Path> directories)
	{
		return new AppServerPromptSources(json, directories);
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

package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.runner.ClaudeRunnerCli;
import io.github.cowwoc.cat.common.optimizer.adapter.Bridge;
import io.github.cowwoc.cat.common.optimizer.adapter.HarnessEvents;
import io.github.cowwoc.cat.common.optimizer.adapter.PromptSources;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs nested sessions through the Claude runner.
 * <p>
 * Evidence in {@link SessionRequest#readOnly()} is passed to the runner as read-only evidence. The runner exposes it
 * as an added directory, which Claude treats as advisory (design &sect;11, H23).
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ClaudeSessions extends RunnerSessions
{
	/**
	 * The harness's identifier.
	 */
	static final String HARNESS = "claude";

	/**
	 * Creates a port that runs sessions through the real Claude runner.
	 *
	 * @param settings how the runner is configured
	 * @throws NullPointerException if {@code settings} is null
	 */
	public ClaudeSessions(Settings settings)
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
	ClaudeSessions(Settings settings, Bridge bridge)
	{
		super(HARNESS, settings, bridge);
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
		return new StreamJsonEvents(json);
	}

	@Override
	protected PromptSources newPromptSources(JsonMapper json, Map<String, Path> directories)
	{
		return new StreamJsonPromptSources(json, directories);
	}

	@Override
	protected List<String> readOnlyArguments(List<Path> evidence)
	{
		List<String> arguments = new ArrayList<>();
		for (Path path : evidence)
			arguments.addAll(List.of("--read-only-evidence", path.toString()));
		return arguments;
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
		return (arguments, input, output) -> ClaudeRunnerCli.serve(arguments.toArray(String[]::new), input, output,
			settings.environment(), _ -> HarnessCapacity.unlimited());
	}
}

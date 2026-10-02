package io.github.cowwoc.cat.common.optimizer.adapter;

import io.github.cowwoc.cat.common.optimizer.port.HostLogin;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs sessions of the generic harness {@value #HARNESS}, whose events report nothing.
 */
final class TestSessions extends RunnerSessions
{
	/**
	 * The harness's identifier.
	 */
	static final String HARNESS = "harness";

	/**
	 * Creates a port.
	 *
	 * @param settings how the runner is configured
	 * @param bridge runs the runner's bridge
	 */
	TestSessions(Settings settings, Bridge bridge)
	{
		super(HARNESS, settings, bridge);
	}

	@Override
	public HostLogin hostLogin()
	{
		return HostLogin.UNKNOWN;
	}

	@Override
	protected HarnessEvents newEvents(JsonMapper json)
	{
		return new Events(json);
	}

	@Override
	protected PromptSources newPromptSources(JsonMapper json, Map<String, Path> directories)
	{
		return new Sources(json, directories);
	}

	@Override
	protected List<String> readOnlyArguments(List<Path> evidence)
	{
		return List.of();
	}

	/**
	 * Harness events that report nothing.
	 */
	static final class Events extends HarnessEvents
	{
		/**
		 * Creates a parser.
		 *
		 * @param json the JSON mapper
		 */
		Events(JsonMapper json)
		{
			super(json);
		}

		@Override
		protected void read(JsonNode event)
		{
		}
	}

	/**
	 * Harness events that report no prompt source.
	 */
	static final class Sources extends PromptSources
	{
		/**
		 * Creates an observation.
		 *
		 * @param json the JSON mapper
		 * @param directories maps each placeholder to a directory
		 */
		Sources(JsonMapper json, Map<String, Path> directories)
		{
			super(json, directories);
		}

		@Override
		protected void read(JsonNode event)
		{
		}
	}
}

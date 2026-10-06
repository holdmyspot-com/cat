package io.github.cowwoc.cat.common.optimizer.adapter;

import io.github.cowwoc.cat.common.optimizer.port.HostLogin;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs sessions of the generic harness {@value #HARNESS}, whose events report only whether the model produced output
 * and whether a hook is running.
 */
final class TestSessions extends RunnerSessions
{
	/**
	 * The harness's identifier.
	 */
	static final String HARNESS = "harness";
	/**
	 * An event that reports model output.
	 */
	static final String OUTPUT_EVENT = "{\"type\":\"output\"}";
	/**
	 * An event that reports that the hook {@code hook} started.
	 */
	static final String HOOK_STARTED_EVENT = "{\"type\":\"hook_started\",\"id\":\"hook\"}";
	/**
	 * An event that reports that the hook {@code hook} completed.
	 */
	static final String HOOK_COMPLETED_EVENT = "{\"type\":\"hook_completed\",\"id\":\"hook\"}";

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
	public int sessionEnvironmentVersion()
	{
		return 1;
	}

	@Override
	public int judgeVersion()
	{
		return 1;
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
	 * Harness events that report model output and hooks, and nothing else.
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
			String id = event.path("id").asString("");
			switch (event.path("type").asString(""))
			{
				case "output" -> setOutputProduced();
				case "hook_started" -> setHookStarted(id);
				case "hook_completed" -> setHookCompleted(id);
				default ->
				{
				}
			}
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

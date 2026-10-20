package com.holdmyspot.cat.common.optimizer.adapter.test;

import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.optimizer.adapter.HarnessEvents;
import com.holdmyspot.cat.common.optimizer.adapter.PluginFiles;
import com.holdmyspot.cat.common.optimizer.adapter.PromptSources;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs sessions of the generic harness {@value #HARNESS}, whose events report only whether the model produced output,
 * whether a hook is running, and whether the provider rejected or refused a request.
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
	 * An event that reports a rate-limit rejection without a reset time.
	 */
	static final String REJECTED_EVENT = "{\"type\":\"rejected\"}";
	/**
	 * An event that reports that the provider refused a request and the harness gave up on it.
	 */
	static final String REFUSED_EVENT = "{\"type\":\"refused\"}";
	/**
	 * The instruction file that the harness reads at the project root.
	 */
	static final String INSTRUCTIONS = "HARNESS.md";
	/**
	 * The rule that loads {@link #INSTRUCTIONS}.
	 */
	static final String INSTRUCTIONS_RULE = "The harness reads HARNESS.md at the project root in every session";

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
	public boolean reportsExitStatus()
	{
		return true;
	}

	@Override
	public HostLogin hostLogin()
	{
		return HostLogin.UNKNOWN;
	}

	@Override
	public List<String> unapprovedHooks(Path plugin)
	{
		return List.of();
	}

	@Override
	public List<Path> installedPlugins(String name)
	{
		return List.of();
	}

	@Override
	protected List<DerivedSource> instructionFiles(Path project)
	{
		if (Files.isRegularFile(project.resolve(INSTRUCTIONS)))
			return List.of(new DerivedSource(INSTRUCTIONS, INSTRUCTIONS_RULE));
		return List.of();
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

	@Override
	protected List<Path> projectHookFiles()
	{
		return List.of();
	}

	@Override
	protected Optional<List<String>> hookProcess(JsonNode hook)
	{
		return Optional.empty();
	}

	@Override
	protected String pluginRootVariable()
	{
		return "PLUGIN_ROOT";
	}

	@Override
	protected Map<String, String> harnessHome(Path home)
	{
		return Map.of();
	}

	@Override
	protected List<McpServer> projectMcpServers(Path project)
	{
		return List.of();
	}

	@Override
	protected List<HookDeclaration> pluginHooks(PluginFiles plugin, JsonMapper json)
	{
		return List.of();
	}

	/**
	 * Harness events that report model output, hooks, rejections and refusals, and nothing else.
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
				case "rejected" -> setRejection(new Rejection(null));
				case "refused" -> setRefused();
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

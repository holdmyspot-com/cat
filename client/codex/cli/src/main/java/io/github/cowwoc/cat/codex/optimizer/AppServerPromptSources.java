package io.github.cowwoc.cat.codex.optimizer;

import io.github.cowwoc.cat.common.optimizer.adapter.PromptSources;
import io.github.cowwoc.cat.codex.secrets.AppServerPromptSourcesAccess;
import io.github.cowwoc.cat.codex.secrets.AppServerPromptSourcesSecrets;

import java.nio.file.Path;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Observes the prompt sources that a Codex session's app-server notifications report.
 * <p>
 * The value lists the hooks that ran on {@code sessionStart}, with their source and source path. Hooks triggered by
 * other events contribute no entries. Codex does not report the instruction files ({@code AGENTS.md}) that it loaded,
 * so they are not part of the value.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class AppServerPromptSources extends PromptSources
{
	static
	{
		AppServerPromptSourcesSecrets.setAccess(new AppServerPromptSourcesAccess()
		{
			@Override
			public AppServerPromptSources newAppServerPromptSources(JsonMapper json, Map<String, Path> directories)
			{
				return new AppServerPromptSources(json, directories);
			}
		});
	}

	/**
	 * Creates an observation that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @param directories maps each placeholder to a directory whose staged location must not appear in the value
	 * @throws NullPointerException if any argument is null
	 */
	AppServerPromptSources(JsonMapper json, Map<String, Path> directories)
	{
		super(json, directories);
	}

	@Override
	protected void read(JsonNode event)
	{
		String method = event.path("method").asString("");
		if (!method.equals("hook/started") && !method.equals("hook/completed"))
			return;
		JsonNode run = event.path("params").path("run");
		if (!run.path("eventName").asString("").equals("sessionStart"))
			return;
		add("hook:" + run.path("eventName").asString("none") + " " + run.path("source").asString("none") + " " +
			relativize(run.path("sourcePath").asString("none")));
	}
}

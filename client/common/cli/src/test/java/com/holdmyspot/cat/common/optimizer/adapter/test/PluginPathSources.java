package com.holdmyspot.cat.common.optimizer.adapter.test;

import com.holdmyspot.cat.common.optimizer.adapter.PromptSources;

import java.nio.file.Path;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A prompt-source observation of a harness whose events name each loaded plugin and the directory that it loaded from,
 * such as {@code {"plugin":"cat","path":"/home/slot/.claude/plugins/cat"}}, and each loaded skill, such as
 * {@code {"skill":"cat:help"}}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class PluginPathSources extends PromptSources
{
	/**
	 * Creates an observation that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @param directories maps each placeholder to a directory whose staged location must not appear in the value
	 * @throws NullPointerException if any argument is null
	 */
	public PluginPathSources(JsonMapper json, Map<String, Path> directories)
	{
		super(json, directories);
	}

	@Override
	protected void read(JsonNode event)
	{
		if (event.has("plugin"))
			add("plugin:" + event.path("plugin").asString("none") + " " + relativize(event.path("path").asString("none")));
		if (event.has("skill"))
			add("skill:" + event.path("skill").asString("none"));
	}
}

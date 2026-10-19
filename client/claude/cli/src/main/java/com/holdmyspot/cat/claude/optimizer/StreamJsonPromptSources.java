package com.holdmyspot.cat.claude.optimizer;

import com.holdmyspot.cat.common.optimizer.adapter.PromptSources;
import com.holdmyspot.cat.claude.secrets.StreamJsonPromptSourcesAccess;
import com.holdmyspot.cat.claude.secrets.StreamJsonPromptSourcesSecrets;

import java.nio.file.Path;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Observes the prompt sources that a Claude session's {@code stream-json} records report.
 * <p>
 * The value lists the skills, agents and plugins that the {@code init} record names and the hooks that ran on
 * {@code SessionStart}. Hooks triggered by other events contribute no entries. Claude does not report the instruction
 * files ({@code CLAUDE.md}) that it loaded, so they are not part of the value.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class StreamJsonPromptSources extends PromptSources
{
	static
	{
		StreamJsonPromptSourcesSecrets.setAccess(new StreamJsonPromptSourcesAccess()
		{
			@Override
			public StreamJsonPromptSources newStreamJsonPromptSources(JsonMapper json, Map<String, Path> directories)
			{
				return new StreamJsonPromptSources(json, directories);
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
	StreamJsonPromptSources(JsonMapper json, Map<String, Path> directories)
	{
		super(json, directories);
	}

	@Override
	protected void read(JsonNode event)
	{
		if (!event.path("type").asString("").equals("system"))
			return;
		String subtype = event.path("subtype").asString("");
		if (subtype.equals("init"))
			readInit(event);
		else if ((subtype.equals("hook_started") || subtype.equals("hook_response")) &&
			event.path("hook_event").asString("").equals("SessionStart"))
		{
			add("hook:" + event.path("hook_event").asString("none") + " " +
				event.path("hook_name").asString("none"));
		}
	}

	/**
	 * Reads the sources that an {@code init} record lists.
	 *
	 * @param init the record
	 */
	private void readInit(JsonNode init)
	{
		for (JsonNode skill : init.path("skills"))
		{
			if (skill.isString())
				add("skill:" + skill.stringValue());
		}
		for (JsonNode agent : init.path("agents"))
		{
			if (agent.isString())
				add("agent:" + agent.stringValue());
		}
		for (JsonNode plugin : init.path("plugins"))
		{
			add("plugin:" + plugin.path("name").asString("none") + " " + plugin.path("source").asString("none") +
				" " + relativize(plugin.path("path").asString("none")));
		}
	}
}

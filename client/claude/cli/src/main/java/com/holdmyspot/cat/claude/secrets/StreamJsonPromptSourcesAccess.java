package com.holdmyspot.cat.claude.secrets;

import com.holdmyspot.cat.claude.optimizer.StreamJsonPromptSources;

import java.nio.file.Path;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes the creation of stream-json prompt-source observations to the module's tests.
 */
@FunctionalInterface
public interface StreamJsonPromptSourcesAccess
{
	/**
	 * Creates an observation that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @param directories maps each placeholder to a directory whose staged location must not appear in the value
	 * @return the observation
	 * @throws NullPointerException if any argument is null
	 */
	StreamJsonPromptSources newStreamJsonPromptSources(JsonMapper json, Map<String, Path> directories);
}

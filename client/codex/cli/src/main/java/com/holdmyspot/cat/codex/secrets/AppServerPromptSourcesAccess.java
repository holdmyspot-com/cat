package com.holdmyspot.cat.codex.secrets;

import com.holdmyspot.cat.codex.optimizer.AppServerPromptSources;

import java.nio.file.Path;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes the creation of app-server prompt-source observations to the module's tests.
 */
@FunctionalInterface
public interface AppServerPromptSourcesAccess
{
	/**
	 * Creates an observation that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @param directories maps each placeholder to a directory whose staged location must not appear in the value
	 * @return the observation
	 * @throws NullPointerException if any argument is null
	 */
	AppServerPromptSources newAppServerPromptSources(JsonMapper json, Map<String, Path> directories);
}

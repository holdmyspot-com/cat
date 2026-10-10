package io.github.cowwoc.cat.codex.secrets;

import io.github.cowwoc.cat.codex.optimizer.AppServerEvents;

import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes the creation of app-server event parsers to the module's tests.
 */
@FunctionalInterface
public interface AppServerEventsAccess
{
	/**
	 * Creates a parser that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @return the parser
	 * @throws NullPointerException if {@code json} is null
	 */
	AppServerEvents newAppServerEvents(JsonMapper json);
}

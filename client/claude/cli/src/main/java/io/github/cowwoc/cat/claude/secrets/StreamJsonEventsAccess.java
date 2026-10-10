package io.github.cowwoc.cat.claude.secrets;

import io.github.cowwoc.cat.claude.optimizer.StreamJsonEvents;

import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes the creation of stream-json event parsers to the module's tests.
 */
@FunctionalInterface
public interface StreamJsonEventsAccess
{
	/**
	 * Creates a parser that has seen no events.
	 *
	 * @param json the JSON mapper
	 * @return the parser
	 * @throws NullPointerException if {@code json} is null
	 */
	StreamJsonEvents newStreamJsonEvents(JsonMapper json);
}

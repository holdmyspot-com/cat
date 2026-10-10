package io.github.cowwoc.cat.common.git.replay;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates the mapper for topic-replay manifests, review evidence, receipts and command output.
 */
public final class ReplayJson
{
	/**
	 * Prevents instantiation.
	 */
	private ReplayJson()
	{
	}

	/**
	 * Creates a mapper that spells every key in snake_case and rejects a key that the target record does not declare.
	 *
	 * @return a new mapper
	 */
	public static JsonMapper newMapper()
	{
		return JsonMapper.builder().
			propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).
			enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).
			build();
	}
}

package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Creates the strict portable JSON mapper used by snapshots and their source inventories.
 */
public final class SnapshotJson
{
	private static final JsonMapper MAPPER = createMapper();

	/**
	 * Prevents construction.
	 */
	private SnapshotJson()
	{
	}

	/**
	 * Creates a strict portable snapshot mapper.
	 *
	 * @return configured mapper
	 */
	public static JsonMapper mapper()
	{
		return MAPPER;
	}

	/**
	 * Configures the mapper used for snapshot JSON and portable paths.
	 *
	 * @return the configured mapper
	 */
	private static JsonMapper createMapper()
	{
		SimpleModule paths = new SimpleModule();
		paths.addSerializer(Path.class, new RelativePathSerializer());
		paths.addDeserializer(Path.class, new RelativePathDeserializer());
		return JsonMapper.builder().addModule(paths).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).
			enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).
			enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).
			enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
	}

	private static final class RelativePathSerializer extends ValueSerializer<Path>
	{
		@Override
		public void serialize(Path value, JsonGenerator output, SerializationContext context)
		{
			output.writeString(value.toString().replace('\\', '/'));
		}
	}

	private static final class RelativePathDeserializer extends ValueDeserializer<Path>
	{
		@Override
		public Path deserialize(JsonParser input, DeserializationContext context)
		{
			return Path.of(input.getValueAsString());
		}
	}
}

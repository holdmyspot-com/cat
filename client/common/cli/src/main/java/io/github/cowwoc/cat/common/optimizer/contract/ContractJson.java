package io.github.cowwoc.cat.common.optimizer.contract;

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
 * Creates the canonical JSON mapper for relative contract paths.
 */
final class ContractJson
{
	private static final JsonMapper MAPPER = createMapper();

	/**
 * Prevents construction.
 */
	private ContractJson()
	{
	}

	/**
 * Returns the strict mapper shared by proposal parsing and immutable persistence.
	 *
	 * @return the configured mapper
	 */
	static JsonMapper mapper()
	{
		return MAPPER;
	}

	/**
	 * Configures the mapper used for contract JSON and relative paths.
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

	/**
 * Serializes a path without resolving it against the process directory.
 */
	private static final class RelativePathSerializer extends ValueSerializer<Path>
	{
		/**
 * Writes a path's portable relative spelling.
		 *
		 * @param value path to serialize
		 * @param output JSON destination
		 * @param context serialization context
		 * @throws JacksonException if output fails
		 */
		@Override
		public void serialize(Path value, JsonGenerator output, SerializationContext context)
		{
			output.writeString(value.toString());
		}
	}

	/**
 * Reconstructs a path without accepting an absolute URI.
 */
	private static final class RelativePathDeserializer extends ValueDeserializer<Path>
	{
		/**
 * Reads the stored relative spelling.
		 *
		 * @param input JSON source
		 * @param context deserialization context
		 * @return the relative path
		 * @throws JacksonException if input cannot be read
		 */
		@Override
		public Path deserialize(JsonParser input, DeserializationContext context)
		{
			return Path.of(input.getValueAsString());
		}
	}
}

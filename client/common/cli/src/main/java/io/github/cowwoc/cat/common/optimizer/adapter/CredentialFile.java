package io.github.cowwoc.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the shape of a harness's JSON credential file without reading a credential value (design &sect;11, H16).
 * <p>
 * The file is streamed and never held in memory as a tree.
 */
public final class CredentialFile
{
	/**
	 * Prevents construction of utility class instances.
	 */
	private CredentialFile()
	{
	}

	/**
	 * Reads the names and value types of a JSON file's top-level properties.
	 *
	 * @param file the file
	 * @param json the JSON mapper
	 * @return each property's name and the token type that starts its value, empty if the file is missing or is not a
	 *   JSON object
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the file exists but cannot be read
	 */
	public static Map<String, JsonToken> topLevelTypes(Path file, JsonMapper json) throws IOException
	{
		requireThat(file, "file").isNotNull();
		requireThat(json, "json").isNotNull();
		Map<String, JsonToken> types = new HashMap<>();
		try (InputStream input = Files.newInputStream(file);
			JsonParser parser = json.createParser(input))
		{
			if (parser.nextToken() != JsonToken.START_OBJECT)
				return Map.of();
			String name = parser.nextName();
			while (name != null)
			{
				types.put(name, parser.nextToken());
				// Skipping never decodes a value, so a string value is not materialized.
				parser.skipChildren();
				name = parser.nextName();
			}
		}
		catch (NoSuchFileException | JacksonException _)
		{
			return Map.of();
		}
		return types;
	}
}

package com.holdmyspot.cat.common.optimizer.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and atomically replaces the JSON files that campaign storage keeps.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class JsonFiles
{
	/**
	 * Prevents instantiation.
	 */
	private JsonFiles()
	{
	}

	/**
	 * Reads a file and converts its document to a value.
	 *
	 * @param <T> the type of the value
	 * @param json the JSON mapper
	 * @param file the file
	 * @param reader converts the document to the value, reporting a document that does not describe a valid value
	 *   with an {@link IllegalArgumentException} or a {@link NullPointerException}
	 * @param invalid what the error says about a file whose document the reader rejects, following the file's path
	 *   (for example, "is malformed")
	 * @return the value
	 * @throws IOException if the file cannot be read, is not valid JSON, or does not describe a valid value; the
	 *   message names the file
	 */
	static <T> T read(JsonMapper json, Path file, Function<JsonNode, T> reader, String invalid)
		throws IOException
	{
		byte[] content = Files.readAllBytes(file);
		JsonNode tree;
		try
		{
			tree = json.readTree(content);
		}
		catch (JacksonException e)
		{
			throw new IOException("The file " + file + " is not valid JSON: " + e.getOriginalMessage(), e);
		}
		try
		{
			return reader.apply(tree);
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			throw new IOException("The file " + file + " " + invalid + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Atomically replaces a file with a document (see {@link AtomicFiles#replace(Path, byte[], Consumer)}).
	 *
	 * @param json the JSON mapper
	 * @param file the file
	 * @param document the document
	 * @param afterStep receives each step after it completes
	 * @throws IOException if the file cannot be replaced
	 */
	static void replace(JsonMapper json, Path file, JsonNode document, Consumer<WriteStep> afterStep)
		throws IOException
	{
		AtomicFiles.replace(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(document), afterStep);
	}
}

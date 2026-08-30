package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Records the owner and last consumer of durable CAT work that cannot carry a marker of its own.
 */
final class WorkflowWorkOwnership
{
	static final String FILE_NAME = ".cat-work-ownership.json";
	private static final int SCHEMA_VERSION = 1;

	/**
	 * Prevents construction of this static utility.
	 */
	private WorkflowWorkOwnership()
	{
	}

	/**
	 * Reads ownership declarations for existing direct children of CAT work storage.
	 *
	 * @param workRoot the CAT work root
	 * @param json the caller-owned JSON mapper
	 * @return ownership keyed by normalized direct entry path
	 * @throws IOException if an existing declaration is malformed or unsafe
	 */
	static Map<Path, Entry> read(Path workRoot, JsonMapper json) throws IOException
	{
		Path root = workRoot.toAbsolutePath().normalize();
		Path document = root.resolve(FILE_NAME);
		if (Files.notExists(document, LinkOption.NOFOLLOW_LINKS))
			return Map.of();
		if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(document))
			throw new IOException("CAT work ownership declarations are not a regular file: " + document);
		JsonNode value = json.readTree(document.toFile());
		if (!(value instanceof ObjectNode ownerDocument))
			throw new IOException("CAT work ownership declarations are not a JSON object: " + document);
		if (ownerDocument.path("schema_version").asInt(-1) != SCHEMA_VERSION)
			throw new IOException("Unsupported CAT work ownership schema: " + document);
		if (!(ownerDocument.path("entries") instanceof ObjectNode entries))
			throw new IOException("CAT work ownership declarations have no entries object: " + document);
		Map<Path, Entry> result = new HashMap<>();
		for (String name : entries.propertyNames())
		{
			Path entry = directEntry(root, name);
			if (Files.notExists(entry, LinkOption.NOFOLLOW_LINKS))
				continue;
			JsonNode declared = entries.path(name);
			if (!(declared instanceof ObjectNode details))
				throw new IOException("CAT work ownership entry is not an object: " + name);
			result.put(entry, new Entry(text(details, "creator", name), text(details, "declared_consumer", name)));
		}
		return Map.copyOf(result);
	}

	/**
	 * Records why a direct durable CAT work entry remains after its creating invocation finishes.
	 *
	 * @param workRoot the CAT work root
	 * @param entry the direct durable entry to retain
	 * @param creator the workflow that created the entry
	 * @param consumer the last workflow purpose that still needs the entry
	 * @param json the caller-owned JSON mapper
	 * @return the resolved direct CAT work entry
	 * @throws IOException if the entry is not an existing non-symbolic direct CAT work entry or the declaration cannot
	 *                     be persisted
	 */
	static Path retain(Path workRoot, Path entry, String creator, String consumer, JsonMapper json) throws IOException
	{
		Path root = workRoot.toAbsolutePath().normalize();
		Path requested = root.resolve(entry).normalize();
		if (entry.isAbsolute())
			requested = entry.toAbsolutePath().normalize();
		Path owned = directEntry(root, root.relativize(requested).toString());
		if (!Files.exists(owned, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(owned))
			throw new IOException("CAT work entry is not an existing non-symbolic direct entry: " + owned);
		validateText(creator, "creator", owned.toString());
		validateText(consumer, "declared_consumer", owned.toString());
		Path document = root.resolve(FILE_NAME);
		Files.createDirectories(root);
		try (FileChannel channel = FileChannel.open(document.resolveSibling(FILE_NAME + ".lock"),
			StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel.lock())
		{
			if (!lock.isValid())
				throw new IOException("Cannot lock CAT work ownership declarations: " + document);
			ObjectNode ownerDocument = readDocument(document, json);
			ObjectNode entries = (ObjectNode) ownerDocument.path("entries");
			ObjectNode declaration = entries.putObject(owned.getFileName().toString());
			declaration.put("creator", creator);
			declaration.put("declared_consumer", consumer);
			write(document, ownerDocument, json);
		}
		return owned;
	}

	/**
	 * Gets the managed ownership-document filename.
	 *
	 * @return the direct CAT work filename that stores ownership declarations
	 */
	static String fileName()
	{
		return FILE_NAME;
	}

	/**
	 * Gets the managed lock filename used only while updating ownership declarations.
	 *
	 * @return the direct CAT work filename used for the ownership-document lock
	 */
	static String lockFileName()
	{
		return FILE_NAME + ".lock";
	}

	/**
	 * Reads the current ownership document, or creates its initial in-memory form.
	 *
	 * @param document the ownership-document path
	 * @param json the caller-owned JSON mapper
	 * @return the parsed or initial ownership document
	 * @throws IOException if an existing document is malformed
	 */
	private static ObjectNode readDocument(Path document, JsonMapper json) throws IOException
	{
		if (Files.notExists(document, LinkOption.NOFOLLOW_LINKS))
		{
			ObjectNode result = json.createObjectNode();
			result.put("schema_version", SCHEMA_VERSION);
			result.putObject("entries");
			return result;
		}
		JsonNode value = json.readTree(document.toFile());
		if (!(value instanceof ObjectNode result) || result.path("schema_version").asInt(-1) != SCHEMA_VERSION ||
			!(result.path("entries") instanceof ObjectNode))
		{
			throw new IOException("CAT work ownership declarations are malformed: " + document);
		}
		return result;
	}

	/**
	 * Validates and resolves one direct CAT work child.
	 *
	 * @param root the CAT work root
	 * @param value the declared relative child name
	 * @return the normalized direct child
	 * @throws IOException if the value does not name one direct child
	 */
	private static Path directEntry(Path root, String value) throws IOException
	{
		Path relative;
		try
		{
			relative = Path.of(value);
		}
		catch (RuntimeException e)
		{
			throw new IOException("CAT work ownership entry has an invalid path: " + value, e);
		}
		if (relative.isAbsolute() || relative.getNameCount() != 1 || relative.getFileName().toString().equals(FILE_NAME))
			throw new IOException("CAT work ownership entry must name one direct work child: " + value);
		Path entry = root.resolve(relative).normalize();
		if (!root.equals(entry.getParent()))
			throw new IOException("CAT work ownership entry must name one direct work child: " + value);
		return entry;
	}

	/**
	 * Reads one required one-line declaration value.
	 *
	 * @param entry one ownership declaration
	 * @param field the required field name
	 * @param name the direct work entry name used in diagnostics
	 * @return the validated declaration text
	 * @throws IOException if the required value is invalid
	 */
	private static String text(ObjectNode entry, String field, String name) throws IOException
	{
		String value = entry.path(field).asString();
		validateText(value, field, name);
		return value;
	}

	/**
	 * Validates text stored in an ownership declaration.
	 *
	 * @param value the candidate value
	 * @param field the field name used in diagnostics
	 * @param entry the declared direct entry used in diagnostics
	 * @throws IOException if the value is blank or spans multiple lines
	 */
	private static void validateText(String value, String field, String entry) throws IOException
	{
		if (value == null || value.isBlank() || value.contains("\n") || value.contains("\r"))
			throw new IOException("CAT work ownership entry has invalid " + field + ": " + entry);
	}

	/**
	 * Atomically replaces the ownership document after its complete next state is available.
	 *
	 * @param destination the ownership-document path
	 * @param contents the complete next document
	 * @param json the caller-owned JSON mapper
	 * @throws IOException if the document cannot be replaced
	 */
	private static void write(Path destination, ObjectNode contents, JsonMapper json) throws IOException
	{
		Path temporary = Files.createTempFile(destination.getParent(), ".cat-work-ownership-", ".json");
		try
		{
			json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), contents);
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Names one durable entry's creator and last consumer.
	 *
	 * @param creator the workflow that created the entry
	 * @param consumer the workflow purpose that still needs the entry
	 */
	record Entry(String creator, String consumer)
	{
		/**
		 * Validates one retained-work ownership declaration.
		 *
		 * @param creator the workflow that created the entry
		 * @param consumer the workflow purpose that still needs the entry
		 */
		Entry
		{
			requireThat(creator, "creator").isNotBlank();
			requireThat(consumer, "consumer").isNotBlank();
		}
	}
}

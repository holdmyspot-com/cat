package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Delete;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Write;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Map.Entry;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts a candidate's {@link Overlay} to and from a JSON tree.
 * <p>
 * The document holds {@code format_version}, the identity of the fork that the overlay is on, and the overlay's
 * entries in path order. It does not hold the fork's files: campaign storage already records them once, and reading
 * supplies them. Each entry holds its path and kind ({@code WRITE} or
 * {@code DELETE}); a write also holds its mode in {@code rwxr-xr-x} form and its bytes in Base64.
 * <p>
 * Reading reports a document that does not describe an overlay on the supplied fork with an
 * {@link IllegalArgumentException} or a {@link NullPointerException}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class OverlayJson
{
	/**
	 * The version of the layout that this class writes and reads. It holds no category or cell, so it did not change
	 * when campaign files became keyed by cell.
	 */
	static final int FORMAT_VERSION = 1;
	private static final String VERSION = "format_version";
	private static final Set<String> ROOT_FIELDS = Set.of(VERSION, "fork", "entries");
	private static final Set<String> WRITE_FIELDS = Set.of("path", "kind", "permissions", "content");
	private static final Set<String> DELETE_FIELDS = Set.of("path", "kind");
	private final JsonMapper json;

	/**
	 * Creates a converter.
	 *
	 * @param json the JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	OverlayJson(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
	}

	/**
	 * Converts an overlay to a document.
	 *
	 * @param overlay the overlay
	 * @return the document
	 */
	ObjectNode write(Overlay overlay)
	{
		ObjectNode root = json.createObjectNode();
		root.put(VERSION, FORMAT_VERSION);
		root.put("fork", overlay.fork().identity().hex());
		ArrayNode entries = root.putArray("entries");
		for (Entry<Path, OverlayEntry> entry : overlay.entries().entrySet())
		{
			ObjectNode node = entries.addObject();
			node.put("path", entry.getKey().toString());
			switch (entry.getValue())
			{
				case Write write ->
				{
					node.put("kind", "WRITE");
					node.put("permissions", PosixFilePermissions.toString(write.permissions()));
					node.put("content", Base64.getEncoder().encodeToString(write.content().bytes()));
				}
				case Delete _ -> node.put("kind", "DELETE");
			}
		}
		return root;
	}

	/**
	 * Converts a document to an overlay.
	 *
	 * @param root the document
	 * @param fork the files recorded at the campaign's fork
	 * @return the overlay
	 * @throws IllegalArgumentException if the document has a different format version, is on a different fork, or
	 *   does not describe a valid overlay
	 * @throws NullPointerException if a required value is missing
	 */
	Overlay read(JsonNode root, FileInventory fork)
	{
		requireFields(root, "$", ROOT_FIELDS);
		JsonNode version = field(root, VERSION);
		if (!version.isInt() || version.asInt() != FORMAT_VERSION)
		{
			throw new IllegalArgumentException(VERSION + " is " + version + ", but this version of CAT reads " +
				FORMAT_VERSION);
		}
		String forkIdentity = text(root, "fork");
		if (!forkIdentity.equals(fork.identity().hex()))
		{
			throw new IllegalArgumentException("fork: the overlay is on fork " + forkIdentity + ", but the campaign's " +
				"fork is " + fork.identity().hex());
		}
		JsonNode entries = field(root, "entries");
		if (!entries.isArray())
			throw new IllegalArgumentException("entries: must be an array");

		Overlay overlay = Overlay.of(fork);
		int index = 0;
		for (JsonNode entry : entries)
		{
			overlay = apply(overlay, entry, "entries[" + index + "]");
			++index;
		}
		return overlay;
	}

	/**
	 * Applies one entry document to an overlay.
	 *
	 * @param overlay the overlay
	 * @param node the entry's document
	 * @param name the entry's name in diagnostics
	 * @return the resulting overlay
	 */
	private static Overlay apply(Overlay overlay, JsonNode node, String name)
	{
		if (node == null || !node.isObject())
			throw new IllegalArgumentException(name + ": must be an object");
		Path path = Path.of(text(node, "path"));
		String kind = text(node, "kind");
		return switch (kind)
		{
			case "WRITE" ->
			{
				requireFields(node, name, WRITE_FIELDS);
				FileContent content = new FileContent(Base64.getDecoder().decode(text(node, "content")));
				yield overlay.write(path, content, PosixFilePermissions.fromString(text(node, "permissions")));
			}
			case "DELETE" ->
			{
				requireFields(node, name, DELETE_FIELDS);
				yield overlay.delete(path);
			}
			default -> throw new IllegalArgumentException(name + ".kind: must be WRITE or DELETE, but was " + kind);
		};
	}

	/**
	 * Checks that a value is an object that holds exactly the expected fields.
	 *
	 * @param node the value
	 * @param name the value's name
	 * @param expected the names of the fields
	 * @throws IllegalArgumentException if the value is not an object, or its field names differ from
	 *   {@code expected}
	 */
	private static void requireFields(JsonNode node, String name, Set<String> expected)
	{
		if (node == null || !node.isObject())
			throw new IllegalArgumentException(name + ": must be an object");
		Set<String> actual = Set.copyOf(node.propertyNames());
		if (!actual.equals(expected))
		{
			throw new IllegalArgumentException(name + ": must hold the fields " + expected + ", but holds " +
				actual);
		}
	}

	/**
	 * Returns a field that may not be missing or null.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value
	 * @throws IllegalArgumentException if the field is missing or null
	 */
	private static JsonNode field(JsonNode node, String name)
	{
		JsonNode value = node.get(name);
		if (value == null || value.isNull())
			throw new IllegalArgumentException(name + ": must have a value");
		return value;
	}

	/**
	 * Returns a string field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the string
	 * @throws IllegalArgumentException if the field is not a string
	 */
	private static String text(JsonNode node, String name)
	{
		JsonNode value = field(node, name);
		if (!value.isString())
			throw new IllegalArgumentException(name + ": must be a string");
		return value.asString();
	}
}

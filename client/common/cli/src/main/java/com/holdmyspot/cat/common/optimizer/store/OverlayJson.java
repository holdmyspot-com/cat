package com.holdmyspot.cat.common.optimizer.store;

import static com.holdmyspot.cat.common.optimizer.store.JsonFields.VERSION;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.elements;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireObject;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireVersion;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.text;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.inventory.OverlayEntry;
import com.holdmyspot.cat.common.optimizer.inventory.OverlayEntry.Delete;
import com.holdmyspot.cat.common.optimizer.inventory.OverlayEntry.Write;

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
 * supplies them. Each entry holds its path and kind ({@code write} or
 * {@code delete}); a write also holds its mode in {@code rwxr-xr-x} form and its bytes in Base64.
 * <p>
 * Reading reports a document that does not describe an overlay on the supplied fork with an
 * {@link IllegalArgumentException} or a {@link NullPointerException}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class OverlayJson
{
	/**
	 * The version of the layout that this class writes and reads. It holds no test or cell, so it did not change
	 * when campaign files became keyed by cell.
	 */
	private static final int FORMAT_VERSION = 1;
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
			node.put("path", PortablePaths.text(entry.getKey()));
			switch (entry.getValue())
			{
				case Write write ->
				{
					node.put("kind", "write");
					node.put("permissions", PosixFilePermissions.toString(write.permissions()));
					node.put("content", Base64.getEncoder().encodeToString(write.content().bytes()));
				}
				case Delete _ -> node.put("kind", "delete");
			}
		}
		return root;
	}

	/**
	 * Returns the identity of the fork that a document's overlay is on.
	 *
	 * @param root the document
	 * @return the fork's identity
	 * @throws IllegalArgumentException if the document has a different format version or names no valid identity
	 * @throws NullPointerException if a required value is missing
	 */
	Sha256 fork(JsonNode root)
	{
		requireFields(root, "$", ROOT_FIELDS);
		requireVersion(root, FORMAT_VERSION);
		return new Sha256(text(root, "fork"));
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
		requireVersion(root, FORMAT_VERSION);
		String forkIdentity = text(root, "fork");
		if (!forkIdentity.equals(fork.identity().hex()))
		{
			throw new IllegalArgumentException("fork: the overlay is on fork " + forkIdentity + ", but the campaign's " +
				"fork is " + fork.identity().hex());
		}
		Overlay overlay = Overlay.of(fork);
		int index = 0;
		for (JsonNode entry : elements(root, "entries"))
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
		requireObject(node, name);
		Path path = Path.of(text(node, "path"));
		String kind = text(node, "kind");
		return switch (kind)
		{
			case "write" ->
			{
				requireFields(node, name, WRITE_FIELDS);
				FileContent content = new FileContent(Base64.getDecoder().decode(text(node, "content")));
				yield overlay.restore(path, new Write(content, PosixFilePermissions.fromString(text(node, "permissions"))));
			}
			case "delete" ->
			{
				requireFields(node, name, DELETE_FIELDS);
				yield overlay.restore(path, new Delete());
			}
			default -> throw new IllegalArgumentException(name + ".kind: must be write or delete, but was " + kind);
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
		requireObject(node, name);
		Set<String> actual = Set.copyOf(node.propertyNames());
		if (!actual.equals(expected))
		{
			throw new IllegalArgumentException(name + ": must hold the fields " + expected + ", but holds " +
				actual);
		}
	}
}

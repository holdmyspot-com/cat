package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Create;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Delete;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange.Edit;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts a {@link DeliveryJournal} to and from a JSON tree.
 * <p>
 * The document starts with {@code format_version}, the same version that {@link CampaignJson} writes. Keys are
 * snake_case, kinds are written by name, modes in {@code rwxr-xr-x} form, and file bytes in Base64.
 * <p>
 * Reading reports a document that does not describe a valid journal with an {@link IllegalArgumentException} or a
 * {@link NullPointerException}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class DeliveryJournalJson
{
	private static final String VERSION = "format_version";
	private final JsonMapper json;

	/**
	 * Creates a converter.
	 *
	 * @param json the JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	DeliveryJournalJson(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
	}

	/**
	 * Converts a journal to a document.
	 *
	 * @param journal the journal
	 * @return the document
	 */
	ObjectNode write(DeliveryJournal journal)
	{
		ObjectNode root = json.createObjectNode();
		root.put(VERSION, CampaignJson.FORMAT_VERSION);
		root.put("candidate", journal.candidate().hex());
		root.put("applied", journal.applied());
		ArrayNode changes = root.putArray("changes");
		for (DeliveryChange change : journal.changes())
			changes.add(writeChange(change));
		return root;
	}

	/**
	 * Converts one change to a document. The change's sequence is its position in the journal's list.
	 *
	 * @param change the change
	 * @return the document
	 */
	private ObjectNode writeChange(DeliveryChange change)
	{
		ObjectNode node = json.createObjectNode();
		node.put("target", change.target().toString());
		switch (change)
		{
			case Create create ->
			{
				node.put("kind", "CREATE");
				putContent(node, create.content(), create.permissions());
			}
			case Edit edit ->
			{
				node.put("kind", "EDIT");
				node.set("before", writeFile(edit.before()));
				putContent(node, edit.content(), edit.permissions());
			}
			case Delete delete ->
			{
				node.put("kind", "DELETE");
				node.set("before", writeFile(delete.before()));
			}
		}
		return node;
	}

	/**
	 * Adds the bytes and mode that a change writes to a document.
	 *
	 * @param node the change's document
	 * @param content the file's new bytes
	 * @param permissions the file's new mode
	 */
	private static void putContent(ObjectNode node, FileContent content, Set<PosixFilePermission> permissions)
	{
		node.put("content", Base64.getEncoder().encodeToString(content.bytes()));
		node.put("permissions", PosixFilePermissions.toString(permissions));
	}

	/**
	 * Converts a regular-file state to a document.
	 *
	 * @param file the file's state
	 * @return the document
	 */
	private ObjectNode writeFile(RegularFile file)
	{
		ObjectNode node = json.createObjectNode();
		node.put("content_hash", file.contentHash().hex());
		node.put("permissions", PosixFilePermissions.toString(file.permissions()));
		return node;
	}

	/**
	 * Converts a document to a journal.
	 *
	 * @param root the document
	 * @return the journal
	 * @throws IllegalArgumentException if the document has a different format version or does not describe a valid
	 *   journal
	 * @throws NullPointerException if a required value is missing
	 */
	DeliveryJournal read(JsonNode root)
	{
		requireObject(root, "$");
		int version = integer(root, VERSION);
		if (version != CampaignJson.FORMAT_VERSION)
		{
			throw new IllegalArgumentException(VERSION + " is " + version + ", but this version of CAT reads " +
				CampaignJson.FORMAT_VERSION);
		}
		JsonNode changesNode = field(root, "changes");
		if (!changesNode.isArray())
			throw new IllegalArgumentException("changes: must be an array");
		List<DeliveryChange> changes = new ArrayList<>();
		for (JsonNode change : changesNode)
			changes.add(readChange(change, changes.size()));
		return new DeliveryJournal(new Sha256(text(root, "candidate")), changes, integer(root, "applied"));
	}

	/**
	 * Converts a document to one change.
	 *
	 * @param node the document
	 * @param sequence the change's position in the journal's list
	 * @return the change
	 */
	private static DeliveryChange readChange(JsonNode node, int sequence)
	{
		requireObject(node, "changes[" + sequence + "]");
		Path target = Path.of(text(node, "target"));
		String kind = text(node, "kind");
		return switch (kind)
		{
			case "CREATE" -> new Create(sequence, target, content(node), permissions(node, "permissions"));
			case "EDIT" -> new Edit(sequence, target, readFile(field(node, "before")), content(node),
				permissions(node, "permissions"));
			case "DELETE" -> new Delete(sequence, target, readFile(field(node, "before")));
			default -> throw new IllegalArgumentException("kind: must be CREATE, EDIT or DELETE, but was " + kind);
		};
	}

	/**
	 * Converts a document to a regular-file state.
	 *
	 * @param node the document
	 * @return the file's state
	 */
	private static RegularFile readFile(JsonNode node)
	{
		requireObject(node, "before");
		return new RegularFile(new Sha256(text(node, "content_hash")), permissions(node, "permissions"));
	}

	/**
	 * Returns the bytes that a change writes.
	 *
	 * @param node the change's document
	 * @return the content
	 * @throws IllegalArgumentException if the field is not Base64 text
	 */
	private static FileContent content(JsonNode node)
	{
		return new FileContent(Base64.getDecoder().decode(text(node, "content")));
	}

	/**
	 * Returns a mode field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the permissions
	 * @throws IllegalArgumentException if the field is not in {@code rwxr-xr-x} form
	 */
	private static Set<PosixFilePermission> permissions(JsonNode node, String name)
	{
		return PosixFilePermissions.fromString(text(node, name));
	}

	/**
	 * Checks that a value is an object.
	 *
	 * @param node the value
	 * @param name the value's name
	 * @throws IllegalArgumentException if the value is not an object
	 */
	private static void requireObject(JsonNode node, String name)
	{
		if (node == null || !node.isObject())
			throw new IllegalArgumentException(name + ": must be an object");
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

	/**
	 * Returns an {@code int} field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value
	 * @throws IllegalArgumentException if the field is not an integer in the range of {@code int}
	 */
	private static int integer(JsonNode node, String name)
	{
		JsonNode value = field(node, name);
		if (!value.isIntegralNumber() || !value.canConvertToInt())
			throw new IllegalArgumentException(name + ": must be an integer in the range of int");
		return value.asInt();
	}
}

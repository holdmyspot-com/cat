package com.holdmyspot.cat.common.optimizer.store;

import static com.holdmyspot.cat.common.optimizer.store.JsonFields.VERSION;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.elements;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.field;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.integer;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireObject;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireVersion;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.text;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.inventory.DeliveryChange;
import com.holdmyspot.cat.common.optimizer.inventory.DeliveryChange.Create;
import com.holdmyspot.cat.common.optimizer.inventory.DeliveryChange.Delete;
import com.holdmyspot.cat.common.optimizer.inventory.DeliveryChange.Edit;
import com.holdmyspot.cat.common.optimizer.inventory.DeliveryChange.ReplaceLinkWithDirectory;
import com.holdmyspot.cat.common.optimizer.inventory.DeliveryChange.WriteLink;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.SpecialFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts a {@link DeliveryJournal} to and from a JSON tree.
 * <p>
 * The document starts with {@code format_version}. Keys are snake_case, kinds are written by name, modes in
 * {@code rwxr-xr-x} form, file bytes in Base64, and link targets with {@code /} separators. A {@code before} state is
 * a regular file's {@code content_hash} and {@code permissions}, or a symbolic link's {@code link_target}.
 * <p>
 * Reading reports a document that does not describe a valid journal with an {@link IllegalArgumentException} or a
 * {@link NullPointerException}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class DeliveryJournalJson
{
	/**
	 * The version of the layout that this class writes and reads. It holds no test or cell, so it did not change
	 * when campaign files became keyed by cell.
	 */
	private static final int FORMAT_VERSION = 1;
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
		root.put(VERSION, FORMAT_VERSION);
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
		node.put("target", PortablePaths.text(change.target()));
		switch (change)
		{
			case Create create ->
			{
				node.put("kind", "create");
				putContent(node, create.content(), create.permissions());
			}
			case Edit edit ->
			{
				node.put("kind", "edit");
				node.set("before", writeFile(edit.before()));
				putContent(node, edit.content(), edit.permissions());
			}
			case Delete delete ->
			{
				node.put("kind", "delete");
				node.set("before", writeFile(delete.before()));
			}
			case WriteLink link ->
			{
				node.put("kind", "write_link");
				link.before().ifPresent(before -> node.set("before", writeFile(before)));
				node.put("link_target", PortablePaths.text(link.linkTarget()));
			}
			case ReplaceLinkWithDirectory directory ->
			{
				node.put("kind", "replace_link_with_directory");
				node.set("before", writeFile(directory.before()));
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
	 * Converts the state of a regular file or symbolic link to a document.
	 *
	 * @param file the state
	 * @return the document
	 * @throws IllegalArgumentException if {@code file} is a special file
	 */
	private ObjectNode writeFile(FileEntry file)
	{
		ObjectNode node = json.createObjectNode();
		switch (file)
		{
			case RegularFile regular ->
			{
				node.put("content_hash", regular.contentHash().hex());
				node.put("permissions", PosixFilePermissions.toString(regular.permissions()));
			}
			case SymbolicLink link -> node.put("link_target", PortablePaths.text(link.target()));
			case SpecialFile _ -> throw new IllegalArgumentException("A change's before state is a special " +
				"file");
		}
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
		requireVersion(root, FORMAT_VERSION);
		List<DeliveryChange> changes = new ArrayList<>();
		for (JsonNode change : elements(root, "changes"))
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
			case "create" -> new Create(sequence, target, content(node), permissions(node, "permissions"));
			case "edit" -> new Edit(sequence, target, readEntry(field(node, "before")), content(node),
				permissions(node, "permissions"));
			case "delete" -> new Delete(sequence, target, readFile(field(node, "before")));
			case "write_link" ->
			{
				Optional<FileEntry> before = Optional.empty();
				if (node.has("before"))
					before = Optional.of(readEntry(field(node, "before")));
				yield new WriteLink(sequence, target, before, Path.of(text(node, "link_target")));
			}
			case "replace_link_with_directory" -> new ReplaceLinkWithDirectory(sequence, target,
				readLink(field(node, "before")));
			default -> throw new IllegalArgumentException("kind: must be create, edit, delete, write_link or " +
				"replace_link_with_directory, but was " + kind);
		};
	}

	/**
	 * Converts a document to the state of a regular file or symbolic link.
	 *
	 * @param node the document
	 * @return the state
	 */
	private static FileEntry readEntry(JsonNode node)
	{
		requireObject(node, "before");
		if (node.has("link_target"))
			return new SymbolicLink(Path.of(text(node, "link_target")));
		return readFile(node);
	}

	/**
	 * Converts a document to a symbolic-link state.
	 *
	 * @param node the document
	 * @return the link's state
	 */
	private static SymbolicLink readLink(JsonNode node)
	{
		requireObject(node, "before");
		return new SymbolicLink(Path.of(text(node, "link_target")));
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
}

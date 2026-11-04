package com.holdmyspot.cat.common.updater.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes the activation record that recovery validates before restoring an installation. Absolute destinations belong
 * to a protected user installation and remain independent of the project that requested the update.
 */
final class ActivationJournal
{
	/**
	 * Journals are bounded because recovery needs the entire ordered transaction before making changes.
	 */
	static final int MAXIMUM_BYTES = 16 * 1024 * 1024;
	/**
	 * Rejects duplicate members and trailing documents before the journal's shape is validated.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
		enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
	/**
	 * Members of the one supported journal format.
	 */
	private static final Set<String> ROOT_FIELDS = Set.of("format_version", "previous_version", "new_version",
		"migration_file", "backup_root", "migration_existed", "prepared", "rollback", "entries");
	/**
	 * Format 2 adds the operation, so that an activation into a harness without a plugin, or one that replaces the
	 * installed release with the same release, is recorded as such instead of naming a previous version to restore.
	 * Format 1 journals are always updates.
	 */
	private static final Set<String> ROOT_FIELDS_2 = Set.of("format_version", "operation", "previous_version",
		"new_version", "migration_file", "backup_root", "migration_existed", "prepared", "rollback", "entries");
	/**
	 * Members that describe one activation step and its reverse progress.
	 */
	private static final Set<String> ENTRY_FIELDS = Set.of("kind", "live", "staging", "previous", "older",
		"discard", "state", "undo", "had_previous", "files");
	/**
	 * Members that describe one old file and the new file staged for it.
	 */
	private static final Set<String> FILE_FIELDS = Set.of("live", "backup", "staging", "existed");

	/**
	 * Prevents instantiation.
	 */
	private ActivationJournal()
	{
	}

	/**
	 * Reads a bounded journal and validates its complete shape; destination authority is checked by the transaction.
	 *
	 * @param file the protected journal
	 * @return its typed contents
	 * @throws IOException if the journal is unreadable, oversized, or malformed
	 */
	static Document read(Path file) throws IOException
	{
		byte[] bytes;
		try (InputStream input = Files.newInputStream(file))
		{
			bytes = input.readNBytes(MAXIMUM_BYTES + 1);
		}
		if (bytes.length > MAXIMUM_BYTES)
			throw new IOException("The activation journal exceeds " + MAXIMUM_BYTES + " bytes: " + file);
		try
		{
			JsonNode root = MAPPER.readTree(bytes);
			if (root == null || !root.isObject())
				throw new IOException("The activation journal is not a JSON object");
			JsonNode format = root.get("format_version");
			if (format == null || !format.isIntegralNumber() || !format.canConvertToInt() ||
				(format.intValue() != 1 && format.intValue() != 2))
			{
				throw new IOException("The activation journal must have format_version 1 or 2");
			}
			Operation operation = Operation.UPDATE;
			if (format.intValue() == 1)
				requireObject(root, ROOT_FIELDS);
			else
			{
				requireObject(root, ROOT_FIELDS_2);
				operation = parseOperation(text(root, "operation"));
			}
			String previousVersion = "";
			if (operation == Operation.UPDATE)
				previousVersion = text(root, "previous_version");
			else if (!root.get("previous_version").isString() || !root.get("previous_version").stringValue().isEmpty())
				throw new IOException("An install or repair activation journal must have an empty previous_version");
			List<Entry> entries = new ArrayList<>();
			for (JsonNode entry : array(root, "entries"))
			{
				requireObject(entry, ENTRY_FIELDS);
				List<FileChange> files = new ArrayList<>();
				for (JsonNode item : array(entry, "files"))
				{
					requireObject(item, FILE_FIELDS);
					files.add(new FileChange(path(item, "live"), path(item, "backup"), path(item, "staging"),
						flag(item, "existed")));
				}
				entries.add(new Entry(parseKind(text(entry, "kind")), path(entry, "live"),
					path(entry, "staging"), path(entry, "previous"), path(entry, "older"), path(entry, "discard"),
					parseState(text(entry, "state")), parseUndo(text(entry, "undo")),
					flag(entry, "had_previous"), files));
			}
			return new Document(operation, previousVersion, text(root, "new_version"),
				path(root, "migration_file"), path(root, "backup_root"), flag(root, "migration_existed"),
				flag(root, "prepared"), flag(root, "rollback"), entries);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("The activation journal is malformed: " + file, e);
		}
	}

	/**
	 * Encodes the complete journal, refusing a record too large to recover.
	 *
	 * @param document the current activation state
	 * @return the journal's UTF-8 bytes
	 * @throws IOException if the record exceeds the recovery bound
	 */
	static byte[] encode(Document document) throws IOException
	{
		ObjectNode root = MAPPER.createObjectNode();
		root.put("format_version", 2);
		root.put("operation", document.operation().name().toLowerCase(Locale.ROOT));
		root.put("previous_version", document.previousVersion());
		root.put("new_version", document.newVersion());
		root.put("migration_file", document.migrationFile().toString());
		root.put("backup_root", document.backupRoot().toString());
		root.put("migration_existed", document.migrationExisted());
		root.put("prepared", document.prepared());
		// The member keeps the name that journals of earlier builds use, so that recovery finishes their undo too
		root.put("rollback", document.undoesLast());
		ArrayNode entries = root.putArray("entries");
		for (Entry entry : document.entries())
		{
			ObjectNode item = entries.addObject();
			item.put("kind", entry.kind().name().toLowerCase(Locale.ROOT));
			item.put("live", entry.live().toString());
			item.put("staging", entry.staging().toString());
			item.put("previous", entry.previous().toString());
			item.put("older", entry.older().toString());
			item.put("discard", entry.discard().toString());
			item.put("state", entry.state().name().toLowerCase(Locale.ROOT));
			item.put("undo", entry.undo().name().toLowerCase(Locale.ROOT));
			item.put("had_previous", entry.hadPrevious());
			ArrayNode files = item.putArray("files");
			for (FileChange file : entry.files())
			{
				ObjectNode change = files.addObject();
				change.put("live", file.live().toString());
				change.put("backup", file.backup().toString());
				change.put("staging", file.staging().toString());
				change.put("existed", file.existed());
			}
		}
		byte[] bytes = MAPPER.writeValueAsBytes(root);
		if (bytes.length > MAXIMUM_BYTES)
			throw new IOException("The activation journal exceeds " + MAXIMUM_BYTES + " bytes");
		return bytes;
	}

	/**
	 * Validates an object's exact required members.
	 *
	 * @param node   the object to validate
	 * @param fields the members of its schema
	 * @throws IOException if the object lacks a member or has an unknown member
	 */
	private static void requireObject(JsonNode node, Set<String> fields) throws IOException
	{
		if (node == null || !node.isObject() || !node.propertyNames().equals(fields))
			throw new IOException("The activation journal has missing or unknown members");
	}

	/**
	 * Reads a required array.
	 *
	 * @param node the containing object
	 * @param name the member name
	 * @return the array
	 * @throws IOException if the member is not an array
	 */
	private static JsonNode array(JsonNode node, String name) throws IOException
	{
		JsonNode value = node.get(name);
		if (value == null || !value.isArray())
			throw new IOException("The activation journal member is not an array: " + name);
		return value;
	}

	/**
	 * Reads a required text member.
	 *
	 * @param node the containing object
	 * @param name the member name
	 * @return nonblank text
	 * @throws IOException if the member is absent, blank, or not text
	 */
	private static String text(JsonNode node, String name) throws IOException
	{
		JsonNode value = node.get(name);
		if (value == null || !value.isString() || value.stringValue().isBlank())
			throw new IOException("The activation journal member is not nonblank text: " + name);
		return value.stringValue();
	}

	/**
	 * Reads an absolute normalized destination.
	 *
	 * @param node the containing object
	 * @param name the member name
	 * @return the path
	 * @throws IOException if the member is not a normalized absolute path
	 */
	private static Path path(JsonNode node, String name) throws IOException
	{
		Path value = Path.of(text(node, name));
		if (!value.isAbsolute() || !value.normalize().equals(value) || value.getParent() == null)
			throw new IOException("The activation journal path is not absolute and normalized: " + value);
		return value;
	}

	/**
	 * Reads a required boolean member.
	 *
	 * @param node the containing object
	 * @param name the member name
	 * @return the boolean
	 * @throws IOException if the member is absent or is not boolean
	 */
	private static boolean flag(JsonNode node, String name) throws IOException
	{
		JsonNode value = node.get(name);
		if (value == null || !value.isBoolean())
			throw new IOException("The activation journal member is not boolean: " + name);
		return value.booleanValue();
	}

	/**
	 * Parses a journal operation.
	 *
	 * @param value the encoded operation
	 * @return the operation
	 * @throws IOException if the operation is unknown
	 */
	private static Operation parseOperation(String value) throws IOException
	{
		return switch (value)
		{
			case "update" -> Operation.UPDATE;
			case "install" -> Operation.INSTALL;
			case "repair" -> Operation.REPAIR;
			default -> throw new IOException("The activation journal has an unknown operation: " + value);
		};
	}

	/**
	 * Reads the supported provider step kind.
	 *
	 * @param value the journal value
	 * @return the kind
	 * @throws IOException if the kind is unknown
	 */
	private static Kind parseKind(String value) throws IOException
	{
		return switch (value)
		{
			case "replace_directory" -> Kind.REPLACE_DIRECTORY;
			case "add_directory" -> Kind.ADD_DIRECTORY;
			case "replace_owned_files" -> Kind.REPLACE_OWNED_FILES;
			default -> throw new IOException("The activation journal has an unknown step kind: " + value);
		};
	}

	/**
	 * Reads forward progress.
	 *
	 * @param value the journal value
	 * @return the progress
	 * @throws IOException if the state is unknown
	 */
	private static StepState parseState(String value) throws IOException
	{
		return switch (value)
		{
			case "pending" -> StepState.PENDING;
			case "staged" -> StepState.STAGED;
			case "swapped" -> StepState.SWAPPED;
			case "done" -> StepState.DONE;
			default -> throw new IOException("The activation journal has an unknown step state: " + value);
		};
	}

	/**
	 * Reads reverse progress.
	 *
	 * @param value the journal value
	 * @return the progress
	 * @throws IOException if the state is unknown
	 */
	private static Undo parseUndo(String value) throws IOException
	{
		return switch (value)
		{
			case "live" -> Undo.LIVE;
			case "previous" -> Undo.PREVIOUS;
			case "done" -> Undo.DONE;
			default -> throw new IOException("The activation journal has an unknown undo state: " + value);
		};
	}

	/**
	 * One durable transaction, including its migration marker backup.
	 *
	 * @param operation        whether the activation replaced an installation, added one where none existed or
	 *                         replaced the installed release with the same release
	 * @param previousVersion  the version to restore, or empty for an install or a repair
	 * @param newVersion       the authenticated version being activated
	 * @param migrationFile    the provider's migration marker
	 * @param backupRoot       this activation's private backup directory
	 * @param migrationExisted indicates whether the marker existed before activation
	 * @param prepared         indicates whether all backups completed before live mutation
	 * @param undoesLast       indicates whether the journal undoes the last successful activation, which recovery
	 *                         completes and whose record it then consumes
	 * @param entries          provider steps in execution order
	 */
	record Document(Operation operation, String previousVersion, String newVersion, Path migrationFile, Path backupRoot,
		boolean migrationExisted, boolean prepared, boolean undoesLast, List<Entry> entries)
	{
		/**
		 * Creates a stable record of activation progress.
		 *
		 * @param operation        whether the activation replaced an installation, added one where none existed or
		 *                         replaced the installed release with the same release
		 * @param previousVersion  the version to restore, or empty for an install or a repair
		 * @param newVersion       the version being activated
		 * @param migrationFile    the migration marker
		 * @param backupRoot       the private backup directory
		 * @param migrationExisted indicates whether the marker existed
		 * @param prepared         indicates whether all backups completed
		 * @param undoesLast       indicates whether this undoes the last successful activation
		 * @param entries          steps in execution order
		 */
		Document
		{
			requireThat(operation, "operation").isNotNull();
			if (operation == Operation.UPDATE)
				requireThat(previousVersion, "previousVersion").isNotBlank();
			else
				requireThat(previousVersion, "previousVersion").isEmpty();
			requireThat(newVersion, "newVersion").isNotBlank();
			requireThat(migrationFile, "migrationFile").isNotNull();
			requireThat(backupRoot, "backupRoot").isNotNull();
			entries = List.copyOf(entries);
		}
	}

	/**
	 * One staged change and its independent reverse progress.
	 *
	 * @param kind        the provider step kind
	 * @param live        the destination
	 * @param staging     the prepared replacement directory
	 * @param previous    the old live directory's slot
	 * @param older       a temporary slot for an earlier previous directory
	 * @param discard     the removed new live directory during undo
	 * @param state       forward progress, persisted before the corresponding mutation
	 * @param undo        reverse progress, persisted before the corresponding mutation
	 * @param hadPrevious indicates whether an older previous slot existed
	 * @param files       old and staged files of an owned-file step
	 */
	record Entry(Kind kind, Path live, Path staging, Path previous, Path older, Path discard, StepState state,
		Undo undo, boolean hadPrevious, List<FileChange> files)
	{
		/**
		 * Creates a step record.
		 *
		 * @param kind        the step kind
		 * @param live        the destination
		 * @param staging     the replacement directory
		 * @param previous    the old live slot
		 * @param older       the earlier previous slot
		 * @param discard     the directory removed during undo
		 * @param state       forward progress
		 * @param undo        reverse progress
		 * @param hadPrevious indicates whether an earlier previous slot existed
		 * @param files       owned-file changes
		 */
		Entry
		{
			requireThat(kind, "kind").isNotNull();
			requireThat(live, "live").isNotNull();
			requireThat(staging, "staging").isNotNull();
			requireThat(previous, "previous").isNotNull();
			requireThat(older, "older").isNotNull();
			requireThat(discard, "discard").isNotNull();
			requireThat(state, "state").isNotNull();
			requireThat(undo, "undo").isNotNull();
			files = List.copyOf(files);
		}
	}

	/**
	 * A file's before-image and optional staged replacement. An absent staged file means deletion.
	 *
	 * @param live    the provider-approved file
	 * @param backup  its saved before-image
	 * @param staging its optional new content
	 * @param existed indicates whether it existed before activation
	 */
	record FileChange(Path live, Path backup, Path staging, boolean existed)
	{
		/**
		 * Creates a file change.
		 *
		 * @param live    the destination
		 * @param backup  its saved before-image
		 * @param staging the optional new content
		 * @param existed indicates whether the destination existed
		 */
		FileChange
		{
			requireThat(live, "live").isNotNull();
			requireThat(backup, "backup").isNotNull();
			requireThat(staging, "staging").isNotNull();
		}
	}

	/**
	 * What an activation does to the harness.
	 */
	enum Operation
	{
		/**
		 * Replaces an installed release; undo restores it.
		 */
		UPDATE,
		/**
		 * Adds a release where no plugin was installed; undo returns the harness to that absence.
		 */
		INSTALL,
		/**
		 * Replaces the installed release with the same release and keeps what the last activation retained; undo
		 * restores the replaced files.
		 */
		REPAIR
	}

	/**
	 * Provider step alternatives.
	 */
	enum Kind
	{
		REPLACE_DIRECTORY, ADD_DIRECTORY, REPLACE_OWNED_FILES
	}

	/**
	 * Forward progress; each intent is durable before its live mutation.
	 */
	enum StepState
	{
		PENDING, STAGED, SWAPPED, DONE
	}

	/**
	 * Reverse progress; separates restoring live content from restoring an older previous slot.
	 */
	enum Undo
	{
		LIVE, PREVIOUS, DONE
	}
}

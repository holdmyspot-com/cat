package com.holdmyspot.cat.codex.installer;

import static com.holdmyspot.cat.codex.installer.TomlText.lines;
import static com.holdmyspot.cat.codex.installer.TomlText.parse;
import static com.holdmyspot.cat.codex.installer.TomlText.quote;
import static com.holdmyspot.cat.codex.installer.TomlText.tryParse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Adds one read or write entry to, and removes it from, the {@code filesystem} table of the permission profile that
 * Codex's {@code config.toml} selects with {@code default_permissions}, and the {@code enabled = true} entry to and
 * from its {@code network} table, changing no other part of the file. Codex uses that profile in place of
 * {@code sandbox_mode} and {@code sandbox_workspace_write}.
 * <p>
 * The editor changes the text of two forms only:
 * <ul>
 *   <li>a profile without the table, to which it appends a {@code [permissions.<name>.<table>]} table; and</li>
 *   <li>a {@code [permissions.<name>.<table>]} table header, below which it inserts the entry.</li>
 * </ul>
 * It refuses every other form, such as an inline table or dotted keys, with a message that names the form it edits.
 * Jackson's TOML parser decides whether the file and each line are valid TOML, and the editor accepts its edit only
 * when the edited file parses to the original document with only that entry added or removed.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class PermissionProfile
{
	/**
	 * The table of Codex's permission profiles.
	 */
	private static final String PROFILES = "permissions";
	/**
	 * The table of a profile's filesystem entries.
	 */
	static final String FILESYSTEM = "filesystem";
	/**
	 * The table of a profile's network settings.
	 */
	static final String NETWORK = "network";
	/**
	 * The network setting that lets commands reach the network.
	 */
	private static final String ENABLED = "enabled";
	/**
	 * Matches a profile name that TOML accepts as a key without quotes.
	 */
	private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

	/**
	 * Prevents instantiation.
	 */
	private PermissionProfile()
	{
	}

	/**
	 * Returns the permission profile that the document selects with {@code default_permissions} and defines in its
	 * {@code permissions} table.
	 *
	 * @param document the parsed {@code config.toml}
	 * @return the profile's name, or empty when the document selects no profile, or selects one that it does not
	 * 	define, such as one of Codex's built-in profiles
	 */
	static Optional<String> selected(ObjectNode document)
	{
		JsonNode name = document.path("default_permissions");
		if (!name.isString() || !document.path(PROFILES).path(name.stringValue()).isObject())
			return Optional.empty();
		return Optional.of(name.stringValue());
	}

	/**
	 * Returns the dotted TOML key of a profile's filesystem table.
	 *
	 * @param profile the profile's name
	 * @return {@code permissions.<name>.filesystem}, with the name quoted when TOML requires it
	 */
	static String tableKey(String profile)
	{
		return tableKey(profile, FILESYSTEM);
	}

	/**
	 * Returns the dotted TOML key of a profile's table.
	 *
	 * @param profile the profile's name
	 * @param table   the table's name, such as {@code filesystem}
	 * @return {@code permissions.<name>.<table>}, with the name quoted when TOML requires it
	 */
	static String tableKey(String profile, String table)
	{
		String key = profile;
		if (!BARE_KEY.matcher(profile).matches())
			key = quote(profile);
		return PROFILES + "." + key + "." + table;
	}

	/**
	 * Fails unless {@link #add} and {@link #remove} can edit the profile's form.
	 *
	 * @param text    the file's content
	 * @param profile the name of a profile that the file defines
	 * @param file    the file, named in the refusal
	 * @throws IOException if the content is not valid TOML or the profile is in a form that the editor does not change
	 */
	static void requireEditable(String text, String profile, Path file) throws IOException
	{
		ObjectNode document = parse(text, file);
		if (locate(lines(text), document, profile, FILESYSTEM, file) != -1)
			return;
		ObjectNode expected = document.deepCopy();
		((ObjectNode) expected.get(PROFILES).get(profile)).putObject(FILESYSTEM);
		String header = "[" + tableKey(profile) + "]";
		if (!expected.equals(tryParse(text + TomlText.newline(text) + header + TomlText.newline(text))))
			throw uneditable(profile, FILESYSTEM, file);
	}

	/**
	 * Adds an entry for a path to the profile unless the profile already has an entry for it.
	 *
	 * @param text    the file's content
	 * @param profile the name of a profile that the file defines
	 * @param path    the path of the entry
	 * @param access  what the entry lets commands do with the path
	 * @param file    the file, named in the refusal
	 * @return the edited content, or {@code text} when the profile already has an entry for the path, whatever its
	 * 	access
	 * @throws IOException if the content is not valid TOML or the profile is in a form that the editor does not change
	 */
	static String add(String text, String profile, String path, Access access, Path file) throws IOException
	{
		return add(text, profile, FILESYSTEM, path, quote(access.value), StringNode.valueOf(access.value), file);
	}

	/**
	 * Adds {@code enabled = true} to the profile's network table unless the table already sets {@code enabled}.
	 *
	 * @param text    the file's content
	 * @param profile the name of a profile that the file defines
	 * @param file    the file, named in the refusal
	 * @return the edited content, or {@code text} when the profile's network table already sets {@code enabled},
	 * 	whatever its value
	 * @throws IOException if the content is not valid TOML or the profile is in a form that the editor does not change
	 */
	static String enableNetwork(String text, String profile, Path file) throws IOException
	{
		return add(text, profile, NETWORK, ENABLED, "true", BooleanNode.TRUE, file);
	}

	/**
	 * Adds an entry to one of the profile's tables unless the table already has an entry of that key.
	 *
	 * @param text    the file's content
	 * @param profile the name of a profile that the file defines
	 * @param table   the table's name
	 * @param key     the entry's key
	 * @param literal the entry's value as TOML writes it
	 * @param value   the entry's value as the parser reads it
	 * @param file    the file, named in the refusal
	 * @return the edited content, or {@code text} when the table already has an entry of that key
	 * @throws IOException if the content is not valid TOML or the profile is in a form that the editor does not change
	 */
	private static String add(String text, String profile, String table, String key, String literal,
		JsonNode value, Path file) throws IOException
	{
		ObjectNode document = parse(text, file);
		List<String> lines = lines(text);
		int header = locate(lines, document, profile, table, file);
		ObjectNode profileTable = (ObjectNode) document.get(PROFILES).get(profile);
		if (profileTable.path(table).has(key))
			return text;

		String newline = TomlText.newline(text);
		String entry = quote(key) + " = " + literal;
		if (BARE_KEY.matcher(key).matches())
			entry = key + " = " + literal;
		ObjectNode expected = document.deepCopy();
		ObjectNode expectedTable = (ObjectNode) expected.get(PROFILES).get(profile);
		String edited;
		if (header == -1)
		{
			edited = TomlText.appendTable(text, "[" + tableKey(profile, table) + "]", entry, newline);
			expectedTable.putObject(table).set(key, value);
		}
		else
		{
			TomlText.terminate(lines, header, newline);
			lines.add(header + 1, entry + newline);
			edited = String.join("", lines);
			((ObjectNode) expectedTable.get(table)).set(key, value);
		}
		if (!expected.equals(tryParse(edited)))
			throw uneditable(profile, table, file);
		return edited;
	}

	/**
	 * Removes the entry for a path from a profile when it has the given access, leaving its table and the rest of the
	 * file in place.
	 *
	 * @param text    the file's content
	 * @param profile the profile's name
	 * @param path    the path of the entry
	 * @param access  the access of the entry
	 * @param file    the file, named in the refusal
	 * @return the edited content, or {@code text} when the file does not define the profile or the profile has no entry
	 * 	with that access for the path
	 * @throws IOException if the content is not valid TOML, or the profile holds the entry in a form that the editor
	 *                     does not change
	 */
	static String remove(String text, String profile, String path, Access access, Path file) throws IOException
	{
		return remove(text, profile, FILESYSTEM, path, StringNode.valueOf(access.value), file);
	}

	/**
	 * Removes {@code enabled = true} from the profile's network table, leaving the table and the rest of the file in
	 * place.
	 *
	 * @param text    the file's content
	 * @param profile the profile's name
	 * @param file    the file, named in the refusal
	 * @return the edited content, or {@code text} when the file does not define the profile or its network table does
	 * 	not set {@code enabled = true}
	 * @throws IOException if the content is not valid TOML, or the profile holds the entry in a form that the editor
	 *                     does not change
	 */
	static String disableNetwork(String text, String profile, Path file) throws IOException
	{
		return remove(text, profile, NETWORK, ENABLED, BooleanNode.TRUE, file);
	}

	/**
	 * Removes an entry from one of the profile's tables when it has the given value.
	 *
	 * @param text    the file's content
	 * @param profile the profile's name
	 * @param table   the table's name
	 * @param key     the entry's key
	 * @param value   the entry's value as the parser reads it
	 * @param file    the file, named in the refusal
	 * @return the edited content, or {@code text} when the file does not define the profile or the table has no entry
	 * 	of that key and value
	 * @throws IOException if the content is not valid TOML, or the profile holds the entry in a form that the editor
	 *                     does not change
	 */
	private static String remove(String text, String profile, String table, String key, JsonNode value, Path file)
		throws IOException
	{
		ObjectNode document = parse(text, file);
		JsonNode current = document.path(PROFILES).path(profile).path(table).path(key);
		if (!current.equals(value))
			return text;
		List<String> lines = lines(text);
		int header = locate(lines, document, profile, table, file);

		ObjectNode expected = document.deepCopy();
		((ObjectNode) expected.get(PROFILES).get(profile).get(table)).remove(key);
		for (int index = header + 1; index < lines.size() && !lines.get(index).strip().startsWith("["); ++index)
		{
			JsonNode line = tryParse(lines.get(index));
			if (line == null || line.size() != 1 || !line.has(key))
				continue;
			List<String> edited = new ArrayList<>(lines);
			edited.remove(index);
			String result = String.join("", edited);
			if (expected.equals(tryParse(result)))
				return result;
		}
		throw new IOException("CAT cannot remove " + quote(key) + " from the permission profile " + quote(profile) +
			" in " + file + ". Remove it from " + tableKey(profile, table) + " yourself, then rerun this command.");
	}

	/**
	 * Finds the header line of one of a profile's tables.
	 *
	 * @param lines    the file's lines
	 * @param document the parsed file
	 * @param profile  the name of a profile that the file defines
	 * @param table    the table's name
	 * @param file     the file, named in the refusal
	 * @return the header line's index, or -1 when the profile has no such table
	 * @throws IOException if the table's entries are in a form that the editor does not change
	 */
	private static int locate(List<String> lines, ObjectNode document, String profile, String table, Path file)
		throws IOException
	{
		JsonNode entries = document.get(PROFILES).get(profile).get(table);
		if (entries == null)
			return -1;
		if (!entries.isObject())
		{
			throw new IOException("CAT cannot edit the permission profile " + quote(profile) + " in " + file +
				", because " + tableKey(profile, table) + " is not a table. Make it a table, then rerun this command.");
		}
		ObjectNode header = TomlText.table();
		header.putObject(PROFILES).putObject(profile).putObject(table);
		int index = TomlText.findHeader(lines, header);
		if (index == -1)
			throw uneditable(profile, table, file);
		return index;
	}

	/**
	 * Describes a form that the editor does not change.
	 *
	 * @param profile the profile's name
	 * @param table   the name of the table that the editor changes
	 * @param file    the file
	 * @return the refusal
	 */
	private static IOException uneditable(String profile, String table, Path file)
	{
		String example = "\"/data\" = \"write\"";
		if (table.equals(NETWORK))
			example = ENABLED + " = true";
		return new IOException("CAT cannot edit the permission profile " + quote(profile) + " in " + file +
			". CAT edits it only below a [" + tableKey(profile, table) + "] table header with one entry per line, " +
			"such as " + example + ". Write the table that way, then rerun this command.");
	}

	/**
	 * What a profile's filesystem entry lets commands do with its path.
	 */
	enum Access
	{
		/**
		 * The commands may read the path but not write it. Codex applies the most specific entry, so this entry keeps
		 * the commands from writing the path even inside a broader {@code write} entry.
		 */
		READ("read"),
		/**
		 * The commands may read and write the path.
		 */
		WRITE("write");

		/**
		 * The entry's value in {@code config.toml}.
		 */
		private final String value;

		/**
		 * Creates an access.
		 *
		 * @param value the entry's value in {@code config.toml}
		 */
		Access(String value)
		{
			this.value = value;
		}
	}
}

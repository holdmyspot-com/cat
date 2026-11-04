package com.holdmyspot.cat.codex.installer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Adds one value to, and removes it from, the {@code sandbox_workspace_write.writable_roots} array of Codex's
 * {@code config.toml}, changing no other part of the file.
 * <p>
 * The editor changes the text of three forms only:
 * <ul>
 *   <li>a file without a {@code sandbox_workspace_write} table, to which it appends the table;</li>
 *   <li>a {@code [sandbox_workspace_write]} table header without {@code writable_roots}, below which it inserts the
 *   key; and</li>
 *   <li>a {@code [sandbox_workspace_write]} table header whose {@code writable_roots} array is on one line, at whose
 *   start it inserts the value.</li>
 * </ul>
 * It refuses every other form, such as an inline table, dotted keys or an array written across several lines, with a
 * message that names the form it edits. Jackson's TOML parser decides whether the file and each line are valid TOML,
 * and the editor accepts its edit only when the edited file parses to the original document with only that value
 * added or removed.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class WritableRoots
{
	/**
	 * The table of Codex's workspace-write sandbox settings.
	 */
	private static final String TABLE = "sandbox_workspace_write";
	/**
	 * The key of the directories that the workspace-write sandbox lets commands write.
	 */
	private static final String KEY = "writable_roots";
	/**
	 * Parses the file and its lines.
	 */
	private static final TomlMapper TOML = TomlMapper.builder().build();
	/**
	 * Quotes a value as a TOML basic string, whose escapes include all that JSON's string form uses.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Prevents instantiation.
	 */
	private WritableRoots()
	{
	}

	/**
	 * Fails unless {@link #add} and {@link #remove} can edit the file's form.
	 *
	 * @param text the file's content, empty when the file is absent
	 * @param file the file, named in the refusal
	 * @throws IOException if the content is not valid TOML or is in a form that the editor does not change
	 */
	static void requireEditable(String text, Path file) throws IOException
	{
		locate(lines(text), parse(text, file), file);
	}

	/**
	 * Adds a value to the writable roots unless they already hold it.
	 *
	 * @param text the file's content, empty when the file is absent
	 * @param root the value to add
	 * @param file the file, named in the refusal
	 * @return the edited content, or {@code text} when the writable roots already hold the value
	 * @throws IOException if the content is not valid TOML or is in a form that the editor does not change
	 */
	static String add(String text, String root, Path file) throws IOException
	{
		ObjectNode document = parse(text, file);
		List<String> lines = lines(text);
		Location location = locate(lines, document, file);
		if (location.key() != -1 && contains(document.get(TABLE).get(KEY), root))
			return text;

		String quoted = JSON.writeValueAsString(root);
		String newline = "\n";
		if (text.contains("\r\n"))
			newline = "\r\n";
		ObjectNode expected = document.deepCopy();
		String edited;
		if (location.header() == -1)
		{
			StringBuilder appended = new StringBuilder(text);
			if (!text.isEmpty())
			{
				if (!text.endsWith("\n"))
					appended.append(newline);
				appended.append(newline);
			}
			appended.append('[').append(TABLE).append(']').append(newline).
				append(KEY).append(" = [").append(quoted).append(']').append(newline);
			edited = appended.toString();
			expected.putObject(TABLE).putArray(KEY).add(root);
		}
		else if (location.key() == -1)
		{
			terminate(lines, location.header(), newline);
			lines.add(location.header() + 1, KEY + " = [" + quoted + "]" + newline);
			edited = String.join("", lines);
			((ObjectNode) expected.get(TABLE)).putArray(KEY).add(root);
		}
		else
		{
			ArrayNode roots = (ArrayNode) expected.get(TABLE).get(KEY);
			String separator = ", ";
			if (roots.isEmpty())
				separator = "";
			String line = lines.get(location.key());
			int start = line.indexOf('[', line.indexOf('=')) + 1;
			lines.set(location.key(), line.substring(0, start) + quoted + separator + line.substring(start));
			edited = String.join("", lines);
			roots.insert(0, root);
		}
		if (!expected.equals(tryParse(edited)))
			throw uneditable(file);
		return edited;
	}

	/**
	 * Removes one occurrence of a value from the writable roots, leaving the array, its table and the rest of the
	 * file in place.
	 *
	 * @param text the file's content, empty when the file is absent
	 * @param root the value to remove
	 * @param file the file, named in the refusal
	 * @return the edited content, or {@code text} when the writable roots do not hold the value
	 * @throws IOException if the content is not valid TOML, or the writable roots hold the value in a form that the
	 *                     editor does not change
	 */
	static String remove(String text, String root, Path file) throws IOException
	{
		ObjectNode document = parse(text, file);
		if (!contains(document.path(TABLE).path(KEY), root))
			return text;
		List<String> lines = lines(text);
		Location location = locate(lines, document, file);

		ObjectNode expected = document.deepCopy();
		ArrayNode roots = (ArrayNode) expected.get(TABLE).get(KEY);
		for (int index = 0; index < roots.size(); ++index)
		{
			if (roots.get(index).stringValue().equals(root))
			{
				roots.remove(index);
				break;
			}
		}
		String quoted = JSON.writeValueAsString(root);
		String line = lines.get(location.key());
		for (String candidate : List.of(quoted + ", ", ", " + quoted, quoted + ",", "," + quoted, quoted))
		{
			int start = line.indexOf(candidate);
			if (start == -1)
				continue;
			List<String> edited = new ArrayList<>(lines);
			edited.set(location.key(), line.substring(0, start) + line.substring(start + candidate.length()));
			String result = String.join("", edited);
			if (expected.equals(tryParse(result)))
				return result;
		}
		throw new IOException("CAT cannot remove " + quoted + " from the writable roots in " + file + ". Remove " +
			"it from " + TABLE + "." + KEY + " yourself, then rerun this command.");
	}

	/**
	 * Parses the file's content.
	 *
	 * @param text the content
	 * @param file the file, named in the refusal
	 * @return the document
	 * @throws IOException if the content is not valid TOML
	 */
	private static ObjectNode parse(String text, Path file) throws IOException
	{
		try
		{
			return (ObjectNode) TOML.readTree(text);
		}
		catch (JacksonException e)
		{
			throw new IOException("CAT cannot edit " + file + ", because it is not valid TOML: " +
				e.getOriginalMessage() + ". Correct the file, then rerun this command.", e);
		}
	}

	/**
	 * Parses a text as a TOML document.
	 *
	 * @param text the text
	 * @return the document, or null when the text is not valid TOML
	 */
	private static JsonNode tryParse(String text)
	{
		try
		{
			return TOML.readTree(text);
		}
		catch (JacksonException _)
		{
			return null;
		}
	}

	/**
	 * Splits a text into lines.
	 *
	 * @param text the text
	 * @return the lines, each with its line terminator; the last line has none when the text does not end with one
	 */
	private static List<String> lines(String text)
	{
		List<String> lines = new ArrayList<>();
		int start = 0;
		while (start < text.length())
		{
			int end = text.indexOf('\n', start) + 1;
			if (end == 0)
				end = text.length();
			lines.add(text.substring(start, end));
			start = end;
		}
		return lines;
	}

	/**
	 * Finds the lines that the editor changes.
	 *
	 * @param lines    the file's lines
	 * @param document the parsed file
	 * @param file     the file, named in the refusal
	 * @return the lines' indexes
	 * @throws IOException if the file is in a form that the editor does not change
	 */
	private static Location locate(List<String> lines, ObjectNode document, Path file) throws IOException
	{
		JsonNode table = document.get(TABLE);
		if (table == null)
			return new Location(-1, -1);
		if (!table.isObject())
		{
			throw new IOException("CAT cannot edit the writable roots in " + file + ", because " + TABLE +
				" is not a table. Make it a table, then rerun this command.");
		}
		JsonNode roots = table.get(KEY);
		if (roots != null && !isStringArray(roots))
		{
			throw new IOException("CAT cannot edit the writable roots in " + file + ", because " + TABLE + "." +
				KEY + " is not an array of strings. Correct it, then rerun this command.");
		}

		int header = -1;
		for (int index = 0; index < lines.size() && header == -1; ++index)
		{
			String line = lines.get(index).strip();
			if (line.startsWith("[") && !line.startsWith("[[") &&
				JSON.createObjectNode().set(TABLE, JSON.createObjectNode()).equals(tryParse(line)))
			{
				header = index;
			}
		}
		if (header == -1)
			throw uneditable(file);
		if (roots == null)
			return new Location(header, -1);
		for (int index = header + 1; index < lines.size() && !lines.get(index).strip().startsWith("["); ++index)
		{
			JsonNode line = tryParse(lines.get(index));
			if (line != null && line.size() == 1 && roots.equals(line.get(KEY)))
				return new Location(header, index);
		}
		throw uneditable(file);
	}

	/**
	 * Reports whether a value is an array of strings.
	 *
	 * @param value the value
	 * @return {@code true} if it is an array whose elements are all strings
	 */
	private static boolean isStringArray(JsonNode value)
	{
		if (!value.isArray())
			return false;
		for (JsonNode element : value)
		{
			if (!element.isString())
				return false;
		}
		return true;
	}

	/**
	 * Reports whether the writable roots hold a value.
	 *
	 * @param roots the writable roots, or a missing node
	 * @param root  the value
	 * @return {@code true} if an element equals the value
	 */
	private static boolean contains(JsonNode roots, String root)
	{
		for (JsonNode element : roots)
		{
			if (element.isString() && element.stringValue().equals(root))
				return true;
		}
		return false;
	}

	/**
	 * Ends a line with a line terminator if it has none.
	 *
	 * @param lines   the lines
	 * @param index   the line's index
	 * @param newline the file's line terminator
	 */
	private static void terminate(List<String> lines, int index, String newline)
	{
		String line = lines.get(index);
		if (!line.endsWith("\n"))
			lines.set(index, line + newline);
	}

	/**
	 * Describes a form that the editor does not change.
	 *
	 * @param file the file
	 * @return the refusal
	 */
	private static IOException uneditable(Path file)
	{
		return new IOException("CAT cannot edit the writable roots in " + file + ". CAT edits them only below a [" +
			TABLE + "] table header: write writable_roots on one line there, such as " + KEY + " = [\"/data\"], " +
			"then rerun this command.");
	}

	/**
	 * The lines that the editor changes.
	 *
	 * @param header the index of the {@code [sandbox_workspace_write]} header line, or -1 when the file has no such
	 *               table
	 * @param key    the index of the {@code writable_roots} line, or -1 when the table has no such key
	 */
	private record Location(int header, int key)
	{
	}
}

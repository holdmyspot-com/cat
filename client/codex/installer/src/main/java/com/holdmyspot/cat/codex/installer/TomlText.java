package com.holdmyspot.cat.codex.installer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Reads and splits the text of Codex's {@code config.toml} for the editors that change single lines of it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class TomlText
{
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
	private TomlText()
	{
	}

	/**
	 * Parses the file's content.
	 *
	 * @param text the content
	 * @param file the file, named in the refusal
	 * @return the document
	 * @throws IOException if the content is not valid TOML
	 */
	static ObjectNode parse(String text, Path file) throws IOException
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
	static JsonNode tryParse(String text)
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
	 * Returns an empty table to build an expected document with.
	 *
	 * @return the table
	 */
	static ObjectNode table()
	{
		return JSON.createObjectNode();
	}

	/**
	 * Quotes a value as a TOML basic string.
	 *
	 * @param value the value
	 * @return the quoted value
	 */
	static String quote(String value)
	{
		return JSON.writeValueAsString(value);
	}

	/**
	 * Splits a text into lines.
	 *
	 * @param text the text
	 * @return the lines, each with its line terminator; the last line has none when the text does not end with one
	 */
	static List<String> lines(String text)
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
	 * Returns the line terminator that a text uses.
	 *
	 * @param text the text
	 * @return {@code "\r\n"} if the text contains it, otherwise {@code "\n"}
	 */
	static String newline(String text)
	{
		if (text.contains("\r\n"))
			return "\r\n";
		return "\n";
	}

	/**
	 * Finds the first table header line whose parsed form equals a document.
	 *
	 * @param lines  the file's lines
	 * @param header the document that the header line alone parses to
	 * @return the line's index, or -1 when no line is that header
	 */
	static int findHeader(List<String> lines, JsonNode header)
	{
		for (int index = 0; index < lines.size(); ++index)
		{
			String line = lines.get(index).strip();
			if (line.startsWith("[") && !line.startsWith("[[") && header.equals(tryParse(line)))
				return index;
		}
		return -1;
	}

	/**
	 * Appends a table to a text, separated from the existing content by a blank line.
	 *
	 * @param text    the text
	 * @param header  the table's header line, without its line terminator
	 * @param entry   the table's one entry line, without its line terminator
	 * @param newline the file's line terminator
	 * @return the text with the table appended
	 */
	static String appendTable(String text, String header, String entry, String newline)
	{
		StringBuilder appended = new StringBuilder(text);
		if (!text.isEmpty())
		{
			if (!text.endsWith("\n"))
				appended.append(newline);
			appended.append(newline);
		}
		return appended.append(header).append(newline).append(entry).append(newline).toString();
	}

	/**
	 * Ends a line with a line terminator if it has none.
	 *
	 * @param lines   the lines
	 * @param index   the line's index
	 * @param newline the file's line terminator
	 */
	static void terminate(List<String> lines, int index, String newline)
	{
		String line = lines.get(index);
		if (!line.endsWith("\n"))
			lines.set(index, line + newline);
	}
}

package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the tool results that a Codex session's agents received from the session's rollouts: the files in which Codex
 * records what each thread's model received, one JSON object per line.
 * <p>
 * Codex can shorten a tool's output before its model receives it, so a result can hold less than the output that the
 * app-server reported. A result is a {@code response_item} whose payload is a {@code function_call_output} or a
 * {@code custom_tool_call_output}; its {@code output} is a string, an array of items that each hold a {@code text}, or
 * an object that holds a {@code content} string. A tool can wrap its output in JSON, so each result is also supplied
 * decoded: as every string of its JSON value when it is JSON, and otherwise with its JSON escape sequences replaced,
 * which recovers the text of JSON that was cut short.
 */
final class RolloutToolResults
{
	/**
	 * The payload types of the rollout items that hold a tool's result.
	 */
	private static final Set<String> RESULT_TYPES = Set.of("function_call_output", "custom_tool_call_output");
	/**
	 * Matches a JSON escape sequence; group 1 holds the character after the backslash, or the {@code u} and four
	 * hexadecimal digits of a UTF-16 code unit.
	 */
	private static final Pattern JSON_ESCAPE = Pattern.compile("\\\\([\"\\\\/bfnrt]|u[0-9a-fA-F]{4})");

	/**
	 * Prevents instantiation.
	 */
	private RolloutToolResults()
	{
	}

	/**
	 * Reads every tool result of a session's rollouts. A line that is not a JSON object is ignored, as is a file whose
	 * name does not end with {@code .jsonl}.
	 *
	 * @param directory the directory that holds the session's rollouts
	 * @param json      the JSON mapper
	 * @return each result as received, followed by its decoded text when it is JSON or holds JSON escape sequences
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if {@code directory} is not a directory or a rollout cannot be read
	 */
	static List<String> read(Path directory, JsonMapper json) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(json, "json").isNotNull();
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The session's rollouts were not retained: " + directory + " is not a directory");
		List<Path> rollouts;
		try (Stream<Path> files = Files.list(directory))
		{
			rollouts = files.filter(file -> file.getFileName().toString().endsWith(".jsonl")).sorted().toList();
		}
		List<String> results = new ArrayList<>();
		for (Path rollout : rollouts)
		{
			try (BufferedReader reader = Files.newBufferedReader(rollout))
			{
				String line = reader.readLine();
				while (line != null)
				{
					for (String text : texts(json, line))
						addDecoded(json, text, results);
					line = reader.readLine();
				}
			}
		}
		return results;
	}

	/**
	 * Returns the texts of a rollout line's tool result.
	 *
	 * @param json the JSON mapper
	 * @param line the rollout line
	 * @return the texts, empty if the line does not hold a tool result
	 */
	private static List<String> texts(JsonMapper json, String line)
	{
		JsonNode item;
		try
		{
			item = json.readTree(line);
		}
		catch (JacksonException _)
		{
			return List.of();
		}
		if (!item.path("type").asString("").equals("response_item"))
			return List.of();
		JsonNode payload = item.path("payload");
		if (!RESULT_TYPES.contains(payload.path("type").asString("")))
			return List.of();
		JsonNode output = payload.path("output");
		if (output.isString())
			return List.of(output.stringValue());
		List<String> texts = new ArrayList<>();
		if (output.isArray())
		{
			for (JsonNode element : output)
			{
				JsonNode text = element.path("text");
				if (text.isString())
					texts.add(text.stringValue());
			}
		}
		else if (output.path("content").isString())
			texts.add(output.path("content").stringValue());
		return texts;
	}

	/**
	 * Adds a result's text as received, followed by its decoded text when it differs.
	 *
	 * @param json    the JSON mapper
	 * @param text    the text as received
	 * @param results the destination
	 */
	private static void addDecoded(JsonMapper json, String text, List<String> results)
	{
		results.add(text);
		String stripped = text.strip();
		if (stripped.startsWith("{") || stripped.startsWith("["))
		{
			try
			{
				addStrings(json.readTree(stripped), results);
				return;
			}
			catch (JacksonException _)
			{
				// Text that was cut short is no longer valid JSON; its escape sequences are replaced below
			}
		}
		String unescaped = unescape(text);
		if (!unescaped.equals(text))
			results.add(unescaped);
	}

	/**
	 * Adds every string of a JSON value.
	 *
	 * @param value   the value
	 * @param results the destination
	 */
	private static void addStrings(JsonNode value, List<String> results)
	{
		if (value.isString())
		{
			results.add(value.stringValue());
			return;
		}
		for (JsonNode child : value)
			addStrings(child, results);
	}

	/**
	 * Replaces the JSON escape sequences of a text with the characters that they stand for.
	 *
	 * @param text the text
	 * @return the text without escape sequences
	 */
	private static String unescape(String text)
	{
		Matcher matcher = JSON_ESCAPE.matcher(text);
		StringBuilder result = new StringBuilder(text.length());
		while (matcher.find())
		{
			String escaped = matcher.group(1);
			String character = switch (escaped.charAt(0))
			{
				case 'b' -> "\b";
				case 'f' -> "\f";
				case 'n' -> "\n";
				case 'r' -> "\r";
				case 't' -> "\t";
				case 'u' -> String.valueOf((char) Integer.parseInt(escaped.substring(1), 16));
				default -> escaped;
			};
			matcher.appendReplacement(result, Matcher.quoteReplacement(character));
		}
		matcher.appendTail(result);
		return result.toString();
	}
}

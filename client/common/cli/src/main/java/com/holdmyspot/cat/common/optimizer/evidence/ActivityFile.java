package com.holdmyspot.cat.common.optimizer.evidence;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the activity format shared by a peer trial and a prompt test's assertion example.
 */
public final class ActivityFile
{
	/**
	 * The supported activity file format.
	 */
	public static final int VERSION = 1;

	/**
	 * Reads the trial activity, applying the given replacement to its text values.
	 *
	 * @param json    the process mapper
	 * @param file    the retained activity file
	 * @param replace the replacement for each text value
	 * @return the parsed trial activity
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the activity cannot be read or is malformed
	 */
	public static SessionEvidence read(JsonMapper json, Path file, UnaryOperator<String> replace) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(file, "file").isNotNull();
		requireThat(replace, "replace").isNotNull();
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Expected a regular activity file: " + file);
		try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))
		{
			JsonNode document = json.readTree(input);
			if (document == null || number(document, "format_version") != VERSION)
				throw new IOException("Unsupported retained evaluator activity format");
			JsonNode records = document.path("entries");
			if (!records.isArray())
				throw new IOException("Expected retained evaluator activity entries");
			List<SessionEvidence.Entry> entries = new ArrayList<>();
			for (JsonNode record : records)
				entries.add(readEntry(record, replace));
			Duration slowest = null;
			Integer empty = null;
			if (document.has("slowest_command"))
				slowest = Duration.parse(text(document, "slowest_command", replace));
			if (document.has("empty_running_results"))
				empty = number(document, "empty_running_results");
			return new SessionEvidence(entries, strings(document, "received", replace), new CommandSummary(slowest, empty));
		}
		catch (JacksonException | IllegalArgumentException | DateTimeException e)
		{
			throw new IOException("Malformed retained evaluator activity in " + file, e);
		}
	}

	/**
	 * Reconstructs a field-checked activity entry.
	 *
	 * @param record  the stored activity record
	 * @param replace the replacement for each text value
	 * @return the semantically typed activity
	 * @throws IOException if the entry is malformed
	 */
	private static SessionEvidence.Entry readEntry(JsonNode record, UnaryOperator<String> replace) throws IOException
	{
		JsonNode node = record.path("entry");
		int turn = number(node, "turn");
		String agent = text(node, "agent", replace);
		return switch (text(record, "type", replace))
		{
			case "tool" -> new SessionEvidence.Tool(turn, agent, text(node, "tool", replace),
				text(node, "input", replace), node.path("command").booleanValue(), exitStatus(node),
				text(node, "output", replace), strings(node, "running", replace));
			case "command_end" -> new SessionEvidence.CommandEnd(turn, agent, text(node, "input", replace),
				exitStatus(node), text(node, "output", replace));
			case "hook" -> new SessionEvidence.Hook(turn, agent, text(node, "hook", replace),
				text(node, "status", replace));
			case "assistant" -> new SessionEvidence.Assistant(turn, agent, number(node, "position"),
				text(node, "text", replace), questions(node.path("questions"), replace));
			case "child_event" -> new SessionEvidence.ChildEvent(turn, agent, text(node, "child", replace),
				text(node, "name", replace), SessionEvidence.ChildEvent.Kind.valueOf(text(node, "kind", replace)),
				text(node, "text", replace));
			default -> throw new IOException("Unknown retained evaluator activity type");
		};
	}

	/**
	 * Reads the nullable process exit status.
	 *
	 * @param node the tool or command entry
	 * @return its status, or null when the native record omitted it
	 * @throws IOException if the recorded status is malformed
	 */
	private static Integer exitStatus(JsonNode node) throws IOException
	{
		if (node.path("exit_status").isNull())
			return null;
		return number(node, "exit_status");
	}

	/**
	 * Reads any user-input questions and their options in order.
	 *
	 * @param node    the questions array
	 * @param replace the replacement for each text value
	 * @return the questions the assistant asked
	 * @throws IOException if a question is malformed
	 */
	private static List<SessionEvidence.Question> questions(JsonNode node, UnaryOperator<String> replace)
		throws IOException
	{
		if (!node.isArray())
			throw new IOException("Expected retained evaluator questions");
		List<SessionEvidence.Question> questions = new ArrayList<>();
		for (JsonNode question : node)
		{
			questions.add(new SessionEvidence.Question(text(question, "title", replace),
				strings(question, "options", replace)));
		}
		return questions;
	}

	/**
	 * Reads the text values of an array.
	 *
	 * @param node    the object containing the array
	 * @param name    the property name
	 * @param replace the replacement for each text value
	 * @return the array's text values in order
	 * @throws IOException if the value is missing, is not an array or contains a value that is not text
	 */
	private static List<String> strings(JsonNode node, String name, UnaryOperator<String> replace) throws IOException
	{
		JsonNode array = node.path(name);
		if (!array.isArray())
			throw new IOException("Expected retained evaluator activity array " + name);
		List<String> result = new ArrayList<>();
		for (JsonNode value : array)
		{
			if (!value.isString())
				throw new IOException("Expected retained evaluator activity text in " + name);
			result.add(replace.apply(value.stringValue()));
		}
		return result;
	}

	/**
	 * Reads a text property.
	 *
	 * @param node    the object containing the field
	 * @param name    the property name
	 * @param replace the replacement for the text
	 * @return the property's text, with the replacement applied
	 * @throws IOException if the field does not contain text
	 */
	private static String text(JsonNode node, String name, UnaryOperator<String> replace) throws IOException
	{
		JsonNode value = node.path(name);
		if (!value.isString())
			throw new IOException("Expected retained evaluator activity text field " + name);
		return replace.apply(value.stringValue());
	}

	/**
	 * Reads an integer property.
	 *
	 * @param node the object containing the field
	 * @param name the property name
	 * @return the property's integer
	 * @throws IOException if the field does not contain an integer
	 */
	private static int number(JsonNode node, String name) throws IOException
	{
		JsonNode value = node.path(name);
		if (!value.isIntegralNumber() || !value.canConvertToInt())
			throw new IOException("Expected retained evaluator activity integer field " + name);
		return value.intValue();
	}

	/**
	 * Prevents construction.
	 */
	private ActivityFile()
	{
	}
}

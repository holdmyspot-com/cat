package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.cat.common.PromptEvaluation.array;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Expands one declared shell-free command argument array.
 */
public final class CommandArguments
{
	/**
	 * Prevents construction of this static owner.
	 */
	private CommandArguments()
	{
	}

	/**
	 * Expands one explicitly declared command array with its supported substitutions.
	 *
	 * @param entries the command entries
	 * @param name the command's reader-visible role
	 * @param values the supported substitutions
	 * @return the shell-free command arguments
	 * @throws IOException if the command is malformed
	 */
	public static List<String> expand(JsonNode entries, String name, Map<String, String> values) throws IOException
	{
		ArrayNode commandEntries = array(entries, "command", Path.of(name));
		List<String> result = new ArrayList<>();
		for (JsonNode entry : commandEntries)
		{
			if (!entry.isString() || entry.asString().isBlank())
				throw new IOException("Execution command contains a blank argument: " + name);
			String value = entry.asString();
			for (Map.Entry<String, String> replacement : values.entrySet())
				value = value.replace("${" + replacement.getKey() + "}", replacement.getValue());
			result.add(value);
		}
		return result;
	}

	/**
	 * Returns an optional command array without requiring an empty placeholder.
	 *
	 * @param entries the optional command entries
	 * @param name the command's reader-visible role
	 * @return the command arguments, or an empty list when absent
	 * @throws IOException if a declared command is malformed
	 */
	public static List<String> optional(JsonNode entries, String name) throws IOException
	{
		if (entries.isMissingNode() || entries.isNull())
			return List.of();
		return expand(entries, name, Map.of());
	}
}

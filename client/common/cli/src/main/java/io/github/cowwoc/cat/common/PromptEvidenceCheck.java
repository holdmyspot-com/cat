package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates the required fields of one prompt-builder evidence record.
 */
public final class PromptEvidenceCheck
{
	private static final List<String> REQUIRED = List.of("prompt_revision", "task_input", "harness", "profile",
		"allowed_context", "tool_events", "raw_result", "validator_result", "status");
	private static final List<String> FAILURE_REQUIRED = List.of("failure_class", "owner", "sibling_enumeration",
		"invariant", "next_action");
	private static final Set<String> STATUSES = Set.of("passed", "failed", "unrun", "blocked");

	/**
	 * Validates one line-oriented {@code key=value} evidence record.
	 *
	 * @param args one evidence-record path
	 * @throws IOException if the record cannot be read or lacks a required field
	 */
	public static void main(String[] args) throws IOException
	{
		requireThat(args, "args").isNotNull();
		if (args.length != 1)
			throw new IllegalArgumentException("Usage: prompt-evidence-check <record>");
		Path record = Path.of(args[0]).toAbsolutePath().normalize();
		requireThat(record, "record").isRegularFile();
		Map<String, String> fields = parseFields(record);
		for (String key : REQUIRED)
			requireField(fields, key, record);

		String status = fields.get("status");
		if (!STATUSES.contains(status))
			throw new IOException("Evidence record has an invalid status: " + status);
		if (status.equals("failed"))
			for (String key : FAILURE_REQUIRED)
				requireField(fields, key, record);
	}

	/**
	 * Parses the non-blank fields of one line-oriented evidence record.
	 *
	 * @param record the evidence-record file
	 * @return fields indexed by their unique names
	 * @throws IOException if a field is malformed, blank, or duplicated
	 */
	private static Map<String, String> parseFields(Path record) throws IOException
	{
		Map<String, String> fields = new LinkedHashMap<>();
		int lineNumber = 0;
		for (String line : Files.readAllLines(record))
		{
			++lineNumber;
			if (line.isBlank())
				continue;
			int separator = line.indexOf('=');
			if (separator <= 0)
				throw new IOException("Evidence record has a malformed field on line " + lineNumber + ": " + record);
			String key = line.substring(0, separator);
			String value = line.substring(separator + 1);
			if (key.isBlank() || value.isBlank())
				throw new IOException("Evidence record has a blank field on line " + lineNumber + ": " + record);
			if (fields.putIfAbsent(key, value) != null)
				throw new IOException("Evidence record duplicates " + key + ": " + record);
		}
		return fields;
	}

	/**
	 * Verifies that a record contains one required non-blank field.
	 *
	 * @param fields fields indexed by their names
	 * @param key the required field name
	 * @param record the source evidence-record file
	 * @throws IOException if the field is absent
	 */
	private static void requireField(Map<String, String> fields, String key, Path record) throws IOException
	{
		if (!fields.containsKey(key))
			throw new IOException("Evidence record lacks " + key + ": " + record);
	}

	/**
	 * Prevents utility-class construction.
	 */
	private PromptEvidenceCheck()
	{
	}
}

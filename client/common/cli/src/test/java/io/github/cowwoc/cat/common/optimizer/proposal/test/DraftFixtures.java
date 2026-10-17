package io.github.cowwoc.cat.common.optimizer.proposal.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts a contract author output whose prompt tests hold their scenarios, fixtures and test cases inline into
 * what a contract author session writes: a proposal file or a test revision's result file whose prompt tests name
 * their directories, and those directories' files in a draft directory.
 * <p>
 * A prompt test is converted only when it holds its {@code turns}; any other item, and any text that is not a JSON
 * object with a {@code tests} array or a {@code test} object, is kept as written, so that tests can still write
 * invalid output.
 */
public final class DraftFixtures
{
	/**
	 * The properties of an inline prompt test that its files hold instead of its item of the proposal file. Every
	 * other property, including one that the proposal file does not allow, stays in the item.
	 */
	private static final Set<String> INLINE = Set.of("id", "turns", "time_limit_seconds", "expected_behavior",
		"variables", "test_cases", "fixtures", "regression_scenarios");

	/**
	 * Prevents instantiation.
	 */
	private DraftFixtures()
	{
	}

	/**
	 * Converts a contract author output.
	 *
	 * @param json     the JSON mapper
	 * @param output   the output, whose prompt tests may hold their scenarios inline
	 * @param draft    the draft directory that receives the prompt tests' files
	 * @param target   the campaign's target, relative to the project
	 * @param recorded the directory of each recorded prompt test, by the citation that a prompt test's
	 *                 {@code regression_scenarios} names; a test that cites one is written to its directory
	 * @return the proposal file's text
	 * @throws IOException if a file cannot be written
	 */
	public static String write(JsonMapper json, String output, Path draft, Path target, Map<String, Path> recorded)
		throws IOException
	{
		JsonNode root;
		try
		{
			root = json.readTree(output);
		}
		catch (JacksonException _)
		{
			return output;
		}
		if (!(root instanceof ObjectNode proposal) || !(proposal.get("tests") instanceof ArrayNode tests))
			return output;
		Files.createDirectories(draft);
		for (int i = 0; i < tests.size(); ++i)
		{
			if (tests.get(i) instanceof ObjectNode test && test.has("turns"))
				tests.set(i, write(json, test, draft, directory(test, target, recorded)));
		}
		return json.writerWithDefaultPrettyPrinter().writeValueAsString(proposal);
	}

	/**
	 * Converts a test revision output.
	 *
	 * @param json     the JSON mapper
	 * @param output   the output, whose corrected prompt test may hold its scenario inline
	 * @param draft    the draft directory that receives the corrected test's files
	 * @param target   the campaign's target, relative to the project
	 * @param recorded the directory of each recorded prompt test, by the citation that a prompt test's
	 *                 {@code regression_scenarios} names; a test that cites one is written to its directory
	 * @return the result file's text
	 * @throws IOException if a file cannot be written
	 */
	public static String writeRevision(JsonMapper json, String output, Path draft, Path target,
		Map<String, Path> recorded) throws IOException
	{
		JsonNode root;
		try
		{
			root = json.readTree(output);
		}
		catch (JacksonException _)
		{
			return output;
		}
		if (!(root instanceof ObjectNode result) || !(result.get("test") instanceof ObjectNode test) ||
			!test.has("turns"))
		{
			return output;
		}
		result.set("test", write(json, test, draft, directory(test, target, recorded)));
		return json.writerWithDefaultPrettyPrinter().writeValueAsString(result);
	}

	/**
	 * Returns the directory of a prompt test.
	 *
	 * @param test     the prompt test, holding its scenario inline
	 * @param target   the campaign's target, relative to the project
	 * @param recorded the directory of each recorded prompt test, by its citation
	 * @return the directory of the recorded prompt test that the test cites, or the directory of a created test
	 *   named after its identifier, relative to the draft directory
	 */
	private static Path directory(ObjectNode test, Path target, Map<String, Path> recorded)
	{
		Path directory = PromptTestLocations.scenarioDirectory(target).resolve(test.path("id").asString());
		for (JsonNode citation : test.path("regression_scenarios"))
		{
			Path recordedDirectory = recorded.get(citation.asString());
			if (recordedDirectory != null)
				directory = recordedDirectory;
		}
		return directory;
	}

	/**
	 * Writes one prompt test's files.
	 *
	 * @param json      the JSON mapper
	 * @param test      the prompt test, holding its scenario inline
	 * @param draft     the draft directory
	 * @param directory the test's directory, relative to the draft directory
	 * @return the test's item of the proposal file
	 * @throws IOException if a file cannot be written
	 */
	private static ObjectNode write(JsonMapper json, ObjectNode test, Path draft, Path directory)
		throws IOException
	{
		Path testDirectory = Files.createDirectories(draft.resolve(directory));
		Files.writeString(testDirectory.resolve("scenario.md"), scenario(test), UTF_8);
		Files.writeString(testDirectory.resolve("values.json"), values(json, test), UTF_8);
		for (JsonNode fixture : test.path("fixtures"))
		{
			Path file = testDirectory.resolve("workspace").resolve(fixture.path("path").asString());
			Files.createDirectories(file.getParent());
			Files.writeString(file, fixture.path("content").asString(), UTF_8);
		}

		ObjectNode item = json.createObjectNode();
		item.put("directory", PortablePaths.text(directory));
		for (String name : test.propertyNames())
		{
			if (INLINE.contains(name))
				continue;
			if (name.equals("assertions"))
			{
				ArrayNode assertions = item.putArray("assertions");
				for (JsonNode assertion : test.path("assertions"))
				{
					ObjectNode assertionItem = assertions.addObject();
					assertionItem.set("id", assertion.get("id"));
					assertionItem.set("requirement_id", assertion.get("requirement_id"));
				}
			}
			else
				item.set(name, test.get(name));
		}
		return item;
	}

	/**
	 * Returns the {@code scenario.md} of a prompt test.
	 *
	 * @param test the prompt test, holding its scenario inline
	 * @return the file's content
	 */
	public static String scenario(JsonNode test)
	{
		StringBuilder text = new StringBuilder(256);
		text.append("# ").append(test.path("id").asString()).append("\n\n## Variables\n\n");
		for (JsonNode variable : test.path("variables"))
			appendPair(text, variable.path("name").asString(), variable.path("description").asString());

		text.append("\n## Scenario\n\n");
		JsonNode turns = test.path("turns");
		if (turns.size() == 1)
			appendTurn(text, turns.get(0).asString());
		else
		{
			for (int i = 0; i < turns.size(); ++i)
			{
				if (i > 0)
					text.append('\n');
				text.append("### Turn ").append(i + 1).append("\n\n");
				appendTurn(text, turns.get(i).asString());
			}
		}

		JsonNode expectedBehavior = test.get("expected_behavior");
		if (expectedBehavior != null && expectedBehavior.isString())
		{
			text.append("\n## Expected Behavior\n\n");
			appendPair(text, "description", expectedBehavior.asString());
			appendPair(text, "time_limit_seconds", test.path("time_limit_seconds").asString());
		}

		text.append("\n## Assertions\n");
		for (JsonNode assertion : test.path("assertions"))
		{
			text.append("\n### ").append(assertion.path("id").asString()).append("\n\n");
			appendPair(text, "kind", assertion.path("kind").asString());
			JsonNode tool = assertion.get("tool");
			if (tool != null && tool.isString())
				appendPair(text, "tool", "`" + tool.asString() + "`");
			if (assertion.path("on_tool_failure").asString().equals("judge"))
				appendPair(text, "on_tool_failure", "judge");
			appendPair(text, "description", assertion.path("statement").asString());
		}
		return text.toString();
	}

	/**
	 * Returns the {@code values.json} of a prompt test.
	 *
	 * @param json the JSON mapper
	 * @param test the prompt test, holding its test cases inline
	 * @return the file's content
	 */
	public static String values(JsonMapper json, JsonNode test)
	{
		ObjectNode root = json.createObjectNode();
		root.put("format_version", 1);
		ArrayNode testCases = root.putArray("test_cases");
		for (JsonNode testCase : test.path("test_cases"))
		{
			ObjectNode testCaseNode = testCases.addObject();
			ObjectNode values = testCaseNode.putObject("values");
			for (JsonNode value : testCase.path("values"))
			{
				String name = value.path("variable").asString();
				if (value.has("call"))
					values.set(name, value.get("call"));
				else
					values.set(name, value.get("text"));
			}
			testCaseNode.set("expected_outcome", testCase.get("expected_outcome"));
		}
		return json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
	}

	/**
	 * Appends a key-value pair whose value continues on lines indented by two spaces.
	 *
	 * @param text  the text to append to
	 * @param key   the key
	 * @param value the value
	 */
	private static void appendPair(StringBuilder text, String key, String value)
	{
		text.append("- **").append(key).append("**:");
		String[] lines = value.split("\n", -1);
		if (!lines[0].isEmpty())
			text.append(' ').append(lines[0]);
		text.append('\n');
		for (int i = 1; i < lines.length; ++i)
		{
			if (!lines[i].isEmpty())
				text.append("  ").append(lines[i]);
			text.append('\n');
		}
	}

	/**
	 * Appends a turn as prose, or as a {@code description} pair if a line of it would read as a heading or a pair.
	 *
	 * @param text the text to append to
	 * @param turn the turn
	 */
	private static void appendTurn(StringBuilder text, String turn)
	{
		for (String line : turn.split("\n", -1))
		{
			if (line.startsWith("#") || line.startsWith("- **"))
			{
				appendPair(text, "description", turn);
				return;
			}
		}
		text.append(turn).append('\n');
	}
}

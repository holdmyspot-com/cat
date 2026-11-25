package com.holdmyspot.cat.common.optimizer.proposal.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Identifier;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestLocations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
		"variables", "test_cases", "workspace_files", "regression_scenarios", "expected_outcome_tool",
		"expected_outcome_judge_reason");
	private static final String FIXTURE_JUDGE_REASON = "This fixture's assertion cannot be decided " +
		"from the trial record by a built-in tool.";

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
		writeExamples(json, test, testDirectory);
		for (JsonNode workspaceFile : test.path("workspace_files"))
		{
			Path file = testDirectory.resolve("workspace").resolve(workspaceFile.path("path").asString());
			Files.createDirectories(file.getParent());
			Files.writeString(file, workspaceFile.path("content").asString(), UTF_8);
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
	 * Writes the concrete examples used by the inline proposal fixtures.
	 *
	 * @param json the JSON mapper
	 * @param test the inline prompt test
	 * @param directory the prompt test's draft directory
	 * @throws IOException if an example cannot be written
	 */
	private static void writeExamples(JsonMapper json, JsonNode test, Path directory) throws IOException
	{
		Set<String> classes = new TreeSet<>();
		for (JsonNode testCase : test.path("test_cases"))
		{
			String name = testCase.path("boundary_class").asString("");
			if (Identifier.SNAKE_CASE.matcher(name).matches())
				classes.add(name);
		}
		for (JsonNode assertion : test.path("assertions"))
		{
			String id = assertion.path("id").asString("");
			if (!Identifier.SNAKE_CASE.matcher(id).matches())
				continue;
			BuiltInTool tool;
			try
			{
				JsonNode toolNode = assertion.path("tool");
				if (toolNode.isString())
					tool = BuiltInTool.parse(toolNode.asString());
				else
					tool = null;
			}
			catch (IllegalArgumentException _)
			{
				// The proposal parser reports an invalid tool.
				continue;
			}
			if (tool != null && !tool.examinesOnlyReplies() && !assertion.path("on_tool_failure").asString().
				equals("judge"))
			{
				continue;
			}
			Path examples = directory.resolve(PromptTestFiles.EXAMPLES).resolve(id);
			FixtureReplies replies = exampleReplies(tool);
			for (String name : classes)
				writeExample(json, examples.resolve("pass_" + name), replies.passing(), replies.turn());
			writeExample(json, examples.resolve("fail_omits_answer"), replies.failing(), replies.turn());
		}
	}

	/**
	 * Returns a satisfying reply and a near miss for the tools used by the proposal fixtures.
	 *
	 * @param tool the fixture assertion's tool, or null if the judge decides it
	 * @return the two replies and the turn that the tool reads
	 */
	private static FixtureReplies exampleReplies(BuiltInTool tool)
	{
		return switch (tool)
		{
			case BuiltInTool.ExactText exact -> new FixtureReplies(exact.text(), "", exact.turn());
			case BuiltInTool.ForbiddenText forbidden -> new FixtureReplies("", forbidden.text(), forbidden.turn());
			case BuiltInTool.PatternMatch pattern -> new FixtureReplies(pattern.regex(), "", pattern.turn());
			case BuiltInTool.Count count -> new FixtureReplies("- One\n- Two\n- Three", "- One", count.turn());
			case null, default -> new FixtureReplies("The reply meets the requirement.", "", null);
		};
	}

	/**
	 * Writes one example's ordered replies.
	 *
	 * @param json the JSON mapper
	 * @param directory the example directory
	 * @param reply the reply text
	 * @param turn the turn that holds the reply, or null for the first turn
	 * @throws IOException if the reply file cannot be written
	 */
	private static void writeExample(JsonMapper json, Path directory, String reply, Integer turn) throws IOException
	{
		ArrayNode turns = json.createArrayNode();
		for (int i = 1; turn != null && i < turn; ++i)
			turns.addArray();
		turns.addArray().add(reply);
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("replies.json"), json.writeValueAsString(turns), UTF_8);
	}

	/**
	 * The replies that satisfy and fail one fixture assertion.
	 *
	 * @param passing the satisfying reply
	 * @param failing the near miss
	 * @param turn the turn that the assertion examines, or null for the first turn
	 */
	private record FixtureReplies(String passing, String failing, Integer turn)
	{
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
			if (tool == null || tool.isNull() || assertion.path("on_tool_failure").asString().equals("judge"))
			{
				// A judged assertion without the property is judged as actions; a null scope leaves the key out, as a
				// scenario recorded before the key existed does
				JsonNode scope = assertion.get("evidence_scope");
				if (scope == null)
					appendPair(text, "evidence_scope", "actions");
				else if (scope.isString())
					appendPair(text, "evidence_scope", scope.asString());
				appendPair(text, "judge_reason", assertion.path("judge_reason").asString(FIXTURE_JUDGE_REASON));
			}
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
		root.put("format_version", 2);
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
			if (testCase.has("boundary_class"))
				testCaseNode.set("boundary_class", testCase.get("boundary_class"));
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

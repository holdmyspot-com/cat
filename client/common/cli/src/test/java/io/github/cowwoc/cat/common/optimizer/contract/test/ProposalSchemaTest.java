package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.ContractIdentity;
import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.AssertionKind;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.contract.FixtureFile;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.MappedAssertion;
import io.github.cowwoc.cat.common.optimizer.contract.OnToolFailure;
import io.github.cowwoc.cat.common.optimizer.contract.PluginUnderTest;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestValue;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link ProposalSchema}.
 */
public final class ProposalSchemaTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test fixture.
	 */
	public ProposalSchemaTest()
	{
	}

	/**
	 * Verifies that an assertion with a built-in tool, and the requirement that it checks, survive writing the
	 * contract and reading it back, and that the assertion is part of the contract's identity.
	 *
	 * @throws InvalidProposalException if the stored contract is rejected
	 */
	@Test
	public void assertionsSurviveTheStoredContract() throws InvalidProposalException
	{
		// Requirement: R-C8
		// Requirement: R-C9
		ObjectNode stored = (ObjectNode) json.readTree(schema().toJson(ContractFixtures.contract()));
		ObjectNode assertion = (ObjectNode) json.readTree("""
			{"id":"avoids_disclaimer","kind":"guard","tool":"forbidden-text turn=1 \\"As an AI\\"",
			 "on_tool_failure":"judge","statement":"The list has no disclaimer.","requirement_id":"r1"}
			""");
		((ArrayNode) stored.get("tests").get(1).get("assertions")).add(assertion);

		Contract loaded = schema().parse(json.writeValueAsString(stored));

		List<MappedAssertion> assertions = loaded.findTest("plain_list").orElseThrow().assertions();
		requireThat(assertions.getLast(), "assertions.getLast()").isEqualTo(new MappedAssertion(new Assertion(
			"avoids_disclaimer", AssertionKind.GUARD, "forbidden-text turn=1 \"As an AI\"", OnToolFailure.JUDGE,
			"The list has no disclaimer."), "r1"));
		requireThat(json.readTree(schema().toJson(loaded)), "roundTrip").isEqualTo(stored);
		ProposalSchema schema = schema();
		requireThat(ContractIdentity.of(schema, loaded), "identity").
			isNotEqualTo(ContractIdentity.of(schema, ContractFixtures.contract()));
	}

	/**
	 * Verifies that an assertion whose {@code tool} line names no built-in tool, or gives a tool arguments that do
	 * not suit it, is rejected at the assertion's location.
	 */
	@Test
	public void rejectsAnInvalidToolLine()
	{
		// Requirement: R-C9
		String[] tool = {"tests", "1", "assertions", "0", "tool"};

		requireThat(String.join(" | ", rejection(text(tool, "no-such-check x")).problems()), "unknownCheck").
			contains("$.tests[1].assertions[0]").
			contains("There is no built-in tool named no-such-check");
		requireThat(String.join(" | ", rejection(text(tool, "forbidden-text")).problems()), "missingArgument").
			contains("$.tests[1].assertions[0]").
			contains("forbidden-text takes");
	}

	/**
	 * Verifies that an assertion that leaves a failure of its built-in tool to the judge but has no tool is rejected at
	 * the assertion's location.
	 */
	@Test
	public void rejectsJudgeWithoutTool()
	{
		// Requirement: R-C9
		String[] onToolFailure = {"tests", "1", "assertions", "0", "on_tool_failure"};

		requireThat(String.join(" | ", rejection(text(onToolFailure, "judge")).problems()), "problems").
			contains("$.tests[1].assertions[0]").
			contains("no tool");
	}

	/**
	 * Returns the schema reader under test.
	 *
	 * @return the reader
	 */
	private ProposalSchema schema()
	{
		return new ProposalSchema(json);
	}

	/**
	 * Returns the valid proposal with one value replaced.
	 *
	 * @param path the property names and array indexes that lead to the value's parent, ending with the property
	 *   name to replace
	 * @param replacement the new value, or null to remove the property
	 * @return the changed proposal text
	 */
	private String changed(String[] path, JsonNode replacement)
	{
		JsonNode root = json.readTree(ContractFixtures.resource("valid-proposal.json"));
		JsonNode parent = root;
		for (int i = 0; i < path.length - 1; ++i)
		{
			String step = path[i];
			if (parent instanceof ArrayNode)
				parent = parent.get(Integer.parseInt(step));
			else
				parent = parent.get(step);
		}
		String last = path[path.length - 1];
		if (parent instanceof ArrayNode array)
		{
			int index = Integer.parseInt(last);
			if (replacement == null)
				array.remove(index);
			else
				array.set(index, replacement);
		}
		else
		{
			ObjectNode object = (ObjectNode) parent;
			if (replacement == null)
				object.remove(last);
			else
				object.set(last, replacement);
		}
		return json.writeValueAsString(root);
	}

	/**
	 * Returns the valid proposal with a string replaced.
	 *
	 * @param path the location of the value
	 * @param value the new string
	 * @return the changed proposal text
	 */
	private String text(String[] path, String value)
	{
		return changed(path, json.getNodeFactory().stringNode(value));
	}

	/**
	 * Returns the valid proposal with a whole number replaced.
	 *
	 * @param path the location of the value
	 * @param value the new number
	 * @return the changed proposal text
	 */
	private String integer(String[] path, int value)
	{
		return changed(path, json.getNodeFactory().numberNode(value));
	}

	/**
	 * Returns the valid proposal with a number replaced.
	 *
	 * @param path the location of the value
	 * @param value the new number
	 * @return the changed proposal text
	 */
	private String number(String[] path, double value)
	{
		return changed(path, json.getNodeFactory().numberNode(value));
	}

	/**
	 * Parses a proposal that must be rejected.
	 *
	 * @param proposal the proposal text
	 * @return the rejection
	 * @throws AssertionError if the proposal is accepted
	 */
	private InvalidProposalException rejection(String proposal)
	{
		try
		{
			schema().parse(proposal);
		}
		catch (InvalidProposalException e)
		{
			return e;
		}
		throw new AssertionError("Proposal was accepted");
	}

	/**
	 * Verifies that the valid fixture proposal is accepted and becomes the contract that it describes, with every
	 * item that the design requires the proposal to list.
	 *
	 * @throws InvalidProposalException if the fixture proposal is rejected
	 */
	@Test
	public void parsesValidProposal() throws InvalidProposalException
	{
		// Requirement: R-E21
		// Requirement: R-C1
		Contract contract = schema().parse(ContractFixtures.resource("valid-proposal.json"));
		Contract fixture = ContractFixtures.contract();
		Contract expected = new Contract(fixture.requirements(), fixture.tests(), fixture.profiles(),
			frozenJudge(), fixture.fixtures(), fixture.limitations(), fixture.minimumPassRate(), fixture.salt());

		requireThat(contract, "contract").isEqualTo(expected);
		requireThat(contract.requirements().get(1).source(), "source").isEqualTo("rules/safety.md");
		PromptTest styled = contract.tests().get(0);
		requireThat(styled.timeLimit().toSeconds(), "timeLimitSeconds").isEqualTo(300L);
		requireThat(styled.turns().size(), "turns.size()").isEqualTo(2);
		requireThat(contract.fixtures().ignoredPaths(), "ignoredPaths").isEqualTo(List.of(".env.example"));
	}

	/**
	 * Verifies that writing the valid fixture contract and reading it back preserves its confirmed values.
	 *
	 * @throws InvalidProposalException if the written fixture contract is rejected
	 */
	@Test
	public void writtenContractParsesBackEqual() throws InvalidProposalException
	{
		// Requirement: R-C1
		Contract original = ContractFixtures.contract();

		Contract reread = schema().parse(schema().toJson(original));

		requireThat(reread, "reread").isEqualTo(original);
	}

	/**
	 * Verifies that every test lists its regression scenario citations explicitly, including an empty list.
	 */
	@Test
	public void rejectsMissingScenarioCitations()
	{
		InvalidProposalException e = rejection(changed(
			new String[]{"tests", "0", "regression_scenarios"}, null));

		requireThat(String.join(" | ", e.problems()), "problems").contains("regression_scenarios");
	}

	/**
	 * Verifies that a contract author's test may write fixture files and give a variable a generator call, which CAT
	 * completes with the generator's version and a distinct seed for each test case, and that the completed contract
	 * reads back unchanged. A call that the stored form holds without its seed is refused.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void readsFixturesAndGeneratorCalls() throws InvalidProposalException
	{
		// Requirement: R-C21
		ObjectNode proposal = (ObjectNode) json.readTree(ContractFixtures.resource("valid-proposal.json"));
		proposal.remove(List.of("salt", "trial_profiles"));
		for (JsonNode testNode : proposal.get("tests"))
			((ObjectNode) testNode).remove("committed_test_cases");
		ObjectNode test = (ObjectNode) proposal.get("tests").get(0);
		test.withArray("fixtures").addObject().put("path", "notes/{{tone}}.md").
			put("content", "{{before}}\nWrite for {{audience}} readers.\n");
		test.withArray("variables").addObject().put("name", "before").
			put("description", "Log lines before the notes.");
		for (JsonNode testCase : test.get("test_cases"))
		{
			((ArrayNode) testCase.get("values")).addObject().put("variable", "before").putObject("call").
				put("generator", "log_lines").put("count", 3);
		}
		String output = json.writeValueAsString(proposal);

		Contract contract = schema().parse(output, ContractFixtures::profiles, ContractFixtures.SALT,
			new SplittableRandom(3), Map.of(), Map.of());

		PromptTest parsed = contract.tests().getFirst();
		requireThat(parsed.fixtures(), "fixtures").isEqualTo(List.of(new FixtureFile("notes/{{tone}}.md",
			"{{before}}\nWrite for {{audience}} readers.\n")));
		Set<Long> seeds = new HashSet<>();
		for (TestCase testCase : parsed.testCases())
		{
			TestValue.Generated before = (TestValue.Generated) testCase.values().get("before");
			requireThat(before.version(), "version").isEqualTo(1);
			requireThat(before.parameters(), "parameters").isEqualTo(Map.of("count", 3L));
			seeds.add(before.seed());
		}
		requireThat(seeds.size(), "distinctSeeds").isEqualTo(parsed.testCases().size());
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);

		ObjectNode stored = (ObjectNode) json.readTree(schema().toJson(contract));
		for (JsonNode value : stored.get("tests").get(0).get("test_cases").get(0).get("values"))
		{
			if (value.get("variable").asString().equals("before"))
				((ObjectNode) value.get("call")).remove("seed");
		}
		InvalidProposalException e = rejection(json.writeValueAsString(stored));
		requireThat(String.join(" | ", e.problems()), "problems").contains("seed");
	}

	/**
	 * Verifies that CAT adds to a prompt test the fixtures of the recorded scenarios that it cites, before the fixtures
	 * that the contract author wrote, that the stored contract keeps each with the file that CAT copied it from, and
	 * that the contract author can neither write them nor write another fixture at one's path.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void addsRecordedFixtures() throws InvalidProposalException
	{
		ObjectNode proposal = (ObjectNode) json.readTree(ContractFixtures.resource("valid-proposal.json"));
		proposal.remove(List.of("salt", "trial_profiles"));
		for (JsonNode testNode : proposal.get("tests"))
			((ObjectNode) testNode).remove("committed_test_cases");
		FixtureFile written = new FixtureFile("notes/{{tone}}.md", "Write for {{audience}} readers.\n");
		((ObjectNode) proposal.get("tests").get(0)).withArray("fixtures").addObject().put("path", written.path()).
			put("content", written.content());
		FixtureFile recorded = new FixtureFile("docs/glossary.md", "A glossary.\n",
			"plugin/src/test/resources/fixtures/docs/glossary.md");
		Map<String, List<FixtureFile>> recordedByTest = Map.of("formatting", List.of(recorded));

		Contract contract = schema().parse(json.writeValueAsString(proposal), ContractFixtures::profiles,
			ContractFixtures.SALT, new SplittableRandom(3), Map.of(), recordedByTest);

		requireThat(contract.tests().getFirst().fixtures(), "fixtures").isEqualTo(List.of(recorded, written));
		requireThat(contract.tests().get(1).fixtures(), "plain_list.fixtures").isEmpty();
		JsonNode stored = json.readTree(schema().toJson(contract));
		JsonNode storedTest = stored.get("tests").get(0);
		requireThat(storedTest.get("recorded_fixtures"), "recorded_fixtures").isEqualTo(json.createArrayNode().
			add(json.createObjectNode().put("path", recorded.path()).put("content", recorded.content()).
				put("source", recorded.source())));
		requireThat(storedTest.get("fixtures").size(), "fixtures.size()").isEqualTo(1);
		requireThat(stored.get("tests").get(1).has("recorded_fixtures"), "plain_list.hasRecordedFixtures").isFalse();
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);

		ObjectNode copied = proposal.deepCopy();
		((ObjectNode) copied.get("tests").get(0)).set("recorded_fixtures", storedTest.get("recorded_fixtures"));
		requireThat(String.join(" | ", derivationRejection(copied, recordedByTest).problems()), "copied").
			contains("recorded_fixtures");

		ObjectNode colliding = proposal.deepCopy();
		((ObjectNode) colliding.get("tests").get(0)).withArray("fixtures").addObject().put("path", recorded.path()).
			put("content", "Another glossary.\n");
		requireThat(String.join(" | ", derivationRejection(colliding, recordedByTest).problems()), "colliding").
			contains("$.tests[0].fixtures[1]").contains(recorded.source());
	}

	/**
	 * Parses contract author output that must be rejected.
	 *
	 * @param output   the contract author's output
	 * @param recorded the fixtures of the cited recorded scenarios, by prompt test identifier
	 * @return the rejection
	 * @throws AssertionError if the output is accepted
	 */
	private InvalidProposalException derivationRejection(ObjectNode output, Map<String, List<FixtureFile>> recorded)
	{
		try
		{
			schema().parse(json.writeValueAsString(output), ContractFixtures::profiles, ContractFixtures.SALT,
				new SplittableRandom(3), Map.of(), recorded);
		}
		catch (InvalidProposalException e)
		{
			return e;
		}
		throw new AssertionError("Output was accepted");
	}

	/**
	 * Verifies that reading and writing a proposal preserves every cited scenario, in its original order.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	@Test
	public void preservesScenarioCitations() throws InvalidProposalException
	{
		ArrayNode citations = json.createArrayNode().add(".cat/tests/rule/reported.md").
			add(".cat/tests/rule/analogous.md");
		Contract contract = schema().parse(changed(
			new String[]{"tests", "0", "regression_scenarios"}, citations));

		JsonNode written = json.readTree(schema().toJson(contract));
		requireThat(written.get("tests").get(0).get("regression_scenarios"),
			"regressionScenarios").isEqualTo(citations);
	}

	/**
	 * Verifies that a proposal rejects a blank citation and a citation list represented by a string.
	 */
	@Test
	public void rejectsInvalidScenarioCitations()
	{
		String[] location = {"tests", "0", "regression_scenarios"};
		InvalidProposalException blank = rejection(changed(location, json.createArrayNode().add(" ")));
		InvalidProposalException scalar = rejection(text(location, ".cat/tests/rule/reported.md"));

		requireThat(String.join(" | ", blank.problems()), "blank").contains("regression_scenarios[0]");
		requireThat(String.join(" | ", scalar.problems()), "scalar").contains("regression_scenarios");
	}

	/**
	 * Verifies that the schema text names every top-level item that a proposal lists, so the contract author's prompt
	 * can state its output schema.
	 */
	@Test
	public void schemaIsJsonObjectForContractAuthor()
	{
		// Requirement: R-C1
		JsonNode schema = json.readTree(schema().schemaText());

		requireThat(schema.get("type").asString(), "type").isEqualTo("object");
		requireThat(schema.get("additionalProperties").asBoolean(), "additionalProperties").isFalse();
		Set<String> required = new HashSet<>();
		for (JsonNode name : schema.get("required"))
			required.add(name.asString());
		requireThat(required, "required").isEqualTo(
			Set.of("requirements", "tests", "trial_profiles", "judge", "fixtures", "limitations",
				"minimum_pass_rate", "salt"));
	}

	/**
	 * Returns the locations of items that a proposal must list, one per item.
	 *
	 * @return a description and the path whose removal must be rejected
	 */
	@DataProvider(name = "requiredItems")
	public Object[][] requiredItems()
	{
		return new Object[][]
			{
				{"requirements", new String[]{"requirements"}},
				{"requirement id", new String[]{"requirements", "0", "id"}},
				{"requirement description", new String[]{"requirements", "0", "description"}},
				{"requirement source", new String[]{"requirements", "0", "source"}},
				{"tests", new String[]{"tests"}},
				{"test id", new String[]{"tests", "0", "id"}},
				{"test risk rank", new String[]{"tests", "0", "risk_rank"}},
				{"test requirements", new String[]{"tests", "0", "requirement_ids"}},
				{"test applicability", new String[]{"tests", "0", "applicability"}},
				{"test turns", new String[]{"tests", "0", "turns"}},
				{"test time limit", new String[]{"tests", "0", "time_limit_seconds"}},
				{"test expected behavior", new String[]{"tests", "0", "expected_behavior"}},
				{"test uncovered interactions", new String[]{"tests", "0", "uncovered_interactions"}},
				{"test variables", new String[]{"tests", "0", "variables"}},
				{"test assertions", new String[]{"tests", "0", "assertions"}},
				{"assertion id", new String[]{"tests", "0", "assertions", "0", "id"}},
				{"assertion kind", new String[]{"tests", "0", "assertions", "0", "kind"}},
				{"assertion tool", new String[]{"tests", "0", "assertions", "0", "tool"}},
				{"assertion statement", new String[]{"tests", "0", "assertions", "0", "statement"}},
				{"assertion requirement", new String[]{"tests", "0", "assertions", "0",
					"requirement_id"}},
				{"test regression scenarios", new String[]{"tests", "0", "regression_scenarios"}},
				{"test committed test cases", new String[]{"tests", "0", "committed_test_cases"}},
				{"test test cases", new String[]{"tests", "0", "test_cases"}},
				{"variable name", new String[]{"tests", "0", "variables", "0", "name"}},
				{"variable description", new String[]{"tests", "0", "variables", "0", "description"}},
				{"test case values", new String[]{"tests", "0", "test_cases", "0", "values"}},
				{"test case value variable", new String[]{"tests", "0", "test_cases", "0", "values",
					"0", "variable"}},
				{"test case value text", new String[]{"tests", "0", "test_cases", "0", "values", "0",
					"text"}},
				{"test case expected outcome", new String[]{"tests", "0", "test_cases", "0",
					"expected_outcome"}},
				{"judge", new String[]{"judge"}},
				{"fixtures", new String[]{"fixtures"}},
				{"ignored paths", new String[]{"fixtures", "ignored_paths"}},
				{"limitations", new String[]{"limitations"}},
				{"salt", new String[]{"salt"}}
			};
	}

	/**
	 * Verifies that a proposal that omits any item that the design requires it to list is rejected and that the
	 * problem names the omitted location.
	 *
	 * @param description the omitted item
	 * @param path the location of the omitted item
	 */
	@Test(dataProvider = "requiredItems")
	public void rejectsOmittedItem(String description, String[] path)
	{
		// Requirement: R-C1
		InvalidProposalException e = rejection(changed(path, null));

		String missing = path[path.length - 1];
		requireThat(String.join(" | ", e.problems()), "problems").contains(missing);
	}

	/**
	 * Verifies that each requirement states its trigger, or {@code null} when it always applies, that each test lists
	 * the triggers it leaves absent, and that a blank or non-text trigger is rejected.
	 */
	@Test
	public void requiresTriggerAndAbsentTriggers()
	{
		// Requirement: R-D17
		String[] trigger = {"requirements", "0", "trigger"};
		String[] absent = {"tests", "0", "absent_triggers"};

		requireThat(rejection(changed(trigger, null)).problems(), "missingTrigger").
			contains("$.requirements[0].trigger: required property is missing");
		requireThat(rejection(changed(absent, null)).problems(), "missingAbsentTriggers").
			contains("$.tests[0].absent_triggers: required property is missing");
		InvalidProposalException blankTrigger = rejection(text(trigger, " "));
		requireThat(blankTrigger.problems(), "blankTrigger").
			contains("$.requirements[0].trigger: must not be blank");
		requireThat(String.join(" | ", rejection(integer(trigger, 1)).problems()), "numericTrigger").
			contains("$.requirements[0].trigger");
		requireThat(String.join(" | ", rejection(changed(absent, json.createArrayNode().add(" "))).problems()),
			"blankAbsentTrigger").contains("$.tests[0].absent_triggers[0]");
	}

	/**
	 * Verifies that each requirement lists its failure behaviors as lowercase words joined by underscores, and that a
	 * stored contract keeps them.
	 *
	 * @throws InvalidProposalException if the contract is rejected
	 */
	@Test
	public void requiresFailureBehaviors() throws InvalidProposalException
	{
		// Requirement: R-C18
		String[] behaviors = {"requirements", "0", "behaviors"};

		requireThat(rejection(changed(behaviors, null)).problems(), "missingBehaviors").
			contains("$.requirements[0].behaviors: required property is missing");
		requireThat(String.join(" | ", rejection(changed(behaviors, json.createArrayNode())).problems()),
			"emptyBehaviors").contains("$.requirements[0].behaviors");
		requireThat(String.join(" | ", rejection(changed(behaviors, json.createArrayNode().add("Wrong tone"))).
			problems()), "spacedBehavior").contains("$.requirements[0].behaviors[0]");
		requireThat(String.join(" | ", rejection(changed(behaviors, json.createArrayNode().add("other"))).
			problems()), "otherBehavior").contains("'other'");

		Contract contract = ContractFixtures.contract();
		requireThat(contract.requirements().getFirst().behaviors(), "behaviors").isEqualTo(List.of("wrong_tone"));
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
	}

	/**
	 * Verifies that a proposal spells each identifier that the contract author names, which are the requirement,
	 * behavior, test, test and variable identifiers, as lowercase words joined by underscores.
	 */
	@Test
	public void requiresSnakeCaseIdentifiers()
	{
		String[][] locations = {
			{"requirements", "0", "id"},
			{"requirements", "0", "behaviors", "0"},
			{"tests", "0", "id"},
			{"tests", "0", "id"},
			{"tests", "0", "requirement_ids", "0"},
			{"tests", "2", "variables", "0", "name"}};
		for (String[] location : locations)
		{
			StringBuilder path = new StringBuilder("$");
			for (String step : location)
			{
				if (step.chars().allMatch(Character::isDigit))
					path.append('[').append(step).append(']');
				else
					path.append('.').append(step);
			}
			String problems = String.join(" | ", rejection(changed(location,
				json.getNodeFactory().stringNode("kebab-case"))).problems());
			requireThat(problems, "problems").withContext(path, "path").contains(path + ":");
		}
	}

	/**
	 * Verifies that a stored contract keeps each requirement's trigger and each test's absent triggers.
	 *
	 * @throws InvalidProposalException if the contract is rejected
	 */
	@Test
	public void triggersRoundTripThroughContract() throws InvalidProposalException
	{
		// Requirement: R-D17
		ObjectNode root = (ObjectNode) json.readTree(ContractFixtures.resource("valid-proposal.json"));
		((ObjectNode) root.get("requirements").get(1)).put("trigger", "The user asks to delete a directory.");
		((ObjectNode) root.get("tests").get(1)).putArray("absent_triggers").add("r2");

		Contract contract = schema().parse(json.writeValueAsString(root));

		requireThat(contract.requirements().get(1).trigger(), "trigger").
			isEqualTo("The user asks to delete a directory.");
		requireThat(contract.findTest("plain_list").orElseThrow().absentTriggers(), "absentTriggers").
			isEqualTo(List.of("r2"));
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
	}

	/**
	 * Verifies that an aggregate count cannot stand in for the items: a count in place of a list and a count beside
	 * a list are both rejected.
	 */
	@Test
	public void rejectsAggregateSummaries()
	{
		// Requirement: R-C1
		InvalidProposalException countInsteadOfList = rejection(number(new String[]{"requirements"}, 12));
		InvalidProposalException countBesideList = rejection(number(new String[]{"requirement_count"}, 2));
		InvalidProposalException countInsideTest = rejection(number(new String[]{"tests", "0", "test_count"}, 2));

		requireThat(String.join(" | ", countInsteadOfList.problems()), "countInsteadOfList").contains("$.requirements");
		requireThat(String.join(" | ", countBesideList.problems()), "countBesideList").
			contains("requirement_count");
		requireThat(String.join(" | ", countInsideTest.problems()), "countInsideTest").
			contains("$.tests[0].test_count");
	}

	/**
	 * Verifies that an empty list in place of the items is rejected, because an empty proposal lists nothing.
	 */
	@Test
	public void rejectsEmptyItemLists()
	{
		// Requirement: R-C1
		ArrayNode empty = json.createArrayNode();
		String[][] locations =
			{
				new String[]{"requirements"},
				new String[]{"tests"},
				new String[]{"tests", "0", "turns"},
				new String[]{"tests", "0", "requirement_ids"},
				new String[]{"tests", "0", "variables", "0", "values"}
			};
		for (String[] location : locations)
			rejection(changed(location, empty));
	}

	/**
	 * Verifies that rejection reports two independent violations together, so the contract author can correct both.
	 */
	@Test
	public void reportsIndependentViolations()
	{
		// Requirement: R-C1
		String proposal = text(new String[]{"requirements", "0", "source"}, "");
		proposal = proposal.replaceFirst("\"risk_rank\":2", "\"risk_rank\":0");

		InvalidProposalException e = rejection(proposal);

		String problems = String.join(" | ", e.problems());
		requireThat(problems, "problems").contains("$.requirements[0].source");
		requireThat(problems, "problems").contains("$.tests[0].risk_rank");
		requireThat(e.problems().size(), "problems.size()").isEqualTo(2);
	}

	/**
	 * Verifies that text that is not a JSON object is rejected with a problem, not an unchecked exception.
	 */
	@Test
	public void rejectsNonObjectInput()
	{
		// Requirement: R-C1
		for (String text : new String[]
			{
				"", "not json", "[]", "\"proposal\"", "{\"requirements\":"
			})
			requireThat(rejection(text).problems().size(), "problems.size()").isGreaterThan(0);
	}

	/**
	 * Verifies that a test case that omits a variable's value is rejected at the test's location.
	 */
	@Test
	public void rejectsTestCaseThatOmitsAVariable()
	{
		// Requirement: R-C1
		String[] values = {"tests", "0", "test_cases", "0", "values"};
		ArrayNode toneOnly = json.createArrayNode();
		toneOnly.addObject().put("variable", "tone").put("text", "a formal");

		InvalidProposalException e = rejection(changed(values, toneOnly));

		requireThat(String.join(" | ", e.problems()), "problems").contains("$.tests[0]: ").
			contains("audience");
	}

	/**
	 * Verifies that a test that names a requirement the proposal does not list is rejected.
	 */
	@Test
	public void rejectsUnknownRequirementReference()
	{
		// Requirement: R-C1
		String[] ids = {"tests", "0", "requirement_ids"};
		ArrayNode unknown = json.createArrayNode().add("r99");

		InvalidProposalException e = rejection(changed(ids, unknown));

		requireThat(String.join(" | ", e.problems()), "problems").contains("r99");
	}

	/**
	 * Verifies that a stored contract keeps its salt, its test cases and its committed test cases, and that a test case
	 * without an expected outcome is rejected.
	 *
	 * @throws InvalidProposalException if the fixture proposal is rejected
	 */
	@Test
	public void testCasesAndSaltRoundTrip() throws InvalidProposalException
	{
		// Requirement: R-C16
		String[] committed = {"tests", "1", "committed_test_cases"};
		ArrayNode pencils = json.createArrayNode();
		ObjectNode pencil = pencils.addObject();
		pencil.putArray("values").addObject().put("variable", "kind").put("text", "pencils");
		pencil.put("expected_outcome", "three pencils");
		Contract contract = schema().parse(changed(committed, pencils));
		requireThat(contract.salt(), "salt").isEqualTo(ContractFixtures.SALT);
		PromptTest styled = contract.tests().getFirst();
		requireThat(styled.testCases(), "styled.testCases()").
			isEqualTo(ContractFixtures.contract().tests().getFirst().testCases());
		PromptTest list = contract.tests().get(1);
		requireThat(list.committedTestCases(), "list.committedTestCases()").
			isEqualTo(List.of(ContractFixtures.plainList("pencils")));
		JsonNode written = json.readTree(schema().toJson(contract));
		requireThat(written.get("salt").asLong(), "written.salt").isEqualTo(ContractFixtures.SALT);
		JsonNode tests = written.get("tests");
		requireThat(tests.get(1).get("committed_test_cases"), "written.committed_test_cases").isEqualTo(pencils);
		requireThat(schema().parse(json.writeValueAsString(written)), "reparsed").isEqualTo(contract);

		String[] outcome = {"tests", "0", "test_cases", "0", "expected_outcome"};
		InvalidProposalException e = rejection(changed(outcome, json.getNodeFactory().stringNode(" ")));
		requireThat(String.join(" | ", e.problems()), "problems").contains("expected_outcome");
	}

	/**
	 * Verifies that a turn placeholder that no variable defines is rejected, because the generator could not
	 * replace it.
	 */
	@Test
	public void rejectsUndefinedPlaceholder()
	{
		// Requirement: R-C1
		String[] turns = {"tests", "1", "turns"};
		ArrayNode placeholder = json.createArrayNode().add("List three {{things}}.");

		InvalidProposalException e = rejection(changed(turns, placeholder));

		requireThat(String.join(" | ", e.problems()), "problems").contains("things");
	}

	/**
	 * Verifies that a test that cannot be written as a regression scenario, because its turn opens a code fence that
	 * would hide the sections after it, is rejected at the test's location.
	 */
	@Test
	public void rejectsTestThatCannotBeWrittenAsScenario()
	{
		// Requirement: R-W11
		String[] turns = {"tests", "1", "turns"};
		ArrayNode fence = json.createArrayNode().add("List three {{kind}}.\n```\nKeep it short.");

		InvalidProposalException e = rejection(changed(turns, fence));

		requireThat(String.join(" | ", e.problems()), "problems").contains("$.tests[1]").contains("scenario.md");
	}

	/**
	 * Verifies that a time limit that is not a positive whole number of seconds, an unknown rank and a path that
	 * leaves the project are rejected.
	 */
	@Test
	public void rejectsInvalidScalars()
	{
		// Requirement: R-C1
		String[] timeLimit = {"tests", "0", "time_limit_seconds"};
		rejection(integer(timeLimit, 0));
		rejection(integer(timeLimit, -5));
		rejection(number(timeLimit, 1.5));
		rejection(text(timeLimit, "300"));
		String[] rank = {"tests", "0", "risk_rank"};
		rejection(integer(rank, 0));
		rejection(integer(rank, -1));
		rejection(number(rank, 1.5));
		String[] ignored = {"fixtures", "ignored_paths"};
		rejection(changed(ignored, json.createArrayNode().add("../outside")));
		rejection(changed(ignored, json.createArrayNode().add("/etc/passwd")));
		rejection(changed(ignored, json.createArrayNode().add("a").add("a")));
	}

	/**
	 * Verifies that duplicate identifiers are rejected.
	 */
	@Test
	public void rejectsDuplicateIdentifiers()
	{
		// Requirement: R-C1
		rejection(text(new String[]{"requirements", "1", "id"}, "r1"));
		rejection(text(new String[]{"tests", "1", "id"}, "formatting"));
		rejection(text(new String[]{"tests", "2", "id"}, "plain_list"));
	}

	/**
	 * Verifies that a judge profile requires a model, prompt, rubric and decision rule as non-blank text and a result
	 * schema as a JSON object, and rejects unexpected properties.
	 */
	@Test
	public void judgeProfileRequiresModelAndInstructions()
	{
		// Requirement: R-C5
		for (String field : new String[]
			{
				"model", "prompt", "rubric", "decision_rule", "result_schema"
			})
		{
			InvalidProposalException omitted = rejection(changed(new String[]{"judge", field}, null));
			requireThat(String.join(" | ", omitted.problems()), "problems").contains("$.judge");
		}
		for (String field : new String[]
			{
				"model", "prompt", "rubric", "decision_rule"
			})
			rejection(text(new String[]{"judge", field}, "  "));
		rejection(text(new String[]{"judge", "result_schema"}, "{}"));
		rejection(changed(new String[]{"judge", "unexpected"}, json.getNodeFactory().stringNode("x")));
	}

	/**
	 * Verifies that the parsed judge profile preserves its model, effort and judging instructions, and keeps the
	 * result schema as JSON text.
	 *
	 * @throws InvalidProposalException if the fixture proposal is rejected
	 */
	@Test
	public void judgeProfileCarriesFrozenFields() throws InvalidProposalException
	{
		// Requirement: R-E21
		// Requirement: R-C5
		JudgeProfile judge = schema().parse(ContractFixtures.resource("valid-proposal.json")).judge();

		requireThat(judge.profile(), "profile").isEqualTo(new TrialProfile("judge-model", "high"));
		requireThat(judge.prompt(), "prompt").isEqualTo(frozenJudge().prompt());
		requireThat(judge.rubric(), "rubric").isEqualTo("The response follows the requirement.");
		requireThat(judge.decisionRule(), "decisionRule").isEqualTo("Pass only if every rubric item holds.");
		requireThat(json.readTree(judge.resultSchema()).get("required").get(0).asString(), "required").
			isEqualTo("assertions");
	}

	/**
	 * Returns the expected judge profile with the exact prompt frozen in the proposal fixture.
	 *
	 * @return the frozen profile
	 */
	private JudgeProfile frozenJudge()
	{
		JudgeProfile judge = ContractFixtures.judge();
		String prompt = json.readTree(ContractFixtures.resource("valid-proposal.json")).path("judge").
			path("prompt").asString();
		return new JudgeProfile(judge.profile(), prompt, judge.rubric(), judge.decisionRule(), judge.resultSchema());
	}

	/**
	 * Verifies that a proposal's minimum pass rate round-trips through the stored contract, and that one outside 0.80
	 * to 0.99 or with more than two decimal places is rejected.
	 *
	 * @throws InvalidProposalException if the fixture proposal is rejected
	 */
	@Test
	public void minimumPassRateRoundTripsWithinRange() throws InvalidProposalException
	{
		// Requirement: R-S10
		String[] path = {"minimum_pass_rate"};
		Contract contract = schema().parse(number(path, 0.9));
		requireThat(contract.minimumPassRate(), "parsed").isEqualTo(new BigDecimal("0.9"));
		JsonNode written = json.readTree(schema().toJson(contract));
		requireThat(written.get("minimum_pass_rate").decimalValue(), "written").
			isEqualTo(new BigDecimal("0.9"));

		rejection(number(path, 0.79));
		rejection(number(path, 0.995));
		InvalidProposalException e = rejection(number(path, 0.955));
		requireThat(String.join(" | ", e.problems()), "problems").contains("minimumPassRate");
	}

	/**
	 * Verifies that a plugin root survives parsing and writing, and that a contract without one has none.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	// Requirement: R-W12
	@Test
	public void parsesPluginRoot() throws InvalidProposalException
	{
		String[] path = {"plugin_under_test"};
		Contract contract = schema().parse(changed(path, json.readTree("""
			{"root": "plugins/release-docs", "base": null, "mappings": [], "depends_on_cat": false,
			 "cat_evidence": "Its manifest names no dependency and its skills invoke no CAT skill."}
			""")));

		requireThat(contract.plugin(), "plugin").isEqualTo(Optional.of(new PluginUnderTest.Root(
			Path.of("plugins/release-docs"), false,
			"Its manifest names no dependency and its skills invoke no CAT skill.")));
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
		requireThat(schema().parse(ContractFixtures.resource("valid-proposal.json")).plugin(), "withoutPlugin").
			isEmpty();
	}

	/**
	 * Verifies that a built plugin survives parsing and writing with the base digest that CAT computes, and that the
	 * plugin, its mappings, its base digest and whether trials install CAT each change the contract's identity.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	// Requirement: R-W12
	@Test
	public void builtPluginIsPartOfTheIdentity() throws InvalidProposalException
	{
		String[] path = {"plugin_under_test"};
		String base = json.writeValueAsString(Path.of("/opt/cat/plugin").toAbsolutePath().toString());
		Contract contract = schema().parse(changed(path, json.readTree("""
			{"root": null, "base": %s, "depends_on_cat": true, "cat_evidence": "It is CAT.",
			 "mappings": [{"source": "client/rules/codex", "target": "rules/codex"}]}
			""".formatted(base))));
		PluginUnderTest.Built built = (PluginUnderTest.Built) contract.plugin().orElseThrow();
		requireThat(built.mappings(), "mappings").isEqualTo(List.of(new PluginUnderTest.Mapping(
			Path.of("client/rules/codex"), Path.of("rules/codex"))));
		Contract digested = with(contract, built.withBaseDigest("a".repeat(64)));

		requireThat(schema().parse(schema().toJson(digested)), "reread").isEqualTo(digested);
		ProposalSchema schema = schema();
		Set<Sha256> identities = new HashSet<>();
		identities.add(ContractIdentity.of(schema, with(contract, null)));
		identities.add(ContractIdentity.of(schema, digested));
		identities.add(ContractIdentity.of(schema, with(contract, built.withBaseDigest("b".repeat(64)))));
		identities.add(ContractIdentity.of(schema, with(contract, new PluginUnderTest.Built(built.base(),
			built.baseDigest(), List.of(new PluginUnderTest.Mapping(Path.of("client/rules"), Path.of("rules"))),
			true, "It is CAT."))));
		identities.add(ContractIdentity.of(schema, with(contract, new PluginUnderTest.Built(built.base(),
			built.baseDigest(), built.mappings(), false, "It is CAT."))));
		identities.add(ContractIdentity.of(schema, with(contract, new PluginUnderTest.Root(Path.of("plugin"), true,
			"It is CAT."))));
		requireThat(identities.size(), "identities.size()").isEqualTo(6);
	}

	/**
	 * Verifies that a plugin under test with an unknown key, with both forms, or with neither form is rejected, and
	 * that the contract author cannot supply the base digest.
	 */
	// Requirement: R-W12
	@Test
	public void rejectsInvalidPluginUnderTest()
	{
		String[] path = {"plugin_under_test"};
		rejection(changed(path, json.readTree("""
			{"root": "plugin", "base": null, "mappings": [], "depends_on_cat": false, "cat_evidence": "None.",
			 "version": "1.0"}
			""")));
		rejection(changed(path, json.readTree("""
			{"root": "plugin", "base": "/opt/plugin", "mappings": [], "depends_on_cat": false, "cat_evidence": "None."}
			""")));
		rejection(changed(path, json.readTree("""
			{"root": null, "base": null, "mappings": [], "depends_on_cat": false, "cat_evidence": "None."}
			""")));
		JsonNode plugin = schema().derivationSchema().get("properties").get("plugin_under_test");
		requireThat(plugin.get("properties").has("base_digest"), "derivationHasBaseDigest").isFalse();
	}

	/**
	 * Verifies that each kind of dependent survives parsing and writing and changes the contract's identity, and that a
	 * contract without dependents writes none.
	 *
	 * @throws InvalidProposalException if the proposal is rejected
	 */
	// Requirement: R-W10
	@Test
	public void dependentsRoundTrip() throws InvalidProposalException
	{
		Contract contract = schema().parse(changed(new String[]{"dependents"}, json.readTree("""
			[
			  {"location": "project", "path": "repair.sh", "removes": "file", "text": null, "event": null,
			   "command": null, "serves": "Repairs the output."},
			  {"location": "project", "path": "AGENTS.md", "removes": "span", "text": "Read prompt.md first.\\n",
			   "event": null, "command": null, "serves": "Routes the agent to the prompt."},
			  {"location": "project", "path": ".codex/hooks.json", "removes": "hook_registration", "text": null,
			   "event": "PostToolUse", "command": "repair.sh", "serves": "Runs the repair script."}
			]
			""")));
		Contract without = schema().parse(ContractFixtures.resource("valid-proposal.json"));

		requireThat(contract.dependents(), "dependents").isEqualTo(List.of(
			new Dependent(Dependent.Location.PROJECT, Path.of("repair.sh"), new Dependent.WholeFile(),
				"Repairs the output."),
			new Dependent(Dependent.Location.PROJECT, Path.of("AGENTS.md"), new Dependent.Span("Read prompt.md first.\n"),
				"Routes the agent to the prompt."),
			new Dependent(Dependent.Location.PROJECT, Path.of(".codex/hooks.json"),
				new Dependent.HookRegistration("PostToolUse", "repair.sh"), "Runs the repair script.")));
		requireThat(schema().parse(schema().toJson(contract)), "reread").isEqualTo(contract);
		requireThat(json.readTree(schema().toJson(without)).has("dependents"), "withoutDependents").isFalse();
		requireThat(ContractIdentity.of(schema(), contract), "identity").
			isNotEqualTo(ContractIdentity.of(schema(), without));
	}

	/**
	 * Verifies that a span without its text and a hook registration without its command are rejected, because control
	 * trials could not tell what to remove.
	 */
	// Requirement: R-W10
	@Test
	public void rejectsIncompleteDependents()
	{
		InvalidProposalException span = rejection(changed(new String[]{"dependents"}, json.readTree("""
			[{"location": "project", "path": "AGENTS.md", "removes": "span", "text": null, "event": null,
			  "command": null, "serves": "Routes the agent to the prompt."}]
			""")));
		InvalidProposalException hook = rejection(changed(new String[]{"dependents"}, json.readTree("""
			[{"location": "project", "path": "hooks.json", "removes": "hook_registration", "text": null,
			  "event": "PostToolUse", "command": null, "serves": "Runs the repair script."}]
			""")));

		requireThat(String.join(" | ", span.problems()), "spanProblems").contains("gives text");
		requireThat(String.join(" | ", hook.problems()), "hookProblems").contains("gives command");
	}

	/**
	 * Returns a contract with another plugin under test.
	 *
	 * @param contract the contract
	 * @param plugin the plugin under test, or null for none
	 * @return the contract
	 */
	private static Contract with(Contract contract, PluginUnderTest plugin)
	{
		return new Contract(contract.requirements(), contract.tests(), contract.profiles(), contract.judge(),
			contract.fixtures(), contract.limitations(), contract.minimumPassRate(), contract.salt(),
			plugin);
	}
}

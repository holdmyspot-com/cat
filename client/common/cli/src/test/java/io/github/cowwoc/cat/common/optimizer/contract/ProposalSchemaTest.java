package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
	 * Names a location in a proposal.
	 *
	 * @param steps the property names and array indexes
	 * @return the steps
	 */
	private static String[] path(String... steps)
	{
		return steps;
	}

	/**
	 * Verifies that the valid fixture proposal is accepted and becomes the contract that it describes, with every
	 * item that the design requires the proposal to list.
	 */
	@Test
	public void parsesValidProposal() throws InvalidProposalException
	{
		// Requirement: R-C1
		Contract contract = schema().parse(ContractFixtures.resource("valid-proposal.json"));

		requireThat(contract, "contract").isEqualTo(ContractFixtures.contract());
		requireThat(contract.requirements().get(1).source(), "source").isEqualTo("rules/safety.md");
		TestDefinition styled = contract.categories().get(0).tests().get(0);
		requireThat(styled.timeLimit().toSeconds(), "timeLimitSeconds").isEqualTo(300L);
		requireThat(styled.turns().size(), "turns.size()").isEqualTo(2);
		requireThat(contract.fixtures().ignoredPaths(), "ignoredPaths").isEqualTo(java.util.List.of(".env.example"));
	}

	/**
	 * Verifies that writing a contract and reading it back yields an equal contract, so a persisted contract keeps
	 * every frozen item.
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
	 * Verifies that the schema text names every top-level item that a proposal lists, so the deriver prompt can
	 * state its output schema.
	 */
	@Test
	public void schemaTextIsJsonObjectForDeriverPrompt()
	{
		// Requirement: R-C1
		JsonNode schema = json.readTree(schema().schemaText());

		requireThat(schema.get("type").asString(), "type").isEqualTo("object");
		requireThat(schema.get("additionalProperties").asBoolean(), "additionalProperties").isFalse();
		java.util.Set<String> required = new java.util.HashSet<>();
		for (JsonNode name : schema.get("required"))
			required.add(name.asString());
		requireThat(required, "required").isEqualTo(
			java.util.Set.of("requirements", "categories", "trial_profiles", "judge", "fixtures", "limitations"));
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
				{"requirements", path("requirements")},
				{"requirement id", path("requirements", "0", "id")},
				{"requirement description", path("requirements", "0", "description")},
				{"requirement source", path("requirements", "0", "source")},
				{"categories", path("categories")},
				{"category id", path("categories", "0", "id")},
				{"category rationale", path("categories", "0", "rationale")},
				{"category risk rank", path("categories", "0", "risk_rank")},
				{"category tests", path("categories", "0", "tests")},
				{"test id", path("categories", "0", "tests", "0", "id")},
				{"test requirements", path("categories", "0", "tests", "0", "requirement_ids")},
				{"test applicability", path("categories", "0", "tests", "0", "applicability")},
				{"test turns", path("categories", "0", "tests", "0", "turns")},
				{"test time limit", path("categories", "0", "tests", "0", "time_limit_seconds")},
				{"test expected behavior", path("categories", "0", "tests", "0", "expected_behavior")},
				{"test uncovered interactions", path("categories", "0", "tests", "0", "uncovered_interactions")},
				{"test variations", path("categories", "0", "tests", "0", "variations")},
				{"variation dimension", path("categories", "0", "tests", "0", "variations", "0", "dimension")},
				{"variation values", path("categories", "0", "tests", "0", "variations", "0", "values")},
				{"variation value label", path("categories", "0", "tests", "0", "variations", "0", "values", "0",
					"label")},
				{"variation value text", path("categories", "0", "tests", "0", "variations", "0", "values", "0",
					"text")},
				{"variation value probability", path("categories", "0", "tests", "0", "variations", "0", "values",
					"0", "probability")},
				{"judge", path("judge")},
				{"fixtures", path("fixtures")},
				{"ignored paths", path("fixtures", "ignored_paths")},
				{"limitations", path("limitations")}
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
	 * Verifies that an aggregate count cannot stand in for the items: a count in place of a list and a count beside
	 * a list are both rejected.
	 */
	@Test
	public void rejectsAggregateSummaries()
	{
		// Requirement: R-C1
		InvalidProposalException countInsteadOfList = rejection(number(path("requirements"), 12));
		InvalidProposalException countBesideList = rejection(number(path("requirement_count"), 2));
		InvalidProposalException countInsideCategory = rejection(number(path("categories", "0", "test_count"), 2));

		requireThat(String.join(" | ", countInsteadOfList.problems()), "countInsteadOfList").contains("$.requirements");
		requireThat(String.join(" | ", countBesideList.problems()), "countBesideList").
			contains("requirement_count");
		requireThat(String.join(" | ", countInsideCategory.problems()), "countInsideCategory").
			contains("$.categories[0].test_count");
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
				path("requirements"),
				path("categories"),
				path("categories", "0", "tests"),
				path("categories", "0", "tests", "0", "turns"),
				path("categories", "0", "tests", "0", "requirement_ids"),
				path("categories", "0", "tests", "0", "variations", "0", "values")
			};
		for (String[] location : locations)
			rejection(changed(location, empty));
	}

	/**
	 * Verifies that the rejection reports every violation, so that a deriver can fix them in one revision.
	 */
	@Test
	public void reportsEveryViolation()
	{
		// Requirement: R-C1
		String proposal = text(path("requirements", "0", "source"), "");
		proposal = proposal.replace("\"risk_rank\":2", "\"risk_rank\":0");

		InvalidProposalException e = rejection(proposal);

		String problems = String.join(" | ", e.problems());
		requireThat(problems, "problems").contains("$.requirements[0].source");
		requireThat(problems, "problems").contains("$.categories[0].risk_rank");
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
	 * Verifies that the variation probabilities of a dimension must sum to 1.
	 */
	@Test
	public void rejectsProbabilitiesThatDoNotSumToOne()
	{
		// Requirement: R-C3
		String[] probability = path("categories", "0", "tests", "0", "variations", "0", "values", "0", "probability");

		InvalidProposalException e = rejection(number(probability, 0.6));

		requireThat(String.join(" | ", e.problems()), "problems").contains("sum to 1");
	}

	/**
	 * Verifies that a probability outside (0, 1] is rejected.
	 */
	@Test
	public void rejectsProbabilityOutOfRange()
	{
		// Requirement: R-C3
		String[] probability = path("categories", "0", "tests", "0", "variations", "0", "values", "0", "probability");

		rejection(number(probability, 0.0));
		rejection(number(probability, 1.5));
		rejection(number(probability, -0.5));
	}

	/**
	 * Verifies that a test that names a requirement the proposal does not list is rejected.
	 */
	@Test
	public void rejectsUnknownRequirementReference()
	{
		// Requirement: R-C1
		String[] ids = path("categories", "0", "tests", "0", "requirement_ids");
		ArrayNode unknown = json.createArrayNode().add("r99");

		InvalidProposalException e = rejection(changed(ids, unknown));

		requireThat(String.join(" | ", e.problems()), "problems").contains("r99");
	}

	/**
	 * Verifies that a turn placeholder that no variation defines is rejected, because the generator could not
	 * replace it.
	 */
	@Test
	public void rejectsUndefinedPlaceholder()
	{
		// Requirement: R-C1
		String[] turns = path("categories", "0", "tests", "1", "turns");
		ArrayNode placeholder = json.createArrayNode().add("List three {{things}}.");

		InvalidProposalException e = rejection(changed(turns, placeholder));

		requireThat(String.join(" | ", e.problems()), "problems").contains("things");
	}

	/**
	 * Verifies that a time limit that is not a positive whole number of seconds, an unknown rank and a path that
	 * leaves the project are rejected.
	 */
	@Test
	public void rejectsInvalidScalars()
	{
		// Requirement: R-C1
		String[] timeLimit = path("categories", "0", "tests", "0", "time_limit_seconds");
		rejection(integer(timeLimit, 0));
		rejection(integer(timeLimit, -5));
		rejection(number(timeLimit, 1.5));
		rejection(text(timeLimit, "300"));
		String[] rank = path("categories", "0", "risk_rank");
		rejection(integer(rank, 0));
		rejection(integer(rank, -1));
		rejection(number(rank, 1.5));
		String[] ignored = path("fixtures", "ignored_paths");
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
		rejection(text(path("requirements", "1", "id"), "r1"));
		rejection(text(path("categories", "1", "id"), "formatting"));
		rejection(text(path("categories", "1", "tests", "0", "id"), "plain-list"));
	}

	/**
	 * Verifies that the judge profile of a proposal must name its model, prompt, rubric, decision rule and result
	 * schema, and that none may be blank.
	 */
	@Test
	public void judgeProfileRequiresEveryField()
	{
		// Requirement: R-C5
		for (String field : new String[]
			{
				"model", "prompt", "rubric", "decision_rule", "result_schema"
			})
		{
			InvalidProposalException omitted = rejection(changed(path("judge", field), null));
			requireThat(String.join(" | ", omitted.problems()), "problems").contains("$.judge");
		}
		for (String field : new String[]
			{
				"model", "prompt", "rubric", "decision_rule"
			})
			rejection(text(path("judge", field), "  "));
		rejection(text(path("judge", "result_schema"), "{}"));
		rejection(changed(path("judge", "unexpected"), json.getNodeFactory().stringNode("x")));
	}

	/**
	 * Verifies that the parsed judge profile carries the five frozen fields, and that the result schema is kept as
	 * JSON text.
	 */
	@Test
	public void judgeProfileCarriesFrozenFields() throws InvalidProposalException
	{
		// Requirement: R-C5
		JudgeProfile judge = schema().parse(ContractFixtures.resource("valid-proposal.json")).judge();

		requireThat(judge.profile(), "profile").isEqualTo(new TrialProfile("judge-model", "high"));
		requireThat(judge.prompt(), "prompt").isEqualTo("Judge the trial.");
		requireThat(judge.rubric(), "rubric").isEqualTo("The response follows the requirement.");
		requireThat(judge.decisionRule(), "decisionRule").isEqualTo("Pass only if every rubric item holds.");
		requireThat(json.readTree(judge.resultSchema()).get("required").get(0).asString(), "required").
			isEqualTo("verdict");
	}
}

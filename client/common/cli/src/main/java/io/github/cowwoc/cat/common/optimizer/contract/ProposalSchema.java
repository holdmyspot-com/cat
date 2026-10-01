package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON form of a proposed contract, and the validation of deriver output against it.
 *
 * <p>
 * The shape of the proposal is defined once, in {@code optimizer/contract/proposal.schema.json}. Validation first
 * checks the shape, which rejects a missing item, an unknown item and an aggregate count in place of a list. It then
 * builds the contract, whose constructors enforce the rules that a schema cannot express, such as probabilities that
 * sum to 1 and references that name a listed requirement.
 */
public final class ProposalSchema
{
	private static final String SCHEMA_RESOURCE = "/optimizer/contract/proposal.schema.json";
	private final JsonMapper json;
	private final String schemaText;
	private final SchemaValidator validator;

	/**
	 * Creates a schema reader.
	 *
	 * @param json the operation-owned JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 * @throws UncheckedIOException if the packaged schema cannot be read
	 */
	public ProposalSchema(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
		try (InputStream in = ProposalSchema.class.getResourceAsStream(SCHEMA_RESOURCE))
		{
			if (in == null)
				throw new IOException("The packaged resource " + SCHEMA_RESOURCE + " is missing");
			schemaText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		validator = new SchemaValidator(json.readTree(schemaText));
	}

	/**
	 * Returns the JSON schema text for the deriver prompt.
	 *
	 * @return the schema
	 */
	public String schemaText()
	{
		return schemaText;
	}

	/**
	 * Validates deriver output and converts it to a contract.
	 *
	 * @param proposal the deriver's JSON output
	 * @return the contract that the proposal describes
	 * @throws NullPointerException if {@code proposal} is null
	 * @throws InvalidProposalException if {@code proposal} is not valid JSON, violates the schema or breaks a rule
	 *   that the schema cannot express
	 */
	public Contract parse(String proposal) throws InvalidProposalException
	{
		requireThat(proposal, "proposal").isNotNull();
		JsonNode root;
		try
		{
			root = json.readTree(proposal);
		}
		catch (JacksonException e)
		{
			throw new InvalidProposalException(List.of("$: not valid JSON: " + e.getOriginalMessage()));
		}
		if (root == null || root.isMissingNode())
			throw new InvalidProposalException(List.of("$: not valid JSON: the proposal is empty"));

		List<String> violations = validator.violations(root);
		if (!violations.isEmpty())
			throw new InvalidProposalException(violations);

		List<String> problems = new ArrayList<>();
		Contract contract = new Builder(problems).contract(root);
		if (contract == null)
			throw new InvalidProposalException(problems);
		return contract;
	}

	/**
	 * Writes a contract in the proposal schema.
	 *
	 * @param contract the contract
	 * @return the JSON text, which {@link #parse(String)} converts back to an equal contract
	 * @throws NullPointerException if {@code contract} is null
	 */
	public String toJson(Contract contract)
	{
		requireThat(contract, "contract").isNotNull();
		ObjectNode root = json.createObjectNode();
		ArrayNode requirements = root.putArray("requirements");
		for (Requirement requirement : contract.requirements())
		{
			ObjectNode node = requirements.addObject();
			node.put("id", requirement.id());
			node.put("description", requirement.description());
			node.put("source", requirement.source());
		}
		ArrayNode categories = root.putArray("categories");
		for (Category category : contract.categories())
		{
			ObjectNode node = categories.addObject();
			node.put("id", category.id());
			node.put("rationale", category.rationale());
			node.put("risk_rank", category.riskRank().position());
			ArrayNode tests = node.putArray("tests");
			for (TestDefinition test : category.tests())
				write(test, tests.addObject());
		}
		JudgeProfile judge = contract.judge();
		ObjectNode judgeNode = root.putObject("judge");
		judgeNode.put("model", judge.model());
		judgeNode.put("prompt", judge.prompt());
		judgeNode.put("rubric", judge.rubric());
		judgeNode.put("decision_rule", judge.decisionRule());
		judgeNode.set("result_schema", json.readTree(judge.resultSchema()));
		ArrayNode ignoredPaths = root.putObject("fixtures").putArray("ignored_paths");
		contract.fixtures().ignoredPaths().forEach(ignoredPaths::add);
		ArrayNode limitations = root.putArray("limitations");
		contract.limitations().forEach(limitations::add);
		return json.writeValueAsString(root);
	}

	/**
	 * Writes one test definition.
	 *
	 * @param test the test definition
	 * @param node the object that receives its properties
	 */
	private static void write(TestDefinition test, ObjectNode node)
	{
		node.put("id", test.id());
		ArrayNode requirementIds = node.putArray("requirement_ids");
		test.requirementIds().forEach(requirementIds::add);
		node.put("applicability", test.applicability());
		ArrayNode turns = node.putArray("turns");
		test.turns().forEach(turns::add);
		node.put("time_limit_seconds", test.timeLimit().toSeconds());
		node.put("expected_behavior", test.expectedBehaviour());
		ArrayNode uncovered = node.putArray("uncovered_interactions");
		test.uncoveredInteractions().forEach(uncovered::add);
		ArrayNode variations = node.putArray("variations");
		for (VariationRule rule : test.variations())
		{
			ObjectNode ruleNode = variations.addObject();
			ruleNode.put("dimension", rule.dimension());
			ArrayNode values = ruleNode.putArray("values");
			for (VariationValue value : rule.values())
			{
				ObjectNode valueNode = values.addObject();
				valueNode.put("label", value.label());
				valueNode.put("text", value.text());
				valueNode.put("probability", value.probability());
			}
		}
	}

	/**
	 * Builds the contract from a proposal that already satisfies the schema, and reports every rule that the model's
	 * constructors reject.
	 *
	 * <p>
	 * A node whose child failed is not built, so a failure is reported once, at the innermost object that is wrong.
	 * A builder method returns null after it adds a problem.
	 */
	private static final class Builder
	{
		private final List<String> problems;

		/**
		 * Creates a builder.
		 *
		 * @param problems receives one message per rejected object
		 */
		Builder(List<String> problems)
		{
			this.problems = problems;
		}

		/**
		 * Builds the contract.
		 *
		 * @param root the proposal
		 * @return the contract, or null if a problem was added
		 */
		Contract contract(JsonNode root)
		{
			int problemsBefore = problems.size();
			List<Requirement> requirements = list(root.get("requirements"), "$.requirements", (node, location) ->
				attempt(location, () -> new Requirement(text(node, "id"), text(node, "description"),
					text(node, "source"))));
			List<Category> categories = list(root.get("categories"), "$.categories", this::category);
			JsonNode judgeNode = root.get("judge");
			JudgeProfile judge = attempt("$.judge", () -> new JudgeProfile(text(judgeNode, "model"),
				text(judgeNode, "prompt"), text(judgeNode, "rubric"), text(judgeNode, "decision_rule"),
				judgeNode.get("result_schema").toString()));
			TrialFixtures fixtures = attempt("$.fixtures", () -> new TrialFixtures(
				strings(root.get("fixtures").get("ignored_paths"))));
			if (problems.size() > problemsBefore)
				return null;
			return attempt("$", () -> new Contract(requirements, categories, judge, fixtures,
				strings(root.get("limitations"))));
		}

		/**
		 * Builds one category and its tests.
		 *
		 * @param node the category
		 * @param location the location of the category
		 * @return the category, or null if a problem was added
		 */
		private Category category(JsonNode node, String location)
		{
			int problemsBefore = problems.size();
			List<TestDefinition> tests = list(node.get("tests"), location + ".tests", this::test);
			if (problems.size() > problemsBefore)
				return null;
			return attempt(location, () -> new Category(text(node, "id"), text(node, "rationale"),
				new RiskRank(node.get("risk_rank").asInt()), tests));
		}

		/**
		 * Builds one test definition and its variation rules.
		 *
		 * @param node the test definition
		 * @param location the location of the test definition
		 * @return the test definition, or null if a problem was added
		 */
		private TestDefinition test(JsonNode node, String location)
		{
			int problemsBefore = problems.size();
			List<VariationRule> variations = list(node.get("variations"), location + ".variations", this::variation);
			if (problems.size() > problemsBefore)
				return null;
			return attempt(location, () -> new TestDefinition(text(node, "id"), strings(node.get("requirement_ids")),
				text(node, "applicability"), strings(node.get("turns")),
				Duration.ofSeconds(node.get("time_limit_seconds").asLong()), text(node, "expected_behavior"),
				strings(node.get("uncovered_interactions")), variations));
		}

		/**
		 * Builds one variation rule and its values.
		 *
		 * @param node the variation rule
		 * @param location the location of the variation rule
		 * @return the variation rule, or null if a problem was added
		 */
		private VariationRule variation(JsonNode node, String location)
		{
			int problemsBefore = problems.size();
			List<VariationValue> values = list(node.get("values"), location + ".values", (valueNode, valueLocation) ->
				attempt(valueLocation, () -> new VariationValue(text(valueNode, "label"), text(valueNode, "text"),
					valueNode.get("probability").asDouble())));
			if (problems.size() > problemsBefore)
				return null;
			return attempt(location, () -> new VariationRule(text(node, "dimension"), values));
		}

		/**
		 * Builds every item of an array, skipping an item that cannot be built after reporting why.
		 *
		 * @param <T> the type of item
		 * @param array the array
		 * @param location the location of the array
		 * @param builder builds one item from its node and location
		 * @return the items that could be built
		 */
		private <T> List<T> list(JsonNode array, String location, ItemBuilder<T> builder)
		{
			List<T> items = new ArrayList<>();
			for (int i = 0; i < array.size(); ++i)
			{
				T item = builder.build(array.get(i), location + "[" + i + "]");
				if (item != null)
					items.add(item);
			}
			return items;
		}

		/**
		 * Runs a constructor and reports its rejection.
		 *
		 * @param <T> the type of object
		 * @param location the location of the object
		 * @param construction the constructor call
		 * @return the object, or null if the constructor rejected its arguments
		 */
		private <T> T attempt(String location, Supplier<T> construction)
		{
			try
			{
				return construction.get();
			}
			catch (IllegalArgumentException e)
			{
				problems.add(location + ": " + e.getMessage());
				return null;
			}
		}

		/**
		 * Reads a string property.
		 *
		 * @param node the object
		 * @param name the property name
		 * @return the string value
		 */
		private static String text(JsonNode node, String name)
		{
			return node.get(name).asString();
		}

		/**
		 * Reads an array of strings.
		 *
		 * @param array the array
		 * @return its items
		 */
		private static List<String> strings(JsonNode array)
		{
			return array.valueStream().map((Function<JsonNode, String>) JsonNode::asString).toList();
		}
	}

	/**
	 * Builds one item of an array.
	 *
	 * @param <T> the type of item
	 */
	@FunctionalInterface
	private interface ItemBuilder<T>
	{
		/**
		 * Builds an item.
		 *
		 * @param node the item's JSON
		 * @param location the location of the item
		 * @return the item, or null if a problem was added
		 */
		T build(JsonNode node, String location);
	}
}

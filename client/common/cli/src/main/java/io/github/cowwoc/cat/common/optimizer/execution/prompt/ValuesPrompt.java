package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.FailureGroup;
import io.github.cowwoc.cat.common.optimizer.campaign.ReproductionRound;
import io.github.cowwoc.cat.common.optimizer.campaign.TriedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.AddedValues;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.SchemaValidator;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.VariationRule;
import io.github.cowwoc.cat.common.optimizer.contract.VariationValue;
import io.github.cowwoc.cat.common.optimizer.execution.ValuesProtocol;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.proposal.JsonResponse;
import io.github.cowwoc.cat.common.optimizer.proposal.PromptTemplate;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds requests for deriver sessions that write the key-value combinations of a formal failure group's reproduction
 * round, and validates the combinations that they write.
 * <p>
 * <b>Thread Safety:</b> Callers may build requests and validate combinations concurrently.
 */
public final class ValuesPrompt implements ValuesProtocol
{
	private static final Duration TIME_LIMIT = Duration.ofMinutes(30);
	private static final String PROMPT_RESOURCE = "/optimizer/values/values.md";
	private static final String SCHEMA_RESOURCE = "/optimizer/values/values.schema.json";
	private final JsonMapper json;
	private final ProposalSchema schema;
	private final Path directory;
	private final JsonResponse responses;
	private final PromptTemplate prompt;
	private final String outputSchema;
	private final SchemaValidator validator;

	/**
	 * Creates the values prompt.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema writes the confirmed tests as JSON for the deriver
	 * @param directory the existing directory that deriver sessions work in; it must contain no prompt files, so those
	 *   sessions do not load the candidate's instructions
	 * @throws NullPointerException if any of the arguments are null
	 * @throws UncheckedIOException if the packaged prompt or output schema cannot be read
	 */
	public ValuesPrompt(JsonMapper json, ProposalSchema schema, Path directory)
	{
		requireThat(json, "json").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(directory, "directory").isNotNull();
		this.json = json;
		this.schema = schema;
		this.directory = directory;
		this.responses = new JsonResponse(json);
		this.prompt = PromptTemplate.load(PROMPT_RESOURCE);
		JsonNode schemaNode = json.readTree(PromptTemplate.load(SCHEMA_RESOURCE).text());
		this.outputSchema = json.writerWithDefaultPrettyPrinter().writeValueAsString(schemaNode);
		this.validator = new SchemaValidator(schemaNode);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The prompt gives the group's category, its test's definition without the values that only held-out instances
	 * contain, the other combinations known for the test, the group's cell, requirement, behavior and formal failure
	 * count, and each earlier round of the group with its cell and each combination's reproduction counts. The session
	 * reads nothing else.
	 */
	@Override
	public SessionRequest request(Contract contract, AddedValues known, FailureGroup group)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(known, "known").isNotNull();
		requireThat(group, "group").isNotNull();
		String testId = group.testId();
		Category category = categoryOf(contract, testId);
		TestDefinition test = contract.findTest(testId).orElseThrow();
		if (contract.findCell(group.cellId()).isEmpty())
			throw new IllegalArgumentException("The contract has no cell '" + group.cellId() + "'");
		String requirementId = group.failed().requirementId();
		Requirement requirement = contract.requirements().stream().
			filter(candidate -> candidate.id().equals(requirementId)).findFirst().
			orElseThrow(() -> new IllegalArgumentException("The contract has no requirement '" + requirementId + "'"));
		ObjectNode development = DevelopmentContract.toJson(json, schema, contract);
		List<VariationRule> keyRules = test.keyRules();

		ObjectNode entry = json.createObjectNode();
		entry.put("category", category.id());
		entry.set("test", testNode(development, testId));
		Set<AddedCombination> tried = new HashSet<>(group.combinations());
		ArrayNode others = entry.putArray("other_key_combinations");
		for (AddedCombination combination : known.of(testId))
		{
			if (!tried.contains(combination))
				others.add(combinationNode(keyRules, combination));
		}
		entry.put("cell", group.cellId());
		ObjectNode requirementNode = entry.putObject("requirement").
			put("id", requirement.id()).
			put("description", requirement.description());
		ArrayNode behaviors = requirementNode.putArray("behaviors");
		requirement.behaviors().forEach(behaviors::add);
		entry.put("behavior", group.failed().behavior());
		entry.put("formal_failures", group.formalFailures());
		ArrayNode rounds = entry.putArray("tried_rounds");
		for (ReproductionRound round : group.rounds())
		{
			if (round.awaitsValues())
				continue;
			ObjectNode roundNode = rounds.addObject();
			roundNode.put("cell", round.cellId());
			ArrayNode combinations = roundNode.putArray("combinations");
			for (TriedCombination combination : round.combinations())
			{
				ObjectNode combinationNode = combinations.addObject();
				combinationNode.set("values", combinationNode(keyRules, combination.combination()));
				combinationNode.put("trials", combination.trials());
				combinationNode.put("failures", combination.failures());
			}
		}
		String turn = prompt.fill(Map.of(
			"failure_group", json.writerWithDefaultPrettyPrinter().writeValueAsString(entry),
			"output_schema", outputSchema));
		return new SessionRequest(SessionRole.DERIVER, Part.DEVELOPMENT, directory, List.of(turn), List.of(), TIME_LIMIT,
			contract.judge().profile());
	}

	/**
	 * Returns the JSON of a combination: each key dimension's value with its label and text.
	 *
	 * @param keyRules the test's key dimensions
	 * @param combination the combination
	 * @return the JSON
	 */
	private ObjectNode combinationNode(List<VariationRule> keyRules, AddedCombination combination)
	{
		ObjectNode node = json.createObjectNode();
		for (int i = 0; i < keyRules.size(); ++i)
		{
			VariationValue value = combination.values().get(i);
			node.putObject(keyRules.get(i).dimension()).
				put("label", value.label()).
				put("text", value.text());
		}
		return node;
	}

	/**
	 * Returns the category that holds a test.
	 *
	 * @param contract the confirmed contract
	 * @param testId the test's identifier
	 * @return the category
	 * @throws IllegalArgumentException if the contract has no such test
	 */
	private static Category categoryOf(Contract contract, String testId)
	{
		for (Category category : contract.categories())
		{
			for (TestDefinition test : category.tests())
			{
				if (test.id().equals(testId))
					return category;
			}
		}
		throw new IllegalArgumentException("The contract has no test '" + testId + "'");
	}

	/**
	 * Returns a test's JSON.
	 *
	 * @param contract the contract's JSON
	 * @param testId the test's identifier
	 * @return the test's JSON
	 */
	private static JsonNode testNode(ObjectNode contract, String testId)
	{
		for (JsonNode category : contract.get("categories"))
		{
			for (JsonNode test : category.get("tests"))
			{
				if (test.get("id").asString().equals(testId))
					return test;
			}
		}
		throw new AssertionError("The contract's JSON has no test '" + testId + "'");
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * A value equals an existing value of its dimension when its label or its text equals the label or the text of any
	 * value that the test's dimension holds, or of any known combination's value, ignoring case and surrounding white
	 * space. The rejection names the offending value but never the part of the test that holds the existing value.
	 * Each value's probability is its share of the round's combinations.
	 */
	@Override
	public List<AddedCombination> combinations(SessionResult.Completed result, Contract contract, AddedValues known,
		int attempt, FailureGroup group) throws InvalidProposalException
	{
		requireThat(result, "result").isNotNull();
		requireThat(contract, "contract").isNotNull();
		requireThat(known, "known").isNotNull();
		requireThat(group, "group").isNotNull();
		Optional<ObjectNode> output = responses.parseFinalResponse(result);
		if (output.isEmpty())
		{
			throw new InvalidProposalException(List.of("The final response is not exactly one JSON object, bare or " +
				"alone inside one Markdown code fence"));
		}
		List<String> problems = validator.violations(output.get());
		if (!problems.isEmpty())
			throw new InvalidProposalException(problems);

		TestDefinition test = contract.findTest(group.testId()).orElseThrow(() ->
			new IllegalArgumentException("The contract has no test '" + group.testId() + "'"));
		List<List<VariationValue>> parsed = parseCombinations(output.get(), test, known, problems);
		if (!problems.isEmpty())
			throw new InvalidProposalException(problems);

		double probability = 1.0 / parsed.size();
		List<AddedCombination> combinations = new ArrayList<>();
		for (List<VariationValue> values : parsed)
		{
			combinations.add(new AddedCombination(attempt, test.id(), values.stream().
				map(value -> new VariationValue(value.label(), value.text(), probability)).toList()));
		}
		return combinations;
	}

	/**
	 * Reads the combinations of the response.
	 *
	 * @param node the response
	 * @param test the test definition
	 * @param known the combinations of the contract's tests beyond the confirmed values
	 * @param problems receives each problem found
	 * @return the value of each key dimension of each combination, in the test's key-dimension order; the values'
	 *   probabilities are unspecified
	 */
	private static List<List<VariationValue>> parseCombinations(JsonNode node, TestDefinition test,
		AddedValues known, List<String> problems)
	{
		String location = "$";
		List<VariationRule> keyRules = test.keyRules();
		Map<String, Integer> keyIndex = new HashMap<>();
		Map<String, Set<String>> existing = new HashMap<>();
		for (int i = 0; i < keyRules.size(); ++i)
		{
			VariationRule rule = keyRules.get(i);
			keyIndex.put(rule.dimension(), i);
			Set<String> names = new HashSet<>();
			for (VariationValue value : rule.values())
			{
				names.add(normalize(value.label()));
				names.add(normalize(value.text()));
			}
			for (AddedCombination combination : known.of(test.id()))
			{
				VariationValue value = combination.values().get(i);
				names.add(normalize(value.label()));
				names.add(normalize(value.text()));
			}
			existing.put(rule.dimension(), names);
		}

		// The values of each dimension in this response, by label
		Map<String, Map<String, VariationValue>> newValues = new HashMap<>();
		for (VariationRule rule : keyRules)
			newValues.put(rule.dimension(), new HashMap<>());
		Set<List<String>> seen = new HashSet<>();
		List<List<VariationValue>> combinations = new ArrayList<>();
		JsonNode combinationNodes = node.get("combinations");
		for (int i = 0; i < combinationNodes.size(); ++i)
		{
			String combinationLocation = location + ".combinations[" + i + "]";
			VariationValue[] values = new VariationValue[keyRules.size()];
			boolean valid = true;
			JsonNode valueNodes = combinationNodes.get(i);
			for (int j = 0; j < valueNodes.size(); ++j)
			{
				String valueLocation = combinationLocation + "[" + j + "]";
				JsonNode valueNode = valueNodes.get(j);
				String dimension = valueNode.get("dimension").asString();
				VariationValue value = new VariationValue(valueNode.get("label").asString(),
					valueNode.get("text").asString(), 1.0);
				Integer index = keyIndex.get(dimension);
				if (index == null)
				{
					problems.add(valueLocation + ".dimension: '" + dimension + "' is not a key dimension of test '" +
						test.id() + "'");
					valid = false;
				}
				else if (values[index] != null)
				{
					problems.add(valueLocation + ".dimension: gives dimension '" + dimension + "' a second value");
					valid = false;
				}
				else if (!isNew(value, dimension, valueLocation, existing.get(dimension), newValues.get(dimension),
					problems))
				{
					valid = false;
				}
				else
					values[index] = value;
			}
			for (int j = 0; j < values.length; ++j)
			{
				if (values[j] == null && valid)
				{
					problems.add(combinationLocation + ": gives no value for key dimension '" +
						keyRules.get(j).dimension() + "'");
					valid = false;
				}
			}
			if (!valid)
				continue;
			List<VariationValue> combination = List.of(values);
			if (!seen.add(combination.stream().map(VariationValue::label).toList()))
			{
				problems.add(combinationLocation + ": repeats an earlier combination");
				continue;
			}
			combinations.add(combination);
		}
		if (combinations.size() < MINIMUM_COMBINATIONS && combinationNodes.size() >= MINIMUM_COMBINATIONS)
		{
			problems.add(location + ".combinations: must list at least " + MINIMUM_COMBINATIONS +
				" distinct combinations but lists " + combinations.size());
		}
		return combinations;
	}

	/**
	 * Checks that a value in the response is new to its dimension and consistent with the other values of the
	 * response, and records it.
	 *
	 * @param value the value, with an unspecified probability
	 * @param dimension the value's dimension
	 * @param location the location of the value
	 * @param existing the normalized labels and texts of the values that the dimension holds or that known
	 *   combinations give it
	 * @param written the values of the dimension that the response wrote before, by label
	 * @param problems receives each problem found
	 * @return {@code true} if the value is valid
	 */
	private static boolean isNew(VariationValue value, String dimension, String location, Set<String> existing,
		Map<String, VariationValue> written, List<String> problems)
	{
		VariationValue earlier = written.get(value.label());
		if (earlier != null)
		{
			if (earlier.text().equals(value.text()))
				return true;
			problems.add(location + ": gives label '" + value.label() + "' of dimension '" + dimension +
				"' a second text");
			return false;
		}
		String label = normalize(value.label());
		String text = normalize(value.text());
		if (existing.contains(label) || existing.contains(text))
		{
			problems.add(location + ": the value '" + value.label() + "' equals an existing value of dimension '" +
				dimension + "'");
			return false;
		}
		for (VariationValue other : written.values())
		{
			if (normalize(other.label()).equals(label) || normalize(other.text()).equals(text))
			{
				problems.add(location + ": the value '" + value.label() + "' equals the value '" + other.label() +
					"' that the response gave dimension '" + dimension + "' with another label or text");
				return false;
			}
		}
		written.put(value.label(), value);
		return true;
	}

	/**
	 * Returns the form under which two labels or texts are equal.
	 *
	 * @param text a label or text
	 * @return the text without surrounding white space, in lower case
	 */
	private static String normalize(String text)
	{
		return text.strip().toLowerCase(Locale.ROOT);
	}
}

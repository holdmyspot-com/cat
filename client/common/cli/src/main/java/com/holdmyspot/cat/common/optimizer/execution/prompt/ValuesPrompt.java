package com.holdmyspot.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.FailureGroup;
import com.holdmyspot.cat.common.optimizer.campaign.ReproductionRound;
import com.holdmyspot.cat.common.optimizer.campaign.TriedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCases;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.SchemaValidator;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.Split;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.contract.TestValueJson;
import com.holdmyspot.cat.common.optimizer.contract.ValueGenerators;
import com.holdmyspot.cat.common.optimizer.contract.VariableDeclaration;
import com.holdmyspot.cat.common.optimizer.execution.ValuesProtocol;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.proposal.JsonResponse;
import com.holdmyspot.cat.common.optimizer.proposal.PromptTemplate;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds requests for contract author sessions that write the test cases of a formal failure group's reproduction
 * round, and validates the test cases that they write.
 * <p>
 * <b>Thread Safety:</b> Callers may build requests and validate test cases concurrently.
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
	private final RandomGenerator seeds;

	/**
	 * Creates the values prompt.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema writes the confirmed tests as JSON for the contract author
	 * @param directory the existing directory that contract author sessions work in; it must contain no prompt files,
	 *   so those sessions do not load the candidate's instructions
	 * @param seeds the source of the seeds that CAT adds to each generator call; this prompt draws from it only
	 *   while holding its lock
	 * @throws NullPointerException if any of the arguments are null
	 * @throws UncheckedIOException if the packaged prompt or output schema cannot be read
	 */
	public ValuesPrompt(JsonMapper json, ProposalSchema schema, Path directory, RandomGenerator seeds)
	{
		requireThat(json, "json").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(seeds, "seeds").isNotNull();
		this.json = json;
		this.seeds = seeds;
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
	 * The prompt gives the definition of the group's test with its development test cases only, the other test cases
	 * added to the test, the group's cell, failed assertion with the requirement that it checks and the behaviors
	 * through which it can fail, the group's behavior and formal failure count, and each earlier round of the group
	 * with its cell and each test case's reproduction counts. The session reads nothing else.
	 */
	@Override
	public SessionRequest request(Contract contract, AddedTestCases known, FailureGroup group)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(known, "known").isNotNull();
		requireThat(group, "group").isNotNull();
		String testId = group.testId();
		PromptTest test = contract.findTest(testId).orElseThrow(() -> new IllegalArgumentException(
			"The contract has no test '" + testId + "'"));
		if (contract.findCell(group.cellId()).isEmpty())
			throw new IllegalArgumentException("The contract has no cell '" + group.cellId() + "'");
		String assertionId = group.failed().assertionId();
		Assertion assertion = test.allAssertions().stream().
			filter(candidate -> candidate.id().equals(assertionId)).findFirst().
			orElseThrow(() -> new IllegalArgumentException("The test '" + testId + "' has no assertion '" +
				assertionId + "'"));
		ObjectNode development = DevelopmentContract.toJson(json, schema, contract);

		ObjectNode entry = json.createObjectNode();
		entry.set("test", testNode(development, testId));
		Set<AddedTestCase> tried = new HashSet<>(group.testCases());
		ArrayNode others = entry.putArray("other_test_cases");
		for (AddedTestCase testCase : known.of(testId))
		{
			if (!tried.contains(testCase))
				TestCaseJson.write(testCase.testCase(), others.addObject());
		}
		entry.put("cell", group.cellId());
		writeAssertion(contract, test, assertion, entry.putObject("assertion"));
		entry.put("behavior", group.failed().behavior());
		entry.put("formal_failures", group.formalFailures());
		ArrayNode rounds = entry.putArray("tried_rounds");
		for (ReproductionRound round : group.rounds())
		{
			if (round.awaitsValues())
				continue;
			ObjectNode roundNode = rounds.addObject();
			roundNode.put("cell", round.cellId());
			ArrayNode testCases = roundNode.putArray("test_cases");
			for (TriedTestCase testCase : round.testCases())
			{
				ObjectNode testCaseNode = testCases.addObject();
				TestCaseJson.write(testCase.testCase().testCase(), testCaseNode);
				testCaseNode.put("trials", testCase.trials());
				testCaseNode.put("failures", testCase.failures());
			}
		}
		String turn = prompt.fill(Map.of(
			"failure_group", json.writerWithDefaultPrettyPrinter().writeValueAsString(entry),
			"output_schema", outputSchema,
			"generators", ValueGenerators.describeAll()));
		return new SessionRequest(SessionRole.CONTRACT_AUTHOR, Part.DEVELOPMENT, directory, List.of(turn), List.of(),
			TIME_LIMIT, contract.judge().profile());
	}

	/**
	 * Writes a failed assertion: its identifier, its statement, the requirement that it checks and the behaviors
	 * through which a trial can fail it besides {@value Requirement#OTHER_BEHAVIOR}.
	 *
	 * @param contract the contract
	 * @param test the test that declares the assertion
	 * @param assertion the assertion
	 * @param node the object to write to
	 * @throws IllegalArgumentException if the contract does not list the requirement that the assertion checks
	 */
	private void writeAssertion(Contract contract, PromptTest test, Assertion assertion, ObjectNode node)
	{
		node.put("id", assertion.id());
		node.put("statement", assertion.statement());
		ArrayNode behaviors = json.createArrayNode();
		Optional<String> requirementId = test.requirementId(assertion.id());
		if (requirementId.isEmpty())
		{
			node.putNull("requirement");
			behaviors.add(PromptTest.UNEXPECTED_OUTCOME);
		}
		else
		{
			Requirement requirement = contract.requirements().stream().
				filter(candidate -> candidate.id().equals(requirementId.get())).findFirst().
				orElseThrow(() -> new IllegalArgumentException("The contract has no requirement '" +
					requirementId.get() + "'"));
			node.putObject("requirement").
				put("id", requirement.id()).
				put("description", requirement.description());
			if (assertion.tool() == null)
				requirement.behaviors().forEach(behaviors::add);
			else
				behaviors.add(assertion.builtInTool().orElseThrow().behavior());
		}
		node.set("behaviors", behaviors);
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
		for (JsonNode test : contract.get("tests"))
		{
			if (test.get("id").asString().equals(testId))
				return test;
		}
		throw new AssertionError("The contract's JSON has no test '" + testId + "'");
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The rejection of an expected outcome that a held-out test case holds never names the part that holds it.
	 */
	@Override
	public List<AddedTestCase> testCases(SessionResult.Completed result, Contract contract, AddedTestCases known,
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

		PromptTest test = contract.findTest(group.testId()).orElseThrow(() ->
			new IllegalArgumentException("The contract has no test '" + group.testId() + "'"));
		List<TestCase> parsed;
		synchronized (seeds)
		{
			parsed = parseTestCases(output.get(), contract, test, known, problems);
		}
		if (!problems.isEmpty())
			throw new InvalidProposalException(problems);
		return parsed.stream().map(testCase -> new AddedTestCase(attempt, test.id(), testCase)).toList();
	}

	/**
	 * Reads the test cases of the response.
	 *
	 * @param node the response
	 * @param contract the confirmed contract
	 * @param test the prompt test
	 * @param known the test cases of the contract's tests beyond the confirmed ones
	 * @param problems receives each problem found
	 * @return the valid test cases, in the response's order
	 */
	private List<TestCase> parseTestCases(JsonNode node, Contract contract, PromptTest test,
		AddedTestCases known, List<String> problems)
	{
		Set<String> names = new LinkedHashSet<>();
		for (VariableDeclaration variable : test.variables())
			names.add(variable.name());
		Split split = contract.split();
		// The normalized values of every test case that development already holds
		Set<Map<String, String>> existing = new HashSet<>();
		for (TestCase testCase : split.testCases(test, Part.DEVELOPMENT))
			existing.add(normalize(testCase.values()));
		for (AddedTestCase testCase : known.of(test.id()))
			existing.add(normalize(testCase.testCase().values()));
		Set<Long> taken = new HashSet<>();
		for (PromptTest contractTest : contract.tests())
		{
			for (TestCase testCase : contractTest.allTestCases())
				addSeeds(testCase, taken);
			for (AddedTestCase testCase : known.of(contractTest.id()))
				addSeeds(testCase.testCase(), taken);
		}
		Set<String> heldOutOutcomes = new HashSet<>();
		for (TestCase testCase : split.testCases(test, Part.HELD_OUT))
			heldOutOutcomes.add(Split.normalize(testCase.expectedOutcome()));

		Set<Map<String, String>> seen = new HashSet<>();
		List<TestCase> testCases = new ArrayList<>();
		JsonNode testCaseNodes = node.get("test_cases");
		for (int i = 0; i < testCaseNodes.size(); ++i)
		{
			String testCaseLocation = "$.test_cases[" + i + "]";
			JsonNode testCaseNode = testCaseNodes.get(i);
			boolean valid = true;
			JsonNode valueNodes = testCaseNode.get("values");
			Set<String> given = new HashSet<>();
			for (int j = 0; j < valueNodes.size(); ++j)
			{
				String variable = valueNodes.get(j).get("variable").asString();
				String valueLocation = testCaseLocation + ".values[" + j + "].variable";
				if (!names.contains(variable))
				{
					problems.add(valueLocation + ": '" + variable + "' is not a variable of test '" + test.id() + "'");
					valid = false;
				}
				else if (!given.add(variable))
				{
					problems.add(valueLocation + ": gives variable '" + variable + "' a second value");
					valid = false;
				}
			}
			for (String name : names)
			{
				if (valid && !given.contains(name))
				{
					problems.add(testCaseLocation + ": gives no value for variable '" + name + "'");
					valid = false;
				}
			}
			if (!valid)
				continue;
			Map<String, TestValue> read;
			try
			{
				read = TestValueJson.readCalls(valueNodes, seeds, taken);
			}
			catch (IllegalArgumentException e)
			{
				problems.add(testCaseLocation + ".values: " + e.getMessage());
				continue;
			}
			String expectedOutcome = testCaseNode.get(TestCase.EXPECTED_OUTCOME).asString();
			if (heldOutOutcomes.contains(Split.normalize(expectedOutcome)))
			{
				problems.add(testCaseLocation + "." + TestCase.EXPECTED_OUTCOME + ": '" + expectedOutcome +
					"' is the expected outcome of another test case; write a test case with a different expected outcome");
				continue;
			}
			Map<String, String> normalized = normalize(read);
			if (existing.contains(normalized))
			{
				problems.add(testCaseLocation + ".values: equal the values of an existing test case");
				continue;
			}
			if (!seen.add(normalized))
			{
				problems.add(testCaseLocation + ".values: repeat the values of an earlier test case");
				continue;
			}
			Map<String, TestValue> values = new LinkedHashMap<>();
			for (String name : names)
				values.put(name, read.get(name));
			TestCase testCase = new TestCase(values, expectedOutcome);
			try
			{
				test.workspaceTargets(testCase);
			}
			catch (IllegalArgumentException e)
			{
				problems.add(testCaseLocation + ": " + e.getMessage());
				continue;
			}
			testCases.add(testCase);
		}
		if (testCases.size() < MINIMUM_TEST_CASES && testCaseNodes.size() >= MINIMUM_TEST_CASES)
		{
			problems.add("$.test_cases: must list at least " + MINIMUM_TEST_CASES +
				" distinct test cases but lists " + testCases.size());
		}
		return testCases;
	}

	/**
	 * Adds the seeds of a test case's generated values to a set.
	 *
	 * @param testCase the test case
	 * @param taken    receives the seeds
	 */
	private static void addSeeds(TestCase testCase, Set<Long> taken)
	{
		for (TestValue value : testCase.values().values())
		{
			if (value instanceof TestValue.Generated generated)
				taken.add(generated.seed());
		}
	}

	/**
	 * Returns the values of a test case with each text normalized, so that values that differ only in case or spacing
	 * are equal. A generated value stands for its generator and parameters without its seed, so that two calls that
	 * differ only in their seeds are equal.
	 *
	 * @param values the value of each variable, by name
	 * @return the normalized form of each variable's value, by name
	 */
	private static Map<String, String> normalize(Map<String, TestValue> values)
	{
		Map<String, String> normalized = new HashMap<>();
		for (Map.Entry<String, TestValue> value : values.entrySet())
		{
			String form = switch (value.getValue())
			{
				case TestValue.Text text -> "text:" + Split.normalize(text.text());
				case TestValue.Generated generated -> "call:" + generated.generator() + new TreeMap<>(
					generated.parameters());
			};
			normalized.put(value.getKey(), form);
		}
		return normalized;
	}
}

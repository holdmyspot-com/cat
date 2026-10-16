package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.report.EnumNames;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the JSON form of a proposed contract, validating the output of the agent that proposes its
 * requirements and tests (the contract author).
 * <p>
 * The shape of the proposal is defined once, in {@code optimizer/contract/proposal.schema.json}. Validation first
 * checks the shape, which rejects a missing item, an unknown item and an aggregate count in place of a list. It then
 * builds the contract, whose constructors enforce the rules that a schema cannot express, such as test cases that
 * give a value to each declared variable, references that name a listed requirement and {@code tool} values that name
 * a built-in tool with arguments that suit it. Both the confirmation of contract author output and the loading of a
 * stored contract parse through this class, so both apply these rules.
 */
public final class ProposalSchema
{
	private static final String SCHEMA_RESOURCE = "/optimizer/contract/proposal.schema.json";
	/**
	 * The schema of one prompt test of a proposal file, whose properties that the full prompt test also has are
	 * copied from the proposal schema.
	 */
	private static final String PROPOSAL_FILE_TEST_RESOURCE = "/optimizer/contract/proposal-file-test.schema.json";
	/**
	 * The property that holds the trial profiles, which CAT computes rather than the contract author.
	 */
	public static final String TRIAL_PROFILES = "trial_profiles";

	/**
	 * The proposal field holding the paths of recorded scenarios covered by a test.
	 */
	private static final String REGRESSION_SCENARIOS = "regression_scenarios";
	/**
	 * The property of a test that holds the {@code scenario.md} of its directory in the draft directory, which CAT
	 * copies from the draft rather than the contract author writes.
	 */
	public static final String SCENARIO_FILE = "scenario_file";
	/**
	 * The property of a test that holds the test cases that an earlier campaign committed, which CAT reads rather than
	 * the contract author writes.
	 */
	private static final String COMMITTED_TEST_CASES = "committed_test_cases";
	/**
	 * The property of a test that holds the fixtures of the recorded scenarios that it cites, which CAT copies from
	 * the scenarios' files rather than the contract author writes.
	 */
	public static final String RECORDED_FIXTURES = "recorded_fixtures";
	/**
	 * The property that holds the contract's salt, which CAT draws rather than the contract author.
	 */
	private static final String SALT = "salt";
	/**
	 * The property that holds the dependents of the target prompt file.
	 */
	private static final String DEPENDENTS = "dependents";
	private final JsonMapper json;
	private final String schemaText;
	private final SchemaValidator validator;
	private final ObjectNode derivationSchema;
	private final SchemaValidator derivation;
	private final ObjectNode proposalFileSchema;
	private final SchemaValidator proposalFileValidator;

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
		schemaText = readResource(SCHEMA_RESOURCE);
		validator = new SchemaValidator(json.readTree(schemaText));
		derivationSchema = derivationSchema(json, schemaText);
		derivation = new SchemaValidator(derivationSchema);
		proposalFileSchema = proposalFileSchema(json, derivationSchema);
		proposalFileValidator = new SchemaValidator(proposalFileSchema);
	}

	/**
	 * Returns the schema of a proposal file: the derivation schema whose prompt tests each hold only their directory in
	 * the draft directory, the requirement of each assertion and the fields that belong to the contract rather than to
	 * the prompt test.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param derivationSchema the schema of a proposal that CAT has not completed yet
	 * @return the schema
	 * @throws UncheckedIOException if the packaged schema of a proposal file's prompt test cannot be read
	 */
	private static ObjectNode proposalFileSchema(JsonMapper json, ObjectNode derivationSchema)
	{
		ObjectNode root = derivationSchema.deepCopy();
		ObjectNode tests = (ObjectNode) root.get("properties").get("tests");
		JsonNode fullProperties = tests.get("items").get("properties");
		ObjectNode test = (ObjectNode) json.readTree(readResource(PROPOSAL_FILE_TEST_RESOURCE));
		ObjectNode properties = (ObjectNode) test.get("properties");
		// The fields that the full prompt test shares keep their one definition
		for (JsonNode name : test.get("required"))
		{
			if (!properties.has(name.asString()))
				properties.set(name.asString(), fullProperties.get(name.asString()).deepCopy());
		}
		tests.set("items", test);
		tests.put("description", "Every prompt test, each by its directory in the draft directory. Each has its own " +
			"claim in each of its cells.");
		return root;
	}

	/**
	 * Reads a packaged resource as UTF-8 text.
	 *
	 * @param resource the resource's absolute name
	 * @return the text
	 * @throws UncheckedIOException if the resource is missing or cannot be read
	 */
	private static String readResource(String resource)
	{
		try (InputStream in = ProposalSchema.class.getResourceAsStream(resource))
		{
			if (in == null)
				throw new IOException("The packaged resource " + resource + " is missing");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Returns the proposal schema without the parts that CAT computes: the salt, the trial profiles, each test's
	 * committed test cases and recorded fixtures.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schemaText the proposal schema
	 * @return the schema of a proposal that CAT has not completed yet
	 */
	private static ObjectNode derivationSchema(JsonMapper json, String schemaText)
	{
		ObjectNode root = (ObjectNode) json.readTree(schemaText);
		removeProperty(json, root, TRIAL_PROFILES);
		removeProperty(json, root, SALT);
		ObjectNode test = (ObjectNode) root.get("properties").get("tests").get("items");
		removeProperty(json, test, COMMITTED_TEST_CASES);
		removeProperty(json, test, RECORDED_FIXTURES);
		return root;
	}

	/**
	 * Removes a property from an object schema and from its required properties.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema the object schema
	 * @param name the property's name
	 */
	private static void removeProperty(JsonMapper json, ObjectNode schema, String name)
	{
		((ObjectNode) schema.get("properties")).remove(name);
		ArrayNode required = json.createArrayNode();
		for (JsonNode requiredName : schema.get("required"))
		{
			if (!requiredName.asString().equals(name))
				required.add(requiredName);
		}
		schema.set("required", required);
	}

	/**
	 * Returns the JSON schema of a stored contract.
	 *
	 * @return the schema
	 */
	public String schemaText()
	{
		return schemaText;
	}

	/**
	 * Returns the JSON schema of contract author output: the proposal schema without the salt, the trial profiles, the
	 * committed test cases and the recorded fixtures that CAT supplies.
	 *
	 * @return a copy of the schema, which the caller may change
	 */
	public ObjectNode derivationSchema()
	{
		return derivationSchema.deepCopy();
	}

	/**
	 * Returns the JSON schema of a proposal file, which a contract author writes beside its draft directory: the
	 * proposal schema without the parts that CAT computes, whose prompt tests each hold only their directory in the
	 * draft directory and what the contract says about them.
	 *
	 * @return a copy of the schema, which the caller may change
	 */
	public ObjectNode proposalFileSchema()
	{
		return proposalFileSchema.deepCopy();
	}

	/**
	 * Returns the problems of a proposal file that its schema finds.
	 *
	 * @param proposalFile the proposal file, completed with the judge fields that CAT supplies
	 * @return each problem with its JSON path, empty if the file satisfies the schema
	 * @throws NullPointerException if {@code proposalFile} is null
	 */
	public List<String> proposalFileViolations(JsonNode proposalFile)
	{
		requireThat(proposalFile, "proposalFile").isNotNull();
		return proposalFileValidator.violations(proposalFile);
	}

	/**
	 * Validates a stored contract and converts it to a contract.
	 *
	 * @param proposal the contract's JSON form
	 * @return the contract that the proposal describes
	 * @throws NullPointerException if {@code proposal} is null
	 * @throws InvalidProposalException if {@code proposal} is not valid JSON, violates the schema or breaks a rule
	 *   that the schema cannot express
	 */
	public Contract parse(String proposal) throws InvalidProposalException
	{
		requireThat(proposal, "proposal").isNotNull();
		return parse(proposal, validator, null, null, null, Map.of(), Map.of());
	}

	/**
	 * Validates contract author output that holds no salt, no trial profiles, no committed test cases and no recorded
	 * fixtures, computes the profiles, adds the committed test cases and the recorded fixtures, and converts the output
	 * to a contract.
	 *
	 * @param proposal the contract author's JSON output, without {@value #SALT}, {@value #TRIAL_PROFILES},
	 *   {@value #COMMITTED_TEST_CASES} and {@value #RECORDED_FIXTURES}
	 * @param profiles computes the trial profiles from the proposal's requirements and tests
	 * @param salt the salt that CAT drew for the proposal
	 * @param seeds the source of the seeds that CAT adds to each generator call
	 * @param committed the test cases that earlier campaigns committed, by the identifier of the prompt test that
	 *   receives them; a prompt test that it does not name has none
	 * @param recorded the fixtures of the recorded scenarios that each prompt test cites, by the identifier of the
	 *   prompt test that receives them, each with its source; a prompt test that it does not name has none
	 * @return the contract that the proposal describes
	 * @throws NullPointerException if any of the arguments are null
	 * @throws InvalidProposalException if {@code proposal} is not valid JSON, violates the schema, holds a salt or
	 *   trial profiles, states only one of a generator call's seed and version, writes a fixture at the path of a
	 *   fixture that {@code recorded} gives its test, breaks a rule that the schema cannot express, or
	 *   {@code profiles} rejects it
	 */
	public Contract parse(String proposal, ProfileRule profiles, long salt, RandomGenerator seeds,
		Map<String, List<TestCase>> committed, Map<String, List<FixtureFile>> recorded) throws InvalidProposalException
	{
		requireThat(proposal, "proposal").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		requireThat(seeds, "seeds").isNotNull();
		requireThat(committed, "committed").isNotNull();
		requireThat(recorded, "recorded").isNotNull();
		return parse(proposal, derivation, profiles, salt, seeds, committed, recorded);
	}

	/**
	 * Validates a proposal and converts it to a contract.
	 *
	 * @param proposal the JSON text
	 * @param schema the schema that the proposal must satisfy
	 * @param profiles computes the trial profiles, or {@code null} to read them from the proposal
	 * @param salt the contract's salt, or {@code null} to read it from the proposal
	 * @param seeds the source of the seeds that CAT adds to each generator call, or {@code null} if each call carries
	 *   its seed and version
	 * @param committed the committed test cases that CAT adds, by prompt test identifier, to tests whose JSON holds none
	 * @param recorded the recorded fixtures that CAT adds, by prompt test identifier, to tests whose JSON holds none
	 * @return the contract
	 * @throws InvalidProposalException if the proposal is invalid
	 */
	private Contract parse(String proposal, SchemaValidator schema, ProfileRule profiles, Long salt,
		RandomGenerator seeds, Map<String, List<TestCase>> committed, Map<String, List<FixtureFile>> recorded)
		throws InvalidProposalException
	{
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

		List<String> violations = schema.violations(root);
		if (!violations.isEmpty())
			throw new InvalidProposalException(violations);

		List<String> problems = new ArrayList<>();
		Contract contract = new Builder(problems, seeds, committed, recorded).contract(root, profiles, salt);
		if (contract == null)
			throw new InvalidProposalException(problems);
		return contract;
	}

	/**
	 * Writes a contract in the proposal schema.
	 *
	 * @param contract the contract
	 * @return the contract's JSON text
	 * @throws NullPointerException if {@code contract} is null
	 */
	public String toJson(Contract contract)
	{
		requireThat(contract, "contract").isNotNull();
		ObjectNode root = json.createObjectNode();
		ArrayNode requirements = root.putArray("requirements");
		for (Requirement requirement : contract.requirements())
			write(requirement, requirements.addObject());
		ArrayNode tests = root.putArray("tests");
		for (PromptTest test : contract.tests())
			write(test, tests.addObject());
		write(contract.profiles(), root.putObject(TRIAL_PROFILES));
		JudgeProfile judge = contract.judge();
		ObjectNode judgeNode = root.putObject("judge");
		write(judge.profile(), judgeNode);
		judgeNode.put("prompt", judge.prompt());
		judgeNode.put("rubric", judge.rubric());
		judgeNode.put("decision_rule", judge.decisionRule());
		judgeNode.set("result_schema", json.readTree(judge.resultSchema()));
		ArrayNode ignoredPaths = root.putObject("fixtures").putArray("ignored_paths");
		contract.fixtures().ignoredPaths().forEach(ignoredPaths::add);
		ArrayNode limitations = root.putArray("limitations");
		contract.limitations().forEach(limitations::add);
		root.put("minimum_pass_rate", contract.minimumPassRate());
		root.put(SALT, contract.salt());
		writeDependents(contract, root);
		return json.writeValueAsString(root);
	}

	/**
	 * Writes one prompt test of a contract with the requirements that it checks and the dependents of the target
	 * prompt file: everything
	 * that decides how its trials run and how they are decided, apart from the judge fields that CAT supplies to every
	 * contract.
	 *
	 * @param contract the contract
	 * @param test a prompt test of {@code contract}
	 * @return the JSON text: an object whose {@code test} holds the prompt test as the proposal schema writes it,
	 *   whose {@code requirements} holds, in contract order, each requirement that the test checks, and whose
	 *   {@code dependents}, present only when the contract lists dependents, holds them
	 * @throws NullPointerException if any of the arguments are null
	 */
	public String toJson(Contract contract, PromptTest test)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(test, "test").isNotNull();
		ObjectNode root = json.createObjectNode();
		write(test, root.putObject("test"));
		ArrayNode requirements = root.putArray("requirements");
		for (Requirement requirement : contract.requirements())
		{
			if (test.requirementIds().contains(requirement.id()))
				write(requirement, requirements.addObject());
		}
		writeDependents(contract, root);
		return json.writeValueAsString(root);
	}

	/**
	 * Writes the dependents of the target prompt file, if the contract lists any.
	 *
	 * @param contract the contract
	 * @param root the object that receives the {@value #DEPENDENTS} property
	 */
	private static void writeDependents(Contract contract, ObjectNode root)
	{
		// A contract without dependents keeps the identity that it had before the property existed
		if (contract.dependents().isEmpty())
			return;
		ArrayNode dependents = root.putArray(DEPENDENTS);
		for (Dependent dependent : contract.dependents())
		{
			ObjectNode node = dependents.addObject();
			node.put("location", EnumNames.lowerCase(dependent.location()));
			if (dependent.plugin().isEmpty())
				node.putNull("plugin");
			else
				node.put("plugin", dependent.plugin());
			node.put("path", PortablePaths.text(dependent.path()));
			node.putNull("text");
			node.putNull("event");
			node.putNull("command");
			switch (dependent.removal())
			{
				case Dependent.WholeFile _ -> node.put("removes", "file");
				case Dependent.Span span ->
				{
					node.put("removes", "span");
					node.put("text", span.text());
				}
				case Dependent.HookRegistration hook ->
				{
					node.put("removes", "hook_registration");
					node.put("event", hook.event());
					node.put("command", hook.command());
				}
			}
			node.put("serves", dependent.serves());
		}
	}

	/**
	 * Writes one requirement.
	 *
	 * @param requirement the requirement
	 * @param node the object that receives its properties
	 */
	private static void write(Requirement requirement, ObjectNode node)
	{
		node.put("id", requirement.id());
		node.put("description", requirement.description());
		node.put("source", requirement.source());
		node.put("trigger", requirement.trigger());
		ArrayNode behaviors = node.putArray("behaviors");
		requirement.behaviors().forEach(behaviors::add);
	}

	/**
	 * Writes the trial profiles.
	 *
	 * @param profiles the trial profiles
	 * @param node the object that receives their properties
	 */
	private static void write(TrialProfiles profiles, ObjectNode node)
	{
		node.put("inventory_source", profiles.inventory().source());
		ArrayNode inventory = node.putArray("inventory");
		for (TrialProfile profile : profiles.inventory().profiles())
			write(profile, inventory.addObject());
		ObjectNode ranking = node.putObject("ranking");
		ranking.put("source", profiles.ranking().source());
		ArrayNode families = ranking.putArray("families");
		profiles.ranking().families().forEach(families::add);
		ArrayNode bindings = node.putArray("bindings");
		for (ProfileBinding binding : profiles.bindings())
		{
			ObjectNode bindingNode = bindings.addObject();
			bindingNode.put("file", binding.file());
			write(binding.profile(), bindingNode);
		}
		ArrayNode restriction = node.putArray("restriction");
		for (TrialProfile pattern : profiles.restriction())
			write(pattern, restriction.addObject());
		ArrayNode described = node.putArray("described");
		for (TrialProfile pattern : profiles.described())
			write(pattern, described.addObject());
		if (profiles.scope() == null)
			node.putNull("scope");
		else
			node.put("scope", EnumNames.lowerCase(profiles.scope()));
		ArrayNode cells = node.putArray("cells");
		for (Cell cell : profiles.cells())
		{
			ObjectNode cellNode = cells.addObject();
			cellNode.put("id", cell.id());
			cellNode.put("test", cell.testId());
			write(cell.profile(), cellNode);
		}
	}

	/**
	 * Writes a profile's dimensions.
	 *
	 * @param profile the profile
	 * @param node the object that receives its {@code model} and {@code effort}
	 */
	private static void write(TrialProfile profile, ObjectNode node)
	{
		node.put("model", profile.model());
		node.put("effort", profile.effort());
	}

	/**
	 * Writes one prompt test.
	 *
	 * @param test the prompt test
	 * @param node the object that receives its properties
	 */
	private static void write(PromptTest test, ObjectNode node)
	{
		node.put("id", test.id());
		node.put("risk_rank", test.riskRank().position());
		ArrayNode requirementIds = node.putArray("requirement_ids");
		test.requirementIds().forEach(requirementIds::add);
		ArrayNode absentTriggers = node.putArray("absent_triggers");
		test.absentTriggers().forEach(absentTriggers::add);
		node.put("applicability", test.applicability());
		ArrayNode turns = node.putArray("turns");
		test.turns().forEach(turns::add);
		node.put("time_limit_seconds", test.timeLimit().toSeconds());
		node.put("expected_behavior", test.expectedBehaviour());
		ArrayNode uncovered = node.putArray("uncovered_interactions");
		test.uncoveredInteractions().forEach(uncovered::add);
		ArrayNode scenarios = node.putArray(REGRESSION_SCENARIOS);
		test.regressionScenarios().forEach(scenarios::add);
		// A test without a draft's scenario.md keeps the identity that it had before the property existed
		test.scenarioFile().ifPresent(scenarioFile -> node.put(SCENARIO_FILE, scenarioFile));
		ArrayNode fixtures = node.putArray("fixtures");
		ArrayNode recorded = null;
		for (FixtureFile fixture : test.fixtures())
		{
			ObjectNode fixtureNode;
			if (fixture.source().isEmpty())
				fixtureNode = fixtures.addObject();
			else
			{
				// A test without recorded fixtures keeps the identity that it had before the property existed
				if (recorded == null)
					recorded = node.putArray(RECORDED_FIXTURES);
				fixtureNode = recorded.addObject();
			}
			fixtureNode.put("path", fixture.path());
			fixtureNode.put("content", fixture.content());
			if (!fixture.source().isEmpty())
				fixtureNode.put("source", fixture.source());
		}
		ArrayNode variables = node.putArray("variables");
		for (VariableDeclaration variable : test.variables())
		{
			ObjectNode variableNode = variables.addObject();
			variableNode.put("name", variable.name());
			variableNode.put("description", variable.description());
		}
		write(test.committedTestCases(), node.putArray(COMMITTED_TEST_CASES));
		write(test.testCases(), node.putArray("test_cases"));
		ArrayNode assertions = node.putArray("assertions");
		for (MappedAssertion mapped : test.assertions())
		{
			Assertion assertion = mapped.assertion();
			ObjectNode assertionNode = assertions.addObject();
			assertionNode.put("id", assertion.id());
			assertionNode.put("kind", EnumNames.lowerCase(assertion.kind()));
			assertionNode.put("tool", assertion.tool());
			assertionNode.put("on_tool_failure", EnumNames.lowerCase(assertion.onToolFailure()));
			assertionNode.put("statement", assertion.statement());
			assertionNode.put("requirement_id", mapped.requirementId());
		}
	}

	/**
	 * Writes test cases.
	 *
	 * @param testCases the test cases
	 * @param node the array that receives them
	 */
	private static void write(List<TestCase> testCases, ArrayNode node)
	{
		for (TestCase testCase : testCases)
		{
			ObjectNode testCaseNode = node.addObject();
			TestValueJson.write(testCase.values(), testCaseNode.putArray("values"));
			testCaseNode.put(TestCase.EXPECTED_OUTCOME, testCase.expectedOutcome());
		}
	}

	/**
	 * Builds a contract from a proposal that satisfies the schema, reporting rejected model values.
	 * <p>
	 * A node whose child failed is not built, so a failure is reported once, at the innermost object that is wrong.
	 * A builder method returns null after it adds a problem.
	 */
	private static final class Builder
	{
		private final List<String> problems;
		private final RandomGenerator seeds;
		private final Map<String, List<TestCase>> committed;
		private final Map<String, List<FixtureFile>> recorded;
		private final Set<Long> taken = new HashSet<>();

		/**
		 * Creates a builder.
		 *
		 * @param problems receives one message per rejected object
		 * @param seeds the source of the seeds that CAT adds to each generator call, or {@code null} if each call
		 *   carries its seed and version
		 * @param committed the committed test cases that CAT adds, by prompt test identifier, to tests whose JSON
		 *   holds none
		 * @param recorded the recorded fixtures that CAT adds, by prompt test identifier, to tests whose JSON holds
		 *   none
		 */
		Builder(List<String> problems, RandomGenerator seeds, Map<String, List<TestCase>> committed,
			Map<String, List<FixtureFile>> recorded)
		{
			this.problems = problems;
			this.seeds = seeds;
			this.committed = committed;
			this.recorded = recorded;
			for (List<TestCase> testCases : committed.values())
			{
				for (TestCase testCase : testCases)
				{
					for (TestValue value : testCase.values().values())
					{
						if (value instanceof TestValue.Generated generated)
							taken.add(generated.seed());
					}
				}
			}
		}

		/**
		 * Builds the contract.
		 *
		 * @param root the proposal
		 * @param rule computes the trial profiles, or {@code null} to read them from the proposal
		 * @param salt the contract's salt, or {@code null} to read it from the proposal
		 * @return the contract, or null if a problem was added
		 */
		Contract contract(JsonNode root, ProfileRule rule, Long salt)
		{
			if (seeds != null)
				takeStatedSeeds(root);
			int problemsBefore = problems.size();
			List<Requirement> requirements = list(root.get("requirements"), "$.requirements", (node, location) ->
				attempt(location, () -> new Requirement(text(node, "id"), text(node, "description"),
					text(node, "source"), optionalText(node, "trigger"), strings(node.get("behaviors")))));
			List<PromptTest> tests = list(root.get("tests"), "$.tests", this::test);
			JsonNode judgeNode = root.get("judge");
			JudgeProfile judge = attempt("$.judge", () -> new JudgeProfile(profile(judgeNode),
				text(judgeNode, "prompt"), text(judgeNode, "rubric"), text(judgeNode, "decision_rule"),
				judgeNode.get("result_schema").toString()));
			TrialFixtures fixtures = attempt("$.fixtures", () -> new TrialFixtures(
				strings(root.get("fixtures").get("ignored_paths"))));
			List<Dependent> dependents = List.of();
			JsonNode dependentsNode = root.get(DEPENDENTS);
			if (dependentsNode != null && !dependentsNode.isNull())
			{
				dependents = list(dependentsNode, "$." + DEPENDENTS, (node, location) ->
					attempt(location, () -> dependent(node)));
			}
			if (problems.size() > problemsBefore)
				return null;
			TrialProfiles profiles;
			if (rule == null)
				profiles = attempt("$." + TRIAL_PROFILES, () -> profiles(root.get(TRIAL_PROFILES)));
			else
				profiles = attempt("$." + TRIAL_PROFILES, () -> rule.profiles(requirements, tests));
			if (profiles == null)
				return null;
			long contractSalt;
			if (salt == null)
				contractSalt = root.get(SALT).asLong();
			else
				contractSalt = salt;
			List<Dependent> contractDependents = dependents;
			return attempt("$", () -> new Contract(requirements, tests, profiles, judge, fixtures,
				strings(root.get("limitations")), root.get("minimum_pass_rate").decimalValue(), contractSalt,
				contractDependents));
		}

		/**
		 * Adds to the taken seeds each seed that a generator call of the proposal states, so that no call that
		 * receives a new seed receives one of them.
		 *
		 * @param root the proposal
		 */
		private void takeStatedSeeds(JsonNode root)
		{
			for (JsonNode test : root.get("tests"))
			{
				for (JsonNode testCase : test.get("test_cases"))
				{
					for (JsonNode value : testCase.get("values"))
					{
						JsonNode seed = value.path("call").path("seed");
						if (seed.isIntegralNumber() && seed.canConvertToLong())
							taken.add(seed.asLong());
					}
				}
			}
		}

		/**
		 * Builds a dependent of the target prompt file.
		 *
		 * @param node an item of the {@value #DEPENDENTS} array
		 * @return the dependent
		 * @throws IllegalArgumentException if the item lacks the property that its kind of removal needs, or a model
		 *   constructor rejects a value
		 */
		private static Dependent dependent(JsonNode node)
		{
			Dependent.Location location = EnumNames.parse(text(node, "location"), Dependent.Location.class);
			String removes = text(node, "removes");
			Dependent.Removal removal = switch (removes)
			{
				case "file" -> new Dependent.WholeFile();
				case "span" -> new Dependent.Span(required(node, "text", removes));
				case "hook_registration" -> new Dependent.HookRegistration(required(node, "event", removes),
					required(node, "command", removes));
				default -> throw new IllegalArgumentException("removes must be file, span or hook_registration: " +
					removes);
			};
			String plugin = optionalText(node, "plugin");
			if (plugin == null)
				plugin = "";
			return new Dependent(location, plugin, Path.of(text(node, "path")), removal, text(node, "serves"));
		}

		/**
		 * Reads a string property that a kind of removal needs.
		 *
		 * @param node the dependent
		 * @param name the name of the property
		 * @param removes the kind of removal
		 * @return the value
		 * @throws IllegalArgumentException if the value is null
		 */
		private static String required(JsonNode node, String name, String removes)
		{
			String value = optionalText(node, name);
			if (value == null)
				throw new IllegalArgumentException("A dependent that removes a " + removes + " gives " + name);
			return value;
		}

		/**
		 * Builds the trial profiles that a proposal holds.
		 *
		 * @param node the {@value #TRIAL_PROFILES} object
		 * @return the trial profiles
		 * @throws IllegalArgumentException if a model constructor rejects a value
		 */
		private static TrialProfiles profiles(JsonNode node)
		{
			List<TrialProfile> inventory = new ArrayList<>();
			for (JsonNode profileNode : node.get("inventory"))
				inventory.add(profile(profileNode));
			List<ProfileBinding> bindings = new ArrayList<>();
			for (JsonNode bindingNode : node.get("bindings"))
				bindings.add(new ProfileBinding(text(bindingNode, "file"), profile(bindingNode)));
			List<TrialProfile> restriction = new ArrayList<>();
			for (JsonNode patternNode : node.get("restriction"))
				restriction.add(profile(patternNode));
			List<TrialProfile> described = new ArrayList<>();
			for (JsonNode patternNode : node.get("described"))
				described.add(profile(patternNode));
			JsonNode rankingNode = node.get("ranking");
			CapabilityRanking ranking = new CapabilityRanking(strings(rankingNode.get("families")),
				text(rankingNode, "source"));
			CertificationScope scope = null;
			if (!node.get("scope").isNull())
				scope = EnumNames.parse(node.get("scope").asString(), CertificationScope.class);
			List<Cell> cells = new ArrayList<>();
			for (JsonNode cellNode : node.get("cells"))
				cells.add(new Cell(text(cellNode, "id"), text(cellNode, "test"), profile(cellNode)));
			return new TrialProfiles(new ProfileCatalog(text(node, "inventory_source"), inventory), ranking,
				bindings, restriction, described, scope, cells);
		}

		/**
		 * Reads a profile's dimensions.
		 *
		 * @param node an object with {@code model} and {@code effort}
		 * @return the profile
		 */
		private static TrialProfile profile(JsonNode node)
		{
			return new TrialProfile(text(node, "model"), text(node, "effort"));
		}

		/**
		 * Builds one prompt test and its variables.
		 *
		 * @param node the prompt test
		 * @param location the location of the prompt test
		 * @return the prompt test, or null if a problem was added
		 */
		private PromptTest test(JsonNode node, String location)
		{
			int problemsBefore = problems.size();
			List<VariableDeclaration> variables = list(node.get("variables"), location + ".variables",
				(variableNode, variableLocation) -> attempt(variableLocation, () ->
					new VariableDeclaration(text(variableNode, "name"), text(variableNode, "description"))));
			List<FixtureFile> fixtures = recordedFixtures(node, location);
			List<FixtureFile> written = list(node.get("fixtures"), location + ".fixtures", (fixtureNode, at) ->
				attempt(at, () -> written(fixtureNode, fixtures)));
			fixtures.addAll(written);
			List<TestCase> committedTestCases = committed.getOrDefault(node.get("id").asString(), List.of());
			JsonNode committedNode = node.get(COMMITTED_TEST_CASES);
			if (committedNode != null)
				committedTestCases = list(committedNode, location + "." + COMMITTED_TEST_CASES, this::testCase);
			List<TestCase> testCases = list(node.get("test_cases"), location + ".test_cases", this::testCase);
			List<MappedAssertion> assertions = list(node.get("assertions"), location + ".assertions",
				this::assertion);
			if (problems.size() > problemsBefore)
				return null;
			List<TestCase> committed = committedTestCases;
			Optional<String> scenarioFile = Optional.ofNullable(node.get(SCENARIO_FILE)).map(JsonNode::asString);
			PromptTest test = attempt(location, () -> new PromptTest(text(node, "id"),
				new RiskRank(node.get("risk_rank").asInt()), strings(node.get("requirement_ids")),
				strings(node.get("absent_triggers")), text(node, "applicability"), strings(node.get("turns")),
				Duration.ofSeconds(node.get("time_limit_seconds").asLong()), text(node, "expected_behavior"),
				strings(node.get("uncovered_interactions")), variables, committed, testCases, assertions,
				strings(node.get(REGRESSION_SCENARIOS)), fixtures, scenarioFile));
			if (test == null)
				return null;
			// Delivery writes every prompt test as a scenario.md
			if (attempt(location, () -> test.scenario().render()) == null)
				return null;
			return test;
		}

		/**
		 * Returns the recorded fixtures of one prompt test: those that its JSON holds, or else those that CAT adds.
		 *
		 * @param node the prompt test
		 * @param location the location of the prompt test
		 * @return a list that the caller may change, holding the fixtures that could be built
		 */
		private List<FixtureFile> recordedFixtures(JsonNode node, String location)
		{
			JsonNode recordedNode = node.get(RECORDED_FIXTURES);
			if (recordedNode == null)
				return new ArrayList<>(recorded.getOrDefault(node.get("id").asString(), List.of()));
			return list(recordedNode, location + "." + RECORDED_FIXTURES, (fixtureNode, at) ->
				attempt(at, () -> new FixtureFile(text(fixtureNode, "path"), text(fixtureNode, "content"),
					text(fixtureNode, "source"))));
		}

		/**
		 * Builds a fixture that the contract author wrote.
		 *
		 * @param node the fixture
		 * @param recordedFixtures the recorded fixtures of the same prompt test
		 * @return the fixture
		 * @throws IllegalArgumentException if a recorded fixture has the same path, or a model constructor rejects a
		 *   value
		 */
		private static FixtureFile written(JsonNode node, List<FixtureFile> recordedFixtures)
		{
			FixtureFile fixture = new FixtureFile(text(node, "path"), text(node, "content"));
			for (FixtureFile recordedFixture : recordedFixtures)
			{
				if (recordedFixture.path().equals(fixture.path()))
				{
					throw new IllegalArgumentException("CAT adds the fixture '" + fixture.path() + "' from the file '" +
						recordedFixture.source() + "' of a cited scenario. Leave it out of fixtures.");
				}
			}
			return fixture;
		}

		/**
		 * Builds one assertion and the requirement that it checks.
		 *
		 * @param node the assertion
		 * @param location the location of the assertion
		 * @return the assertion, or null if a problem was added
		 */
		private MappedAssertion assertion(JsonNode node, String location)
		{
			return attempt(location, () -> new MappedAssertion(new Assertion(text(node, "id"),
				EnumNames.parse(text(node, "kind"), AssertionKind.class), optionalText(node, "tool"),
				EnumNames.parse(text(node, "on_tool_failure"), OnToolFailure.class), text(node, "statement")),
				text(node, "requirement_id")));
		}

		/**
		 * Builds one test case.
		 *
		 * @param node the test case
		 * @param location the location of the test case
		 * @return the test case, or null if a problem was added
		 */
		private TestCase testCase(JsonNode node, String location)
		{
			return attempt(location, () ->
			{
				Map<String, TestValue> values;
				if (seeds == null)
					values = TestValueJson.read(node.get("values"));
				else
					values = TestValueJson.readCalls(node.get("values"), seeds, taken);
				return new TestCase(values, text(node, TestCase.EXPECTED_OUTCOME));
			});
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
		private <T> List<T> list(JsonNode array, String location, BiFunction<JsonNode, String, T> builder)
		{
			List<T> items = new ArrayList<>();
			for (int i = 0; i < array.size(); ++i)
			{
				T item = builder.apply(array.get(i), location + "[" + i + "]");
				if (item != null)
					items.add(item);
			}
			return items;
		}

		/**
		 * Runs a model-building operation and records its argument rejection.
		 *
		 * @param <T> the type of object
		 * @param location the location of the object
		 * @param construction builds the model value
		 * @return the value, or null if the operation rejected its arguments
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
		 * Reads a string property that may be null.
		 *
		 * @param node the object
		 * @param name the name of the property
		 * @return the value, or {@code null} if it is null
		 */
		private static String optionalText(JsonNode node, String name)
		{
			JsonNode value = node.get(name);
			if (value.isNull())
				return null;
			return value.asString();
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
}

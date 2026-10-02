package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.Count;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.ExactText;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.ExitStatus;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.FileContent;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.FileExists;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.ForbiddenText;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.PatternMatch;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion.ToolUsed;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the JSON form of a proposed contract, validating the output of the agent that proposes its
 * requirements and tests (the deriver).
 * <p>
 * The shape of the proposal is defined once, in {@code optimizer/contract/proposal.schema.json}. Validation first
 * checks the shape, which rejects a missing item, an unknown item and an aggregate count in place of a list. It then
 * builds the contract, whose constructors enforce the rules that a schema cannot express, such as probabilities that
 * sum to 1 and references that name a listed requirement. Last, it runs each condition evaluated directly from trial
 * evidence (a deterministic check) against evidence it must accept and evidence it must reject (its controls), and
 * discards each check that fails one. Both the confirmation of deriver
 * output and the loading of a stored contract parse through this class, so both validate the controls.
 */
public final class ProposalSchema
{
	private static final String SCHEMA_RESOURCE = "/optimizer/contract/proposal.schema.json";
	/**
	 * The property that holds the trial profiles, which CAT computes rather than the deriver.
	 */
	public static final String TRIAL_PROFILES = "trial_profiles";
	/**
	 * The property of a test that holds the checks that CAT discarded, which CAT computes rather than the deriver.
	 */
	private static final String DISCARDED_CHECKS = "discarded_checks";

	/**
	 * The proposal field holding the paths of recorded scenarios covered by a test.
	 */
	private static final String REGRESSION_SCENARIOS = "regression_scenarios";
	private final JsonMapper json;
	private final String schemaText;
	private final SchemaValidator validator;
	private final ObjectNode derivationSchema;
	private final SchemaValidator derivation;

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
		derivationSchema = derivationSchema(json, schemaText);
		derivation = new SchemaValidator(derivationSchema);
	}

	/**
	 * Returns the proposal schema without the parts that CAT computes: the trial profiles and each test's discarded
	 * checks.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schemaText the proposal schema
	 * @return the schema of a proposal that CAT has not completed yet
	 */
	private static ObjectNode derivationSchema(JsonMapper json, String schemaText)
	{
		ObjectNode root = (ObjectNode) json.readTree(schemaText);
		removeProperty(json, root, TRIAL_PROFILES);
		ObjectNode test = (ObjectNode) root.get("properties").get("categories").get("items").get("properties").
			get("tests").get("items");
		removeProperty(json, test, DISCARDED_CHECKS);
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
	 * Returns the JSON schema of deriver output: the proposal schema without the trial profiles and the discarded
	 * checks that CAT computes.
	 *
	 * @return a copy of the schema, which the caller may change
	 */
	public ObjectNode derivationSchema()
	{
		return derivationSchema.deepCopy();
	}

	/**
	 * Validates a stored contract, runs its checks against their controls and converts it to a contract.
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
		return parse(proposal, validator, null);
	}

	/**
	 * Validates deriver output that holds no trial profiles and no discarded checks, computes the profiles, runs the
	 * checks against their controls and converts the output to a contract.
	 *
	 * @param proposal the deriver's JSON output, without {@value #TRIAL_PROFILES} and {@value #DISCARDED_CHECKS}
	 * @param profiles computes the trial profiles from the proposal's requirements and categories
	 * @return the contract that the proposal describes
	 * @throws NullPointerException if any of the arguments are null
	 * @throws InvalidProposalException if {@code proposal} is not valid JSON, violates the schema, holds trial
	 *   profiles, breaks a rule that the schema cannot express, or {@code profiles} rejects it
	 */
	public Contract parse(String proposal, ProfileRule profiles) throws InvalidProposalException
	{
		requireThat(proposal, "proposal").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		return parse(proposal, derivation, profiles);
	}

	/**
	 * Validates a proposal and converts it to a contract.
	 *
	 * @param proposal the JSON text
	 * @param schema the schema that the proposal must satisfy
	 * @param profiles computes the trial profiles, or {@code null} to read them from the proposal
	 * @return the contract
	 * @throws InvalidProposalException if the proposal is invalid
	 */
	private Contract parse(String proposal, SchemaValidator schema, ProfileRule profiles)
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
		Contract contract = new Builder(problems).contract(root, profiles);
		if (contract == null)
			throw new InvalidProposalException(problems);
		return contract;
	}

	/**
	 * Writes a contract in the proposal schema.
	 *
	 * @param contract the contract
	 * @return the contract's JSON text; {@link #parse(String)} revalidates controls and may discard a check that
	 *   fails one
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
		return json.writeValueAsString(root);
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
		ArrayNode cells = node.putArray("cells");
		for (Cell cell : profiles.cells())
		{
			ObjectNode cellNode = cells.addObject();
			cellNode.put("id", cell.id());
			cellNode.put("category", cell.categoryId());
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
		ArrayNode scenarios = node.putArray(REGRESSION_SCENARIOS);
		test.regressionScenarios().forEach(scenarios::add);
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
		ArrayNode checks = node.putArray("checks");
		for (DeterministicCheck check : test.checks())
			write(check, checks.addObject());
		ArrayNode discarded = node.putArray(DISCARDED_CHECKS);
		for (DiscardedCheck check : test.discardedChecks())
		{
			ObjectNode discardedNode = discarded.addObject();
			write(check.check(), discardedNode.putObject("check"));
			discardedNode.put("reason", check.reason());
		}
	}

	/**
	 * Writes one deterministic check.
	 *
	 * @param check the check
	 * @param node the object that receives its properties
	 */
	private static void write(DeterministicCheck check, ObjectNode node)
	{
		node.put("requirement_id", check.requirementId());
		node.put("kind", check.criterion().kind());
		ObjectNode parameters = node.putObject("parameters");
		switch (check.criterion())
		{
			case ExactText exact ->
			{
				putTurn(parameters, exact.turn());
				parameters.put("text", exact.text());
			}
			case ForbiddenText forbidden ->
			{
				putTurn(parameters, forbidden.turn());
				parameters.put("text", forbidden.text());
			}
			case PatternMatch pattern ->
			{
				putTurn(parameters, pattern.turn());
				parameters.put("pattern", pattern.pattern());
			}
			case Count count ->
			{
				putTurn(parameters, count.turn());
				parameters.put("pattern", count.pattern());
				if (count.minimum() != null)
					parameters.put("minimum", count.minimum());
				if (count.maximum() != null)
					parameters.put("maximum", count.maximum());
			}
			case FileExists exists ->
			{
				parameters.put("path", exists.path());
				parameters.put("exists", exists.exists());
			}
			case FileContent content ->
			{
				parameters.put("path", content.path());
				parameters.put("text", content.text());
			}
			case ExitStatus status ->
			{
				parameters.put("command", status.command());
				parameters.put("status", status.status());
			}
			case ToolUsed used ->
			{
				parameters.put("tool", used.tool());
				parameters.put("used", used.used());
			}
		}
		write(check.knownGood(), node.putObject("known_good"));
		write(check.knownBad(), node.putObject("known_bad"));
	}

	/**
	 * Writes the turn that a reply kind examines.
	 *
	 * @param parameters the check's parameters
	 * @param turn the turn, or {@code null} for every turn
	 */
	private static void putTurn(ObjectNode parameters, Integer turn)
	{
		if (turn != null)
			parameters.put("turn", turn);
	}

	/**
	 * Writes a check's control.
	 *
	 * @param control the control's evidence
	 * @param node the object that receives its properties
	 */
	private static void write(ControlEvidence control, ObjectNode node)
	{
		ArrayNode replies = node.putArray("replies");
		for (List<String> turn : control.replies())
		{
			ArrayNode messages = replies.addArray();
			turn.forEach(messages::add);
		}
		ArrayNode files = node.putArray("files");
		for (EvidenceFile file : control.files())
		{
			ObjectNode fileNode = files.addObject();
			fileNode.put("path", file.path());
			fileNode.put("content", file.content());
		}
		ArrayNode calls = node.putArray("tool_calls");
		for (ToolCall call : control.calls())
		{
			ObjectNode callNode = calls.addObject();
			callNode.put("tool", call.tool());
			if (call.command() != null)
				callNode.put("command", call.command());
			if (call.exitStatus() != null)
				callNode.put("exit_status", call.exitStatus());
		}
		ArrayNode choices = node.putArray("choices");
		for (Map.Entry<String, String> choice : control.choices().entrySet())
		{
			ObjectNode choiceNode = choices.addObject();
			choiceNode.put("dimension", choice.getKey());
			choiceNode.put("label", choice.getValue());
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
		 * @param rule computes the trial profiles, or {@code null} to read them from the proposal
		 * @return the contract, or null if a problem was added
		 */
		Contract contract(JsonNode root, ProfileRule rule)
		{
			int problemsBefore = problems.size();
			List<Requirement> requirements = list(root.get("requirements"), "$.requirements", (node, location) ->
				attempt(location, () -> new Requirement(text(node, "id"), text(node, "description"),
					text(node, "source"))));
			List<Category> categories = list(root.get("categories"), "$.categories", this::category);
			JsonNode judgeNode = root.get("judge");
			JudgeProfile judge = attempt("$.judge", () -> new JudgeProfile(profile(judgeNode),
				text(judgeNode, "prompt"), text(judgeNode, "rubric"), text(judgeNode, "decision_rule"),
				judgeNode.get("result_schema").toString()));
			TrialFixtures fixtures = attempt("$.fixtures", () -> new TrialFixtures(
				strings(root.get("fixtures").get("ignored_paths"))));
			if (problems.size() > problemsBefore)
				return null;
			TrialProfiles profiles;
			if (rule == null)
				profiles = attempt("$." + TRIAL_PROFILES, () -> profiles(root.get(TRIAL_PROFILES)));
			else
				profiles = attempt("$." + TRIAL_PROFILES, () -> rule.profiles(requirements, categories));
			if (profiles == null)
				return null;
			return attempt("$", () -> new Contract(requirements, categories, profiles, judge, fixtures,
				strings(root.get("limitations"))));
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
			List<Cell> cells = new ArrayList<>();
			for (JsonNode cellNode : node.get("cells"))
				cells.add(new Cell(text(cellNode, "id"), text(cellNode, "category"), profile(cellNode)));
			return new TrialProfiles(new ProfileCatalog(text(node, "inventory_source"), inventory), bindings,
				restriction, cells);
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
			List<DeterministicCheck> checks = list(node.get("checks"), location + ".checks", this::check);
			List<DiscardedCheck> discarded = List.of();
			JsonNode discardedNode = node.get(DISCARDED_CHECKS);
			if (discardedNode != null)
				discarded = list(discardedNode, location + "." + DISCARDED_CHECKS, this::discarded);
			if (problems.size() > problemsBefore)
				return null;
			List<DiscardedCheck> discardedChecks = discarded;
			return attempt(location, () -> new TestDefinition(text(node, "id"), strings(node.get("requirement_ids")),
				text(node, "applicability"), strings(node.get("turns")),
				Duration.ofSeconds(node.get("time_limit_seconds").asLong()), text(node, "expected_behavior"),
				strings(node.get("uncovered_interactions")), variations, checks, discardedChecks,
				strings(node.get(REGRESSION_SCENARIOS))).
				withValidatedControls());
		}

		/**
		 * Builds one discarded check.
		 *
		 * @param node the discarded check
		 * @param location the location of the discarded check
		 * @return the discarded check, or null if a problem was added
		 */
		private DiscardedCheck discarded(JsonNode node, String location)
		{
			DeterministicCheck check = check(node.get("check"), location + ".check");
			if (check == null)
				return null;
			return attempt(location, () -> new DiscardedCheck(check, text(node, "reason")));
		}

		/**
		 * Builds one deterministic check and its controls.
		 *
		 * @param node the check
		 * @param location the location of the check
		 * @return the check, or null if a problem was added
		 */
		private DeterministicCheck check(JsonNode node, String location)
		{
			int problemsBefore = problems.size();
			CheckCriterion criterion = attempt(location + ".parameters", () -> criterion(text(node, "kind"),
				node.get("parameters")));
			ControlEvidence knownGood = attempt(location + ".known_good", () -> control(node.get("known_good")));
			ControlEvidence knownBad = attempt(location + ".known_bad", () -> control(node.get("known_bad")));
			if (problems.size() > problemsBefore)
				return null;
			return attempt(location, () -> new DeterministicCheck(text(node, "requirement_id"), criterion, knownGood,
				knownBad));
		}

		/**
		 * Builds a check's criterion from its kind and parameters.
		 *
		 * @param kind the kind's name
		 * @param parameters the parameters
		 * @return the criterion
		 * @throws IllegalArgumentException if a parameter that the kind needs is missing, a parameter does not apply to
		 *   the kind, or the criterion rejects a value
		 */
		private static CheckCriterion criterion(String kind, JsonNode parameters)
		{
			return switch (kind)
			{
				case "exact_text" ->
				{
					onlyParameters(kind, parameters, "turn", "text");
					yield new ExactText(optionalInteger(parameters, "turn"), requiredText(kind, parameters, "text"));
				}
				case "forbidden_text" ->
				{
					onlyParameters(kind, parameters, "turn", "text");
					yield new ForbiddenText(optionalInteger(parameters, "turn"), requiredText(kind, parameters, "text"));
				}
				case "pattern" ->
				{
					onlyParameters(kind, parameters, "turn", "pattern");
					yield new PatternMatch(optionalInteger(parameters, "turn"),
						requiredText(kind, parameters, "pattern"));
				}
				case "count" ->
				{
					onlyParameters(kind, parameters, "turn", "pattern", "minimum", "maximum");
					yield new Count(optionalInteger(parameters, "turn"), requiredText(kind, parameters, "pattern"),
						optionalInteger(parameters, "minimum"), optionalInteger(parameters, "maximum"));
				}
				case "file_exists" ->
				{
					onlyParameters(kind, parameters, "path", "exists");
					yield new FileExists(requiredText(kind, parameters, "path"), required(kind, parameters, "exists").
						asBoolean());
				}
				case "file_content" ->
				{
					onlyParameters(kind, parameters, "path", "text");
					yield new FileContent(requiredText(kind, parameters, "path"), requiredText(kind, parameters,
						"text"));
				}
				case "exit_status" ->
				{
					onlyParameters(kind, parameters, "command", "status");
					yield new ExitStatus(requiredText(kind, parameters, "command"), required(kind, parameters,
						"status").asInt());
				}
				case "tool_used" ->
				{
					onlyParameters(kind, parameters, "tool", "used");
					yield new ToolUsed(requiredText(kind, parameters, "tool"), required(kind, parameters, "used").
						asBoolean());
				}
				default -> throw new IllegalArgumentException("Unknown check kind '" + kind + "'");
			};
		}

		/**
		 * Rejects a parameter that a kind does not take.
		 *
		 * @param kind the kind's name
		 * @param parameters the parameters
		 * @param names the parameters that the kind takes
		 * @throws IllegalArgumentException if {@code parameters} holds another parameter
		 */
		private static void onlyParameters(String kind, JsonNode parameters, String... names)
		{
			Set<String> allowed = Set.of(names);
			for (String name : parameters.propertyNames())
			{
				if (!allowed.contains(name))
				{
					throw new IllegalArgumentException("The kind '" + kind + "' does not take the parameter '" + name +
						"'. It takes: " + String.join(", ", names));
				}
			}
		}

		/**
		 * Returns a parameter that a kind needs.
		 *
		 * @param kind the kind's name
		 * @param parameters the parameters
		 * @param name the parameter's name
		 * @return the parameter's value
		 * @throws IllegalArgumentException if the parameter is missing
		 */
		private static JsonNode required(String kind, JsonNode parameters, String name)
		{
			JsonNode value = parameters.get(name);
			if (value == null)
				throw new IllegalArgumentException("The kind '" + kind + "' needs the parameter '" + name + "'");
			return value;
		}

		/**
		 * Returns a string parameter that a kind needs.
		 *
		 * @param kind the kind's name
		 * @param parameters the parameters
		 * @param name the parameter's name
		 * @return the parameter's value
		 * @throws IllegalArgumentException if the parameter is missing
		 */
		private static String requiredText(String kind, JsonNode parameters, String name)
		{
			return required(kind, parameters, name).asString();
		}

		/**
		 * Returns an optional integer parameter.
		 *
		 * @param parameters the parameters
		 * @param name the parameter's name
		 * @return the parameter's value, or {@code null} if it is missing
		 */
		private static Integer optionalInteger(JsonNode parameters, String name)
		{
			JsonNode value = parameters.get(name);
			if (value == null)
				return null;
			return value.asInt();
		}

		/**
		 * Builds a check's control.
		 *
		 * @param node the control
		 * @return the control's evidence
		 * @throws IllegalArgumentException if a model constructor rejects a value
		 */
		private static ControlEvidence control(JsonNode node)
		{
			List<EvidenceFile> files = new ArrayList<>();
			for (JsonNode fileNode : node.get("files"))
				files.add(new EvidenceFile(text(fileNode, "path"), text(fileNode, "content")));
			List<ToolCall> calls = new ArrayList<>();
			for (JsonNode callNode : node.get("tool_calls"))
			{
				String command = null;
				if (callNode.has("command"))
					command = text(callNode, "command");
				Integer exitStatus = optionalInteger(callNode, "exit_status");
				calls.add(new ToolCall(text(callNode, "tool"), command, exitStatus));
			}
			Map<String, String> choices = new HashMap<>();
			for (JsonNode choiceNode : node.get("choices"))
			{
				String dimension = text(choiceNode, "dimension");
				if (choices.put(dimension, text(choiceNode, "label")) != null)
					throw new IllegalArgumentException("The control chooses a value for '" + dimension + "' twice");
			}
			List<List<String>> replies = new ArrayList<>();
			for (JsonNode turn : node.get("replies"))
				replies.add(strings(turn));
			return new ControlEvidence(replies, files, calls, choices);
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

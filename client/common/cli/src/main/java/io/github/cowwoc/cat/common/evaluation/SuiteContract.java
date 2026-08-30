package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.cat.common.PromptEvaluation.array;
import static io.github.cowwoc.cat.common.PromptEvaluation.object;
import static io.github.cowwoc.cat.common.PromptEvaluation.text;

import io.github.cowwoc.cat.common.PromptEvaluation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Materializes the evaluator contract declared by a version-controlled scenario suite.
 */
public final class SuiteContract
{
	private static final Set<String> SUPPORTED_SUITE_FIELDS = Set.of("schema_version", "purpose", "routing_owner",
		"sprt", "generalization", "diagnostic_kind", "source_root", "source_suite", "source_run",
		"source_case_id", "source_trial", "scenarios", "evaluation_test_package");
	private static final Set<String> SUPPORTED_METADATA_FIELDS = Set.of("measures", "route_expectation",
		"route_preparation", "deterministic_check", "semantic_controls", "turns", "routing_diagnostic",
		"source_case_id", "assertion_obligation_families", "assertion_failure_classes");
	private static final double REQUIRED_COMPLIANCE = 0.95;
	private static final double UNACCEPTABLE_COMPLIANCE = 0.85;
	private static final double CERTIFICATION_FALSE_POSITIVE_RATE = 0.05;
	private static final double SPRT_FALSE_REJECTION_RATE = 0.05;

	private enum Measurement
	{
		ORGANIC_SELECTION,
		DELIVERY,
		APPLICATION;

		/**
		 * Parses one externally declared testcase measurement.
		 *
		 * @param value the manifest measurement name
		 * @return the supported measurement
		 * @throws IOException if {@code value} is unsupported
		 */
		private static Measurement parse(String value) throws IOException
		{
			try
			{
				return valueOf(value.toUpperCase(java.util.Locale.ROOT));
			}
			catch (IllegalArgumentException e)
			{
				throw new IOException("Suite has an unsupported testcase measurement: " + value, e);
			}
		}
	}

	/**
	 * Prevents construction of this static owner.
	 */
	private SuiteContract()
	{
	}

	/**
	 * Reads one suite, deriving scenario cases from its version-controlled directories.
	 *
	 * @param suitePath the scenario-suite root
	 * @return the materialized suite contract
	 * @throws IOException if the suite contract or a scenario is invalid
	 */
	public static ObjectNode read(Path suitePath) throws IOException
	{
		if (!Files.isDirectory(suitePath))
			throw new IOException("Expected scenario-suite directory: " + suitePath);

		Path root = suitePath.toAbsolutePath().normalize();
		Path manifest = root.resolve("suite.json");
		ObjectNode suite = object(PromptEvaluation.read(manifest), "scenario suite");
		validateSupportedFields(suite, SUPPORTED_SUITE_FIELDS, "scenario suite", manifest);
		JsonNode declaredScenarios = suite.path("scenarios");
		if (!declaredScenarios.isMissingNode() && !declaredScenarios.isObject())
			throw new IOException("Scenario suite metadata must map scenario IDs to evaluator declarations: " + manifest);
		ArrayNode cases = suite.putArray("cases");
		try (Stream<Path> files = Files.walk(root))
		{
			List<Path> scenarioPaths = files.filter(path -> path.getFileName().toString().equals("scenario.md")).
				sorted().toList();
			for (Path scenarioPath : scenarioPaths)
				addScenario(suite, declaredScenarios, cases, root, manifest, scenarioPath);
		}
		return suite;
	}

	/**
	 * Validates one durable testcase-suite manifest.
	 *
	 * @param suitePath the suite root
	 * @throws IOException if the suite is invalid
	 */
	public static void validate(Path suitePath) throws IOException
	{
		ObjectNode suite = read(suitePath);
		if (suite.path("schema_version").asInt(-1) != 3)
			throw new IOException("Unsupported suite schema_version: " + suitePath);
		SuitePurpose purpose = SuitePurpose.parse(text(suite, "purpose", suitePath));
		ArrayNode cases = array(suite.path("cases"), "cases", suitePath);
		if (cases.isEmpty())
			throw new IOException("Suite has no test cases: " + suitePath);
		DiagnosticKind diagnosticKind = diagnosticKind(suite, suitePath, purpose);
		if (purpose == SuitePurpose.DIAGNOSTIC && diagnosticKind != DiagnosticKind.ROUTING_ONLY && cases.size() != 1)
			throw new IOException("A generated direct-application diagnostic suite must contain exactly one test case: " +
				suitePath);
		Set<String> identifiers = new HashSet<>();
		boolean hasOrganicRoute = false;
		for (JsonNode node : cases)
		{
			ObjectNode testCase = object(node, "test case");
			String id = text(testCase, "id", suitePath);
			if (!id.matches("[a-z][a-z0-9-]*") || !identifiers.add(id))
				throw new IOException("Suite has an invalid or duplicate test-case id: " + id);
			requiredFile(suitePath, text(testCase, "prompt", suitePath), "Test prompt", id);
			if (testCase.has("fixture"))
				requiredDirectory(suitePath, text(testCase, "fixture", suitePath), "Test fixture", id);
			ArrayNode assertions = array(testCase.path("assertions"), "assertions", suitePath);
			if (assertions.isEmpty())
				throw new IOException("Test case has no independent acceptance assertions: " + id);
			Set<String> assertionIds = new HashSet<>();
			Set<String> deterministicAssertionIds = new HashSet<>();
			Set<String> semanticAssertionIds = new HashSet<>();
			for (JsonNode assertion : assertions)
			{
				ObjectNode assertionObject = object(assertion, "assertion");
				String assertionId = text(assertionObject, "id", suitePath);
				if (!assertionIds.add(assertionId))
					throw new IOException("Scenario has duplicate evaluator assertion id: " + assertionId);
				if (EvaluationKind.parse(text(assertionObject, "evaluation_kind", suitePath)) == EvaluationKind.DETERMINISTIC)
					deterministicAssertionIds.add(assertionId);
				else
					semanticAssertionIds.add(assertionId);
				if (assertionObject.has("failure_class"))
					FailureClass.parse(text(assertionObject, "failure_class", suitePath));
				if (assertionObject.has("obligation_family"))
					text(assertionObject, "obligation_family", suitePath);
				text(assertionObject, "instruction", suitePath);
			}
			boolean evaluatesRouteOrRuntime = testCase.has("route_expectation") || testCase.has("deterministic_check");
			if (evaluatesRouteOrRuntime)
			{
				validateRoutePreparation(suitePath, testCase, id);
				validateMeasurements(testCase, suitePath, id, purpose, diagnosticKind);
				hasOrganicRoute |= testCase.path("route_expectation").path("mode").asString().equals("organic");
				validateDeterministicCheck(testCase, assertionIds, deterministicAssertionIds, suitePath, id, purpose,
					diagnosticKind);
			}
			validateSemanticControls(testCase, semanticAssertionIds, suitePath, id);
			validateFollowUpTurns(suitePath, testCase, id);
		}
		if (purpose == SuitePurpose.CERTIFICATION && hasOrganicRoute)
			routingOwner(suite, suitePath);
		validateGeneralization(suite, suitePath, identifiers);
		validateSprt(object(suite.path("sprt"), "sprt"), suitePath);
	}

	/**
	 * Returns the fixed minimum compliance rate that certification accepts.
	 *
	 * @return the accepted compliance rate
	 */
	public static double requiredCompliance()
	{
		return REQUIRED_COMPLIANCE;
	}

	/**
	 * Validates whether the supplied SPRT thresholds are the evaluator's fixed certification contract.
	 *
	 * @param sprt the declared SPRT thresholds
	 * @param suitePath the suite source used in diagnostics
	 * @throws IOException if the thresholds do not distinguish acceptance from rejection or differ from the contract
	 */
	public static void validateSprt(ObjectNode sprt, Path suitePath) throws IOException
	{
		double acceptRate = probability(sprt, "accept_pass_rate", suitePath);
		double rejectRate = probability(sprt, "reject_pass_rate", suitePath);
		double alpha = probability(sprt, "false_accept_rate", suitePath);
		double beta = probability(sprt, "false_reject_rate", suitePath);
		if (acceptRate <= rejectRate || alpha >= 0.5 || beta >= 0.5)
			throw new IOException("Suite SPRT thresholds do not distinguish acceptance from rejection: " + suitePath);
		if (Double.compare(acceptRate, REQUIRED_COMPLIANCE) != 0 ||
			Double.compare(rejectRate, UNACCEPTABLE_COMPLIANCE) != 0 ||
			Double.compare(alpha, CERTIFICATION_FALSE_POSITIVE_RATE) != 0 ||
			Double.compare(beta, SPRT_FALSE_REJECTION_RATE) != 0)
		{
			throw new IOException("Prompt evaluation supports only 95%/85% SPRT with 5% error limits: " + suitePath);
		}
	}

	/**
	 * Validates the declaration that separates fixture-only details from general reader guidance.
	 *
	 * @param suite the testcase suite
	 * @param suitePath the suite manifest
	 * @param caseIds all declared testcase identifiers
	 * @throws IOException if the declaration is incomplete or names an absent testcase
	 */
	public static void validateGeneralization(ObjectNode suite, Path suitePath, Set<String> caseIds) throws IOException
	{
		if (!suite.has("generalization"))
			return;
		ObjectNode generalization = object(suite.path("generalization"), "generalization");
		ArrayNode distinctives = array(generalization.path("fixture_distinctives"), "fixture_distinctives", suitePath);
		if (distinctives.isEmpty())
			throw new IOException("Suite generalization requires one or more fixture_distinctives: " + suitePath);
		Set<String> phrases = new HashSet<>();
		for (JsonNode node : distinctives)
		{
			ObjectNode distinctive = object(node, "fixture distinctive");
			String caseId = text(distinctive, "case_id", suitePath);
			if (!caseIds.contains(caseId))
				throw new IOException("Fixture distinctive names no testcase: " + caseId);
			String phrase = text(distinctive, "text", suitePath);
			if (!phrases.add(phrase.toLowerCase(java.util.Locale.ROOT)))
				throw new IOException("Suite generalization repeats fixture-specific phrase: " + phrase);
			if (distinctive.has("external_authority"))
			{
				ObjectNode authority = object(distinctive.path("external_authority"), "external authority");
				requiredFile(suitePath, text(authority, "path", suitePath), "External authority", caseId);
			}
		}
	}

	/**
	 * Reads one strict probability value.
	 *
	 * @param value the containing JSON object
	 * @param field the field name
	 * @param source the source path
	 * @return the probability
	 * @throws IOException if the field is absent or outside the open unit interval
	 */
	public static double probability(ObjectNode value, String field, Path source) throws IOException
	{
		if (!value.has(field) || !value.get(field).isNumber())
			throw new IOException("Missing numeric " + field + ": " + source);
		double result = value.get(field).asDouble();
		if (result <= 0 || result >= 1)
			throw new IOException("Probability must be between zero and one: " + field);
		return result;
	}

	/**
	 * Returns the declared identifiers for one kind of evaluation decision.
	 *
	 * @param testCase the testcase declaration
	 * @param source the source used for diagnostics
	 * @param evaluationKind the requested decision kind
	 * @return the declared assertion identifiers of that kind
	 * @throws IOException if the declaration is invalid
	 */
	public static Set<String> assertionIds(ObjectNode testCase, Path source, EvaluationKind evaluationKind)
		throws IOException
	{
		Set<String> result = new HashSet<>();
		for (JsonNode assertion : array(testCase.path("assertions"), "assertions", source))
		{
			ObjectNode assertionObject = object(assertion, "assertion");
			if (EvaluationKind.parse(text(assertionObject, "evaluation_kind", source)) == evaluationKind)
				result.add(text(assertionObject, "id", source));
		}
		return result;
	}

	/**
	 * Returns the assertion identifiers owned by a testcase's deterministic checks.
	 *
	 * @param testCase the selected testcase
	 * @param source the declaration source used in diagnostics
	 * @return the nonempty owned assertion identifiers
	 * @throws IOException if the deterministic-check declaration is invalid
	 */
	public static Set<String> deterministicAssertionIds(ObjectNode testCase, Path source) throws IOException
	{
		Set<String> result = new HashSet<>();
		if (testCase.has("deterministic_check"))
			for (JsonNode node : array(object(testCase.path("deterministic_check"), "deterministic check").path("assertions"),
				"deterministic check assertions", source))
				if (!node.isString() || !result.add(node.asString()))
					throw new IOException("Deterministic check has an invalid or duplicate assertion identifier: " + source);
		if (testCase.has("java_check_assertions"))
			for (JsonNode node : array(testCase.path("java_check_assertions"), "Java check assertions", source))
				if (!node.isString() || !result.add(node.asString()))
					throw new IOException("Java check has an invalid or duplicate assertion identifier: " + source);
		if (result.isEmpty())
			throw new IOException("Deterministic check has no assertions: " + source);
		return result;
	}

	/**
	 * Returns deterministic-check results that a direct-application diagnostic retains only as prior evidence.
	 *
	 * @param testCase the selected testcase
	 * @param source the declaration source used in diagnostics
	 * @return the declared ignored result identifiers
	 * @throws IOException if the declaration is invalid
	 */
	public static Set<String> ignoredDeterministicAssertionIds(ObjectNode testCase, Path source) throws IOException
	{
		JsonNode declaredCheck = testCase.path("deterministic_check");
		if (declaredCheck.isMissingNode())
			return Set.of();
		ObjectNode check = object(declaredCheck, "deterministic check");
		JsonNode declared = check.path("ignored_result_assertions");
		if (declared.isMissingNode())
			return Set.of();
		Set<String> owned = deterministicAssertionIds(testCase, source);
		Set<String> result = new HashSet<>();
		for (JsonNode node : array(declared, "ignored deterministic check assertions", source))
		{
			if (!node.isString() || !result.add(node.asString()) || owned.contains(node.asString()))
				throw new IOException("Deterministic check has an invalid ignored result assertion: " + source);
		}
		if (result.isEmpty())
			throw new IOException("Deterministic check has no ignored result assertions: " + source);
		return result;
	}

	/**
	 * Returns the bounded diagnostic kind declared by a diagnostic suite.
	 *
	 * @param suite the suite declaration
	 * @param suitePath the suite source used in diagnostics
	 * @param purpose the suite purpose
	 * @return the diagnostic kind, or {@code null} for certification suites
	 * @throws IOException if the kind is incompatible with {@code purpose}
	 */
	public static DiagnosticKind diagnosticKind(ObjectNode suite, Path suitePath, SuitePurpose purpose)
		throws IOException
	{
		JsonNode declared = suite.path("diagnostic_kind");
		if (purpose == SuitePurpose.CERTIFICATION)
		{
			if (!declared.isMissingNode())
				throw new IOException("A certification suite cannot declare diagnostic_kind: " + suitePath);
			return null;
		}
		if (!declared.isString() || declared.asString().isBlank())
			throw new IOException("A diagnostic suite must declare diagnostic_kind: " + suitePath);
		return DiagnosticKind.parse(declared.asString());
	}

	/**
	 * Returns the declared rules-root-relative Markdown owner for organic certification routing.
	 *
	 * @param suite the certification suite
	 * @param suitePath the suite source used in diagnostics
	 * @return the nonblank owner path
	 * @throws IOException if the owner is absent or unsafe
	 */
	public static String routingOwner(ObjectNode suite, Path suitePath) throws IOException
	{
		JsonNode declared = suite.path("routing_owner");
		if (!declared.isString())
			throw new IOException("An organically routed certification suite must declare routing_owner as a relative " +
				"Markdown path below the rules root, for example common/default-rules.md: " + suitePath);
		String value = declared.stringValue();
		Path owner = Path.of(value);
		if (value.isBlank() || owner.isAbsolute() || !value.endsWith(".md") || owner.normalize().startsWith(".."))
		{
			throw new IOException("routing_owner must be a nonblank relative Markdown path below the rules root, for " +
				"example common/default-rules.md; received '" + value + "' in " + suitePath);
		}
		return value;
	}

	/**
	 * Indicates whether a declared route field supplies one or more safe relative rule paths.
	 *
	 * @param declared the optional route field
	 * @param caseId the testcase identifier used in diagnostics
	 * @param field the route field name
	 * @return {@code true} when the field supplies paths
	 * @throws IOException if a supplied path is invalid
	 */
	public static boolean nonemptyRoutePaths(JsonNode declared, String caseId, String field) throws IOException
	{
		if (declared.isMissingNode())
			return false;
		if (!declared.isArray() || declared.isEmpty())
			throw new IOException("Certification route expectation " + field + " must be a nonempty path array: " + caseId);
		for (JsonNode entry : declared)
		{
			String value = entry.stringValue();
			if (!entry.isString() || value.isBlank() || value.startsWith("/") || value.contains(".."))
				throw new IOException("Certification route expectation has an invalid " + field + " path: " + caseId);
		}
		return true;
	}

	/**
	 * Validates optional testcase messages that follow the initial task turn.
	 *
	 * @param suitePath the suite root
	 * @param testCase the testcase declaration
	 * @param caseId the testcase identifier
	 * @throws IOException if a declared follow-up message is incompatible with the suite contract
	 */
	public static void validateFollowUpTurns(Path suitePath, ObjectNode testCase, String caseId) throws IOException
	{
		JsonNode messages = testCase.path("turns");
		if (messages.isMissingNode())
			return;
		for (JsonNode message : array(messages, "turns", suitePath))
		{
			ObjectNode declared = object(message, "test turn");
			String type = text(declared, "type", suitePath);
			if (!Set.of("turn", "steer", "interrupt").contains(type))
				throw new IOException("Test case has an unsupported turn type: " + type);
			boolean suppliesPrompt = type.equals("turn") || type.equals("steer");
			if (suppliesPrompt)
				requiredFile(suitePath, text(declared, "prompt", suitePath), "Test turn prompt", caseId);
			else if (declared.has("prompt"))
				throw new IOException("Interrupt test turn must not define a prompt: " + caseId);
			boolean requiresActiveTurn = type.equals("steer") || type.equals("interrupt");
			if (requiresActiveTurn && !"turn-started".equals(text(declared, "after", suitePath)))
				throw new IOException("Mid-turn test control must wait for turn-started: " + caseId);
			if (!requiresActiveTurn && declared.has("after"))
				throw new IOException("Follow-up turn must not define after: " + caseId);
		}
	}

	/**
	 * Validates response fixtures that calibrate one semantic reviewer assertion at a time.
	 *
	 * @param testCase the testcase that declares controls
	 * @param semanticAssertionIds the assertion identifiers owned by semantic review
	 * @param suitePath the suite root
	 * @param caseId the stable testcase identifier
	 * @throws IOException if a control cannot isolate one semantic assertion
	 */
	public static void validateSemanticControls(ObjectNode testCase, Set<String> semanticAssertionIds, Path suitePath,
		String caseId) throws IOException
	{
		JsonNode declared = testCase.path("semantic_controls");
		if (declared.isMissingNode())
			return;
		if (semanticAssertionIds.isEmpty())
			throw new IOException("Semantic controls require semantic assertions: " + caseId);
		if (!declared.isArray() || declared.isEmpty())
			throw new IOException("Semantic controls must be a nonempty array: " + caseId);
		Set<String> controlIds = new HashSet<>();
		for (JsonNode node : declared)
		{
			ObjectNode control = object(node, "semantic control");
			String controlId = text(control, "id", suitePath);
			if (!controlId.matches("[a-z][a-z0-9-]*") || !controlIds.add(controlId))
				throw new IOException("Semantic control has an invalid or duplicate id: " + caseId);
			requiredFile(suitePath, text(control, "response", suitePath), "Semantic control response", caseId);
			ObjectNode expected = object(control.path("expected_assertions"), "semantic control expectations");
			Set<String> expectedIds = new HashSet<>();
			expected.propertyNames().forEach(expectedIds::add);
			if (!expectedIds.equals(semanticAssertionIds))
			{
				throw new IOException("Semantic control must cover exactly the semantic assertions: " + caseId + "/" +
					controlId);
			}
			int failures = 0;
			for (String assertionId : semanticAssertionIds)
			{
				String status = text(expected, assertionId, suitePath);
				if (!status.equals("passed") && !status.equals("failed"))
					throw new IOException("Semantic control has an invalid assertion status: " + caseId + "/" + controlId);
				if (status.equals("failed"))
					++failures;
			}
			if (failures != 1)
				throw new IOException("Semantic control must fail exactly one semantic assertion: " + caseId + "/" + controlId);
		}
	}

	/**
	 * Returns the declared deterministic-check implementation kind.
	 *
	 * @param check the deterministic-check declaration
	 * @param suitePath the declaration source used in diagnostics
	 * @return {@code command} or {@code organic_route_delivery}
	 * @throws IOException if the type is unsupported
	 */
	public static String deterministicCheckType(ObjectNode check, Path suitePath) throws IOException
	{
		String type = check.path("type").asString("command");
		if (!type.equals("command") && !type.equals("organic_route_delivery"))
			throw new IOException("Deterministic check has an unsupported type: " + suitePath);
		return type;
	}

	/**
	 * Returns the sole assertion owned by an organic route-delivery check.
	 *
	 * @param testCase the testcase declaration
	 * @param suitePath the declaration source used in diagnostics
	 * @return the deterministic route-delivery assertion identifier
	 * @throws IOException if the check owns anything other than one assertion
	 */
	public static String organicRouteDeliveryAssertion(ObjectNode testCase, Path suitePath) throws IOException
	{
		Set<String> assertions = deterministicAssertionIds(testCase, suitePath);
		if (assertions.size() != 1)
			throw new IOException("An organic route-delivery check must own exactly one assertion: " + suitePath);
		return assertions.iterator().next();
	}

	/**
	 * Indicates whether a deterministic check requires a writable isolated project copy.
	 *
	 * @param check the deterministic-check declaration
	 * @param source the declaration source used in diagnostics
	 * @return {@code true} when the check requires a writable project
	 * @throws IOException if the optional declaration is not boolean
	 */
	public static boolean requiresWritableProject(ObjectNode check, Path source) throws IOException
	{
		JsonNode declared = check.path("writable_project");
		if (declared.isMissingNode())
			return false;
		if (!declared.isBoolean())
			throw new IOException("Deterministic check writable_project must be true or false: " + source);
		return declared.asBoolean();
	}

	/**
	 * Indicates whether a generated routing matrix uses the evaluator-owned native-event check.
	 *
	 * @param testCase the generated testcase
	 * @param deterministicAssertionIds its deterministic assertion identifiers
	 * @param purpose the suite purpose
	 * @param diagnosticKind the declared diagnostic kind
	 * @return {@code true} if the evaluator owns the routing-only assertion
	 */
	public static boolean isBuiltInRoutingMatrixCheck(ObjectNode testCase, Set<String> deterministicAssertionIds,
		SuitePurpose purpose, DiagnosticKind diagnosticKind)
	{
		return purpose == SuitePurpose.DIAGNOSTIC && diagnosticKind == DiagnosticKind.ROUTING_ONLY &&
			testCase.path("routing_diagnostic").asBoolean(false) && deterministicAssertionIds.equals(Set.of("routing-only"));
	}

	/**
	 * Validates the substitutions that a deterministic check may receive from the evaluator.
	 *
	 * @param entries the check command entries
	 * @throws IOException if the command refers to an unavailable evaluator value
	 */
	public static void validateDeterministicCheckCommand(JsonNode entries) throws IOException
	{
		List<String> expanded = CommandArguments.expand(entries, "deterministic check",
			Map.of("fixture", "fixture", "check", "check", "project", "project", "executor_result",
				"executor-result", "output", "output"));
		for (String entry : expanded)
			if (entry.contains("${"))
				throw new IOException("Deterministic check command refers to an unsupported evaluator value: " + entry);
	}

	/**
	 * Validates that a deterministic check owns exactly its declared deterministic assertions and controls.
	 *
	 * @param testCase the testcase declaration
	 * @param assertionIds the testcase assertion identifiers
	 * @param deterministicAssertionIds the deterministic assertion identifiers
	 * @param suitePath the suite manifest
	 * @param caseId the testcase identifier
	 * @param purpose the suite purpose
	 * @param diagnosticKind the diagnostic kind, when applicable
	 * @throws IOException if the deterministic-check declaration is invalid
	 */
	public static void validateDeterministicCheck(ObjectNode testCase, Set<String> assertionIds,
		Set<String> deterministicAssertionIds, Path suitePath, String caseId, SuitePurpose purpose,
		DiagnosticKind diagnosticKind) throws IOException
	{
		JsonNode declared = testCase.path("deterministic_check");
		if (declared.isMissingNode())
		{
			if (isBuiltInRoutingMatrixCheck(testCase, deterministicAssertionIds, purpose, diagnosticKind))
				return;
			if (!deterministicAssertionIds.isEmpty())
				throw new IOException("Deterministic assertions require a deterministic check: " + caseId);
			return;
		}
		ObjectNode check = object(declared, "deterministic check");
		boolean builtInRouteDelivery = deterministicCheckType(check, suitePath).equals("organic_route_delivery");
		if (!builtInRouteDelivery)
			validateDeterministicCheckCommand(check.path("command"));
		if (builtInRouteDelivery)
		{
			ObjectNode expectation = object(testCase.path("route_expectation"), "route expectation");
			if (!text(expectation, "mode", suitePath).equals("organic"))
				throw new IOException("An organic route-delivery check requires an organic route expectation: " + caseId);
		}
		else
			requiredFile(suitePath, text(check, "artifact", suitePath), "Deterministic check artifact", caseId);
		Set<String> ownedAssertions = deterministicAssertionIds(testCase, suitePath);
		Set<String> ignoredAssertions = ignoredDeterministicAssertionIds(testCase, suitePath);
		if (!ignoredAssertions.isEmpty() &&
			(purpose != SuitePurpose.DIAGNOSTIC || diagnosticKind != DiagnosticKind.DIRECT_APPLICATION))
		{
			throw new IOException("Only a direct-application diagnostic may ignore preserved deterministic results: " +
				caseId);
		}
		if (!assertionIds.containsAll(ownedAssertions))
			throw new IOException("Deterministic check owns an unknown assertion: " + caseId);
		if (!ownedAssertions.equals(deterministicAssertionIds))
			throw new IOException("Deterministic check must own exactly the deterministic assertions: " + caseId);
		JsonNode selfTests = check.path("self_tests");
		if (selfTests.isMissingNode())
			return;
		if (!selfTests.isArray() || selfTests.isEmpty())
			throw new IOException("Deterministic check self_tests must be a nonempty array: " + caseId);
		Set<String> observedControlStates = new HashSet<>();
		for (JsonNode node : selfTests)
		{
			ObjectNode control = object(node, "deterministic check control fixture");
			validateSupportedFields(control, Set.of("project", "evidence_events", "expected_assertions"),
				"deterministic check control", suitePath);
			requiredDirectory(suitePath, text(control, "project", suitePath), "Check control project", caseId);
			requiredFile(suitePath, text(control, "evidence_events", suitePath), "Check control events", caseId);
			ObjectNode expected = object(control.path("expected_assertions"), "deterministic check control expectations");
			Set<String> expectedIds = new HashSet<>();
			expected.propertyNames().forEach(expectedIds::add);
			if (!expectedIds.equals(ownedAssertions))
				throw new IOException("Deterministic check control fixture does not cover its assertions: " + caseId);
			for (String assertionId : ownedAssertions)
			{
				String expectedStatus = text(expected, assertionId, suitePath);
				if (!expectedStatus.equals("passed") && !expectedStatus.equals("failed"))
					throw new IOException("Deterministic check control fixture has an invalid status: " + caseId);
				observedControlStates.add(expectedStatus);
			}
		}
		if (!observedControlStates.containsAll(Set.of("passed", "failed")))
			throw new IOException("Deterministic check self_tests must include known-good and known-bad fixtures: " +
				caseId);
	}

	/**
	 * Validates optional rule paths that the runner must deliver before a testcase task.
	 *
	 * @param suitePath the suite root
	 * @param testCase the testcase declaration
	 * @param caseId the testcase identifier
	 * @throws IOException if a declared path is not a nonempty safe relative rule path
	 */
	public static void validateRoutePreparation(Path suitePath, ObjectNode testCase, String caseId) throws IOException
	{
		JsonNode declared = testCase.path("route_preparation");
		if (declared.isMissingNode())
			return;
		if (!declared.isArray() || declared.isEmpty())
			throw new IOException("Test case route_preparation must be a nonempty array: " + caseId);
		for (JsonNode entry : declared)
		{
			String path = entry.stringValue();
			if (!entry.isString() || path.isBlank() || path.startsWith("/") || path.contains(".."))
				throw new IOException("Test case route_preparation contains an invalid relative rule path: " + caseId);
		}
	}

	/**
	 * Validates each claimed evaluation product against the testcase's delivery setup.
	 *
	 * @param testCase the testcase declaration
	 * @param suitePath the suite manifest
	 * @param caseId the stable testcase identifier
	 * @param purpose the suite purpose
	 * @param diagnosticKind the diagnostic kind, when applicable
	 * @throws IOException if a measurement or route expectation is incompatible with the contract
	 */
	public static void validateMeasurements(ObjectNode testCase, Path suitePath, String caseId, SuitePurpose purpose,
		DiagnosticKind diagnosticKind) throws IOException
	{
		JsonNode declared = testCase.path("measures");
		if (!declared.isArray() || declared.isEmpty())
			throw new IOException("Test case must declare one or more measured products: " + caseId);
		Set<Measurement> measurements = EnumSet.noneOf(Measurement.class);
		for (JsonNode entry : declared)
		{
			if (!entry.isString())
				throw new IOException("Test case measures must contain product names: " + caseId);
			if (!measurements.add(Measurement.parse(entry.stringValue())))
				throw new IOException("Test case measures repeats a product: " + caseId);
		}
		boolean routePreparation = !testCase.path("route_preparation").isMissingNode();
		boolean eagerDelivery = testCase.path("route_expectation").path("mode").asString().equals("eager");
		if (purpose == SuitePurpose.CERTIFICATION)
		{
			if (routePreparation)
				throw new IOException("A certification testcase cannot declare route_preparation: " + caseId);
			validateRouteExpectation(testCase, suitePath, caseId, measurements, true);
			return;
		}
		if (diagnosticKind == DiagnosticKind.ROUTING_ONLY)
		{
			if (routePreparation || !measurements.equals(Set.of(Measurement.ORGANIC_SELECTION)))
				throw new IOException("A routing-only diagnostic must measure only organic selection without direct " +
					"delivery: " + caseId);
			validateRouteExpectation(testCase, suitePath, caseId, measurements, false);
			return;
		}
		if (routePreparation || eagerDelivery)
		{
			if (measurements.contains(Measurement.ORGANIC_SELECTION))
				throw new IOException("Test case cannot claim organic selection when CAT supplies the rule before the task: " +
					caseId);
			if (!measurements.containsAll(Set.of(Measurement.DELIVERY, Measurement.APPLICATION)))
				throw new IOException("A testcase with rule delivery before the task must measure both delivery and " +
					"application: " + caseId);
		}
		else
		{
			if (measurements.contains(Measurement.DELIVERY))
				throw new IOException("A testcase cannot measure delivery when no rule is supplied before the task: " + caseId);
			if (measurements.contains(Measurement.ORGANIC_SELECTION) && !measurements.contains(Measurement.APPLICATION))
				throw new IOException("An organic-selection testcase must also measure application: " + caseId);
		}
	}

	/**
	 * Validates the route expectation that establishes an organic or eager measurement.
	 *
	 * @param testCase the testcase declaration
	 * @param suitePath the suite manifest
	 * @param caseId the stable testcase identifier
	 * @param measurements the products the testcase claims to measure
	 * @param requiresApplication whether the expectation must establish task application
	 * @throws IOException if the route expectation is incompatible with the claimed measurement
	 */
	private static void validateRouteExpectation(ObjectNode testCase, Path suitePath, String caseId,
		Set<Measurement> measurements, boolean requiresApplication) throws IOException
	{
		JsonNode declared = testCase.path("route_expectation");
		if (!declared.isObject())
			throw new IOException("A certification testcase requires an evaluator-only route_expectation: " + caseId);
		ObjectNode expectation = object(declared, "route expectation");
		String mode = text(expectation, "mode", suitePath);
		if (mode.equals("organic"))
		{
			Set<Measurement> requiredMeasurements = Set.of(Measurement.ORGANIC_SELECTION);
			if (requiresApplication)
				requiredMeasurements = Set.of(Measurement.ORGANIC_SELECTION, Measurement.APPLICATION);
			if (!measurements.equals(requiredMeasurements))
				throw new IOException("An organic testcase has incompatible measured products: " + caseId);
			boolean selected = nonemptyRoutePaths(expectation.path("selected"), caseId, "selected");
			nonemptyRoutePaths(expectation.path("optional_selected"), caseId, "optional_selected");
			boolean notSelected = nonemptyRoutePaths(expectation.path("not_selected"), caseId, "not_selected");
			if (!selected && !notSelected)
				throw new IOException("An organic certification testcase must expect selected and/or not_selected paths: " +
					caseId);
			Set<String> required = routePaths(expectation.path("selected"), caseId, "selected");
			Set<String> optional = routePaths(expectation.path("optional_selected"), caseId, "optional_selected");
			Set<String> excluded = routePaths(expectation.path("not_selected"), caseId, "not_selected");
			if (!Collections.disjoint(required, optional) || !Collections.disjoint(required, excluded) ||
				!Collections.disjoint(optional, excluded))
				throw new IOException("Organic route expectation assigns one path conflicting selection roles: " + caseId);
			return;
		}
		if (mode.equals("eager"))
		{
			if (measurements.contains(Measurement.ORGANIC_SELECTION) || !measurements.contains(Measurement.APPLICATION))
				throw new IOException("An eager certification testcase must measure application without organic selection: " +
					caseId);
			nonemptyRoutePaths(expectation.path("delivered"), caseId, "delivered");
			return;
		}
		throw new IOException("Certification route expectation has an unsupported mode: " + mode);
	}

	/**
	 * Returns the safe paths declared for one route-selection role.
	 *
	 * @param declared the optional route path array
	 * @param caseId the testcase identifier used in diagnostics
	 * @param field the selection-role field name
	 * @return the declared paths, or an empty set when the field is absent
	 * @throws IOException if the supplied paths are invalid
	 */
	private static Set<String> routePaths(JsonNode declared, String caseId, String field) throws IOException
	{
		if (declared.isMissingNode())
			return Set.of();
		nonemptyRoutePaths(declared, caseId, field);
		Set<String> result = new HashSet<>();
		for (JsonNode entry : declared)
			result.add(entry.stringValue());
		return result;
	}

	/**
	 * Finds one declared test case in a materialized suite.
	 *
	 * @param suite the materialized suite contract
	 * @param caseId the declared testcase identifier
	 * @param suitePath the suite source used in diagnostics
	 * @return the declared testcase
	 * @throws IOException if {@code caseId} is absent from {@code suite}
	 */
	public static ObjectNode testCase(ObjectNode suite, String caseId, Path suitePath) throws IOException
	{
		for (JsonNode node : (ArrayNode) suite.path("cases"))
		{
			ObjectNode testCase = object(node, "test case");
			if (text(testCase, "id", suitePath).equals(caseId))
				return testCase;
		}
		throw new IOException("Suite has no test case: " + caseId);
	}

	/**
	 * Resolves one regular suite input without allowing the declared path to escape the suite root.
	 *
	 * @param suitePath the scenario-suite root
	 * @param relativePath the declared suite-relative file path
	 * @param description the file's reader-visible purpose
	 * @param caseId the owning testcase identifier
	 * @return the existing regular file beneath the suite root
	 * @throws IOException if the suite is invalid or the declared file is absent or escapes its root
	 */
	public static Path requiredFile(Path suitePath, String relativePath, String description, String caseId)
		throws IOException
	{
		Path root = root(suitePath);
		Path result = root.resolve(relativePath).normalize();
		if (!result.startsWith(root) || !Files.isRegularFile(result))
			throw new IOException(description + " is absent or escapes its suite: " + result + " (test case " + caseId + ")");
		return result;
	}

	/**
	 * Resolves one fixture directory without allowing the declared path to escape the suite root.
	 *
	 * @param suitePath the scenario-suite root
	 * @param relativePath the declared suite-relative directory path
	 * @param description the directory's reader-visible purpose
	 * @param caseId the owning testcase identifier
	 * @return the existing fixture directory beneath the suite root
	 * @throws IOException if the suite is invalid or the declared directory is absent or escapes its root
	 */
	public static Path requiredDirectory(Path suitePath, String relativePath, String description, String caseId)
		throws IOException
	{
		Path root = root(suitePath);
		Path result = root.resolve(relativePath).normalize();
		if (!result.startsWith(root) || !Files.isDirectory(result))
			throw new IOException(description + " is absent or escapes its suite: " + caseId);
		return result;
	}

	/**
	 * Returns the normalized root of one existing scenario suite.
	 *
	 * @param suitePath the scenario-suite root
	 * @return the normalized root directory
	 * @throws IOException if {@code suitePath} is not a directory
	 */
	public static Path root(Path suitePath) throws IOException
	{
		if (!Files.isDirectory(suitePath))
			throw new IOException("Expected scenario-suite directory: " + suitePath);
		return suitePath.toAbsolutePath().normalize();
	}

	/**
	 * Adds one materialized scenario to the suite contract.
	 *
	 * @param suite the suite declaration
	 * @param declaredScenarios the optional scenario metadata
	 * @param cases the materialized case collection
	 * @param root the suite root
	 * @param manifest the suite manifest
	 * @param scenarioPath the scenario Markdown file
	 * @throws IOException if the scenario is invalid
	 */
	private static void addScenario(ObjectNode suite, JsonNode declaredScenarios, ArrayNode cases, Path root,
		Path manifest, Path scenarioPath) throws IOException
	{
		Path scenarioDirectory = scenarioPath.getParent();
		String id = scenarioDirectory.getFileName().toString();
		if (!id.matches("[a-z][a-z0-9-]*"))
			throw new IOException("Scenario has an invalid stable id: " + scenarioDirectory);
		ScenarioDocument.Parsed scenario = ScenarioDocument.parse(Files.readString(scenarioPath), scenarioPath);
		ObjectNode testCase = JsonNodeFactory.instance.objectNode();
		if (!declaredScenarios.isMissingNode() && declaredScenarios.has(id))
			testCase = object(declaredScenarios.path(id), "scenario metadata").deepCopy();
		validateSupportedFields(testCase, SUPPORTED_METADATA_FIELDS, "scenario metadata", scenarioPath);
		cases.add(testCase);
		testCase.put("id", id);
		testCase.put("scenario", true);
		discoverJavaChecks(suite, testCase, manifest);
		testCase.put("prompt", root.relativize(scenarioPath).toString());
		Path fixture = scenarioDirectory.resolve("project");
		if (Files.exists(fixture))
			testCase.put("fixture", root.relativize(fixture).toString());
		Set<String> deterministic = Set.of();
		if (testCase.has("deterministic_check"))
			deterministic = deterministicAssertionIds(testCase, scenarioPath);
		ObjectNode families = assertionMetadata(testCase, "assertion_obligation_families");
		ObjectNode failureClasses = assertionMetadata(testCase, "assertion_failure_classes");
		ArrayNode assertions = testCase.putArray("assertions");
		Set<String> ids = new HashSet<>();
		for (ScenarioDocument.Assertion declaration : scenario.assertions())
		{
			String assertionId = declaration.id();
			ids.add(assertionId);
			if (deterministic.contains(assertionId))
				throw new IOException("Scenario must not describe an assertion owned by its deterministic check: " +
					scenarioPath);
			ObjectNode assertion = assertions.addObject().put("id", assertionId).put("evaluation_kind", "semantic").
				put("instruction", declaration.proposition());
			if (families.has(assertionId))
				assertion.put("obligation_family", text(families, assertionId, scenarioPath));
			if (failureClasses.has(assertionId))
				assertion.put("failure_class", text(failureClasses, assertionId, scenarioPath));
		}
		if (assertions.isEmpty() && deterministic.isEmpty() && !testCase.path("routing_diagnostic").asBoolean(false))
			throw new IOException("Scenario has no evaluator assertions: " + scenarioPath);
		if (testCase.path("routing_diagnostic").asBoolean(false))
			assertions.addObject().put("id", "routing-only").put("evaluation_kind", "deterministic").
				put("instruction", "The evaluator's routing diagnostic check passes.");
		for (String assertionId : deterministic)
		{
			ids.add(assertionId);
			ObjectNode assertion = assertions.addObject().put("id", assertionId).put("evaluation_kind", "deterministic").
				put("instruction", "The configured deterministic check passes.");
			if (failureClasses.has(assertionId))
				assertion.put("failure_class", text(failureClasses, assertionId, scenarioPath));
		}
		validateAssertionMetadata(families, ids, scenarioPath);
		validateAssertionMetadata(failureClasses, ids, scenarioPath);
	}

	/**
	 * Adds the conventional Java-check class for one scenario when the suite declares its package.
	 *
	 * @param suite the suite declaration
	 * @param scenarioMetadata the scenario metadata to enrich
	 * @param manifest the suite manifest
	 * @throws IOException if the configured package is invalid
	 */
	private static void discoverJavaChecks(ObjectNode suite, ObjectNode scenarioMetadata, Path manifest)
		throws IOException
	{
		JsonNode packageNode = suite.path("evaluation_test_package");
		if (packageNode.isMissingNode())
			return;
		if (!packageNode.isString() || !packageNode.asString().matches(
			"[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
			throw new IOException("Scenario suite has an invalid evaluation-test package: " + manifest);
		StringBuilder className = new StringBuilder(packageNode.asString()).append('.');
		for (String part : text(scenarioMetadata, "id", manifest).split("-"))
			className.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		scenarioMetadata.put("java_check_class", className.append("ScenarioTest").toString());
	}

	/**
	 * Returns one optional assertion metadata mapping.
	 *
	 * @param scenarioMetadata the scenario metadata
	 * @param field the mapping field
	 * @return the declared mapping, or an empty mapping when it is absent
	 * @throws IOException if the mapping is not an object
	 */
	private static ObjectNode assertionMetadata(ObjectNode scenarioMetadata, String field) throws IOException
	{
		JsonNode declaration = scenarioMetadata.path(field);
		if (declaration.isMissingNode())
			return JsonNodeFactory.instance.objectNode();
		return object(declaration, "assertion metadata");
	}

	/**
	 * Rejects metadata that names no materialized scenario assertion.
	 *
	 * @param declarations the metadata mapping
	 * @param assertionIds the materialized assertion identifiers
	 * @param scenarioPath the scenario source
	 * @throws IOException if metadata names an absent assertion
	 */
	private static void validateAssertionMetadata(ObjectNode declarations, Set<String> assertionIds, Path scenarioPath)
		throws IOException
	{
		for (String assertionId : declarations.propertyNames())
			if (!assertionIds.contains(assertionId))
				throw new IOException("Scenario metadata names an undeclared assertion: " + assertionId + " in " +
					scenarioPath);
	}

	/**
	 * Rejects fields outside one materialized object contract.
	 *
	 * @param value the object to inspect
	 * @param supportedFields the allowed field names
	 * @param contractName the contract name for diagnostics
	 * @param path the source path
	 * @throws IOException if an unsupported field is present
	 */
	private static void validateSupportedFields(ObjectNode value, Set<String> supportedFields, String contractName,
		Path path) throws IOException
	{
		for (String field : value.propertyNames())
			if (!supportedFields.contains(field))
				throw new IOException("Unsupported " + contractName + " field: " + field + " in " + path);
	}
}

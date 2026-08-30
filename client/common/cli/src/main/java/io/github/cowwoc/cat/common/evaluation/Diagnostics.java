package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.cat.common.PromptEvaluation.array;
import static io.github.cowwoc.cat.common.PromptEvaluation.object;
import static io.github.cowwoc.cat.common.PromptEvaluation.path;
import static io.github.cowwoc.cat.common.PromptEvaluation.text;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.nonemptyRoutePaths;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.requiredFile;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.root;
import static io.github.cowwoc.cat.common.evaluation.SuiteContract.testCase;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Recreates bounded diagnostic suites from retained certification evidence.
 * <p>
 * The evaluator supplies only the record interpretation and workspace operations that diagnostics need. This owner
 * neither starts a diagnostic run nor decides a later router-promotion result.
 */
public final class Diagnostics
{
	/**
	 * Prevents construction of this static coordinator.
	 */
	private Diagnostics()
	{
	}

	/**
	 * Supplies evaluator-owned record interpretation and workspace operations to diagnostics.
	 */
	public interface Access
	{
		/**
		 * Returns the mapper owned by the enclosing operation.
		 *
		 * @return the operation mapper
		 */
		JsonMapper json();

		/**
		 * Reads a retained JSON object.
		 *
		 * @param source the JSON artifact
		 * @param description the object description for malformed input
		 * @return the parsed object
		 */
		ObjectNode readObject(Path source, String description) throws IOException;

		/**
		 * Computes a retained suite identity.
		 *
		 * @param suite the retained suite root
		 * @return the suite fingerprint
		 */
		String suiteFingerprint(Path suite) throws IOException;

		/**
		 * Finds a testcase exercised by a retained run.
		 *
		 * @param run the retained evaluation record
		 * @param runPath the record path
		 * @param caseId the testcase identifier
		 * @return the selected testcase
		 */
		ObjectNode testcase(ObjectNode run, Path runPath, String caseId) throws IOException;

		/**
		 * Classifies a retained failed attempt.
		 *
		 * @param runPath the retained evaluation record
		 * @param caseId the testcase identifier
		 * @param trial the whole-prompt trial
		 * @return the established failure classification
		 */
		ObjectNode classifyFailure(Path runPath, String caseId, int trial) throws IOException;

		/**
		 * Extracts the task portion of a scenario.
		 *
		 * @param contents the scenario contents
		 * @param scenario the source scenario
		 * @return the extracted task
		 */
		String scenarioTask(String contents, Path scenario) throws IOException;

		/**
		 * Describes the routing outcome the diagnostic validator must observe.
		 *
		 * @param expectsSelected whether required routes must be selected
		 * @param permitsOptionalSelection whether optional routes may be selected
		 * @param expectsNotSelected whether routes must remain unselected
		 * @return the validator instruction
		 */
		String routingInstruction(boolean expectsSelected, boolean permitsOptionalSelection,
			boolean expectsNotSelected);

		/**
		 * Copies a retained fixture tree.
		 *
		 * @param source the retained fixture tree
		 * @param destination the generated fixture tree
		 */
		void copyFixture(Path source, Path destination) throws IOException;

		/**
		 * Writes a generated JSON object.
		 *
		 * @param destination the generated JSON artifact
		 * @param contents the JSON object to write
		 */
		void write(Path destination, ObjectNode contents) throws IOException;

		/**
		 * Deletes a partially generated diagnostic root.
		 *
		 * @param root the partially generated diagnostic root
		 */
		void delete(Path root) throws IOException;
	}

	/**
	 * Recreates one direct-application diagnostic suite.
	 *
	 * @param access evaluator-owned operations
	 * @param sourceRunPath the retained failed run
	 * @param caseId the failed testcase
	 * @param trial the failed trial
	 * @param outputPath the absent generated-suite root
	 */
	public static void createDirect(Access access, Path sourceRunPath, String caseId, int trial, Path outputPath)
		throws IOException
	{
		Direct.create(access, sourceRunPath, caseId, trial, outputPath);
	}

	/**
	 * Recreates one routing-only diagnostic suite.
	 *
	 * @param access evaluator-owned operations
	 * @param sourceRunPath the retained failed run
	 * @param caseId the failed testcase
	 * @param trial the failed trial
	 * @param outputPath the absent generated-suite root
	 */
	public static void createRouting(Access access, Path sourceRunPath, String caseId, int trial, Path outputPath)
		throws IOException
	{
		Routing.create(access, sourceRunPath, caseId, trial, outputPath);
	}

	/**
	 * Requires retained evidence that authorizes a bounded diagnostic.
	 *
	 * @param access evaluator-owned operations
	 * @param sourceRunPath the retained failed run
	 * @param caseId the failed testcase
	 * @param trial the failed trial
	 * @param diagnosticName the requested diagnostic
	 * @param expectedClass the required failure class
	 * @param requiresOrganicRoute whether the case must use organic routing
	 * @return the immutable certification suite
	 */
	public static Path requireSource(Access access, Path sourceRunPath, String caseId, int trial,
		String diagnosticName, FailureClass expectedClass, boolean requiresOrganicRoute) throws IOException
	{
		return Source.require(access, sourceRunPath, caseId, trial, diagnosticName, expectedClass,
			requiresOrganicRoute);
	}

	/**
	 * Resolves the certification suite named by a diagnostic suite.
	 *
	 * @param access evaluator-owned operations
	 * @param diagnosticSuite the generated diagnostic suite
	 * @return the retained certification suite
	 */
	public static Path sourceCertificationSuite(Access access, Path diagnosticSuite) throws IOException
	{
		return Suite.sourceCertificationSuite(access, diagnosticSuite);
	}
}

final class Source
{
	/**
	 * Prevents construction of this source validator.
	 */
	private Source()
	{
	}

	/**
	 * Requires the retained certification evidence that makes a diagnostic relevant.
	 *
	 * @param access evaluator-owned operations
	 * @param sourceRunPath the retained failed run
	 * @param caseId the failed testcase
	 * @param trial the failed trial
	 * @param diagnosticName the requested diagnostic
	 * @param expectedClass the required failure class
	 * @param requiresOrganicRoute whether the testcase must be organically routed
	 * @return the immutable certification suite
	 */
	static Path require(Diagnostics.Access access, Path sourceRunPath, String caseId, int trial,
		String diagnosticName, FailureClass expectedClass, boolean requiresOrganicRoute) throws IOException
	{
		sourceRunPath = sourceRunPath.toAbsolutePath().normalize();
		ObjectNode sourceRun = access.readObject(sourceRunPath, "diagnostic source run");
		if (!text(sourceRun, "purpose", sourceRunPath).equals("certification"))
		{
			throw new IOException("A " + diagnosticName +
				" diagnostic requires a retained organic certification run: " + sourceRunPath);
		}
		Path certificationSuite = path(text(sourceRun, "suite", sourceRunPath)).toAbsolutePath().normalize();
		if (!Files.isDirectory(certificationSuite, NOFOLLOW_LINKS))
		{
			throw new IOException("Diagnostic source run no longer retains its exercised certification suite: " +
				certificationSuite);
		}
		if (!text(sourceRun, "suite_sha256", sourceRunPath).equals(access.suiteFingerprint(certificationSuite)))
		{
			throw new IOException("Diagnostic source run's retained certification suite no longer matches its recorded " +
				"identity: " + sourceRunPath);
		}
		ObjectNode sourceCase = access.testcase(sourceRun, sourceRunPath, caseId);
		ObjectNode expectation = object(sourceCase.path("route_expectation"), "source route expectation");
		if (requiresOrganicRoute && !text(expectation, "mode", sourceRunPath).equals("organic"))
		{
			throw new IOException("A " + diagnosticName +
				" diagnostic requires an organically routed failed testcase: " + caseId);
		}
		ObjectNode classification = access.classifyFailure(sourceRunPath, caseId, trial);
		FailureClass failureClass = FailureClass.parse(text(classification, "failure_class", sourceRunPath));
		if (failureClass != expectedClass)
		{
			throw new IOException("A " + diagnosticName + " diagnostic requires retained " +
				expectedClass.externalName() + " evidence for " + caseId + ", but this attempt establishes " +
				failureClass.externalName() + ": " + text(classification, "evidence", sourceRunPath));
		}
		return certificationSuite;
	}
}

final class Direct
{
	/**
	 * Prevents construction of this direct-diagnostic builder.
	 */
	private Direct()
	{
	}

	/**
	 * Recreates a direct-application diagnostic from retained unresolved evidence.
	 *
	 * @param access evaluator-owned operations
	 * @param sourceRunPath the retained failed run
	 * @param caseId the failed testcase
	 * @param trial the failed trial
	 * @param outputPath the absent generated-suite root
	 */
	static void create(Diagnostics.Access access, Path sourceRunPath, String caseId, int trial, Path outputPath)
		throws IOException
	{
		outputPath = outputPath.toAbsolutePath().normalize();
		Path certificationSuite = Source.require(access, sourceRunPath, caseId, trial, "direct-application",
			FailureClass.UNRESOLVED, false);
		if (Files.exists(outputPath))
			throw new IOException("Diagnostic manifest already exists: " + outputPath);

		ObjectNode diagnostic = Suite.generate(access.json(), certificationSuite, caseId, "Direct diagnostics");
		ObjectNode selected = testCase(diagnostic, caseId, certificationSuite);
		ObjectNode expectation = object(selected.path("route_expectation"), "route expectation");
		String mode = text(expectation, "mode", certificationSuite);
		if (mode.equals("organic"))
		{
			JsonNode paths = expectation.path("selected");
			if (!nonemptyRoutePaths(paths, caseId, "selected"))
			{
				throw new IOException("A direct diagnostic needs expected selected paths to isolate the failed route: " +
					caseId);
			}
			selected.remove("route_expectation");
			selected.set("route_preparation", paths.deepCopy());
			removeOrganicRoutingAssertions(access.json(), selected);
		}
		else if (!mode.equals("eager"))
			throw new IOException("A direct diagnostic needs an organic or eager certification case: " + caseId);

		ArrayNode measurements = access.json().createArrayNode();
		measurements.add("delivery");
		measurements.add("application");
		selected.set("measures", measurements);
		diagnostic.put("diagnostic_kind", "direct_application");
		Suite.write(access, diagnostic, outputPath);
	}

	/**
	 * Removes assertions that only the deliberately bypassed organic route can answer.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param testCase the generated testcase to change
	 */
	private static void removeOrganicRoutingAssertions(JsonMapper json, ObjectNode testCase) throws IOException
	{
		Set<String> removedIds = new HashSet<>();
		JsonNode declaredCheck = testCase.path("deterministic_check");
		if (declaredCheck.isObject() && SuiteContract.deterministicCheckType(
			object(declaredCheck, "deterministic check"), null).equals("organic_route_delivery"))
		{
			ObjectNode check = object(declaredCheck, "deterministic check");
			for (JsonNode assertion : array(check.path("assertions"), "deterministic check assertions", null))
				removedIds.add(assertion.asString());
		}
		ArrayNode retainedAssertions = json.createArrayNode();
		for (JsonNode node : array(testCase.path("assertions"), "assertions", null))
		{
			ObjectNode assertion = (ObjectNode) node;
			JsonNode failureClass = assertion.path("failure_class");
			if (failureClass.isString() &&
				FailureClass.ROUTING_DELIVERY.externalName().equals(failureClass.stringValue()))
			{
				removedIds.add(text(assertion, "id", null));
				continue;
			}
			retainedAssertions.add(assertion);
		}
		testCase.set("assertions", retainedAssertions);
		if (removedIds.isEmpty())
			return;

		if (declaredCheck.isMissingNode())
			return;
		ObjectNode check = (ObjectNode) declaredCheck;
		ArrayNode retainedCheckAssertions = json.createArrayNode();
		for (JsonNode node : array(check.path("assertions"), "deterministic check assertions", null))
		{
			if (!removedIds.contains(node.asString()))
				retainedCheckAssertions.add(node);
		}
		if (retainedCheckAssertions.isEmpty())
		{
			testCase.remove("deterministic_check");
			return;
		}

		check.set("assertions", retainedCheckAssertions);
		ArrayNode ignoredResults = check.putArray("ignored_result_assertions");
		removedIds.stream().sorted().forEach(ignoredResults::add);
		JsonNode selfTests = check.path("self_tests");
		if (selfTests.isMissingNode())
			return;
		for (JsonNode node : array(selfTests, "deterministic check self_tests", null))
		{
			ObjectNode expected = object(node.path("expected_assertions"), "deterministic check control expectations");
			removedIds.forEach(expected::remove);
		}
	}
}

final class Routing
{
	/**
	 * Prevents construction of this routing-diagnostic builder.
	 */
	private Routing()
	{
	}

	/**
	 * Recreates a routing-only diagnostic from retained routing-delivery evidence.
	 *
	 * @param access evaluator-owned operations
	 * @param sourceRunPath the retained failed run
	 * @param caseId the failed testcase
	 * @param trial the failed trial
	 * @param outputPath the absent generated-suite root
	 */
	static void create(Diagnostics.Access access, Path sourceRunPath, String caseId, int trial, Path outputPath)
		throws IOException
	{
		outputPath = outputPath.toAbsolutePath().normalize();
		Path certificationSuite = Source.require(access, sourceRunPath, caseId, trial, "routing-only",
			FailureClass.ROUTING_DELIVERY, true);
		if (Files.exists(outputPath))
			throw new IOException("Diagnostic manifest already exists: " + outputPath);
		ObjectNode diagnostic = SuiteContract.read(certificationSuite).deepCopy();
		if (SuitePurpose.parse(text(diagnostic, "purpose", certificationSuite)) != SuitePurpose.CERTIFICATION)
		{
			throw new IOException("Routing diagnostics must be recreated from a certification suite: " +
				certificationSuite);
		}

		ArrayNode cases = access.json().createArrayNode();
		for (JsonNode node : array(diagnostic.path("cases"), "cases", certificationSuite))
		{
			ObjectNode testCase = object(node, "test case");
			ObjectNode expectation = object(testCase.path("route_expectation"), "route expectation");
			if (!text(expectation, "mode", certificationSuite).equals("organic"))
				continue;
			String routingCaseId = text(testCase, "id", certificationSuite);
			boolean expectsSelected = nonemptyRoutePaths(expectation.path("selected"), routingCaseId, "selected");
			boolean permitsOptionalSelection = nonemptyRoutePaths(expectation.path("optional_selected"), routingCaseId,
				"optional_selected");
			boolean expectsNotSelected = nonemptyRoutePaths(expectation.path("not_selected"), routingCaseId,
				"not_selected");
			if (!expectsSelected && !expectsNotSelected)
			{
				throw new IOException("A routing diagnostic needs expected selected and/or not_selected paths: " +
					routingCaseId);
			}
			ObjectNode routingCase = testCase.deepCopy();
			ArrayNode measurements = access.json().createArrayNode();
			measurements.add("organic_selection");
			routingCase.set("measures", measurements);
			routingCase.put("routing_diagnostic", true);
			routingCase.remove("deterministic_check");
			routingCase.remove("semantic_controls");
			ArrayNode assertions = access.json().createArrayNode();
			ObjectNode assertion = assertions.addObject();
			assertion.put("id", "routing-only");
			assertion.put("evaluation_kind", "deterministic");
			assertion.put("failure_class", "routing-delivery");
			assertion.put("observable_boundary", "native route commands before task work");
			assertion.put("instruction", access.routingInstruction(expectsSelected, permitsOptionalSelection,
				expectsNotSelected));
			routingCase.set("assertions", assertions);
			cases.add(routingCase);
		}
		if (cases.isEmpty())
		{
			throw new IOException("A routing diagnostic requires at least one organically routed certification case: " +
				certificationSuite);
		}
		diagnostic.set("cases", cases);
		diagnostic.put("purpose", "diagnostic");
		diagnostic.put("source_root", root(certificationSuite).toString());
		diagnostic.put("source_suite", certificationSuite.toString());
		diagnostic.put("source_run", sourceRunPath.toAbsolutePath().normalize().toString());
		diagnostic.put("source_case_id", caseId);
		diagnostic.put("source_trial", trial);
		diagnostic.put("diagnostic_kind", "routing_only");
		Suite.write(access, diagnostic, outputPath);
	}
}

final class Suite
{
	/**
	 * Prevents construction of this diagnostic-suite materializer.
	 */
	private Suite()
	{
	}

	/**
	 * Copies one certification case into an in-memory diagnostic manifest.
	 *
	 * @param json the mapper owned by the enclosing operation
	 * @param certificationSuite the retained certification suite
	 * @param caseId the selected testcase
	 * @param diagnosticName the reader-visible diagnostic name
	 * @return the generated diagnostic manifest
	 */
	static ObjectNode generate(JsonMapper json, Path certificationSuite, String caseId,
		String diagnosticName) throws IOException
	{
		ObjectNode diagnostic = SuiteContract.read(certificationSuite).deepCopy();
		if (SuitePurpose.parse(text(diagnostic, "purpose", certificationSuite)) != SuitePurpose.CERTIFICATION)
		{
			throw new IOException(diagnosticName + " must be recreated from a certification suite: " +
				certificationSuite);
		}
		ObjectNode sourceCase = testCase(diagnostic, caseId, certificationSuite).deepCopy();
		ArrayNode cases = json.createArrayNode();
		cases.add(sourceCase);
		diagnostic.set("cases", cases);
		diagnostic.remove("generalization");
		diagnostic.put("purpose", "diagnostic");
		diagnostic.put("source_root", root(certificationSuite).toString());
		diagnostic.put("source_suite", certificationSuite.toString());
		return diagnostic;
	}

	/**
	 * Resolves the retained certification suite named by a diagnostic manifest.
	 *
	 * @param access evaluator-owned operations
	 * @param diagnosticSuite the generated diagnostic suite
	 * @return the retained certification suite
	 */
	static Path sourceCertificationSuite(Diagnostics.Access access, Path diagnosticSuite) throws IOException
	{
		if (!Files.isDirectory(diagnosticSuite))
			throw new IOException("Expected diagnostic scenario-suite directory: " + diagnosticSuite);
		Path manifest = diagnosticSuite.resolve("suite.json");
		ObjectNode diagnostic = access.readObject(manifest, "diagnostic suite");
		if (SuitePurpose.parse(text(diagnostic, "purpose", manifest)) != SuitePurpose.DIAGNOSTIC)
		{
			throw new IOException("A profile-capability diagnostic requires a generated diagnostic suite: " +
				diagnosticSuite);
		}
		return path(text(diagnostic, "source_suite", manifest));
	}

	/**
	 * Materializes and validates a generated diagnostic suite at an absent root.
	 *
	 * @param access evaluator-owned operations
	 * @param diagnostic the generated diagnostic manifest
	 * @param outputPath the absent generated-suite root
	 */
	static void write(Diagnostics.Access access, ObjectNode diagnostic, Path outputPath) throws IOException
	{
		if (Files.exists(outputPath))
			throw new IOException("Diagnostic scenario suite already exists: " + outputPath);
		Path sourceRoot = path(text(diagnostic, "source_root", outputPath));
		ArrayNode cases = array(diagnostic.path("cases"), "cases", outputPath);
		ObjectNode manifest = diagnostic.deepCopy();
		manifest.remove("cases");
		ObjectNode scenarios = manifest.putObject("scenarios");
		Files.createDirectories(outputPath);
		try
		{
			for (JsonNode node : cases)
			{
				ObjectNode testCase = object(node, "generated diagnostic case");
				String id = text(testCase, "id", outputPath);
				Path sourceScenario = requiredFile(sourceRoot, text(testCase, "prompt", outputPath),
					"Source scenario", id);
				Path scenarioDirectory = sourceScenario.getParent();
				Path generatedScenario = outputPath.resolve(id);
				access.copyFixture(scenarioDirectory, generatedScenario);
				if (testCase.path("routing_diagnostic").asBoolean(false))
				{
					String task = access.scenarioTask(Files.readString(sourceScenario), sourceScenario);
					Files.writeString(generatedScenario.resolve("scenario.md"), "## Scenario\n\n" + task +
						"\n\n## Assertions\n");
				}
				ObjectNode metadata = testCase.deepCopy();
				metadata.remove("id");
				metadata.remove("scenario");
				metadata.remove("prompt");
				metadata.remove("fixture");
				metadata.remove("assertions");
				scenarios.set(id, metadata);
			}
			access.write(outputPath.resolve("suite.json"), manifest);
			SuiteContract.validate(outputPath);
		}
		catch (IOException e)
		{
			access.delete(outputPath);
			throw e;
		}
	}
}

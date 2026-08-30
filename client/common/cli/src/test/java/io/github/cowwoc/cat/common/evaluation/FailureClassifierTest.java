package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests retained failure classification evidence.
 */
public final class FailureClassifierTest
{
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public FailureClassifierTest()
	{
	}

	/**
	 * Closes the scope that owns this test class's JSON mapper.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies a missing retained attempt is rejected before the classifier reads evidence.
	 *
	 * @throws IOException if the classifier reports the required missing-attempt failure
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*No retained attempt exists for case-a trial 1.*")
	public void rejectsMissingRetainedAttempt() throws IOException
	{
		ObjectNode run = scope.json().createObjectNode();
		run.putArray("attempts");

		FailureClassifier.classify(scope.json(), Path.of("run.json"), "case-a", 1, new Access(run, testcase(), null, null));
	}

	/**
	 * Verifies a passed retained attempt is rejected before the classifier reads evidence.
	 *
	 * @throws IOException if the classifier reports the required passed-attempt failure
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp =
		".*A passed attempt has no failure to classify: case-a trial 1.*")
	public void rejectsPassedRetainedAttempt() throws IOException
	{
		ObjectNode run = run("passed", Path.of("validator.json"));

		FailureClassifier.classify(scope.json(), Path.of("run.json"), "case-a", 1, new Access(run, testcase(), null, null));
	}

	/**
	 * Verifies route-delivery evidence takes precedence over later semantic classification.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void routeEvidencePrecedesSemanticClass() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("failure-classifier"));
			Path validator = root.resolve("validator.json");
			Path routingEvidence = root.resolve("events.jsonl");
			Files.writeString(validator, "{\"assertions\":{}}");
			Files.writeString(routingEvidence, "{}");
			ObjectNode result = FailureClassifier.classify(scope.json(), root.resolve("run.json"), "case-a", 1,
				new Access(run("failed", validator), testcase(), routingEvidence,
					() ->
					{
						throw new AssertionError("Route evidence must short-circuit semantic classification");
					}));

			requireThat(result.path("failure_class").asString(), "failureClass").isEqualTo("routing-delivery");
			requireThat(result.path("evidence").asString(), "evidence").isEqualTo(routingEvidence.toString());
		}
	}

	/**
	 * Verifies semantic classification runs after a deterministic receipt establishes no failed deterministic assertion.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void semanticClassFollowsDeterministicPass() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("failure-classifier"));
			Path validator = root.resolve("validator.json");
			Files.writeString(validator, "{\"assertions\":{\"deterministic\":{\"status\":\"passed\"}}}");
			ObjectNode result = FailureClassifier.classify(scope.json(), root.resolve("run.json"), "case-a", 1,
				new Access(run("failed", validator), deterministicTestcase(), null, () -> FailureClass.APPLICATION));

			requireThat(result.path("failure_class").asString(), "failureClass").isEqualTo("application");
			requireThat(result.path("evidence").asString(), "evidence").isEqualTo(validator.toString());
		}
	}

	/**
	 * Verifies explicit evidence establishes a classification without reading lower-precedence evidence sources.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void explicitEvidenceShortCircuitsSources() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("failure-classifier"));
			Path validator = root.resolve("validator.json");
			Files.writeString(validator, "{\"failure_class\":\"application\"}");
			ObjectNode testCase = scope.json().createObjectNode();
			testCase.putArray("assertions");
			ObjectNode attempt = scope.json().createObjectNode().put("trial", 1).put("case_id", "case-a");
			FailureClassifier.Evidence evidence = new FailureClassifier.Evidence(testCase, attempt, validator,
				root.resolve("run.json"), () ->
				{
					throw new AssertionError("Explicit evidence must short-circuit route evidence");
				}, () ->
				{
					throw new AssertionError("Explicit evidence must short-circuit semantic evidence");
				}, false);
			FailureClassifier.Classification result = FailureClassifier.classify(scope.json(), evidence);

			requireThat(result.failureClass(), "failureClass").isEqualTo(FailureClass.APPLICATION);
			requireThat(result.evidence(), "evidence").isEqualTo(validator);
		}
	}

	/**
	 * Verifies an otherwise unclassified blocked attempt receives the infrastructure fallback and validator evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void infrastructureFallbackRetainsEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("failure-classifier"));
			Path validator = root.resolve("validator.json");
			Files.writeString(validator, "{\"assertions\":{}}");
			ObjectNode testCase = scope.json().createObjectNode();
			testCase.putArray("assertions");
			ObjectNode attempt = scope.json().createObjectNode().put("trial", 1).put("case_id", "case-a");
			FailureClassifier.Evidence evidence = new FailureClassifier.Evidence(testCase, attempt, validator,
				root.resolve("run.json"), () -> null, () -> null, true);
			FailureClassifier.Classification result = FailureClassifier.classify(scope.json(), evidence);

			requireThat(result.failureClass(), "failureClass").isEqualTo(FailureClass.INFRASTRUCTURE);
			requireThat(result.evidence(), "evidence").isEqualTo(validator);
		}
	}

	/**
	 * Verifies one failed assertion in a single-case validator result creates its declared failure signature.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void projectsSingleCaseAssertionSignature() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("failure-classifier"));
			Path validator = root.resolve("validator.json");
			Files.writeString(validator, """
				{"assertions":{"required":{"status":"failed","evidence":"missing result"},
				"optional":{"status":"passed"}}}
				""");
			ArrayNode signatures = scope.json().createArrayNode();
			FailureClassifier.appendFailureSignatures(scope.json(), signatures, outcome(validator, "failed"), testcase(),
				root.resolve("run.json"));

			ObjectNode signature = (ObjectNode) signatures.get(0);
			requireThat(signatures.size(), "signatureCount").isEqualTo(1);
			requireThat(signature.path("assertion").asString(), "assertion").isEqualTo("required");
			requireThat(signature.path("boundary").asString(), "boundary").isEqualTo("required observable result");
			requireThat(signature.path("contradiction").asString(), "contradiction").isEqualTo("missing result");
			requireThat(signature.path("evidence_sha256").asString(), "evidenceFingerprint").isEqualTo("fixture-sha");
		}
	}

	/**
	 * Verifies a batched validator result selects only the matching testcase and trial verdict.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void projectsBatchedAssertionSignature() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("failure-classifier"));
			Path validator = root.resolve("validator.json");
			Files.writeString(validator, """
				{"trial-1/case-a":{"assertions":{"required":{"status":"failed","evidence":"batch failure"}}},
				"trial-2/case-a":{"assertions":{"required":{"status":"passed"}}}}
				""");
			ArrayNode signatures = scope.json().createArrayNode();
			FailureClassifier.appendFailureSignatures(scope.json(), signatures, outcome(validator, "failed"), testcase(),
				root.resolve("run.json"));

			ObjectNode signature = (ObjectNode) signatures.get(0);
			requireThat(signatures.size(), "signatureCount").isEqualTo(1);
			requireThat(signature.path("assertion").asString(), "assertion").isEqualTo("required");
			requireThat(signature.path("contradiction").asString(), "contradiction").isEqualTo("batch failure");
		}
	}

	/**
	 * Verifies malformed validator evidence retains the terminal failed outcome as the only signature.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void retainsTerminalFallback() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("failure-classifier"));
			Path validator = root.resolve("validator.json");
			Files.writeString(validator, "not JSON");
			ArrayNode signatures = scope.json().createArrayNode();
			FailureClassifier.appendFailureSignatures(scope.json(), signatures, outcome(validator, "failed"), testcase(),
				root.resolve("run.json"));

			ObjectNode signature = (ObjectNode) signatures.get(0);
			requireThat(signatures.size(), "signatureCount").isEqualTo(1);
			requireThat(signature.path("assertion").asString(), "assertion").isEqualTo("terminal-outcome");
			requireThat(signature.path("contradiction").asString(), "contradiction").isEqualTo("failed");
		}
	}

	/**
	 * Creates one retained failed outcome for a classifier projection test.
	 *
	 * @param validator the retained validator-result file
	 * @param status the terminal testcase status
	 * @return the outcome supplied to the classifier
	 */
	private ObjectNode outcome(Path validator, String status)
	{
		return scope.json().createObjectNode().put("trial", 1).put("case_id", "case-a").put("status", status).
			put("validator_result", validator.toString()).put("validator_result_sha256", "fixture-sha");
	}

	/**
	 * Creates one testcase declaration with a failed required assertion and a passing optional assertion.
	 *
	 * @return the testcase declaration supplied to the classifier
	 */
	private ObjectNode testcase()
	{
		ObjectNode testcase = scope.json().createObjectNode();
		testcase.putArray("assertions").addObject().put("id", "required").
			put("observable_boundary", "required observable result");
		testcase.withArray("assertions").addObject().put("id", "optional");
		return testcase;
	}

	/**
	 * Creates one testcase declaration whose deterministic assertion has a passed validator receipt.
	 *
	 * @return the testcase declaration supplied to the classifier
	 */
	private ObjectNode deterministicTestcase()
	{
		ObjectNode testcase = scope.json().createObjectNode();
		testcase.putArray("assertions").addObject().put("id", "deterministic").
			put("evaluation_kind", "deterministic");
		return testcase;
	}

	/**
	 * Creates one evaluation record containing a requested testcase attempt.
	 *
	 * @param status the retained terminal attempt status
	 * @param validator the retained validator-result artifact
	 * @return the retained evaluation record
	 */
	private ObjectNode run(String status, Path validator)
	{
		ObjectNode run = scope.json().createObjectNode();
		run.putArray("attempts").addObject().put("trial", 1).put("case_id", "case-a").put("status", status).
			put("validator_result", validator.toString());
		return run;
	}

	/**
	 * Supplies test-owned retained evidence to the classifier transaction.
	 *
	 * @param run the test-owned retained evaluation record
	 * @param testCase the test-owned testcase declaration
	 * @param routingEvidence the selected route-delivery artifact
	 * @param semanticClass the selected semantic classification source
	 */
	private record Access(ObjectNode run, ObjectNode testCase, Path routingEvidence,
		FailureClassifier.EvidenceSource<FailureClass> semanticClass) implements FailureClassifier.Access
	{
		/**
		 * Reads the test-owned retained evaluation record.
		 *
		 * @param runPath the path requested by the classifier
		 * @return the test-owned retained evaluation record
		 */
		@Override
		public ObjectNode readRun(Path runPath)
		{
			return run;
		}

		/**
		 * Returns the test-owned attempts stored directly on the fixture record.
		 *
		 * @param run the retained evaluation record
		 * @param runPath the path of the retained evaluation record
		 * @return the test-owned attempts in their recorded order
		 */
		@Override
		public ArrayNode attempts(ObjectNode run, Path runPath)
		{
			return (ArrayNode) run.path("attempts");
		}

		/**
		 * Returns the test-owned testcase declaration.
		 *
		 * @param run the retained evaluation record
		 * @param runPath the path of the retained evaluation record
		 * @param caseId the stable testcase identifier
		 * @return the test-owned testcase declaration
		 */
		@Override
		public ObjectNode testCase(ObjectNode run, Path runPath, String caseId)
		{
			return testCase;
		}

		/**
		 * Admits the test-owned validator-result artifact.
		 *
		 * @param value the retained artifact path
		 * @param description the artifact's diagnostic description
		 * @return the test-owned validator-result artifact
		 */
		@Override
		public Path requiredArtifact(String value, String description)
		{
			return Path.of(value);
		}

		/**
		 * Returns the route-delivery artifact selected by this test.
		 *
		 * @param run the retained evaluation record
		 * @param runPath the path of the retained evaluation record
		 * @param attempt the retained failed testcase attempt
		 * @return the route-delivery artifact, or {@code null} when none is selected
		 */
		@Override
		public Path routingEvidence(ObjectNode run, Path runPath, ObjectNode attempt)
		{
			return routingEvidence;
		}

		/**
		 * Returns the semantic classification selected by this test.
		 *
		 * @param run the retained evaluation record
		 * @param runPath the path of the retained evaluation record
		 * @param attempt the retained failed testcase attempt
		 * @param validatorResult the admitted validator-result artifact
		 * @return the semantic classification, or {@code null} when none is selected
		 * @throws IOException if this test's semantic classifier fails
		 */
		@Override
		public FailureClass semanticClass(ObjectNode run, Path runPath, ObjectNode attempt, Path validatorResult)
			throws IOException
		{
			if (semanticClass == null)
				return null;
			return semanticClass.get();
		}
	}
}

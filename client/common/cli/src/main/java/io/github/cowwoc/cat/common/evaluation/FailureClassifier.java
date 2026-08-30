package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.cat.common.PromptEvaluation.object;
import static io.github.cowwoc.cat.common.PromptEvaluation.requiredArtifact;
import static io.github.cowwoc.cat.common.PromptEvaluation.text;
import static io.github.cowwoc.cat.common.PromptEvaluation.array;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Classifies immutable retained evidence for one failed testcase attempt.
 */
public final class FailureClassifier
{
	/**
	 * Supplies the retained-record and evidence operations needed to classify one failed attempt.
	 */
	public interface Access
	{
		/**
		 * Reads the retained evaluation record that contains the requested attempt.
		 *
		 * @param runPath the path of the retained evaluation record
		 * @return the retained evaluation record
		 * @throws IOException if the retained evaluation record cannot be read
		 */
		ObjectNode readRun(Path runPath) throws IOException;

		/**
		 * Reads the durable append-only ledger of attempts retained by the evaluation record.
		 *
		 * @param run the retained evaluation record
		 * @param runPath the path of the retained evaluation record
		 * @return the retained attempts in their recorded order
		 * @throws IOException if the attempt ledger is absent or malformed
		 */
		ArrayNode attempts(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Returns the testcase declaration named by the requested retained attempt.
		 *
		 * @param run the retained evaluation record
		 * @param runPath the path of the retained evaluation record
		 * @param caseId the stable testcase identifier
		 * @return the testcase declaration that assigns failure owners
		 * @throws IOException if the testcase declaration is absent or invalid
		 */
		ObjectNode testCase(ObjectNode run, Path runPath, String caseId) throws IOException;

		/**
		 * Admits one required retained artifact referenced by the evaluation record.
		 *
		 * @param value the retained artifact path
		 * @param description the artifact's diagnostic description
		 * @return the admitted artifact path
		 * @throws IOException if the artifact is absent or cannot be used as retained evidence
		 */
		Path requiredArtifact(String value, String description) throws IOException;

		/**
		 * Obtains route-delivery evidence after higher-precedence explicit classification is absent.
		 *
		 * @param run the retained evaluation record
		 * @param runPath the path of the retained evaluation record
		 * @param attempt the retained failed testcase attempt
		 * @return the evidence artifact, or {@code null} when route delivery establishes no classification
		 * @throws IOException if route-delivery evidence is malformed
		 */
		Path routingEvidence(ObjectNode run, Path runPath, ObjectNode attempt) throws IOException;

		/**
		 * Obtains semantic classification after every higher-precedence evidence source establishes none.
		 *
		 * @param run the retained evaluation record
		 * @param runPath the path of the retained evaluation record
		 * @param attempt the retained failed testcase attempt
		 * @param validatorResult the admitted validator-result artifact
		 * @return the semantic failure class, or {@code null} when the retained evidence is unresolved
		 * @throws IOException if semantic classification cannot establish its required prerequisites
		 */
		FailureClass semanticClass(ObjectNode run, Path runPath, ObjectNode attempt, Path validatorResult)
			throws IOException;
	}

	/**
	 * Obtains one immutable evidence item only when its classification precedence requires it.
	 *
	 * @param <T> the retained evidence value
	 */
	@FunctionalInterface
	public interface EvidenceSource<T>
	{
		/**
		 * Returns the retained evidence value.
		 *
		 * @return the retained evidence value, or {@code null} when it establishes no classification
		 * @throws IOException if immutable evidence cannot be admitted
		 */
		T get() throws IOException;
	}

	/**
	 * Supplies all immutable inputs needed to classify one failed attempt.
	 *
	 * @param testCase the testcase declaration that assigns failure owners
	 * @param attempt the retained failed attempt
	 * @param validatorResult the admitted validator result
	 * @param source the retained evaluation record used in diagnostics
	 * @param routingEvidence obtains route-delivery evidence after explicit classification is absent
	 * @param semanticClass obtains a semantic class after earlier sources establish none
	 * @param infrastructureFailure whether the terminal attempt state establishes an infrastructure failure
	 */
	public record Evidence(ObjectNode testCase, ObjectNode attempt, Path validatorResult, Path source,
		EvidenceSource<Path> routingEvidence, EvidenceSource<FailureClass> semanticClass,
		boolean infrastructureFailure)
	{
	}

	/**
	 * Describes the authoritative class and retained artifact that establish one failure.
	 *
	 * @param failureClass the established repair class
	 * @param evidence the artifact that established the class
	 */
	public record Classification(FailureClass failureClass, Path evidence)
	{
	}

	/**
	 * Prevents construction of this static owner.
	 */
	private FailureClassifier()
	{
	}

	/**
	 * Classifies one retained failed attempt and projects its stable diagnostic result.
	 *
	 * @param json the lifecycle-owned JSON mapper that projects the classification
	 * @param runPath the retained evaluation record
	 * @param caseId the stable testcase identifier
	 * @param trial the completed trial number
	 * @param access the retained-record and evidence collaborators
	 * @return the established classification result
	 * @throws IOException if the attempt is absent, passed, or has invalid retained evidence
	 */
	public static ObjectNode classify(JsonMapper json, Path runPath, String caseId, int trial, Access access)
		throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(runPath, "runPath").isNotNull();
		requireThat(caseId, "caseId").isNotBlank();
		requireThat(trial, "trial").isPositive();
		requireThat(access, "access").isNotNull();

		ObjectNode run = access.readRun(runPath);
		ObjectNode attempt = null;
		for (JsonNode node : access.attempts(run, runPath))
		{
			ObjectNode candidate = object(node, "attempt");
			if (candidate.path("trial").asInt() == trial && candidate.path("case_id").asString().equals(caseId))
			{
				attempt = candidate;
				break;
			}
		}
		if (attempt == null)
			throw new IOException("No retained attempt exists for " + caseId + " trial " + trial + ": " + runPath);
		ObjectNode failedAttempt = attempt;
		AttemptStatus status = AttemptStatus.parse(text(failedAttempt, "status", runPath));
		if (status == AttemptStatus.PASSED)
			throw new IOException("A passed attempt has no failure to classify: " + caseId + " trial " + trial);
		Path validatorResult = access.requiredArtifact(text(failedAttempt, "validator_result", runPath),
			"validator result");
		ObjectNode testCase = access.testCase(run, runPath, caseId);
		Evidence evidence = new Evidence(testCase, failedAttempt, validatorResult, runPath,
			() -> access.routingEvidence(run, runPath, failedAttempt),
			() -> access.semanticClass(run, runPath, failedAttempt, validatorResult),
			status == AttemptStatus.BLOCKED || status == AttemptStatus.CANCELED);
		Classification classification = classify(json, evidence);
		ObjectNode result = json.createObjectNode();
		result.put("failure_class", classification.failureClass().externalName());
		result.put("repair_owner", classification.failureClass().repairOwner());
		result.put("case_id", caseId);
		result.put("trial", trial);
		result.put("evidence", classification.evidence().toString());
		result.put("next_action", classification.failureClass().nextAction());
		return result;
	}

	/**
	 * Returns the validator verdict for one attempt from either a single-case or batched result file.
	 *
	 * @param json the lifecycle-owned JSON mapper that parses the validator result
	 * @param evidence the validator-result file
	 * @param attempt the retained testcase attempt
	 * @param source the evaluation-record path
	 * @return the verdict that owns the attempt's assertion results
	 * @throws IOException if the result is invalid or omits the required verdict
	 */
	public static ObjectNode assertionVerdict(JsonMapper json, Path evidence, ObjectNode attempt, Path source)
		throws IOException
	{
		requireThat(json, "json").isNotNull();

		ObjectNode result = object(json.readTree(Files.readString(evidence)), "validator result");
		if (result.path("assertions").isObject())
			return result;
		String key = "trial-" + attempt.path("trial").asInt() + "/" + text(attempt, "case_id", source);
		return object(result.path(key), "validator verdict " + key);
	}

	/**
	 * Appends the assertion-level signatures established by one failed retained outcome.
	 *
	 * <p>
	 * The validator may retain one assertion map for a single-case check or maps keyed by trial and testcase for a
	 * batched reviewer. When neither identifies a failed declared assertion, this records the failed terminal outcome
	 * as the only established signature.
	 *
	 * @param json the lifecycle-owned JSON mapper that parses retained validator evidence
	 * @param signatures the mutable signature collection receiving established failures
	 * @param outcome the retained failed testcase outcome
	 * @param testCase the testcase declaration that owns assertion identities
	 * @param runPath the retained evaluation record used in diagnostics
	 * @throws IOException if the retained outcome, testcase, or required evidence is invalid
	 */
	public static void appendFailureSignatures(JsonMapper json, ArrayNode signatures, ObjectNode outcome,
		ObjectNode testCase, Path runPath) throws IOException
	{
		requireThat(json, "json").isNotNull();

		Path evidencePath = requiredArtifact(text(outcome, "validator_result", runPath), "validator result");
		JsonNode evidence;
		try
		{
			evidence = json.readTree(Files.readString(evidencePath));
		}
		catch (tools.jackson.core.JacksonException _)
		{
			appendTerminalFailureSignature(signatures, outcome, runPath);
			return;
		}
		if (!(evidence instanceof ObjectNode result))
		{
			appendTerminalFailureSignature(signatures, outcome, runPath);
			return;
		}
		String key = "trial-" + outcome.path("trial").asInt() + "/" + text(outcome, "case_id", runPath);
		ObjectNode assertionResults = result;
		if (result.path(key).isObject())
			assertionResults = object(result.path(key), "batch validator result");
		if (!assertionResults.path("assertions").isObject())
		{
			appendTerminalFailureSignature(signatures, outcome, runPath);
			return;
		}
		ObjectNode assertions = object(assertionResults.path("assertions"), "assertion results");
		boolean appended = false;
		for (JsonNode node : array(testCase.path("assertions"), "assertions", runPath))
		{
			ObjectNode declaration = object(node, "assertion declaration");
			String id = text(declaration, "id", runPath);
			JsonNode observed = assertions.path(id);
			if (!observed.isObject() || !observed.path("status").asString().equals("failed"))
				continue;
			ObjectNode signature = signatures.addObject();
			signature.put("case_id", text(outcome, "case_id", runPath));
			signature.put("trial", outcome.path("trial").asInt());
			signature.put("assertion", id);
			String boundary = "acceptance assertion " + id;
			if (declaration.has("observable_boundary"))
				boundary = text(declaration, "observable_boundary", runPath);
			signature.put("boundary", boundary);
			if (declaration.has("obligation_family"))
				signature.put("obligation_family", text(declaration, "obligation_family", runPath));
			ObjectNode observedAssertion = object(observed, "failed assertion result");
			String contradiction = observedAssertion.path("evidence").stringValue();
			if (contradiction == null)
				contradiction = "failed";
			signature.put("contradiction", contradiction);
			signature.put("evidence_sha256", text(outcome, "validator_result_sha256", runPath));
			appended = true;
		}
		if (!appended)
			appendTerminalFailureSignature(signatures, outcome, runPath);
	}

	/**
	 * Appends the only signature available when validator evidence does not identify a failed assertion.
	 *
	 * @param signatures the mutable signature collection receiving the terminal failure
	 * @param outcome the retained testcase outcome
	 * @param runPath the retained evaluation record used in diagnostics
	 * @throws IOException if the retained terminal outcome is invalid
	 */
	private static void appendTerminalFailureSignature(ArrayNode signatures, ObjectNode outcome, Path runPath)
		throws IOException
	{
		AttemptStatus status = AttemptStatus.parse(text(outcome, "status", runPath));
		signatures.addObject().put("case_id", text(outcome, "case_id", runPath)).
			put("trial", outcome.path("trial").asInt()).
			put("assertion", "terminal-outcome").put("boundary", "retained testcase outcome").
			put("contradiction", status.name().toLowerCase(java.util.Locale.ROOT)).
			put("evidence_sha256", text(outcome, "validator_result_sha256", runPath));
	}

	/**
	 * Reads an explicit failure classification from retained evidence.
	 *
	 * @param json the lifecycle-owned JSON mapper that parses retained evidence
	 * @param evidence the deterministic or validator result
	 * @return the established class, or {@code null} when the evidence does not establish one
	 * @throws IOException if a declared classification is malformed
	 */
	public static FailureClass explicitClass(JsonMapper json, Path evidence) throws IOException
	{
		requireThat(json, "json").isNotNull();

		try
		{
			JsonNode parsed = json.readTree(Files.readString(evidence));
			if (!(parsed instanceof ObjectNode result) || !result.path("failure_class").isString())
				return null;
			return FailureClass.parse(result.path("failure_class").asString());
		}
		catch (tools.jackson.core.JacksonException _)
		{
			return null;
		}
	}

	/**
	 * Returns the declared class for the first failed deterministic assertion.
	 *
	 * @param json the lifecycle-owned JSON mapper that parses the executable-check result
	 * @param testCase the testcase declaration
	 * @param evidence the executable-check result
	 * @param source the retained-record source used in diagnostics
	 * @return the declared class, or {@code null} when the evidence establishes none
	 * @throws IOException if the declaration is malformed
	 */
	public static FailureClass deterministicClass(JsonMapper json, ObjectNode testCase, Path evidence, Path source)
		throws IOException
	{
		requireThat(json, "json").isNotNull();

		JsonNode parsed;
		try
		{
			parsed = json.readTree(Files.readString(evidence));
		}
		catch (tools.jackson.core.JacksonException _)
		{
			return null;
		}
		if (!(parsed instanceof ObjectNode result) || !result.path("assertions").isObject())
			return null;
		ObjectNode assertions = object(result.path("assertions"), "deterministic check assertions");
		for (JsonNode declaration : array(testCase.path("assertions"), "assertions", source))
		{
			ObjectNode assertion = object(declaration, "assertion");
			if (EvaluationKind.parse(text(assertion, "evaluation_kind", source)) != EvaluationKind.DETERMINISTIC)
				continue;
			String id = text(assertion, "id", source);
			JsonNode resultAssertion = assertions.path(id);
			if (resultAssertion.isObject() && resultAssertion.path("status").asString().equals("failed") &&
				assertion.has("failure_class"))
			{
				return FailureClass.parse(text(assertion, "failure_class", source));
			}
		}
		return null;
	}

	/**
	 * Returns the declared class for the first failed semantic assertion in an established validator verdict.
	 *
	 * @param json the lifecycle-owned JSON mapper that parses the validator result
	 * @param testCase the testcase declaration
	 * @param evidence the validator-result file
	 * @param attempt the retained testcase attempt
	 * @param source the retained-record source used in diagnostics
	 * @return the declared class, or {@code null} when the verdict establishes none
	 * @throws IOException if the declaration or verdict is malformed
	 */
	public static FailureClass semanticClass(JsonMapper json, ObjectNode testCase, Path evidence, ObjectNode attempt,
		Path source) throws IOException
	{
		requireThat(json, "json").isNotNull();

		boolean hasDeclaredSemanticClass = false;
		for (JsonNode node : array(testCase.path("assertions"), "assertions", source))
		{
			ObjectNode declaration = object(node, "assertion");
			if (EvaluationKind.parse(text(declaration, "evaluation_kind", source)) == EvaluationKind.SEMANTIC &&
				declaration.has("failure_class"))
				hasDeclaredSemanticClass = true;
		}
		if (!hasDeclaredSemanticClass)
			return null;
		ObjectNode verdict = assertionVerdict(json, evidence, attempt, source);
		ObjectNode verdicts = object(verdict.path("assertions"), "semantic assertion verdicts");
		for (JsonNode node : array(testCase.path("assertions"), "assertions", source))
		{
			ObjectNode declaration = object(node, "assertion");
			if (EvaluationKind.parse(text(declaration, "evaluation_kind", source)) != EvaluationKind.SEMANTIC ||
				!declaration.has("failure_class"))
				continue;
			String id = text(declaration, "id", source);
			JsonNode assertion = verdicts.path(id);
			if (assertion.isObject() && assertion.path("status").asString().equals("failed"))
				return FailureClass.parse(text(declaration, "failure_class", source));
		}
		return null;
	}

	/**
	 * Classifies one immutable failed attempt using the established evidence-precedence order.
	 *
	 * @param json the lifecycle-owned JSON mapper that parses retained evidence
	 * @param evidence the admitted failed-attempt evidence
	 * @return the authoritative class and artifact that establish it
	 * @throws IOException if a required retained artifact is malformed or unavailable
	 */
	public static Classification classify(JsonMapper json, Evidence evidence) throws IOException
	{
		requireThat(json, "json").isNotNull();

		FailureClass failureClass = explicitClass(json, evidence.validatorResult());
		Path classificationEvidence = evidence.validatorResult();
		if (failureClass == null)
		{
			Path routingEvidence = evidence.routingEvidence().get();
			if (routingEvidence != null)
			{
				failureClass = FailureClass.ROUTING_DELIVERY;
				classificationEvidence = routingEvidence;
			}
		}
		if (failureClass == null)
			failureClass = deterministicClass(json, evidence.testCase(), evidence.validatorResult(), evidence.source());
		if (failureClass == null)
			failureClass = evidence.semanticClass().get();
		if (failureClass == null && evidence.infrastructureFailure())
			failureClass = FailureClass.INFRASTRUCTURE;
		if (failureClass == null)
			failureClass = FailureClass.UNRESOLVED;
		return new Classification(failureClass, classificationEvidence);
	}

	/**
	 * Indicates whether a retained executor stream contains a native harness event.
	 *
	 * @param json the lifecycle-owned JSON mapper that parses each retained stream record
	 * @param rawResult the retained executor stream
	 * @return {@code true} if the stream contains at least one native event
	 * @throws IOException if the stream cannot be read
	 */
	public static boolean hasNativeEvents(JsonMapper json, Path rawResult) throws IOException
	{
		requireThat(json, "json").isNotNull();

		try (BufferedReader reader = Files.newBufferedReader(rawResult))
		{
			String line = reader.readLine();
			while (line != null)
			{
				try
				{
					JsonNode envelope = json.readTree(line);
					if (envelope.path("type").asString().equals("native-event") && envelope.path("event").isString())
						return true;
				}
				catch (tools.jackson.core.JacksonException _)
				{
				}
				line = reader.readLine();
			}
		}
		return false;
	}
}

package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;
import io.github.cowwoc.cat.common.ToolScope;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies that a retained prompt evaluation still names the immutable inputs and evidence it originally accepted.
 */
public final class RunIntegrity
{
	/**
	 * Prevents construction of this static owner.
	 */
	private RunIntegrity()
	{
	}

	/**
	 * Verifies inputs whose change would make a prepared, resumed, reported, or handed-off evaluation invalid.
	 *
	 * @param run the retained evaluation record
	 * @param runPath the record path used in diagnostics
	 * @throws IOException if an immutable input, execution identity, route receipt, or candidate delivery changed
	 */
	public static void verifyInputs(ObjectNode run, Path runPath) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			verifyInputs(scope.json(), run, runPath);
		}
	}

	/**
	 * Verifies inputs using the mapper owned by the current record-validation operation.
	 *
	 * @param json the mapper that creates and serializes identity records
	 * @param run the retained evaluation record
	 * @param runPath the record path used in diagnostics
	 * @throws IOException if an immutable input, execution identity, route receipt, or candidate delivery changed
	 */
	public static void verifyInputs(JsonMapper json, ObjectNode run, Path runPath) throws IOException
	{
		Path suitePath = PromptEvaluation.path(PromptEvaluation.text(run, "suite", runPath));
		SuiteContract.validate(suitePath);
		if (!PromptEvaluation.suiteFingerprint(suitePath).equals(PromptEvaluation.text(run, "suite_sha256", runPath)))
			throw new IOException("Suite changed after the evaluation began: " + suitePath);

		Path promptArtifact = PromptEvaluation.path(PromptEvaluation.text(run, "prompt_artifact", runPath));
		PromptEvaluation.verifyFingerprint(promptArtifact, PromptEvaluation.text(run, "prompt_sha256", runPath),
			"Prompt artifact changed after the evaluation began");
		Path executionContext = PromptEvaluation.path(PromptEvaluation.text(run, "execution_context", runPath));
		PromptEvaluation.verifyFingerprint(executionContext,
			PromptEvaluation.text(run, "execution_context_sha256", runPath),
			"Execution context changed after the evaluation began");
		if (!run.has("execution_plan"))
			return;

		Path executionPlan = PromptEvaluation.path(PromptEvaluation.text(run, "execution_plan", runPath));
		PromptEvaluation.verifyFingerprint(executionPlan, PromptEvaluation.text(run, "execution_plan_sha256", runPath),
			"Execution plan changed after the evaluation began");
		ObjectNode identity = Identity.executionIdentity(json, executionPlan);
		if (!Identity.executionFingerprint(json, identity).equals(
			PromptEvaluation.text(run, "execution_identity_sha256", runPath)))
		{
			throw new IOException("Execution conditions changed after the evaluation began: " + executionPlan);
		}
		ObjectNode epoch = Identity.evaluationEpoch(json, run, identity, runPath);
		String epochFingerprint = PromptEvaluation.sha256(json.writeValueAsString(epoch));
		if (!epochFingerprint.equals(PromptEvaluation.text(run, "evaluation_epoch_sha256", runPath)))
			throw new IOException("Evaluation epoch changed after the evaluation began: " + executionPlan);
		if (!run.has("comparison_contract") || !PromptEvaluation.object(run.path("comparison_contract"),
			"comparison contract").equals(epoch) ||
			!epochFingerprint.equals(PromptEvaluation.text(run, "comparison_contract_sha256", runPath)))
		{
			throw new IOException("Comparison contract changed after the evaluation began: " + executionPlan);
		}
		ObjectNode route = Identity.assembledRoute(json, run, identity, runPath);
		if (!PromptEvaluation.sha256(json.writeValueAsString(route)).equals(
			PromptEvaluation.text(run, "assembled_prompt_route_sha256", runPath)))
		{
			throw new IOException("Prompt route changed after the evaluation began: " + executionPlan);
		}
		verifyCandidateBundleDelivery(run, route, runPath);
	}

	/**
	 * Verifies every artifact retained by completed attempts without selecting or altering an evaluation result.
	 *
	 * @param run the retained evaluation record
	 * @param runPath the record path used in diagnostics
	 * @throws IOException if a retained artifact or attempt declaration is incomplete, missing, or changed
	 */
	public static void verifyEvidence(ObjectNode run, Path runPath) throws IOException
	{
		for (JsonNode node : PromptEvaluation.attempts(run, runPath))
		{
			ObjectNode attempt = PromptEvaluation.object(node, "trial record");
			verifyArtifact(attempt, runPath, "raw_result", "raw result", "Raw result changed after recording");
			verifyArtifact(attempt, runPath, "validator_result", "validator result",
				"Validator result changed after recording");
			if (attempt.has("deterministic_result") || attempt.has("deterministic_result_sha256"))
			{
				if (!attempt.has("deterministic_result") || !attempt.has("deterministic_result_sha256"))
					throw new IOException("Attempt records an incomplete deterministic result: " + runPath);
				verifyArtifact(attempt, runPath, "deterministic_result", "deterministic result",
					"Deterministic result changed after recording");
			}
			AttemptStatus.parse(PromptEvaluation.text(attempt, "status", runPath));
		}
	}

	/**
	 * Requires every candidate rule to be present at its declared installed route with its recorded content.
	 *
	 * @param run the evaluation record that may name a candidate bundle
	 * @param route the assembled installed-rule receipt
	 * @param runPath the record path used in diagnostics
	 * @throws IOException if an evaluated candidate file was not delivered by the runner artifact
	 */
	public static void verifyCandidateBundleDelivery(ObjectNode run, ObjectNode route, Path runPath) throws IOException
	{
		if (!run.has("candidate_bundle"))
			return;

		ObjectNode bundle = PromptEvaluation.object(run.path("candidate_bundle"), "candidate bundle");
		Map<String, String> delivered = new HashMap<>();
		if (route.has("routing_owner"))
			delivered.put(text(route, "routing_owner"), text(route, "routing_owner_sha256"));
		for (JsonNode caseNode : PromptEvaluation.array(route.path("cases"), "assembled route cases", runPath))
		{
			ObjectNode routeCase = PromptEvaluation.object(caseNode, "assembled route case");
			for (String field : List.of("selected", "optional_selected", "delivered"))
			{
				JsonNode entries = routeCase.path(field);
				if (entries.isMissingNode())
					continue;
				for (JsonNode node : PromptEvaluation.array(entries, "assembled route " + field, runPath))
				{
					ObjectNode rule = PromptEvaluation.object(node, "assembled routed rule");
					String path = text(rule, "relative_path");
					String hash = text(rule, "sha256");
					String previous = delivered.putIfAbsent(path, hash);
					if (previous != null && !previous.equals(hash))
						throw new IOException("Candidate delivery receipt assigns conflicting content to " + path);
				}
			}
		}
		for (JsonNode node : PromptEvaluation.array(bundle.path("rules"), "candidate bundle rules", runPath))
		{
			ObjectNode rule = PromptEvaluation.object(node, "candidate bundle rule");
			String path = text(rule, "relative_path");
			String expected = text(rule, "sha256");
			if (!expected.equals(delivered.get(path)))
			{
				throw new IOException("Candidate bundle rule was not delivered at its canonical route: " + path +
					" in " + runPath);
			}
		}
	}

	/**
	 * Verifies one retained attempt artifact against its recorded fingerprint.
	 *
	 * @param attempt the retained attempt
	 * @param runPath the record path used in diagnostics
	 * @param field the artifact field name
	 * @param description the artifact purpose
	 * @param failureMessage the fingerprint-mismatch diagnostic
	 * @throws IOException if the artifact is missing or changed
	 */
	private static void verifyArtifact(ObjectNode attempt, Path runPath, String field, String description,
		String failureMessage) throws IOException
	{
		Path artifact = PromptEvaluation.requiredArtifact(PromptEvaluation.text(attempt, field, runPath), description);
		PromptEvaluation.verifyFingerprint(artifact, PromptEvaluation.text(attempt, field + "_sha256", runPath),
			failureMessage);
	}

	/**
	 * Reads a required text field from an assembled route receipt.
	 *
	 * @param value the receipt object
	 * @param field the required field
	 * @return the non-blank text
	 * @throws IOException if the receipt is malformed
	 */
	private static String text(ObjectNode value, String field) throws IOException
	{
		JsonNode text = value.path(field);
		if (!text.isString() || text.asString().isBlank())
			throw new IOException("Assembled prompt route is missing text " + field);
		return text.asString();
	}
}

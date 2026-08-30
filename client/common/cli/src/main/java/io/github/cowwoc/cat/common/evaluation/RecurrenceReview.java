package io.github.cowwoc.cat.common.evaluation;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.secrets.EvaluatorSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prepares and records independent reviews for recurring candidate failures.
 */
public final class RecurrenceReview
{
	/**
	 * Supplies coordinator-owned record parsing and retained-artifact validation.
	 */
	public interface Access
	{
		/**
		 * Validates a JSON value as an object.
		 *
		 * @param value the candidate object
		 * @param description the value role in malformed-content diagnostics
		 * @return the parsed object
		 * @throws IOException if the value is not an object
		 */
		ObjectNode object(JsonNode value, String description) throws IOException;

		/**
		 * Reads a JSON record as an object.
		 *
		 * @param source the record location
		 * @param description the record role in malformed-content diagnostics
		 * @return the parsed record
		 * @throws IOException if the record cannot be read or is not an object
		 */
		ObjectNode readObject(Path source, String description) throws IOException;

		/**
		 * Returns a required text field from a record.
		 *
		 * @param record the parsed record
		 * @param field the required field name
		 * @param source the record location used in diagnostics
		 * @return the required field value
		 * @throws IOException if the field is missing or malformed
		 */
		String text(ObjectNode record, String field, Path source) throws IOException;

		/**
		 * Returns a required array from a record.
		 *
		 * @param value the candidate array
		 * @param name the array role in diagnostics
		 * @param source the record location used in diagnostics
		 * @return the parsed array
		 * @throws IOException if the value is not an array
		 */
		ArrayNode array(JsonNode value, String name, Path source) throws IOException;

		/**
		 * Returns the recorded attempts for a completed evaluation.
		 *
		 * @param run the evaluation record
		 * @param runPath the evaluation-record location
		 * @return the retained attempt index
		 * @throws IOException if the attempt index is malformed
		 */
		ArrayNode attempts(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Validates and normalizes a retained evidence artifact.
		 *
		 * @param value the persisted artifact path
		 * @param description the artifact's review role
		 * @return the existing normalized artifact path
		 * @throws IOException if the artifact is unavailable
		 */
		Path requiredArtifact(String value, String description) throws IOException;
	}

	private static volatile Access access;

	/**
	 * Prevents construction of this static review owner.
	 */
	private RecurrenceReview()
	{
	}

	/**
	 * Registers the coordinator-owned record operations.
	 *
	 * @param access the record operations
	 */
	public static void configure(Access access)
	{
		RecurrenceReview.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Prepares a sealed evidence bundle for every active recurrence that requires independent review.
	 *
	 * @param json the mapper that creates and seals the recurrence-review receipt
	 * @param temporaryRoot the CAT temporary root that owns the review bundle
	 * @param candidateStatePath the candidate-state ledger with active failure signatures
	 * @param sourceRunPath the retained run that supplied the recurring failures
	 * @param bundleDirectory the initially absent review-bundle directory
	 * @return the sealed bundle receipt and its required signature keys
	 * @throws IOException if recurrence evidence, review inputs, or the output location is invalid
	 */
	public static ObjectNode prepare(JsonMapper json, Path temporaryRoot, Path candidateStatePath, Path sourceRunPath,
		Path bundleDirectory) throws IOException
	{
		Path statePath = candidateStatePath.toAbsolutePath().normalize();
		Path runPath = sourceRunPath.toAbsolutePath().normalize();
		ObjectNode state = access().readObject(statePath, "candidate state");
		ObjectNode run = access().readObject(runPath, "source run");
		CandidateState.validateCandidateStateEpoch(state, run, statePath, runPath);
		List<String> signatureKeys = CandidateState.activeReviewSignatureKeys(state, statePath);
		if (signatureKeys.isEmpty())
		{
			throw new IOException("A recurrence review requires a repeated failure or two active failures in one " +
				"obligation family: " + statePath);
		}

		Path question = Files.createTempFile(temporaryRoot, "prompt-recurrence-review-", ".md");
		try
		{
			Files.writeString(question, CandidateReviews.recurrenceQuestion(signatureKeys), StandardCharsets.UTF_8);
			List<Path> evidence = evidence(run, runPath, statePath, signatureKeys);
			ObjectNode receipt = CandidateReviews.createBundle(json, temporaryRoot, question, bundleDirectory,
				"review-result.json", evidence);
			receipt.set("signature_keys", json.valueToTree(signatureKeys));
			CandidateReviews.prepareRecurrence(json, statePath, bundleDirectory, runPath, signatureKeys);
			return receipt;
		}
		finally
		{
			Files.deleteIfExists(question);
		}
	}

	/**
	 * Records the structured conclusion for a prepared recurrence-review bundle.
	 *
	 * @param json the mapper that reads and validates the recurrence-review result
	 * @param candidateStatePath the candidate-state ledger whose pending review is completed
	 * @param bundleDirectory the prepared review bundle
	 * @param resultPath the reviewer's structured conclusion inside that bundle
	 * @return the completed review record
	 * @throws IOException if the result is incomplete or does not address every recurrence
	 */
	public static ObjectNode record(JsonMapper json, Path candidateStatePath, Path bundleDirectory, Path resultPath)
		throws IOException
	{
		return CandidateReviews.completeRecurrence(json, candidateStatePath, bundleDirectory, resultPath);
	}

	/**
	 * Collects the retained run and prompt inputs required to inspect every recurrence in the bundle.
	 *
	 * @param run the retained source run
	 * @param runPath the source-run path
	 * @param statePath the candidate-state path
	 * @param signatureKeys the exact failed acceptance conditions under review
	 * @return the exact evidence files to copy
	 * @throws IOException if a declared required input is unavailable
	 */
	private static List<Path> evidence(ObjectNode run, Path runPath, Path statePath, List<String> signatureKeys)
		throws IOException
	{
		List<Path> evidence = new ArrayList<>();
		addEvidence(evidence, runPath);
		addEvidence(evidence, statePath);
		for (String field : List.of("suite", "prompt_artifact", "execution_plan"))
		{
			Path input = Path.of(access().text(run, field, runPath)).toAbsolutePath().normalize();
			boolean missing = !Files.isRegularFile(input, NOFOLLOW_LINKS);
			if (field.equals("suite"))
				missing = !Files.isDirectory(input, NOFOLLOW_LINKS);
			if (missing)
				throw new IOException("Repeated-failure review needs the retained " + field + " input: " + input);
			addEvidence(evidence, input);
		}

		ObjectNode state = access().readObject(statePath, "candidate state");
		for (String key : signatureKeys)
		{
			ObjectNode signature = CandidateState.signature(access().array(state.path("failure_signatures"),
				"failure signatures", statePath), key, statePath);
			if (signature == null)
				throw new IOException("Repeated-failure review cannot find its active signature: " + key);
			addFailureEvidence(evidence, signature, runPath, statePath);
		}
		return evidence;
	}

	/**
	 * Adds executor and validator evidence for one reviewed occurrence when the signature records a trial.
	 *
	 * @param evidence the review bundle's selected source paths
	 * @param signature the active failed acceptance condition
	 * @param fallbackRun the current review source when an older record has no occurrence reference
	 * @param statePath the candidate-state source used in diagnostics
	 * @throws IOException if a recorded occurrence cannot provide its retained evidence
	 */
	private static void addFailureEvidence(List<Path> evidence, ObjectNode signature, Path fallbackRun, Path statePath)
		throws IOException
	{
		ObjectNode failure = access().object(signature.path("latest_case"), "latest failure case");
		if (!failure.has("trial"))
			return;

		Path observedRun = occurrenceRun(signature, fallbackRun, statePath);
		addEvidence(evidence, observedRun);
		String caseId = access().text(failure, "case_id", statePath);
		int trial = failure.path("trial").asInt();
		for (JsonNode node : access().attempts(access().readObject(observedRun, "recorded failure run"), observedRun))
		{
			ObjectNode attempt = access().object(node, "recorded failure attempt");
			if (attempt.path("trial").asInt() != trial || !access().text(attempt, "case_id", observedRun).equals(caseId))
				continue;
			addEvidence(evidence, access().requiredArtifact(access().text(attempt, "raw_result", observedRun),
				"raw result"));
			addEvidence(evidence, access().requiredArtifact(access().text(attempt, "validator_result", observedRun),
				"validator result"));
			return;
		}
		throw new IOException("Repeated-failure review cannot find the recorded occurrence for " + caseId + "/" +
			trial + ": " + observedRun);
	}

	/**
	 * Returns the retained run that recorded a failure occurrence.
	 *
	 * @param signature the active failure signature
	 * @param fallbackRun the source run selected for the review
	 * @param statePath the candidate-state path used in diagnostics
	 * @return the existing retained occurrence run
	 * @throws IOException if the recorded occurrence is unavailable
	 */
	private static Path occurrenceRun(ObjectNode signature, Path fallbackRun, Path statePath) throws IOException
	{
		Path observedRun = fallbackRun;
		if (signature.has("last_observed_check"))
			observedRun = Path.of(access().text(signature, "last_observed_check", statePath)).toAbsolutePath().normalize();
		else if (signature.has("last_observed_formal_run"))
		{
			observedRun = Path.of(access().text(signature, "last_observed_formal_run", statePath)).toAbsolutePath().
				normalize();
		}
		if (!Files.isRegularFile(observedRun, NOFOLLOW_LINKS))
			throw new IOException("Repeated-failure review needs the recorded occurrence: " + observedRun);
		return observedRun;
	}

	/**
	 * Adds one source to the evidence bundle at most once.
	 *
	 * @param evidence the selected evidence paths
	 * @param source the source artifact
	 */
	private static void addEvidence(List<Path> evidence, Path source)
	{
		if (!evidence.contains(source))
			evidence.add(source);
	}

	/**
	 * Returns the configured coordinator record operations.
	 *
	 * @return the coordinator operations
	 */
	private static Access access()
	{
		EvaluatorSecrets.access().initialize();
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure recurrence-review access");
		return result;
	}
}

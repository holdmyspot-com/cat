package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import io.github.cowwoc.cat.common.PromptEvaluation;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs and records the independent review required for a changed candidate with retained obligations.
 */
public final class RevisionReview
{
	/**
	 * Supplies coordinator-owned run validation and candidate identity to revision review.
	 */
	public interface Access
	{
		/**
		 * Validates the retained inputs of one prepared run.
		 *
		 * @param run the prepared run
		 * @param runPath the run location
		 * @throws IOException if the run inputs are malformed
		 */
		void validateRunInputs(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Returns the candidate revision represented by one prepared run.
		 *
		 * @param run the prepared run
		 * @param runPath the run location
		 * @return the candidate revision
		 * @throws IOException if the revision cannot be derived
		 */
		String candidateRevision(ObjectNode run, Path runPath) throws IOException;
	}

	private static volatile Access access;

	/**
	 * Registers the coordinator operations used by revision review.
	 *
	 * @param access the coordinator operations
	 */
	public static void configure(Access access)
	{
		RevisionReview.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}
	/**
	 * Prevents construction of a utility class.
	 */
	private RevisionReview()
	{
	}

	/**
	 * Runs one complete revision review through the production native runner.
	 *
	 * @param json the mapper used by the native runner and review records
	 * @param temporaryRoot the CAT work temporary directory that owns the prepared bundle
	 * @param candidateStatePath the durable candidate-state ledger
	 * @param proposedRunPath the prepared candidate record that declares the reviewer plan
	 * @param bundleDirectory the initially absent review bundle directory
	 * @return the sealed bundle receipt, runner receipt, and recorded candidate-state review
	 * @throws IOException if the review cannot be prepared, retained, or recorded
	 */
	public static ObjectNode run(JsonMapper json, Path temporaryRoot, Path candidateStatePath, Path proposedRunPath,
		Path bundleDirectory)
		throws IOException
	{
		return run(json, temporaryRoot, candidateStatePath, proposedRunPath, bundleDirectory,
			new io.github.cowwoc.cat.common.runner.NativeHarnessEvaluationRunner(json));
	}

	/**
	 * Runs one complete revision review through a caller-supplied typed harness runner.
	 *
	 * @param json the mapper used to create and validate review records
	 * @param temporaryRoot the CAT work temporary directory that owns the prepared bundle
	 * @param candidateStatePath the durable candidate-state ledger
	 * @param proposedRunPath the prepared candidate record that declares the reviewer plan
	 * @param bundleDirectory the initially absent review bundle directory
	 * @param runner the runner that creates the reviewer's isolated project copy
	 * @return the sealed bundle receipt, runner receipt, and recorded candidate-state review
	 * @throws IOException if the review cannot be prepared, retained, or recorded
	 */
	public static ObjectNode run(JsonMapper json, Path temporaryRoot, Path candidateStatePath, Path proposedRunPath,
		Path bundleDirectory, HarnessEvaluationRunner runner) throws IOException
	{
		Path bundleDirectoryPath = bundleDirectory.toAbsolutePath().normalize();
		ObjectNode bundle = prepare(json, temporaryRoot, candidateStatePath, proposedRunPath, bundleDirectoryPath);
		ObjectNode proposedRun = PromptEvaluation.object(PromptEvaluation.read(proposedRunPath),
			"prepared candidate run");
		Path planPath = PromptEvaluation.path(PromptEvaluation.text(proposedRun, "execution_plan", proposedRunPath));
		ObjectNode plan = PromptEvaluation.object(PromptEvaluation.read(planPath), "execution plan");

		Path question = PromptEvaluation.path(PromptEvaluation.text(bundle, "question", bundleDirectoryPath));
		Path transcript = bundleDirectoryPath.resolve("reviewer-output.txt");
		Path receipt = bundleDirectoryPath.resolve("reviewer.json");
		HarnessEvaluationExecution execution = PromptEvaluation.executeHarnessEvaluation(plan, "validator",
			bundleDirectoryPath, PromptEvaluation.bridgeInput(Files.readString(question, StandardCharsets.UTF_8)), transcript,
			receipt, List.of(), runner);
		ObjectNode recorded = record(json, candidateStatePath, bundleDirectoryPath, receipt);
		ObjectNode result = json.createObjectNode();
		result.set("bundle", bundle);
		result.put("reviewer_receipt", receipt.toString());
		result.put("reviewer_project", execution.result().retainedProject().toString());
		result.set("review", recorded);
		return result;
	}

	/**
	 * Prepares the sealed evidence bundle for one changed candidate's independent review.
	 *
	 * @param json the mapper used to create and validate the review bundle
	 * @param temporaryRoot the CAT work temporary directory
	 * @param candidateStatePath the durable obligation ledger
	 * @param proposedRunPath the prepared candidate record
	 * @param bundleDirectory the initially absent review bundle directory
	 * @return the sealed review-bundle receipt
	 * @throws IOException if the proposed candidate or its retained obligations cannot be reviewed safely
	 */
	public static ObjectNode prepare(JsonMapper json, Path temporaryRoot, Path candidateStatePath, Path proposedRunPath,
		Path bundleDirectory)
		throws IOException
	{
		Path statePath = candidateStatePath.toAbsolutePath().normalize();
		Path runPath = proposedRunPath.toAbsolutePath().normalize();
		ObjectNode state = PromptEvaluation.object(PromptEvaluation.read(statePath), "candidate state");
		ObjectNode proposedRun = PromptEvaluation.object(PromptEvaluation.read(runPath), "prepared candidate run");
		validateRunInputs(proposedRun, runPath);
		String candidateIdentity = candidateRevision(proposedRun, runPath);
		List<String> obligationKeys = CandidateReviews.revisionObligationKeys(state, statePath);
		if (obligationKeys.isEmpty())
			throw new IOException("A revision review requires a retained active or recently covered obligation: " +
				statePath);
		if (!CandidateReviews.requiresRevision(state, statePath, candidateIdentity, obligationKeys))
			throw new IOException("Candidate already has a completed revision review for its current identity: " +
				runPath);
		Path question = Files.createTempFile(temporaryRoot, "prompt-revision-review-", ".md");
		try
		{
			Files.writeString(question, CandidateReviews.revisionQuestion(obligationKeys));
			ObjectNode receipt = CandidateReviews.createBundle(json, temporaryRoot, question, bundleDirectory,
				"review-result.json",
				evidence(proposedRun, runPath, state, statePath, obligationKeys));
			receipt.set("obligation_keys", json.valueToTree(obligationKeys));
			receipt.put("candidate_identity_sha256", candidateIdentity);
			CandidateReviews.prepareRevision(json, statePath, bundleDirectory, candidateIdentity, obligationKeys);
			return receipt;
		}
		finally
		{
			Files.deleteIfExists(question);
		}
	}

	/**
	 * Records the independent review conclusion named by the runner's retained receipt.
	 *
	 * @param json the mapper used to validate the reviewer conclusion
	 * @param candidateStatePath the durable candidate-state ledger
	 * @param bundleDirectory the prepared revision-review bundle
	 * @param reviewerReceiptPath the retained runner receipt
	 * @return the completed review record
	 * @throws IOException if the conclusion does not meet every retained obligation
	 */
	public static ObjectNode record(JsonMapper json, Path candidateStatePath, Path bundleDirectory,
		Path reviewerReceiptPath)
		throws IOException
	{
		return CandidateReviews.completeRevision(json, candidateStatePath, bundleDirectory, reviewerReceiptPath);
	}

	/**
	 * Collects the proposed candidate and retained obligation evidence for a review bundle.
	 *
	 * @param proposedRun the prepared candidate record
	 * @param proposedRunPath the prepared candidate record path
	 * @param state the durable candidate state
	 * @param statePath the durable candidate-state path
	 * @param obligationKeys the obligations review must address
	 * @return the source artifacts to seal into the review bundle
	 * @throws IOException if a required candidate or obligation artifact is unavailable
	 */
	private static List<Path> evidence(ObjectNode proposedRun, Path proposedRunPath, ObjectNode state, Path statePath,
		List<String> obligationKeys) throws IOException
	{
		List<Path> evidence = new ArrayList<>();
		addEvidence(evidence, proposedRunPath);
		addEvidence(evidence, statePath);
		for (String field : List.of("suite", "prompt_artifact", "execution_plan"))
		{
			Path input = PromptEvaluation.path(PromptEvaluation.text(proposedRun, field, proposedRunPath));
			boolean suite = field.equals("suite");
			boolean missing = !Files.isRegularFile(input, NOFOLLOW_LINKS);
			if (suite)
				missing = !Files.isDirectory(input, NOFOLLOW_LINKS);
			if (missing)
				throw new IOException("Revision review needs the proposed candidate " + field + ": " + input);
			addEvidence(evidence, input);
		}
		for (String key : obligationKeys)
			addObligationEvidence(evidence, state, statePath, key);
		return evidence;
	}

	/**
	 * Adds the retained source record for one reviewed obligation.
	 *
	 * @param evidence the selected review evidence
	 * @param state the durable candidate state
	 * @param statePath the durable candidate-state path
	 * @param key the obligation key
	 * @throws IOException if the retained obligation has no usable evidence
	 */
	private static void addObligationEvidence(List<Path> evidence, ObjectNode state, Path statePath, String key)
		throws IOException
	{
		ObjectNode retained = null;
		if (state.path("failure_signatures").isArray())
			retained = CandidateState.signature(PromptEvaluation.array(state.path("failure_signatures"),
				"failure signatures", statePath), key, statePath);
		if (retained != null)
		{
			if (retained.has("last_observed_check"))
				addEvidence(evidence, PromptEvaluation.requiredArtifact(PromptEvaluation.text(retained,
					"last_observed_check", statePath), "last observed check"));
			else if (retained.has("last_observed_formal_run"))
				addEvidence(evidence, PromptEvaluation.requiredArtifact(PromptEvaluation.text(retained,
					"last_observed_formal_run", statePath), "last observed formal run"));
			return;
		}
		if (!state.path("lineage").isObject())
			return;
		for (JsonNode node : PromptEvaluation.array(
			CandidateState.candidateLineage(state).path("obligations"), "lineage obligations", statePath))
		{
			ObjectNode obligation = PromptEvaluation.object(node, "lineage obligation");
			if (!PromptEvaluation.text(obligation, "key", statePath).equals(key))
				continue;
			if (obligation.has("last_observed_check"))
				addEvidence(evidence, PromptEvaluation.requiredArtifact(PromptEvaluation.text(obligation,
					"last_observed_check", statePath), "last observed check"));
			else if (obligation.path("covered_by_check").isObject())
				addEvidence(evidence, PromptEvaluation.requiredArtifact(PromptEvaluation.text(
					PromptEvaluation.object(obligation.path("covered_by_check"), "covered obligation"), "run", statePath),
					"covered check"));
			return;
		}
		throw new IOException("Revision review cannot find its retained obligation: " + key);
	}

	/**
	 * Adds one source to the bundle evidence at most once.
	 *
	 * @param evidence the selected review evidence
	 * @param source the source artifact
	 */
	private static void addEvidence(List<Path> evidence, Path source)
	{
		if (!evidence.contains(source))
			evidence.add(source);
	}

	/**
	 * Returns the registered coordinator access.
	 *
	 * @return the coordinator access
	 */
	private static Access access()
	{
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure revision-review access");
		return result;
	}

	/**
	 * Delegates prepared-run input validation.
	 *
	 * @param run the prepared run
	 * @param runPath the run location
	 * @throws IOException if the run inputs are malformed
	 */
	private static void validateRunInputs(ObjectNode run, Path runPath) throws IOException
	{
		access().validateRunInputs(run, runPath);
	}

	/**
	 * Delegates candidate revision derivation.
	 *
	 * @param run the prepared run
	 * @param runPath the run location
	 * @return the candidate revision
	 * @throws IOException if the revision is unavailable
	 */
	private static String candidateRevision(ObjectNode run, Path runPath) throws IOException
	{
		return access().candidateRevision(run, runPath);
	}
}

package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Path;
import io.github.cowwoc.cat.common.PromptEvaluation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns authorization of a replacement evaluation runtime after a passed smoke run.
 */
public final class RuntimeRevalidation
{
	/**
	 * Supplies coordinator-owned record locking and atomic publication to runtime revalidation.
	 */
	public interface Access
	{
		/**
		 * Runs one record mutation while holding the record lock.
		 *
		 * @param recordPath the record to lock
		 * @param operation the mutation to execute
		 * @throws IOException if the record cannot be locked or mutated
		 */
		void withLock(Path recordPath, RecordStore.IoOperation operation) throws IOException;

		/**
		 * Publishes one complete record atomically.
		 *
		 * @param destination the record destination
		 * @param value the complete replacement record
		 * @throws IOException if publication fails
		 */
		void writeAtomically(Path destination, ObjectNode value) throws IOException;

		/**
		 * Classifies the retained evaluator result that requests a new execution epoch.
		 *
		 * @param runPath the retained evaluator record
		 * @param caseId the failed scenario identifier
		 * @param trial the failed whole-prompt trial
		 * @return the retained failure classification
		 * @throws IOException if the retained result cannot be classified
		 */
		ObjectNode classifyFailure(Path runPath, String caseId, int trial) throws IOException;

		/**
		 * Returns whether one retained run completed a passed smoke evaluation.
		 *
		 * @param run the retained run
		 * @return whether the run completed a passed smoke evaluation
		 */
		boolean hasCompletedSmoke(ObjectNode run);
	}

	private static volatile Access access;

	/**
	 * Registers the coordinator operations used by runtime revalidation.
	 *
	 * @param access the coordinator operations
	 */
	public static void configure(Access access)
	{
		RuntimeRevalidation.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}
	/**
	 * Records the passed smoke evidence that authorizes one exact replacement execution identity.
	 *
	 * <p>
	 * A smoke run does not establish compliance or promotion. It establishes that the unchanged candidate runs one
	 * representative case under replacement conditions. A later full regression check determines whether those
	 * conditions preserve every required case.
	 *
	 * @param candidateStatePath the durable state for the candidate whose runtime is being replaced
	 * @param smokeRunPath the completed passed smoke record for the replacement runtime
	 * @return the persisted runtime authorization
	 * @throws IOException if the smoke record is incomplete, failed, for another candidate, or does not replace the
	 *                   current runtime
	 */
	public static ObjectNode revalidate(Path candidateStatePath, Path smokeRunPath) throws IOException
	{
		Path statePath = candidateStatePath.toAbsolutePath().normalize();
		Path smokePath = smokeRunPath.toAbsolutePath().normalize();
		ObjectNode smokeRun = PromptEvaluation.object(PromptEvaluation.read(smokePath), "smoke evaluation record");
		verifyPassedSmoke(smokeRun, smokePath);

		ObjectNode[] result = new ObjectNode[1];
		withLock(statePath, () ->
		{
			ObjectNode state = PromptEvaluation.object(PromptEvaluation.read(statePath), "candidate state");
			String promptSha256 = PromptEvaluation.text(smokeRun, "prompt_sha256", smokePath);
			if (!promptSha256.equals(candidatePromptSha256(state, statePath)))
			{
				throw new IOException("Revalidated smoke does not use the unfinished candidate: " + smokePath);
			}
			String currentEpoch = PromptEvaluation.text(state, "evaluation_epoch_sha256", statePath);
			String replacementEpoch = PromptEvaluation.text(smokeRun, "evaluation_epoch_sha256", smokePath);
			if (currentEpoch.equals(replacementEpoch))
				throw new IOException("Revalidated smoke must prove a replacement execution identity: " + smokePath);

		ArrayNode supersessions = epochSupersessions(state, statePath);
			for (JsonNode node : supersessions)
			{
				ObjectNode existing = PromptEvaluation.object(node, "evaluation epoch supersession");
				if (existing.path("kind").asString().equals("revalidated-runtime") &&
					PromptEvaluation.text(existing, "replacement_run", statePath).equals(smokePath.toString()))
				{
					result[0] = existing.deepCopy();
					return;
				}
			}
			ObjectNode authorization = supersessions.addObject();
			authorization.put("kind", "revalidated-runtime");
			authorization.put("superseded_evaluation_epoch_sha256", currentEpoch);
			authorization.put("replacement_evaluation_epoch_sha256", replacementEpoch);
			authorization.put("replacement_run", smokePath.toString());
			ObjectNode smoke = PromptEvaluation.object(smokeRun.path("smoke_result"), "smoke result");
			authorization.put("smoke_case_id", PromptEvaluation.text(smoke, "case_id", smokePath));
			authorization.put("smoke_trial", smoke.path("trial").asInt());
			authorization.put("prompt_sha256", promptSha256);
			authorization.put("authorized_at_epoch_millis", System.currentTimeMillis());
			writeAtomically(statePath, state);
			result[0] = authorization.deepCopy();
		});
		return result[0];
	}


	/**
	 * Records a deterministic evaluator defect that authorizes a successor execution epoch.
	 *
	 * @param candidateStatePath identifies the candidate state whose epoch is superseded
	 * @param defectRunPath identifies the retained evaluator-defect record
	 * @param caseId identifies the failed scenario
	 * @param trial identifies the failed whole-prompt trial
	 * @return the persisted supersession authorization
	 * @throws IOException if the failure is not a deterministic evaluator defect in the current epoch
	 */
	public static ObjectNode authorizeDefectSupersession(Path candidateStatePath, Path defectRunPath, String caseId,
		int trial) throws IOException
	{
		Path statePath = candidateStatePath.toAbsolutePath().normalize();
		Path defectPath = defectRunPath.toAbsolutePath().normalize();
		ObjectNode defectRun = PromptEvaluation.object(PromptEvaluation.read(defectPath), "evaluator defect run");
		ObjectNode classification = access().classifyFailure(defectPath, caseId, trial);
		if (!classification.path("failure_class").asString().equals(FailureClass.DETERMINISTIC_TOOL.externalName()))
		{
			throw new IOException("Execution conditions may be superseded only by a retained evaluator-owned deterministic " +
				"failure: " + defectPath);
		}

		ObjectNode[] result = new ObjectNode[1];
		withLock(statePath, () ->
		{
			ObjectNode state = PromptEvaluation.object(PromptEvaluation.read(statePath), "candidate state");
			String currentEpoch = PromptEvaluation.text(state, "evaluation_epoch_sha256", statePath);
			if (!currentEpoch.equals(PromptEvaluation.text(defectRun, "evaluation_epoch_sha256", defectPath)))
			{
				throw new IOException("Evaluator defect does not belong to the candidate's current execution epoch: " +
					defectPath);
			}

			ArrayNode supersessions = epochSupersessions(state, statePath);
			for (JsonNode node : supersessions)
			{
				ObjectNode existing = PromptEvaluation.object(node, "evaluation epoch supersession");
				if (PromptEvaluation.text(existing, "defect_run", statePath).equals(defectPath.toString()) &&
					existing.path("case_id").asString().equals(caseId) && existing.path("trial").asInt() == trial)
				{
					result[0] = existing.deepCopy();
					return;
				}
			}
			ObjectNode authorization = supersessions.addObject();
			authorization.put("superseded_evaluation_epoch_sha256", currentEpoch);
			authorization.put("defect_run", defectPath.toString());
			authorization.put("case_id", caseId);
			authorization.put("trial", trial);
			authorization.set("classification", classification);
			authorization.put("authorized_at_epoch_millis", System.currentTimeMillis());
			writeAtomically(statePath, state);
			result[0] = authorization.deepCopy();
		});
		return result[0];
	}


	/**
	 * Returns the persisted execution-epoch supersession records, creating the collection when absent.
	 *
	 * @param state the mutable candidate state
	 * @param statePath identifies {@code state} in malformed-record diagnostics
	 * @return the mutable supersession records
	 * @throws IOException if the persisted supersession value is malformed
	 */
	public static ArrayNode epochSupersessions(ObjectNode state, Path statePath) throws IOException
	{
		JsonNode existing = state.path("evaluation_epoch_supersessions");
		if (existing.isMissingNode())
			return state.putArray("evaluation_epoch_supersessions");
		return PromptEvaluation.array(existing, "evaluation epoch supersessions", statePath);
	}

	/**
	 * Indicates whether retained evidence authorizes the proposed replacement execution epoch.
	 *
	 * @param state the candidate state
	 * @param statePath identifies {@code state} in malformed-record diagnostics
	 * @param currentEpoch identifies the execution epoch being replaced
	 * @param proposedEpoch identifies the proposed successor epoch
	 * @return {@code true} if a retained authorization permits the replacement
	 * @throws IOException if retained supersession evidence is malformed
	 */
	public static boolean hasSupersession(ObjectNode state, Path statePath, String currentEpoch, String proposedEpoch)
		throws IOException
	{
		for (JsonNode node : epochSupersessions(state, statePath))
		{
			ObjectNode supersession = PromptEvaluation.object(node, "evaluation epoch supersession");
			if (!PromptEvaluation.text(supersession, "superseded_evaluation_epoch_sha256", statePath).
				equals(currentEpoch))
			{
				continue;
			}
			if (!supersession.path("kind").asString().equals("revalidated-runtime") ||
				PromptEvaluation.text(supersession, "replacement_evaluation_epoch_sha256", statePath).
					equals(proposedEpoch))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the candidate prompt identity the durable state permits a smoke run to revalidate.
	 *
	 * @param state the durable candidate state
	 * @param statePath the location of {@code state}
	 * @return the prompt identity of the unfinished candidate
	 * @throws IOException if the state has neither a promotion nor a retained latest check
	 */
	private static String candidatePromptSha256(ObjectNode state, Path statePath) throws IOException
	{
		if (state.path("current_promotion").isObject())
		{
			return PromptEvaluation.text(PromptEvaluation.object(state.path("current_promotion"), "current promotion"),
				"prompt_sha256", statePath);
		}
		if (!state.hasNonNull("latest_check"))
		{
			throw new IOException("Revalidated execution requires a promoted candidate or retained latest check: " +
				statePath);
		}

		Path latestCheckPath = Path.of(PromptEvaluation.text(state, "latest_check", statePath)).toAbsolutePath().
			normalize();
		ObjectNode latestCheck = PromptEvaluation.object(PromptEvaluation.read(latestCheckPath), "latest candidate check");
		return PromptEvaluation.text(latestCheck, "prompt_sha256", latestCheckPath);
	}

	/**
	 * Verifies one smoke record completed a passed representative trial.
	 *
	 * @param smokeRun the retained smoke record
	 * @param smokePath the smoke record location
	 * @throws IOException if the record lacks a passed smoke result
	 */
	private static void verifyPassedSmoke(ObjectNode smokeRun, Path smokePath) throws IOException
	{
		if (!hasCompletedSmoke(smokeRun))
			throw new IOException("Revalidated execution requires a completed smoke run: " + smokePath);
		ObjectNode smoke = PromptEvaluation.object(smokeRun.path("smoke_result"), "smoke result");
		String caseId = PromptEvaluation.text(smoke, "case_id", smokePath);
		int trial = smoke.path("trial").asInt();
		for (JsonNode attempt : PromptEvaluation.attempts(smokeRun, smokePath))
		{
			if (attempt.path("case_id").asString().equals(caseId) && attempt.path("trial").asInt() == trial &&
				attempt.path("status").asString().equals("passed"))
			{
				return;
			}
		}
		throw new IOException("Revalidated execution requires a passed smoke result for " + caseId + " trial " +
			trial + ": " + smokePath);
	}

	/**
	 * Prevents utility-class construction.
	 */
	private RuntimeRevalidation()
	{
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
			throw new AssertionError("PromptEvaluation did not configure runtime-revalidation access");
		return result;
	}

	/**
	 * Delegates one locked record mutation.
	 *
	 * @param recordPath the record to lock
	 * @param operation the mutation to execute
	 * @throws IOException if the record cannot be locked or mutated
	 */
	private static void withLock(Path recordPath, RecordStore.IoOperation operation) throws IOException
	{
		access().withLock(recordPath, operation);
	}

	/**
	 * Delegates atomic record publication.
	 *
	 * @param destination the record destination
	 * @param value the complete replacement record
	 * @throws IOException if publication fails
	 */
	private static void writeAtomically(Path destination, ObjectNode value) throws IOException
	{
		access().writeAtomically(destination, value);
	}

	/**
	 * Delegates completed-smoke detection.
	 *
	 * @param run the retained run
	 * @return whether the run completed a passed smoke evaluation
	 */
	private static boolean hasCompletedSmoke(ObjectNode run)
	{
		return access().hasCompletedSmoke(run);
	}
}

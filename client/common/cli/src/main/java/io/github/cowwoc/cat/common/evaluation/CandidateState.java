package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.PromptEvaluation;

import static io.github.cowwoc.cat.common.PromptEvaluation.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores atomic changes to the durable candidate-state record.
 * <p>
 * The workflow controller uses this type to create the record, preserve the formal evaluation it may resume, and
 * invalidate a promotion when the retained evidence no longer matches the current inputs.
 */
public final class CandidateState
{
	/**
	 * Supplies coordinator-owned run validation and record mutation to candidate-state transitions.
	 */
	public interface Access
	{
		/**
		 * Validates one retained evaluation record's declared inputs.
		 *
		 * @param run the retained evaluation record
		 * @param runPath the record location
		 * @throws IOException if the record inputs are malformed
		 */
		void validateRunInputs(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Validates one retained evaluation record's evidence integrity.
		 *
		 * @param run the retained evaluation record
		 * @param runPath the record location
		 * @throws IOException if retained evidence is missing or invalid
		 */
		void validateEvidenceIntegrity(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Runs one atomic record mutation under its durable lock.
		 *
		 * @param recordPath the record to lock
		 * @param operation the mutation to execute while locked
		 * @throws IOException if the record cannot be locked or mutated
		 */
		void withLock(Path recordPath, RecordStore.IoOperation operation) throws IOException;

		/**
		 * Publishes one complete record atomically.
		 *
		 * @param destination the durable record destination
		 * @param value the complete new record
		 * @throws IOException if the record cannot be published
		 */
		void writeAtomically(Path destination, ObjectNode value) throws IOException;

		/**
		 * Returns the candidate identity represented by one retained run.
		 *
		 * @param run the retained run
		 * @param runPath the run location
		 * @return the candidate identity
		 * @throws IOException if the identity is unavailable
		 */
		String candidateIdentity(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Returns the candidate revision represented by one retained run.
		 *
		 * @param run the retained run
		 * @param runPath the run location
		 * @return the candidate revision
		 * @throws IOException if the revision is unavailable
		 */
		String candidateRevision(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Returns whether a retained run completed a passed regression check.
		 *
		 * @param run the retained run
		 * @return whether the run completed a passed regression check
		 */
		boolean hasCompletedRegressionCheck(ObjectNode run);

		/**
		 * Returns whether one retained epoch supersession authorizes a proposed replacement.
		 *
		 * @param state the candidate state
		 * @param statePath the candidate-state location
		 * @param currentEpoch the current execution epoch
		 * @param proposedEpoch the proposed replacement epoch
		 * @return whether the supersession authorizes the replacement
		 * @throws IOException if the retained supersession is malformed
		 */
		boolean hasEpochSupersession(ObjectNode state, Path statePath, String currentEpoch, String proposedEpoch)
			throws IOException;
	}

	private static volatile Access access;

	/**
	 * Registers the coordinator operations used by candidate-state transitions.
	 *
	 * @param access the coordinator operations
	 */
	public static void configure(Access access)
	{
		CandidateState.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}
	/**
	 * Prevents construction of this static utility.
	 */
	private CandidateState()
	{
	}

	/**
	 * Reads the current durable candidate state.
	 *
	 * @param candidateStatePath the candidate-state record
	 * @return the parsed candidate state
	 * @throws IOException if the record cannot be read as an object
	 */
	public static ObjectNode read(Path candidateStatePath) throws IOException
	{
		Path normalizedState = candidateStatePath.toAbsolutePath().normalize();
		return object(PromptEvaluation.read(normalizedState), "candidate state");
	}

	/**
	 * Indicates whether candidate state retains a formal evaluation that can be resumed.
	 *
	 * @param state the candidate state
	 * @return whether the state retains a pending formal evaluation
	 */
	public static boolean hasPendingCertification(ObjectNode state)
	{
		return state.path("pending_certification").isObject();
	}

	/**
	 * Indicates whether the latest regression check passed and can participate in policy selection.
	 *
	 * @param state the candidate state containing the latest check
	 * @param uncovered the lineage obligations the latest check did not cover
	 * @param candidateStatePath the durable candidate-state record
	 * @return whether the latest check passed without an outstanding promotion
	 * @throws IOException if the latest check record cannot be read
	 */
	public static boolean latestCheckPassed(ObjectNode state, List<String> uncovered, Path candidateStatePath)
		throws IOException
	{
		if (!state.has("latest_check") || !uncovered.isEmpty() ||
			state.path("latest_check_status").asString().equals("failed") || state.has("current_promotion"))
		{
			return false;
		}
		Path latestCheckPath = latestCheckPath(state, candidateStatePath);
		ObjectNode latestCheckRun = object(PromptEvaluation.read(latestCheckPath), "latest candidate check");
		ObjectNode latestCheck = object(latestCheckRun.path("regression_check"), "latest regression check");
		return text(latestCheck, "status", latestCheckPath).equals("passed");
	}

	/**
	 * Returns the retained latest regression-check record.
	 *
	 * @param state the candidate state containing the latest-check reference
	 * @param candidateStatePath the durable candidate-state record
	 * @return the normalized latest regression-check path
	 * @throws IOException if the latest-check reference is absent or malformed
	 */
	public static Path latestCheckPath(ObjectNode state, Path candidateStatePath) throws IOException
	{
		return Path.of(text(state, "latest_check", candidateStatePath)).toAbsolutePath().normalize();
	}

	/**
	 * Returns obligations that a successful current check did not cover before formal certification.
	 *
	 * @param state the candidate state
	 * @param statePath the candidate-state location
	 * @return stable keys for obligations that remain uncovered
	 * @throws IOException if the lineage record is malformed
	 */
	public static List<String> uncoveredLineageObligationKeys(ObjectNode state, Path statePath) throws IOException
	{
		if (!state.has("lineage"))
			return List.of();
		ObjectNode lineage = object(state.path("lineage"), "candidate lineage");
		List<String> result = new ArrayList<>();
		for (JsonNode node : array(lineage, "obligations", "lineage obligations", statePath))
		{
			ObjectNode obligation = object(node, "lineage obligation");
			if (obligation.path("status").asString().equals("open"))
				result.add(text(obligation, "key", statePath));
		}
		result.sort(String::compareTo);
		return result;
	}

	/**
	 * Creates an initially empty candidate-state record when no controller has recorded one.
	 *
	 * @param json the mapper that creates and updates candidate-state records
	 * @param candidateStatePath the initially absent durable candidate-state path
	 * @throws IOException if the state cannot be created atomically
	 */
	public static void initialize(JsonMapper json, Path candidateStatePath) throws IOException
	{
		Path normalizedState = candidateStatePath.toAbsolutePath().normalize();
		RecordStore.withLock(normalizedState, () ->
		{
			if (Files.exists(normalizedState))
				return;

			ObjectNode state = json.createObjectNode();
			state.put("schema_version", Records.schemaVersion());
			state.putArray("failure_signatures");
			state.putArray("promotions");
			state.put("created_at_epoch_millis", System.currentTimeMillis());
			write(normalizedState, state);
		});
	}

	/**
	 * Invalidates the current promotion after its retained inputs no longer match the controller's current inputs.
	 * <p>
	 * The method records the retired promotion and the supplied diagnostic, then removes the current promotion so the
	 * controller runs a new regression check instead of certifying stale evidence.
	 *
	 * @param candidateStatePath the state that owns the promotion
	 * @param reason the diagnostic describing the input that changed after promotion
	 * @throws IOException if the state cannot be updated
	 */
	public static void invalidatePromotion(Path candidateStatePath, String reason) throws IOException
	{
		Path normalizedState = candidateStatePath.toAbsolutePath().normalize();
		RecordStore.withLock(normalizedState, () ->
		{
			ObjectNode state = object(PromptEvaluation.read(normalizedState), "candidate state");
			ObjectNode promotion = object(state.path("current_promotion"), "current promotion");
			ArrayNode invalidations = array(state, "invalidated_promotions", "invalidated promotions",
				normalizedState);
			ObjectNode invalidation = invalidations.addObject();
			invalidation.set("promotion", promotion.deepCopy());
			invalidation.put("reason", reason);
			invalidation.put("invalidated_at_epoch_millis", System.currentTimeMillis());
			state.remove("current_promotion");
			state.put("updated_at_epoch_millis", System.currentTimeMillis());
			write(normalizedState, state);
		});
	}

	/**
	 * Records a completed controller transition and returns the receipt that identifies it.
	 *
	 * @param json the mapper that creates and updates candidate-state records
	 * @param candidateStatePath the durable candidate state
	 * @param transition the completed transition name
	 * @param recordField the result field that contains the retained transition-record path
	 * @param transitionResult the completed transition result
	 * @return a receipt containing the transition name and completed transition result
	 * @throws IOException if the result does not name a retained transition record or the candidate state is invalid
	 */
	public static ObjectNode recordTransition(JsonMapper json, Path candidateStatePath, String transition,
		String recordField, ObjectNode transitionResult) throws IOException
	{
		Path record = Path.of(text(transitionResult, recordField, Path.of(transition + " result"))).
			toAbsolutePath().normalize();
		if (!Files.isRegularFile(record, NOFOLLOW_LINKS))
			throw new IOException("Controller transition did not retain its required record: " + record);
		Path normalizedState = candidateStatePath.toAbsolutePath().normalize();
		RecordStore.withLock(normalizedState, () ->
		{
			ObjectNode state = object(PromptEvaluation.read(normalizedState), "candidate state");
			ArrayNode transitions = array(state, "controller_transitions", "controller transitions", normalizedState);
			ObjectNode entry = transitions.addObject();
			entry.put("transition", transition);
			entry.put("record", record.toString());
			entry.put("record_sha256", sha256(record));
			entry.put("completed_at_epoch_millis", System.currentTimeMillis());
			state.put("updated_at_epoch_millis", System.currentTimeMillis());
			write(normalizedState, state);
		});
		ObjectNode result = json.createObjectNode();
		result.put("transition", transition);
		result.set("result", transitionResult);
		return result;
	}

	/**
	 * Records the formal evaluation that a later controller may resume.
	 * <p>
	 * The pending record captures the run, workspace, plan, and identity fingerprints before model work starts. A
	 * second controller cannot replace that pending record until the recorded evaluation reaches a terminal decision.
	 *
	 * @param candidateStatePath the state that owns the pending evaluation
	 * @param runPath the prepared formal-evaluation record
	 * @param workspacePath the workspace prepared for the formal evaluation
	 * @param planPath the execution plan bound to the formal evaluation
	 * @throws IOException if the pending identity cannot be recorded atomically
	 */
	public static void recordPendingCertification(Path candidateStatePath, Path runPath, Path workspacePath,
		Path planPath)
		throws IOException
	{
		Path normalizedState = candidateStatePath.toAbsolutePath().normalize();
		Path normalizedRun = runPath.toAbsolutePath().normalize();
		Path normalizedWorkspace = workspacePath.toAbsolutePath().normalize();
		Path normalizedPlan = planPath.toAbsolutePath().normalize();
		RecordStore.withLock(normalizedState, () ->
		{
			ObjectNode state = object(PromptEvaluation.read(normalizedState), "candidate state");
			if (state.path("pending_certification").isObject())
				throw new IOException("Candidate already has a formal evaluation to resume: " + normalizedState);
			ObjectNode run = object(PromptEvaluation.read(normalizedRun), "formal evaluation record");
			ObjectNode pending = state.putObject("pending_certification");
			pending.put("run", normalizedRun.toString());
			pending.put("workspace", normalizedWorkspace.toString());
			pending.put("execution_plan", normalizedPlan.toString());
			pending.put("execution_plan_sha256", sha256(normalizedPlan));
			pending.put("suite_sha256", text(run, "suite_sha256", normalizedRun));
			pending.put("prompt_sha256", text(run, "prompt_sha256", normalizedRun));
			pending.put("execution_context_sha256", text(run, "execution_context_sha256", normalizedRun));
			pending.put("candidate_identity_sha256", candidatePromptIdentity(run, normalizedRun));
			pending.put("assembled_prompt_route_sha256", text(run, "assembled_prompt_route_sha256", normalizedRun));
			pending.put("execution_identity_sha256", text(run, "execution_identity_sha256", normalizedRun));
			pending.set("execution_identity", object(run.path("execution_identity"),
				"formal execution identity").deepCopy());
			pending.put("recorded_at_epoch_millis", System.currentTimeMillis());
			write(normalizedState, state);
		});
	}

	/**
	 * Removes the pending-evaluation pointer only when it still identifies the completed run.
	 * <p>
	 * If another controller replaced the pointer, the method fails rather than deleting that controller's resumable
	 * evaluation.
	 *
	 * @param candidateStatePath the state that owns the pending evaluation
	 * @param runPath the completed formal-evaluation record
	 * @throws IOException if the durable pointer changed before cleanup
	 */
	public static void clearPendingCertification(Path candidateStatePath, Path runPath) throws IOException
	{
		Path normalizedState = candidateStatePath.toAbsolutePath().normalize();
		Path normalizedRun = runPath.toAbsolutePath().normalize();
		RecordStore.withLock(normalizedState, () ->
		{
			ObjectNode state = object(PromptEvaluation.read(normalizedState), "candidate state");
			if (!state.path("pending_certification").isObject())
				return;
			ObjectNode pending = object(state.path("pending_certification"), "pending certification");
			Path pendingRun = Path.of(text(pending, "run", normalizedState)).toAbsolutePath().normalize();
			if (!pendingRun.equals(normalizedRun))
			{
				throw new IOException("Candidate pending certification changed before completion: " + normalizedState);
			}
			state.remove("pending_certification");
			write(normalizedState, state);
		});
	}

	/**
	 * Verifies that a pending record still identifies the formal evaluation prepared before interruption.
	 * <p>
	 * The pending and retained records must have identical suite, prompt, execution-context, candidate, route, and
	 * execution identities. Otherwise, resuming would run evidence different from the evidence the controller recorded.
	 *
	 * @param pending the candidate-state identity snapshot
	 * @param runPath the retained formal-evaluation record
	 * @param candidateStatePath the owning candidate state
	 * @throws IOException if the pending snapshot and retained run differ
	 */
	public static void verifyPendingCertificationIdentity(ObjectNode pending, Path runPath, Path candidateStatePath)
		throws IOException
	{
		ObjectNode run = object(PromptEvaluation.read(runPath), "resumed formal evaluation record");
		for (String field : List.of("suite_sha256", "prompt_sha256", "execution_context_sha256",
			"candidate_identity_sha256", "assembled_prompt_route_sha256", "execution_identity_sha256"))
		{
			if (!text(pending, field, candidateStatePath).equals(text(run, field, runPath)))
			{
				throw new IOException("Pending formal evaluation identity differs from its retained run: " + field);
			}
		}
		ObjectNode pendingIdentity = object(pending.path("execution_identity"), "pending execution identity");
		ObjectNode runIdentity = object(run.path("execution_identity"), "retained execution identity");
		if (!pendingIdentity.equals(runIdentity))
			throw new IOException("Pending formal evaluation execution conditions differ from its retained run");
	}

	/**
	 * Reports whether a validation failure shows that retained evaluation inputs changed.
	 * <p>
	 * A {@code true} result tells the controller to start a new candidate check because the suite, prompt, execution
	 * context, plan, conditions, epoch, comparison contract, or route changed after evaluation began.
	 *
	 * @param failure the validation failure
	 * @return {@code true} if the retained evaluation no longer represents its declared inputs
	 */
	public static boolean inputDrift(IOException failure)
	{
		String message = failure.getMessage();
		if (message == null)
			return false;
		String messagePrefix = message.substring(0, message.indexOf(':') + 1);
		return switch (messagePrefix)
		{
			case "Suite changed after the evaluation began:" -> true;
			case "Visible suite changed after promotion:" -> true;
			case "Prompt artifact changed after the evaluation began:" -> true;
			case "Execution context changed after the evaluation began:" -> true;
			case "Execution plan changed after the evaluation began:" -> true;
			case "Execution conditions changed after the evaluation began:" -> true;
			case "Evaluation epoch changed after the evaluation began:" -> true;
			case "Comparison contract changed after the evaluation began:" -> true;
			case "Prompt route changed after the evaluation began:" -> true;
			default -> false;
		};
	}

	/**
	 * Returns why the latest regression check cannot be promoted for the controller's current inputs.
	 *
	 * @param state the candidate state containing the latest check
	 * @param candidateStatePath the durable candidate state
	 * @param suitePath the visible suite
	 * @param promptPath the candidate prompt
	 * @param harness the selected harness
	 * @param contextPath the validator contract
	 * @return the stale-input diagnostic, or {@code null} when the recorded and current inputs match
	 * @throws IOException if the retained latest check is unavailable or malformed
	 */
	public static String staleLatestCheckReason(ObjectNode state, Path candidateStatePath, Path suitePath,
		Path promptPath,
		String harness, Path contextPath) throws IOException
	{
		if (!state.has("latest_check"))
			return null;
		Path checkPath = Path.of(text(state, "latest_check", candidateStatePath)).toAbsolutePath().normalize();
		ObjectNode check = object(PromptEvaluation.read(checkPath), "latest regression check");
		if (!suiteFingerprint(suitePath).equals(text(check, "suite_sha256", checkPath)))
			return "Suite changed after the latest regression check: " + suitePath;
		if (!sha256(promptPath).equals(text(check, "prompt_sha256", checkPath)))
			return "Prompt artifact changed after the latest regression check: " + promptPath;
		if (!sha256(contextPath).equals(text(check, "execution_context_sha256", checkPath)))
			return "Execution context changed after the latest regression check: " + contextPath;
		if (!harness.equals(text(check, "harness", checkPath)))
			return "Harness changed after the latest regression check: " + harness;
		return null;
	}

	/**
	 * Returns why the current promotion can no longer authorize certification.
	 *
	 * @param candidateStatePath the state that owns the promotion
	 * @param suitePath the current visible suite
	 * @return the stale-input diagnostic, or {@code null} when the promotion's retained inputs remain valid
	 * @throws IOException if retained evidence is unavailable or malformed
	 */
	public static String stalePromotionReason(Path candidateStatePath, Path suitePath) throws IOException
	{
		try
		{
			verifyPromotionReceipt(candidateStatePath);
			verifyPromotedVisibleSuite(candidateStatePath, suitePath);
			return null;
		}
		catch (IOException e)
		{
			if (inputDrift(e))
				return e.getMessage();
			throw e;
		}
	}

	/**
	 * Confirms that a state record and candidate check belong to one comparison epoch.
	 *
	 * @param state the existing candidate state
	 * @param checkRun the new check record
	 * @param statePath the state path
	 * @param checkRunPath the check path
	 * @throws IOException if their stable comparison identity differs
	 */
	public static void validateCandidateStateEpoch(ObjectNode state, ObjectNode checkRun, Path statePath,
		Path checkRunPath) throws IOException
	{
		if (!text(state, "suite_sha256", statePath).equals(promotionSuiteFingerprint(checkRun, checkRunPath)))
		{
			throw new IOException("This candidate state was created with a different visible suite, so its prior check " +
				"cannot be compared with this run. Create a new candidate-state file for this evaluation epoch: " +
				checkRunPath);
		}
		if (!text(state, "evaluation_epoch_sha256", statePath).equals(text(checkRun, "evaluation_epoch_sha256",
			checkRunPath)))
		{
			throw new IOException("This candidate state was created with different evaluation_epoch_sha256, so its prior " +
				"check cannot be compared with this run. Create a new candidate-state file for this evaluation epoch: " +
				checkRunPath);
		}
	}

	/**
	 * Publishes a completed candidate check as eligible for formal certification.
	 *
	 * @param checkRunPath the completed regression-check record
	 * @param json the mapper that creates and updates candidate-state records
	 * @param candidateStatePath the durable candidate-state record
	 * @return the published promotion record
	 * @throws IOException if the check cannot authorize a promotion
	 */
	public static ObjectNode promote(JsonMapper json, Path checkRunPath, Path candidateStatePath) throws IOException
	{
		Path normalizedCheckRunPath = checkRunPath.toAbsolutePath().normalize();
		Path normalizedCandidateStatePath = candidateStatePath.toAbsolutePath().normalize();
		ObjectNode run = object(read(normalizedCheckRunPath), "regression-check run");
		if (!hasCompletedRegressionCheck(run))
			throw new IOException("Candidate has no completed regression check: " + normalizedCheckRunPath);
		ObjectNode check = object(run.path("regression_check"), "regression check");
		String checkStatus = text(check, "status", normalizedCheckRunPath);
		if (!checkStatus.equals("passed"))
		{
			String diagnostic = "Candidate cannot be promoted because its regression check did not complete: ";
			if (checkStatus.equals("failed"))
				diagnostic = "Candidate cannot be promoted because its regression check has failed: ";
			throw new IOException(diagnostic + normalizedCheckRunPath);
		}

		validateRunInputs(run, normalizedCheckRunPath);
		validateEvidenceIntegrity(run, normalizedCheckRunPath);
		if (!run.has("failure_signature_state"))
		{
			throw new IOException("Candidate check has no durable failure-signature state. Run " +
				"record-regression-check-signatures before promotion: " + normalizedCheckRunPath);
		}
		Path recordedState = path(text(run, "failure_signature_state", normalizedCheckRunPath));
		if (!recordedState.equals(normalizedCandidateStatePath))
			throw new IOException("Candidate check belongs to a different candidate state: " + recordedState);

		ObjectNode[] result = new ObjectNode[1];
		withRunLock(normalizedCandidateStatePath, () ->
		{
			ObjectNode state = object(read(normalizedCandidateStatePath), "candidate state");
			validateCandidateStateEpoch(state, run, normalizedCandidateStatePath, normalizedCheckRunPath);
			List<String> uncovered = uncoveredLineageObligationKeys(state, normalizedCandidateStatePath);
			if (!uncovered.isEmpty())
			{
				throw new IOException("Candidate cannot be promoted because its latest check did not exercise every " +
					"unresolved obligation: " + String.join(", ", uncovered));
			}
			if (!text(state, "latest_check", normalizedCandidateStatePath).equals(normalizedCheckRunPath.toString()))
			{
				throw new IOException("Candidate state does not name this completed check as its latest check: " +
					normalizedCandidateStatePath);
			}

			ObjectNode promotion = json.createObjectNode();
			promotion.put("schema_version", Records.schemaVersion());
			promotion.put("check_run", normalizedCheckRunPath.toString());
			promotion.put("prompt_sha256", text(run, "prompt_sha256", normalizedCheckRunPath));
			promotion.put("candidate_identity_sha256", candidatePromptIdentity(run, normalizedCheckRunPath));
			promotion.put("suite_sha256", text(run, "suite_sha256", normalizedCheckRunPath));
			promotion.put("evaluation_epoch_sha256", text(run, "evaluation_epoch_sha256", normalizedCheckRunPath));
			promotion.put("promoted_at_epoch_millis", System.currentTimeMillis());
			array(state.path("promotions"), "promotions", normalizedCandidateStatePath).add(promotion.deepCopy());
			state.set("current_promotion", promotion);
			state.put("updated_at_epoch_millis", System.currentTimeMillis());
			writeAtomically(normalizedCandidateStatePath, state);
			result[0] = promotion;
		});
		return result[0];
	}

	/**
	 * Verifies that a prepared formal run has the identity of the current promotion.
	 *
	 * @param candidateStatePath the state that owns the promotion
	 * @param runPath the prepared formal-evaluation record
	 * @throws IOException if the formal run differs from the promotion
	 */
	public static void verifyPromotion(Path candidateStatePath, Path runPath) throws IOException
	{
		ObjectNode state = object(read(candidateStatePath), "candidate state");
		ObjectNode promotion = object(state.path("current_promotion"), "current promotion");
		ObjectNode run = object(read(runPath), "formal evaluation record");
		if (!text(promotion, "suite_sha256", candidateStatePath).equals(promotionSuiteFingerprint(run, runPath)))
			throw new IOException("Formal candidate is not the promoted candidate because its visible suite differs");
		if (!text(promotion, "candidate_identity_sha256", candidateStatePath).equals(candidatePromptIdentity(run,
			runPath)))
		{
			throw new IOException("Formal candidate is not the promoted candidate because its prompt or selected rules " +
				"differ");
		}
		for (String field : List.of("evaluation_epoch_sha256"))
		{
			if (!text(promotion, field, candidateStatePath).equals(text(run, field, runPath)))
			{
				throw new IOException("Formal candidate is not the promoted candidate because " + field + " differs");
			}
		}
	}

	/**
	 * Verifies that the visible suite remains the suite that the current promotion approved.
	 *
	 * @param candidateStatePath the state that owns the promotion
	 * @param suitePath the current visible suite
	 * @throws IOException if the suite changed after promotion
	 */
	public static void verifyPromotedVisibleSuite(Path candidateStatePath, Path suitePath) throws IOException
	{
		ObjectNode state = object(read(candidateStatePath), "candidate state");
		ObjectNode promotion = object(state.path("current_promotion"), "current promotion");
		String promotedSuite = text(promotion, "suite_sha256", candidateStatePath);
		if (!promotedSuite.equals(suiteFingerprint(suitePath)))
		{
			throw new IOException("Visible suite changed after promotion: " + suitePath +
				". Run the controller's regression check before certification.");
		}
	}

	/**
	 * Verifies that the current promotion retains its completed regression-check receipt.
	 *
	 * @param candidateStatePath the state that owns the promotion
	 * @throws IOException if the promotion receipt is missing, incomplete, or inconsistent
	 */
	public static void verifyPromotionReceipt(Path candidateStatePath) throws IOException
	{
		Path normalizedCandidateStatePath = candidateStatePath.toAbsolutePath().normalize();
		ObjectNode state = object(read(normalizedCandidateStatePath), "candidate state");
		ObjectNode promotion = object(state.path("current_promotion"), "current promotion");
		Path checkPath;
		try
		{
			checkPath = path(text(promotion, "check_run", normalizedCandidateStatePath)).toAbsolutePath().normalize();
		}
		catch (IOException e)
		{
			throw new IOException("Current promotion has no regression-check receipt: " + normalizedCandidateStatePath,
				e);
		}
		if (!Files.isRegularFile(checkPath, NOFOLLOW_LINKS))
			throw new IOException("Current promotion's regression-check receipt is unavailable: " + checkPath);
		ObjectNode checkRun = object(read(checkPath), "promotion regression-check receipt");
		if (!hasCompletedRegressionCheck(checkRun) ||
			!text(object(checkRun.path("regression_check"), "regression check"), "status", checkPath).
				equals("passed"))
		{
			throw new IOException("Current promotion's regression-check receipt is not a completed passing check: " +
				checkPath);
		}
		validateRunInputs(checkRun, checkPath);
		validateEvidenceIntegrity(checkRun, checkPath);
		if (!state.path("latest_check").asString().equals(checkPath.toString()))
		{
			throw new IOException("Current promotion's regression-check receipt is not the candidate state's latest check: " +
				normalizedCandidateStatePath);
		}
		for (String field : List.of("candidate_identity_sha256", "suite_sha256", "evaluation_epoch_sha256"))
		{
			if (!text(promotion, field, normalizedCandidateStatePath).equals(text(checkRun, field, checkPath)))
			{
				throw new IOException("Current promotion differs from its regression-check receipt for " + field + ": " +
					checkPath);
			}
		}
	}

	/**
	 * Returns the visible-suite identity stored for a candidate check.
	 *
	 * @param run the prepared evaluation record
	 * @param runPath the record path
	 * @return the visible-suite fingerprint
	 * @throws IOException if the identity is absent
	 */
	public static String promotionSuiteFingerprint(ObjectNode run, Path runPath) throws IOException
	{
		if (run.has("promotion_suite_sha256"))
			return text(run, "promotion_suite_sha256", runPath);
		return text(run, "suite_sha256", runPath);
	}

	/**
	 * Returns the durable summaries of completed candidate checks.
	 *
	 * @param state the candidate state being updated
	 * @param statePath the candidate-state location
	 * @return the completed check summaries
	 * @throws IOException if a retained predecessor record cannot be read
	 */
	public static ArrayNode recordedScreens(ObjectNode state, Path statePath) throws IOException
	{
		JsonNode existing = state.path("checks");
		if (!existing.isMissingNode())
			return array(existing, "candidate checks", statePath);
		ArrayNode result = state.putArray("checks");
		if (!state.has("latest_check"))
			return result;
		Path previousRunPath = path(text(state, "latest_check", statePath));
		ObjectNode previousRun = object(read(previousRunPath), "previous candidate check");
		ObjectNode previousCheck = object(previousRun.path("regression_check"), "previous regression check");
		ObjectNode summary = result.addObject();
		summary.put("run", previousRunPath.toString());
		summary.put("prompt_sha256", text(previousRun, "prompt_sha256", previousRunPath));
		summary.put("status", text(previousCheck, "status", previousRunPath));
		return result;
	}

	/**
	 * Indicates whether a candidate check already contributed one durable summary.
	 *
	 * @param checks the retained check summaries
	 * @param checkRunPath the candidate check being recorded
	 * @param statePath the candidate-state location
	 * @return whether this exact check was already counted
	 * @throws IOException if a summary is malformed
	 */
	public static boolean containsRecordedScreen(ArrayNode checks, Path checkRunPath, Path statePath) throws IOException
	{
		for (JsonNode node : checks)
		{
			if (text(object(node, "candidate check"), "run", statePath).equals(checkRunPath.toString()))
				return true;
		}
		return false;
	}

	/**
	 * Returns the stable identity of one retained failed acceptance condition.
	 *
	 * @param signature the check failure signature
	 * @param source the signature source
	 * @return the stable signature key
	 * @throws IOException if the signature is incomplete
	 */
	public static String signatureKey(ObjectNode signature, Path source) throws IOException
	{
		return text(signature, "case_id", source) + "/" + text(signature, "assertion", source) + "/" +
			text(signature, "boundary", source);
	}

	/**
	 * Finds a retained signature by its stable key.
	 *
	 * @param signatures the state record's signatures
	 * @param key the stable signature key
	 * @param source the state-record path
	 * @return the retained signature, or {@code null} when this is its first observation
	 * @throws IOException if a retained signature is malformed
	 */
	public static ObjectNode signature(ArrayNode signatures, String key, Path source) throws IOException
	{
		for (JsonNode node : signatures)
		{
			ObjectNode candidate = object(node, "failure signature");
			if (text(candidate, "key", source).equals(key))
				return candidate;
		}
		return null;
	}

	/**
	 * Records a terminal formal decision and removes its matching pending-evaluation pointer.
	 * <p>
	 * A passing decision clears prior candidate failures. A non-infrastructure failure records its failure signatures.
	 * A running decision leaves the candidate state unchanged so a controller can resume the evaluation.
	 *
	 * @param runPath the formal evaluation record
	 * @param json the mapper that creates and updates candidate-state records
	 * @param candidateStatePath the state the record belongs to
	 * @param decision the formal evaluation decision
	 * @return the updated state when the decision changes candidate failures, or {@code null} otherwise
	 * @throws IOException if terminal state cannot be retained
	 */
	public static ObjectNode recordFormalDecision(JsonMapper json, Path runPath, Path candidateStatePath,
		ObjectNode decision) throws IOException
	{
		String outcome = decision.path("overall_decision").asString();
		if (outcome.equals("RUNNING"))
			return null;
		ObjectNode result = switch (outcome)
		{
			case "PASS" -> clearFailureSignaturesAfterCertification(runPath, candidateStatePath);
			case "FAIL" ->
			{
				if (decision.path("infrastructure_failure").asBoolean())
					yield null;
				yield recordFormalFailureSignatures(json, runPath, candidateStatePath);
			}
			default -> null;
		};
		clearPendingCertification(candidateStatePath, runPath);
		return result;
	}

	/**
	 * Records the completed regression check in its candidate's durable failure and lineage ledger.
	 *
	 * @param checkRunPath the completed regression-check record
	 * @param json the mapper that creates and updates candidate-state records
	 * @param candidateStatePath the durable candidate-state record
	 * @return the updated candidate state
	 * @throws IOException if the check is incomplete or its evidence is invalid
	 */
	public static ObjectNode recordCheckSignatures(JsonMapper json, Path checkRunPath, Path candidateStatePath)
		throws IOException
	{
		Path normalizedCheckRunPath = checkRunPath.toAbsolutePath().normalize();
		Path normalizedCandidateStatePath = candidateStatePath.toAbsolutePath().normalize();
		ObjectNode checkRun = object(read(normalizedCheckRunPath), "regression-check run");
		if (!hasCompletedRegressionCheck(checkRun))
			throw new IOException("Candidate has no completed regression check: " + normalizedCheckRunPath);
		validateRunInputs(checkRun, normalizedCheckRunPath);
		validateEvidenceIntegrity(checkRun, normalizedCheckRunPath);
		ObjectNode check = object(checkRun.path("regression_check"), "regression check");
		if (text(check, "status", normalizedCheckRunPath).equals("incomplete"))
		{
			throw new IOException("Cannot record failure signatures from an incomplete regression check: " +
				normalizedCheckRunPath);
		}
		ObjectNode[] result = new ObjectNode[1];
		withRunLock(normalizedCandidateStatePath, () ->
		{
			ObjectNode state;
			if (Files.exists(normalizedCandidateStatePath))
			{
				state = object(read(normalizedCandidateStatePath), "candidate state");
				startCandidateEpoch(state, checkRun, normalizedCandidateStatePath, normalizedCheckRunPath);
			}
			else
			{
				state = json.createObjectNode();
				state.put("schema_version", Records.schemaVersion());
				state.put("suite_sha256", text(checkRun, "suite_sha256", normalizedCheckRunPath));
				state.put("evaluation_epoch_sha256", text(checkRun, "evaluation_epoch_sha256", normalizedCheckRunPath));
				state.putArray("failure_signatures");
				state.putArray("promotions");
				startCandidateEpoch(state, checkRun, normalizedCandidateStatePath, normalizedCheckRunPath);
			}
			ArrayNode recordedScreens = recordedScreens(state, normalizedCandidateStatePath);
			if (containsRecordedScreen(recordedScreens, normalizedCheckRunPath, normalizedCandidateStatePath))
			{
				result[0] = state.deepCopy();
				return;
			}
				ArrayNode signatures = array(state.path("failure_signatures"), "failure signatures",
					normalizedCandidateStatePath);
				Map<String, ObjectNode> observed = new TreeMap<>();
				for (JsonNode node : array(check.path("failure_signatures"), "check failure signatures",
					normalizedCheckRunPath))
			{
				ObjectNode observedSignature = object(node, "check failure signature");
			String key = signatureKey(observedSignature, normalizedCheckRunPath);
			observed.putIfAbsent(key, observedSignature);
			}
			for (Map.Entry<String, ObjectNode> observation : observed.entrySet())
			{
				String key = observation.getKey();
				ObjectNode observedSignature = observation.getValue();
				ObjectNode retained = signature(signatures, key, normalizedCandidateStatePath);
				if (retained == null)
				{
					retained = signatures.addObject();
					retained.put("key", key);
					retained.set("case", observedSignature.deepCopy());
					retained.put("first_observed_candidate_sha256", candidateRevision(checkRun, normalizedCheckRunPath));
					retained.put("first_observed_check", normalizedCheckRunPath.toString());
					retained.put("first_observed_at_epoch_millis", System.currentTimeMillis());
					retained.put("observations", 0);
				}
				retained.put("obligation_family", obligationFamilyKey(observedSignature, normalizedCheckRunPath));
				String candidateHash = candidateRevision(checkRun, normalizedCheckRunPath);
				if (retained.path("coverage_route_sha256").asString().equals(candidateHash))
					retained.put("coverage_observations", retained.path("coverage_observations").asInt() + 1);
				else
				{
					retained.put("coverage_route_sha256", candidateHash);
					retained.put("coverage_observations", 1);
					retained.put("not_observed_comparable_exposures", 0);
				}
				ArrayNode candidateRevisions;
				if (retained.path("observed_candidate_revisions").isMissingNode())
					candidateRevisions = retained.putArray("observed_candidate_revisions");
				else
				{
					candidateRevisions = array(retained.path("observed_candidate_revisions"),
						"observed candidate revisions",
						normalizedCandidateStatePath);
				}
				boolean newCandidateRevision = true;
				for (JsonNode revision : candidateRevisions)
				{
					if (revision.asString().equals(candidateHash))
					{
						newCandidateRevision = false;
						break;
					}
				}
				if (newCandidateRevision)
					candidateRevisions.add(candidateHash);
				int consecutiveObservations = 1;
				if (retained.path("last_observed_candidate_sha256").asString().equals(candidateHash))
					consecutiveObservations = retained.path("consecutive_observations").asInt(1) + 1;
				retained.set("latest_case", observedSignature.deepCopy());
				retained.put("last_observed_candidate_sha256", candidateHash);
				retained.put("last_observed_check", normalizedCheckRunPath.toString());
				retained.put("last_observed_at_epoch_millis", System.currentTimeMillis());
				retained.put("observations", retained.path("observations").asInt() + 1);
				retained.put("consecutive_observations", consecutiveObservations);
				retained.put("not_observed_comparable_exposures", 0);
				retained.remove("cleared_by_certification");
				retained.remove("removed_by_candidate_sha256");
				retained.remove("removed_by_check");
				retained.remove("removed_at_epoch_millis");
			}
			for (JsonNode node : signatures)
			{
				ObjectNode retained = object(node, "failure signature");
				if (retained.has("cleared_by_certification"))
					continue;
				if (observed.containsKey(text(retained, "key", normalizedCandidateStatePath)))
					continue;
				String candidateHash = candidateRevision(checkRun, normalizedCheckRunPath);
				if (!retained.path("coverage_route_sha256").asString().equals(candidateHash))
				{
					retained.put("coverage_route_sha256", candidateHash);
					retained.put("coverage_observations", 0);
					retained.put("not_observed_comparable_exposures", 0);
				}
				retained.remove("removed_by_candidate_sha256");
				retained.remove("removed_by_check");
				retained.remove("removed_at_epoch_millis");
				retained.put("not_observed_comparable_exposures",
					retained.path("not_observed_comparable_exposures").asInt() +
						comparableExposureCount(check, retained, normalizedCandidateStatePath));
				retained.put("last_not_observed_check", normalizedCheckRunPath.toString());
				retained.put("last_not_observed_at_epoch_millis", System.currentTimeMillis());
			}
			updateLineageObligations(state, checkRun, normalizedCandidateStatePath, normalizedCheckRunPath);
			state.put("latest_check", normalizedCheckRunPath.toString());
			state.put("latest_check_status", text(check, "status", normalizedCheckRunPath));
			ObjectNode recordedScreen = recordedScreens.addObject();
			recordedScreen.put("run", normalizedCheckRunPath.toString());
			recordedScreen.put("prompt_sha256", text(checkRun, "prompt_sha256", normalizedCheckRunPath));
			recordedScreen.put("assembled_prompt_route_sha256", candidateRevision(checkRun, normalizedCheckRunPath));
			recordedScreen.put("status", text(check, "status", normalizedCheckRunPath));
			state.put("updated_at_epoch_millis", System.currentTimeMillis());
			writeAtomically(normalizedCandidateStatePath, state);
			result[0] = state.deepCopy();
		});
		withRunLock(normalizedCheckRunPath, () ->
		{
			ObjectNode run = object(read(normalizedCheckRunPath), "regression-check run");
			run.put("failure_signature_state", normalizedCandidateStatePath.toString());
			run.put("failure_signature_state_sha256", sha256(normalizedCandidateStatePath));
			write(normalizedCheckRunPath, run);
		});
		return result[0];
	}

	/**
	 * Opens a new comparable evaluation epoch while retaining failures that still require a later candidate to address.
	 *
	 * <p>
	 * A suite, runner, or sandbox-policy change makes numerical results incomparable, but it does not make an
	 * observed required behavior disappear. The lineage therefore outlives the current epoch; a later successful check
	 * may cover an obligation only when it still exercises the same testcase assertion.
	 *
	 * @param state the candidate state being prepared for the check
	 * @param checkRun the completed check about to be recorded
	 * @param statePath the candidate-state location
	 * @param checkRunPath the check-record location
	 * @throws IOException if a retained failure or epoch is malformed
	 */
	private static void startCandidateEpoch(ObjectNode state, ObjectNode checkRun, Path statePath, Path checkRunPath)
		throws IOException
	{
		ObjectNode lineage = candidateLineage(state);
		migrateFailureSignaturesToLineage(lineage, state, statePath);
		String suiteFingerprint = promotionSuiteFingerprint(checkRun, checkRunPath);
		String epochFingerprint = text(checkRun, "evaluation_epoch_sha256", checkRunPath);
		boolean sameEpoch = state.path("suite_sha256").asString().equals(suiteFingerprint) &&
			state.path("evaluation_epoch_sha256").asString().equals(epochFingerprint);
		if (!sameEpoch)
		{
			state.put("suite_sha256", suiteFingerprint);
			state.put("evaluation_epoch_sha256", epochFingerprint);
			state.remove("current_promotion");
		}
		ArrayNode epochs = array(lineage.path("epochs"), "candidate lineage epochs", statePath);
		for (JsonNode node : epochs)
		{
			ObjectNode epoch = object(node, "candidate lineage epoch");
			if (text(epoch, "suite_sha256", statePath).equals(suiteFingerprint) &&
				text(epoch, "evaluation_epoch_sha256", statePath).equals(epochFingerprint))
			{
				return;
			}
		}
		ObjectNode epoch = epochs.addObject();
		epoch.put("suite_sha256", suiteFingerprint);
		epoch.put("evaluation_epoch_sha256", epochFingerprint);
		epoch.put("first_check", checkRunPath.toString());
		epoch.put("started_at_epoch_millis", System.currentTimeMillis());
	}

	/**
	 * Returns the state-owned lineage ledger, upgrading earlier candidate records without discarding their failures.
	 *
	 * @param state the mutable candidate state
	 * @return the lineage ledger
	 * @throws IOException if an existing lineage is malformed
	 */
	public static ObjectNode candidateLineage(ObjectNode state) throws IOException
	{
		JsonNode existing = state.path("lineage");
		if (existing.isMissingNode())
		{
			ObjectNode lineage = state.putObject("lineage");
			lineage.put("schema_version", 1);
			lineage.putArray("epochs");
			lineage.putArray("obligations");
			return lineage;
		}
		return object(existing, "candidate lineage");
	}

	/**
	 * Copies every pre-lineage active signature into the persistent obligation ledger exactly once.
	 *
	 * @param lineage the candidate's lineage ledger
	 * @param state the legacy or current candidate state
	 * @param statePath the candidate-state location
	 * @throws IOException if a retained signature is malformed
	 */
	private static void migrateFailureSignaturesToLineage(ObjectNode lineage, ObjectNode state, Path statePath)
		throws IOException
	{
		if (!state.path("failure_signatures").isArray())
			return;
		for (JsonNode node : array(state.path("failure_signatures"), "failure signatures", statePath))
		{
			ObjectNode signature = object(node, "failure signature");
			if (signature.has("cleared_by_certification"))
				continue;
			ObjectNode observed = object(signature.path("latest_case"), "latest failure case");
			String key = text(signature, "key", statePath);
			lineageObligation(lineage, key, observed, statePath);
		}
	}

	/**
	 * Updates durable failure obligations from one completed regression check.
	 *
	 * @param state the candidate state being updated
	 * @param checkRun the completed regression check
	 * @param statePath the candidate-state location
	 * @param checkRunPath the check-record location
	 * @throws IOException if a suite or obligation is malformed
	 */
	private static void updateLineageObligations(ObjectNode state, ObjectNode checkRun, Path statePath,
		Path checkRunPath) throws IOException
	{
		ObjectNode lineage = candidateLineage(state);
		ObjectNode check = object(checkRun.path("regression_check"), "regression check");
		Map<String, ObjectNode> failures = new HashMap<>();
		for (JsonNode node : array(check.path("failure_signatures"), "check failure signatures", checkRunPath))
		{
			ObjectNode failure = object(node, "check failure signature");
			failures.put(signatureKey(failure, checkRunPath), failure);
		}
		for (Map.Entry<String, ObjectNode> failure : failures.entrySet())
		{
			ObjectNode obligation = lineageObligation(lineage, failure.getKey(), failure.getValue(), statePath);
			obligation.put("status", "open");
			obligation.put("last_observed_check", checkRunPath.toString());
			obligation.put("last_observed_at_epoch_millis", System.currentTimeMillis());
			obligation.remove("covered_by_check");
		}
		if (!text(check, "status", checkRunPath).equals("passed"))
			return;
		Path suitePath = path(text(checkRun, "suite", checkRunPath));
		ObjectNode suite = SuiteContract.read(suitePath);
		for (JsonNode node : array(lineage.path("obligations"), "lineage obligations", statePath))
		{
			ObjectNode obligation = object(node, "lineage obligation");
			if (!obligation.path("status").asString().equals("open"))
				continue;
			ObjectNode required = object(obligation.path("case"), "lineage obligation case");
			if (!suiteExercisesAssertion(suite, required, suitePath))
				continue;
			obligation.put("status", "covered");
			ObjectNode coverage = obligation.putObject("covered_by_check");
			coverage.put("run", checkRunPath.toString());
			coverage.put("candidate_identity_sha256", candidatePromptIdentity(checkRun, checkRunPath));
			coverage.put("evaluation_epoch_sha256", text(checkRun, "evaluation_epoch_sha256", checkRunPath));
			coverage.put("covered_at_epoch_millis", System.currentTimeMillis());
		}
	}

	/**
	 * Finds or creates one durable failure obligation in the state-owned lineage ledger.
	 *
	 * @param lineage the candidate's lineage ledger
	 * @param key the stable case/assertion/boundary key
	 * @param observed the observed failed assertion
	 * @param statePath the candidate-state location
	 * @return the mutable obligation
	 * @throws IOException if an existing obligation is malformed
	 */
	private static ObjectNode lineageObligation(ObjectNode lineage, String key, ObjectNode observed, Path statePath)
		throws IOException
	{
		for (JsonNode node : array(lineage.path("obligations"), "lineage obligations", statePath))
		{
			ObjectNode obligation = object(node, "lineage obligation");
			if (text(obligation, "key", statePath).equals(key))
				return obligation;
		}
		ObjectNode obligation = array(lineage.path("obligations"), "lineage obligations", statePath).addObject();
		obligation.put("key", key);
		obligation.set("case", observed.deepCopy());
		obligation.put("status", "open");
		obligation.put("first_observed_at_epoch_millis", System.currentTimeMillis());
		return obligation;
	}

	/**
	 * Determines whether a candidate suite still evaluates one previously failed assertion.
	 *
	 * @param suite the candidate suite declaration
	 * @param required the preserved failed assertion
	 * @param suitePath the suite source
	 * @return whether the suite declares the same testcase and assertion, or declares the testcase for a retained
	 *     terminal-outcome obligation whose successful result proves all of its assertions
	 * @throws IOException if the obligation is malformed
	 */
	private static boolean suiteExercisesAssertion(ObjectNode suite, ObjectNode required, Path suitePath)
		throws IOException
	{
		String caseId = text(required, "case_id", suitePath);
		String assertionId = text(required, "assertion", suitePath);
		for (JsonNode node : array(suite.path("cases"), "cases", suitePath))
		{
			ObjectNode testCase = object(node, "test case");
			if (!testCaseIdMatchesObligation(testCase, caseId, suitePath))
				continue;
			if (assertionId.equals("terminal-outcome"))
				return true;
			for (JsonNode assertion : array(testCase.path("assertions"), "assertions", suitePath))
				if (text(object(assertion, "assertion"), "id", suitePath).equals(assertionId))
					return true;
			return false;
		}
		return false;
	}

	/**
	 * Determines whether a derived testcase supplies the obligation identified by its source testcase.
	 *
	 * <p>
	 * A derived testcase has its own identifier and records the source testcase identifier, so a later regression check
	 * can prove that it exercised the same requirement.
	 *
	 * @param testCase one visible testcase
	 * @param requiredCaseId the identity recorded by the failed certification
	 * @param suitePath the suite that declares the testcase
	 * @return whether the testcase represents the required case
	 * @throws IOException if the testcase is malformed
	 */
	private static boolean testCaseIdMatchesObligation(ObjectNode testCase, String requiredCaseId, Path suitePath)
		throws IOException
	{
		if (text(testCase, "id", suitePath).equals(requiredCaseId))
			return true;
		JsonNode sourceCaseId = testCase.path("source_case_id");
		return sourceCaseId.isString() && sourceCaseId.asString().equals(requiredCaseId);
	}

	/**
	 * Records every assertion failure retained by a failed formal certification without clearing any existing obligation.
	 *
	 * @param formalRunPath the failed formal certification record
	 * @param json the mapper that creates and updates candidate-state records
	 * @param candidateStatePath the promoted candidate's durable state
	 * @return the updated candidate state
	 * @throws IOException if the formal evidence or durable state is incomplete
	 */
	public static ObjectNode recordFormalFailureSignatures(JsonMapper json, Path formalRunPath, Path candidateStatePath)
		throws IOException
	{
		Path normalizedFormalRunPath = formalRunPath.toAbsolutePath().normalize();
		Path normalizedCandidateStatePath = candidateStatePath.toAbsolutePath().normalize();
		ObjectNode formalRun = object(read(normalizedFormalRunPath), "formal evaluation run");
		validateRunInputs(formalRun, normalizedFormalRunPath);
		validateEvidenceIntegrity(formalRun, normalizedFormalRunPath);
		Path suitePath = path(text(formalRun, "suite", normalizedFormalRunPath));
		ObjectNode suite = SuiteContract.read(suitePath);
		Map<String, ObjectNode> casesById = new HashMap<>();
		for (JsonNode node : array(suite.path("cases"), "cases", suitePath))
		{
			ObjectNode testCase = object(node, "test case");
			casesById.put(text(testCase, "id", suitePath), testCase);
		}

		ArrayNode observedSignatures = json.createArrayNode();
		for (JsonNode node : attempts(formalRun, normalizedFormalRunPath))
		{
			ObjectNode outcome = object(node, "formal attempt");
			if (AttemptStatus.parse(text(outcome, "status", normalizedFormalRunPath)) != AttemptStatus.FAILED)
				continue;
			String caseId = text(outcome, "case_id", normalizedFormalRunPath);
			ObjectNode testCase = casesById.get(caseId);
			if (testCase == null)
				throw new IOException("Formal attempt names a testcase absent from its suite: " + caseId);
			appendFailureSignatures(json, observedSignatures, outcome, testCase, normalizedFormalRunPath);
		}
		if (observedSignatures.isEmpty())
			throw new IOException("Failed formal certification retained no failed testcase evidence: " +
				normalizedFormalRunPath);

		ObjectNode[] result = new ObjectNode[1];
		withRunLock(normalizedCandidateStatePath, () ->
		{
			ObjectNode state = object(read(normalizedCandidateStatePath), "candidate state");
			validateCandidateStateEpoch(state, formalRun, normalizedCandidateStatePath, normalizedFormalRunPath);
			ArrayNode formalFailures = formalFailureRecords(state, normalizedCandidateStatePath);
			if (containsFormalFailure(formalFailures, normalizedFormalRunPath, normalizedCandidateStatePath))
			{
				result[0] = state.deepCopy();
				return;
			}

			ArrayNode signatures = array(state.path("failure_signatures"), "failure signatures",
				normalizedCandidateStatePath);
			for (JsonNode node : observedSignatures)
			{
				ObjectNode observedSignature = object(node, "formal failure signature");
				recordFormalFailureSignature(signatures, observedSignature, formalRun, normalizedFormalRunPath,
					normalizedCandidateStatePath);
			ObjectNode obligation = lineageObligation(candidateLineage(state),
				signatureKey(observedSignature, normalizedFormalRunPath), observedSignature, normalizedCandidateStatePath);
				obligation.put("status", "open");
				obligation.put("last_observed_formal_run", normalizedFormalRunPath.toString());
				obligation.put("last_observed_at_epoch_millis", System.currentTimeMillis());
				obligation.remove("covered_by_check");
			}
			ObjectNode formalFailure = formalFailures.addObject();
			formalFailure.put("run", normalizedFormalRunPath.toString());
			formalFailure.put("prompt_sha256", text(formalRun, "prompt_sha256", normalizedFormalRunPath));
			formalFailure.put("assembled_prompt_route_sha256", candidateRevision(formalRun, normalizedFormalRunPath));
			formalFailure.put("observed_at_epoch_millis", System.currentTimeMillis());
			state.put("updated_at_epoch_millis", System.currentTimeMillis());
			writeAtomically(normalizedCandidateStatePath, state);
			result[0] = state.deepCopy();
		});
		withRunLock(normalizedFormalRunPath, () ->
		{
			ObjectNode run = object(read(normalizedFormalRunPath), "formal evaluation run");
			run.put("failure_signature_state", normalizedCandidateStatePath.toString());
			run.put("failure_signature_state_sha256", sha256(normalizedCandidateStatePath));
			write(normalizedFormalRunPath, run);
		});
		return result[0];
	}

	/**
	 * Clears the active failure obligations that the matching promoted candidate's terminal certification satisfied.
	 *
	 * @param formalRunPath the terminal PASS certification record
	 * @param candidateStatePath the promoted candidate's durable state
	 * @return the updated candidate state
	 * @throws IOException if the certification is not a matching terminal PASS
	 */
	public static ObjectNode clearFailureSignaturesAfterCertification(Path formalRunPath, Path candidateStatePath)
		throws IOException
	{
		Path normalizedFormalRunPath = formalRunPath.toAbsolutePath().normalize();
		Path normalizedCandidateStatePath = candidateStatePath.toAbsolutePath().normalize();
		ObjectNode formalRun = object(read(normalizedFormalRunPath), "formal evaluation run");
		ObjectNode decision = object(formalRun.path("decision"), "formal decision");
		if (!text(decision, "overall_decision", normalizedFormalRunPath).equals("PASS"))
			throw new IOException("Only a terminal PASS certification can clear failure signatures: " +
				normalizedFormalRunPath);
		ObjectNode[] result = new ObjectNode[1];
		withRunLock(normalizedCandidateStatePath, () ->
		{
			ObjectNode state = object(read(normalizedCandidateStatePath), "candidate state");
			validateCandidateStateEpoch(state, formalRun, normalizedCandidateStatePath, normalizedFormalRunPath);
			ObjectNode promotion = object(state.path("current_promotion"), "current promotion");
			if (!text(promotion, "candidate_identity_sha256", normalizedCandidateStatePath).equals(
				candidatePromptIdentity(formalRun, normalizedFormalRunPath)))
			{
				throw new IOException("Terminal certification does not match the promoted candidate because its prompt or " +
					"selected rules differ: " + normalizedFormalRunPath);
			}
			if (!text(promotion, "evaluation_epoch_sha256", normalizedCandidateStatePath).equals(
				text(formalRun, "evaluation_epoch_sha256", normalizedFormalRunPath)))
			{
				throw new IOException("Terminal certification does not match the promoted candidate because its evaluation " +
					"epoch differs: " + normalizedFormalRunPath);
			}
			for (JsonNode node : array(state.path("failure_signatures"), "failure signatures",
				normalizedCandidateStatePath))
			{
				ObjectNode signature = object(node, "failure signature");
				ObjectNode clearance = signature.putObject("cleared_by_certification");
				clearance.put("run", normalizedFormalRunPath.toString());
				clearance.put("candidate_identity_sha256", candidatePromptIdentity(formalRun, normalizedFormalRunPath));
				clearance.put("evaluation_epoch_sha256", text(formalRun, "evaluation_epoch_sha256",
					normalizedFormalRunPath));
				clearance.put("cleared_at_epoch_millis", System.currentTimeMillis());
			}
			if (state.has("lineage"))
			{
				for (JsonNode node : array(candidateLineage(state).path("obligations"), "lineage obligations",
					normalizedCandidateStatePath))
				{
					ObjectNode obligation = object(node, "lineage obligation");
					obligation.put("status", "cleared");
					ObjectNode clearance = obligation.putObject("cleared_by_certification");
					clearance.put("run", normalizedFormalRunPath.toString());
					clearance.put("cleared_at_epoch_millis", System.currentTimeMillis());
				}
			}
			state.put("updated_at_epoch_millis", System.currentTimeMillis());
			writeAtomically(normalizedCandidateStatePath, state);
			result[0] = state.deepCopy();
		});
		return result[0];
	}

	/**
	 * Counts comparable attempts in one check that did not reproduce a retained failure signature.
	 *
	 * @param check the completed regression check
	 * @param retained the candidate ledger entry
	 * @param statePath the candidate-state location used in diagnostics
	 * @return the baseline case attempt plus any retained stress attempts for that case
	 * @throws IOException if the retained signature is malformed
	 */
	private static int comparableExposureCount(ObjectNode check, ObjectNode retained, Path statePath) throws IOException
	{
		String caseId = text(object(retained.path("latest_case"), "latest failure case"), "case_id", statePath);
		int result = 1;
		if (check.path("sweep_trials").isArray())
			result = array(check.path("sweep_trials"), "pre-certification sweep trials", statePath).size();
		if (!check.path("active_failure_stress").isObject())
			return result;
		ObjectNode stress = object(check.path("active_failure_stress"), "active failure stress");
		for (JsonNode node : array(stress.path("case_ids"), "active failure stress cases", statePath))
			if (node.asString().equals(caseId))
				return result + stress.path("completed_repetitions").asInt();
		return result;
	}

	/**
	 * Returns the durable list of formal runs that have already supplied failure obligations.
	 *
	 * @param state the candidate ledger
	 * @param statePath the candidate-state location
	 * @return the mutable formal-failure record list
	 * @throws IOException if the retained list is malformed
	 */
	private static ArrayNode formalFailureRecords(ObjectNode state, Path statePath) throws IOException
	{
		JsonNode existing = state.path("formal_failures");
		if (existing.isMissingNode())
			return state.putArray("formal_failures");
		return array(existing, "formal failures", statePath);
	}

	/**
	 * Determines whether one failed formal run already updated the candidate ledger.
	 *
	 * @param formalFailures the retained formal-failure records
	 * @param formalRunPath the formal run to look up
	 * @param statePath the candidate-state location
	 * @return whether that run is already recorded
	 * @throws IOException if a retained record is malformed
	 */
	private static boolean containsFormalFailure(ArrayNode formalFailures, Path formalRunPath, Path statePath)
		throws IOException
	{
		for (JsonNode node : formalFailures)
			if (text(object(node, "formal failure"), "run", statePath).equals(formalRunPath.toString()))
				return true;
		return false;
	}

	/**
	 * Adds one normalized formal failure to the durable ledger without clearing another failure obligation.
	 *
	 * @param signatures the ledger's failure signatures
	 * @param observedSignature the newly observed failed assertion
	 * @param formalRun the run that retained the assertion evidence
	 * @param formalRunPath the run location
	 * @param statePath the candidate-state location
	 * @throws IOException if the existing signature is malformed
	 */
	private static void recordFormalFailureSignature(ArrayNode signatures, ObjectNode observedSignature,
		ObjectNode formalRun, Path formalRunPath, Path statePath) throws IOException
	{
		String key = signatureKey(observedSignature, formalRunPath);
		ObjectNode retained = signature(signatures, key, statePath);
		String candidateHash = candidateRevision(formalRun, formalRunPath);
		if (retained == null)
		{
			retained = signatures.addObject();
			retained.put("key", key);
			retained.put("first_observed_candidate_sha256", candidateHash);
			retained.put("first_observed_formal_run", formalRunPath.toString());
			retained.put("first_observed_at_epoch_millis", System.currentTimeMillis());
			retained.put("observations", 0);
		}
		retained.put("obligation_family", obligationFamilyKey(observedSignature, formalRunPath));
		if (retained.path("coverage_route_sha256").asString().equals(candidateHash))
			retained.put("coverage_observations", retained.path("coverage_observations").asInt() + 1);
		else
		{
			retained.put("coverage_route_sha256", candidateHash);
			retained.put("coverage_observations", 1);
			retained.put("not_observed_comparable_exposures", 0);
		}
		retained.set("latest_case", observedSignature.deepCopy());
		retained.put("last_observed_candidate_sha256", candidateHash);
		retained.put("last_observed_formal_run", formalRunPath.toString());
		retained.put("last_observed_at_epoch_millis", System.currentTimeMillis());
		retained.put("observations", retained.path("observations").asInt() + 1);
		retained.put("consecutive_observations", retained.path("consecutive_observations").asInt(0) + 1);
		retained.put("not_observed_comparable_exposures", 0);
		retained.remove("cleared_by_certification");
		retained.remove("removed_by_candidate_sha256");
		retained.remove("removed_by_check");
		retained.remove("removed_at_epoch_millis");
	}

	/**
	 * Verifies that an existing promotion state can compare the prepared run before it starts model work.
	 *
	 * @param candidateStatePath the optional durable candidate state
	 * @param runPath the prepared run whose identity will govern the check
	 * @throws IOException if the existing state belongs to a different suite or execution epoch
	 */
	public static void validateCandidateStateBeforeCheck(Path candidateStatePath, Path runPath) throws IOException
	{
		Path normalizedCandidateStatePath = candidateStatePath.toAbsolutePath().normalize();
		if (!Files.exists(normalizedCandidateStatePath))
			return;

		ObjectNode state = object(read(normalizedCandidateStatePath), "candidate state");
		ObjectNode run = object(read(runPath.toAbsolutePath().normalize()), "prepared candidate run");
		if (state.hasNonNull("evaluation_epoch_sha256"))
		{
			String currentEpoch = text(state, "evaluation_epoch_sha256", normalizedCandidateStatePath);
			String proposedEpoch = text(run, "evaluation_epoch_sha256", runPath);
			if (!currentEpoch.equals(proposedEpoch) &&
				!changesOnlyVisibleSuite(state, normalizedCandidateStatePath, run) &&
				!hasEvaluationEpochSupersession(state, normalizedCandidateStatePath, currentEpoch, proposedEpoch))
			{
					throw new IOException("Candidate's execution epoch is frozen while its prompt remains unfinished. " +
						"Complete the workflow-owned revalidation or evaluator-defect recovery before changing " +
						"execution conditions: " + normalizedCandidateStatePath);
			}
		}
		for (String key : activeReviewSignatureKeys(state, normalizedCandidateStatePath))
			if (!CandidateReviews.hasCompletedRecurrence(state, normalizedCandidateStatePath, key))
				throw new IOException("Candidate has a repeated unresolved failure that requires a retained " +
					"independent workflow-owned review before another check: " + key + " for " +
					normalizedCandidateStatePath);
		String candidateIdentity = candidateRevision(run, runPath);
		List<String> obligationKeys = CandidateReviews.revisionObligationKeys(state, normalizedCandidateStatePath);
		boolean revisionReviewRequired = !obligationKeys.isEmpty() && CandidateReviews.requiresRevision(state,
			normalizedCandidateStatePath, candidateIdentity, obligationKeys);
		if (revisionReviewRequired)
		{
			throw new IOException("Candidate changed while retained obligations remain. Complete the workflow-owned " +
				"independent revision review before another check: " + normalizedCandidateStatePath + " for candidate " +
				candidateIdentity);
		}
	}

	/**
	 * Determines whether a new check changes only the scenario suite that supplies its cases.
	 *
	 * @param state the current candidate state
	 * @param statePath the candidate-state location
	 * @param proposedRun the prepared replacement check
	 * @return {@code true} if the suite is the only changed epoch input; {@code false} otherwise
	 * @throws IOException if an available retained prior epoch cannot be read
	 */
	private static boolean changesOnlyVisibleSuite(ObjectNode state, Path statePath, ObjectNode proposedRun)
		throws IOException
	{
		Path previousRunPath;
		if (state.path("current_promotion").isObject())
		{
			ObjectNode promotion = object(state.path("current_promotion"), "current promotion");
			previousRunPath = path(text(promotion, "check_run", statePath));
		}
		else if (state.hasNonNull("latest_check"))
			previousRunPath = path(text(state, "latest_check", statePath));
		else
			return false;

		if (!Files.isRegularFile(previousRunPath, NOFOLLOW_LINKS))
			return false;

		ObjectNode previousRun = object(read(previousRunPath), "previous candidate check");
		ObjectNode previousEpoch = object(previousRun.path("evaluation_epoch"), "previous evaluation epoch").deepCopy();
		ObjectNode proposedEpoch = object(proposedRun.path("evaluation_epoch"), "proposed evaluation epoch").deepCopy();
		previousEpoch.remove("suite_sha256");
		proposedEpoch.remove("suite_sha256");
		return previousEpoch.equals(proposedEpoch);
	}

	/**
	 * Removes evidence that an earlier incomplete check incorrectly placed in a candidate state.
	 *
	 * <p>
	 * An incomplete check establishes an infrastructure failure, not prompt compliance. Retaining its signatures would
	 * make a later candidate repeat stress work for an unavailable executor or validator rather than for a failed prompt
	 * obligation. The candidate state therefore retains only checks that can supply prompt-failure evidence.
	 *
	 * @param json the mapper that creates and updates candidate-state records
	 * @param candidateStatePath the durable candidate state
	 * @throws IOException if the candidate state cannot be repaired atomically
	 */
	public static void discardIncompleteCandidateCheckEvidence(JsonMapper json, Path candidateStatePath)
		throws IOException
	{
		Path normalizedCandidateStatePath = candidateStatePath.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalizedCandidateStatePath, NOFOLLOW_LINKS))
			return;

		RecordStore.update(json, normalizedCandidateStatePath, state ->
		{
			Set<String> incompleteChecks = incompleteCandidateChecks(state, normalizedCandidateStatePath);
			if (incompleteChecks.isEmpty())
				return false;

			ArrayNode signatures = array(state.path("failure_signatures"), "failure signatures",
				normalizedCandidateStatePath);
			validateDiscardableIncompleteSignatures(signatures, incompleteChecks, normalizedCandidateStatePath);
			Set<String> discardedKeys = CandidatePolicy.discardIncompleteSignatures(signatures,
				incompleteChecks);
			ArrayNode obligations = discardableLineageObligations(state, normalizedCandidateStatePath);
			if (obligations != null)
				CandidatePolicy.discardIncompleteObligations(obligations, discardedKeys, incompleteChecks);
			CandidatePolicy.discardIncompleteCheckRecords(state, incompleteChecks);
			state.put("updated_at_epoch_millis", System.currentTimeMillis());
			return true;
		});
	}

	/**
	 * Returns the paths of checks that did not establish candidate-state evidence.
	 *
	 * @param state the candidate state
	 * @param statePath the candidate-state path
	 * @return the incomplete check paths
	 * @throws IOException if the recorded checks are malformed
	 */
	private static Set<String> incompleteCandidateChecks(ObjectNode state, Path statePath) throws IOException
	{
		if (!state.path("checks").isArray())
			return Set.of();
		ArrayNode checks = array(state.path("checks"), "candidate checks", statePath);
		for (JsonNode node : checks)
		{
			ObjectNode check = object(node, "candidate check");
			text(check, "run", statePath);
			text(check, "status", statePath);
		}
		return CandidatePolicy.incompleteCheckRuns(checks);
	}

	/**
	 * Returns validated lineage obligations that may have been opened by an incomplete check.
	 *
	 * @param state the candidate state
	 * @param statePath the candidate-state path
	 * @return the obligations, or {@code null} when this state has no lineage obligations
	 * @throws IOException if a present obligation is malformed
	 */
	private static ArrayNode discardableLineageObligations(ObjectNode state, Path statePath) throws IOException
	{
		if (!state.path("lineage").isObject())
			return null;
		JsonNode obligations = state.path("lineage").path("obligations");
		if (!obligations.isArray())
			return null;
		ArrayNode entries = (ArrayNode) obligations;
		for (JsonNode node : entries)
		{
			ObjectNode obligation = object(node, "lineage obligation");
			text(obligation, "key", statePath);
			text(obligation, "last_observed_check", statePath);
		}
		return entries;
	}

	/**
	 * Rejects a repair that would erase a signature with evidence from an earlier completed check.
	 *
	 * @param signatures the mutable candidate signatures
	 * @param incompleteChecks the incomplete source records
	 * @param statePath the candidate-state path
	 * @throws IOException if an incomplete check overwrote a previously observed signature
	 */
	private static void validateDiscardableIncompleteSignatures(ArrayNode signatures, Set<String> incompleteChecks,
		Path statePath) throws IOException
	{
		for (JsonNode node : signatures)
		{
			ObjectNode signature = object(node, "failure signature");
			String lastObservedCheck = signature.path("last_observed_check").asString();
			if (!incompleteChecks.contains(lastObservedCheck))
				continue;
			if (CandidatePolicy.discardableIncompleteSignature(signature, incompleteChecks))
			{
				continue;
			}
			throw new IOException("Candidate state mixes an incomplete check with earlier failure evidence and cannot " +
				"discard it safely: " + text(signature, "key", statePath));
		}
	}

	/**
	 * Returns active failure signatures that require one independent review.
	 *
	 * <p>
	 * A review is needed when one signature recurs in consecutive checks or when two current signatures declare the
	 * same obligation family. In the latter case, the review includes every active signature in that family, so a repair
	 * cannot address one testcase while silently breaking or overlooking another testcase that exercises the same
	 * requirement.
	 *
	 * @param state the candidate state
	 * @param statePath the state source used in diagnostics
	 * @return the sorted signature keys requiring review
	 * @throws IOException if the retained signatures are malformed
	 */
	public static List<String> activeReviewSignatureKeys(ObjectNode state, Path statePath) throws IOException
	{
		Map<String, List<ObjectNode>> activeByFamily = new HashMap<>();
		Set<String> reviewedFamilies = new HashSet<>();
		for (JsonNode node : array(state.path("failure_signatures"), "failure signatures", statePath))
		{
			ObjectNode signature = object(node, "failure signature");
			if (signature.has("cleared_by_certification"))
				continue;
			String family = obligationFamilyKey(signature, statePath);
			activeByFamily.computeIfAbsent(family, ignored -> new ArrayList<>()).add(signature);
			if (signature.path("consecutive_observations").asInt() >= 2)
				reviewedFamilies.add(family);
		}
		for (Map.Entry<String, List<ObjectNode>> entry : activeByFamily.entrySet())
			if (entry.getValue().size() >= 2)
				reviewedFamilies.add(entry.getKey());
		List<String> result = new ArrayList<>();
		for (String family : reviewedFamilies)
			for (ObjectNode signature : activeByFamily.get(family))
				result.add(text(signature, "key", statePath));
		result.sort(String::compareTo);
		return result;
	}

	/**
	 * Returns the declared requirement shared by one or more testcase assertions.
	 *
	 * <p>
	 * A testcase and assertion identify where a failure appeared. They do not necessarily identify the requirement that
	 * the prompt revision must preserve: distinct concrete scenarios can independently exercise the same requirement.
	 * A suite author declares that shared requirement on the assertion; the evaluator copies it to the failure signature.
	 * Retained records without the field remain isolated by their exact signature, which avoids joining evidence whose
	 * relationship was never declared.
	 *
	 * @param signature the retained or observed failed assertion
	 * @param source the signature source used in diagnostics
	 * @return the declared family, or the exact signature key when no family was declared
	 * @throws IOException if the signature is incomplete
	 */
	private static String obligationFamilyKey(ObjectNode signature, Path source) throws IOException
	{
		JsonNode declared = signature.path("obligation_family");
		if (declared.isString() && !declared.asString().isBlank())
			return declared.asString();
		if (!signature.has("case_id"))
			return signatureKey(object(signature.path("latest_case"), "latest failure case"), source);
		return signatureKey(signature, source);
	}

	/**
	 * Returns an array field, creating it only when this state version has not recorded it.
	 *
	 * @param state the owning candidate state
	 * @param field the persisted field name
	 * @param name the diagnostic name
	 * @param source the state source
	 * @return the mutable array
	 * @throws IOException if an existing field is not an array
	 */
	private static ArrayNode array(ObjectNode state, String field, String name, Path source) throws IOException
	{
		JsonNode value = state.path(field);
		if (value.isMissingNode())
			return state.putArray(field);
		if (!value.isArray())
			throw new IOException("Expected " + name + " array: " + source);
		return (ArrayNode) value;
	}

	/**
	 * Returns an array supplied by an evidence record.
	 *
	 * @param value the candidate array value
	 * @param name the diagnostic name
	 * @param source the evidence source
	 * @return the validated array
	 * @throws IOException if the value is not an array
	 */
	private static ArrayNode array(JsonNode value, String name, Path source) throws IOException
	{
		return PromptEvaluation.array(value, name, source);
	}

	/**
	 * Returns an object used by the candidate-state contract.
	 *
	 * @param value the value to validate
	 * @param name the diagnostic name
	 * @return the object value
	 * @throws IOException if the value is not an object
	 */
	private static ObjectNode object(JsonNode value, String name) throws IOException
	{
		if (!value.isObject())
			throw new IOException("Expected " + name + " object");
		return (ObjectNode) value;
	}

	/**
	 * Returns a required non-blank text field.
	 *
	 * @param object the containing object
	 * @param field the field name
	 * @param source the diagnostic source
	 * @return the text value
	 * @throws IOException if the field is absent or blank
	 */
	private static String text(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Missing text " + field + ": " + source);
		return value.asString();
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
			throw new AssertionError("PromptEvaluation did not configure candidate-state access");
		return result;
	}

	/**
	 * Delegates retained-run input validation.
	 *
	 * @param run the retained run
	 * @param runPath the run location
	 * @throws IOException if the run inputs are malformed
	 */
	private static void validateRunInputs(ObjectNode run, Path runPath) throws IOException
	{
		access().validateRunInputs(run, runPath);
	}

	/**
	 * Delegates retained-evidence validation.
	 *
	 * @param run the retained run
	 * @param runPath the run location
	 * @throws IOException if retained evidence is invalid
	 */
	private static void validateEvidenceIntegrity(ObjectNode run, Path runPath) throws IOException
	{
		access().validateEvidenceIntegrity(run, runPath);
	}

	/**
	 * Delegates one locked record mutation.
	 *
	 * @param recordPath the record to lock
	 * @param operation the mutation to run
	 * @throws IOException if the record cannot be locked or mutated
	 */
	private static void withRunLock(Path recordPath, RecordStore.IoOperation operation) throws IOException
	{
		access().withLock(recordPath, operation);
	}

	/**
	 * Delegates atomic record publication.
	 *
	 * @param destination the record destination
	 * @param value the replacement record
	 * @throws IOException if publication fails
	 */
	private static void writeAtomically(Path destination, ObjectNode value) throws IOException
	{
		access().writeAtomically(destination, value);
	}

	/**
	 * Collects the signatures established by one retained outcome.
	 *
	 * @param json the mapper that classifies retained failure evidence
	 * @param signatures the mutable signatures
	 * @param outcome the retained outcome
	 * @param testCase the testcase declaration
	 * @param runPath the retained run location
	 * @throws IOException if retained evidence is invalid
	 */
	private static void appendFailureSignatures(JsonMapper json, ArrayNode signatures, ObjectNode outcome,
		ObjectNode testCase, Path runPath) throws IOException
	{
		FailureClassifier.appendFailureSignatures(json, signatures, outcome, testCase, runPath);
	}

	/**
	 * Delegates candidate identity derivation.
	 *
	 * @param run the retained run
	 * @param runPath the run location
	 * @return the candidate identity
	 * @throws IOException if the identity is unavailable
	 */
	private static String candidatePromptIdentity(ObjectNode run, Path runPath) throws IOException
	{
		return access().candidateIdentity(run, runPath);
	}

	/**
	 * Delegates candidate revision derivation.
	 *
	 * @param run the retained run
	 * @param runPath the run location
	 * @return the candidate revision
	 * @throws IOException if the revision is unavailable
	 */
	private static String candidateRevision(ObjectNode run, Path runPath) throws IOException
	{
		return access().candidateRevision(run, runPath);
	}

	/**
	 * Delegates completed-regression-check detection.
	 *
	 * @param run the retained run
	 * @return whether the run completed a passed regression check
	 */
	private static boolean hasCompletedRegressionCheck(ObjectNode run)
	{
		return access().hasCompletedRegressionCheck(run);
	}

	/**
	 * Delegates execution-epoch supersession detection.
	 *
	 * @param state the candidate state
	 * @param statePath the candidate-state location
	 * @param currentEpoch the current epoch
	 * @param proposedEpoch the proposed replacement epoch
	 * @return whether the state authorizes the replacement
	 * @throws IOException if retained supersession evidence is malformed
	 */
	private static boolean hasEvaluationEpochSupersession(ObjectNode state, Path statePath, String currentEpoch,
		String proposedEpoch) throws IOException
	{
		return access().hasEpochSupersession(state, statePath, currentEpoch, proposedEpoch);
	}
}

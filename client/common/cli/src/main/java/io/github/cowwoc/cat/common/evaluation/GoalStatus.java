package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.cat.common.PromptEvaluation.object;
import static io.github.cowwoc.cat.common.PromptEvaluation.text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Projects retained candidate state into one read-only actionable workflow status.
 */
public final class GoalStatus
{
	/**
	 * Supplies coordinator-owned candidate-record interpretation to goal status.
	 */
	public interface Access
	{
		/**
		 * Reads one retained record as an object.
		 *
		 * @param path the retained record path
		 * @param description the record's diagnostic description
		 * @return the parsed retained record
		 * @throws IOException if the retained record cannot be read as an object
		 */
		ObjectNode read(Path path, String description) throws IOException;

		/**
		 * Validates the immutable inputs recorded by a completed evaluation.
		 *
		 * @param run the completed evaluation record
		 * @param runPath the retained evaluation record path
		 * @throws IOException if an immutable input is malformed or no longer matches its retained evidence
		 */
		void validateRunInputs(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Indicates whether a retained evaluation completed a regression check.
		 *
		 * @param run the retained evaluation record
		 * @return {@code true} if the record completed a regression check
		 */
		boolean hasCompletedRegressionCheck(ObjectNode run);

		/**
		 * Returns the SHA-256 fingerprint of one current source file.
		 *
		 * @param artifact the current source file
		 * @return the source file fingerprint
		 * @throws IOException if the artifact cannot be fingerprinted
		 */
		String sha256(Path artifact) throws IOException;

		/**
		 * Returns the stable identity of a current scenario suite.
		 *
		 * @param suitePath the current scenario-suite root
		 * @return the current suite fingerprint
		 * @throws IOException if the suite cannot be fingerprinted
		 */
		String suiteFingerprint(Path suitePath) throws IOException;
	}

	/**
	 * Identifies the candidate record that determines the next workflow stage.
	 *
	 * @param kind the reader-visible checkpoint kind
	 * @param record the retained record
	 * @param completedAtEpochMillis the time the checkpoint completed
	 * @param nextStage the required next workflow stage
	 */
	public record Checkpoint(String kind, Path record, long completedAtEpochMillis, String nextStage)
	{
	}

	/**
	 * Identifies the retained record that currently determines a candidate's next workflow stage.
	 */
	private enum CheckpointKind
	{
		FORMAL_FAILURE("formal-failure", "classify-retained-formal-failure"),
		RECURRENCE_REVIEW("recurrence-review", "apply-recurrence-review"),
		REVISION_REVIEW("revision-review", "run-regression-check"),
		PROMOTION("promotion", "certify-promoted"),
		REGRESSION_CHECK("regression-check", "repair-or-promote-regression-check");

		private final String externalName;
		private final String nextStage;

		/**
		 * Creates one candidate checkpoint category.
		 *
		 * @param externalName the reader-visible checkpoint name
		 * @param nextStage the required workflow stage
		 */
		CheckpointKind(String externalName, String nextStage)
		{
			this.externalName = externalName;
			this.nextStage = nextStage;
		}
	}

	/**
	 * Holds one candidate-state transition before checking whether it remains current.
	 *
	 * @param kind the transition category
	 * @param record the retained transition record
	 * @param completedAtEpochMillis the transition completion time
	 */
	private record CheckpointReference(CheckpointKind kind, Path record, long completedAtEpochMillis)
	{
	}

	private static volatile Access access;

	/**
	 * Registers the coordinator operations used by goal-status projection.
	 *
	 * @param access the coordinator operations
	 */
	public static void configure(Access access)
	{
		GoalStatus.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Prevents construction.
	 */
	private GoalStatus()
	{
	}

	/**
	 * Projects the current candidate state without mutating it.
	 *
	 * @param json the mapper that creates the status projection
	 * @param candidateStatePath the retained candidate state
	 * @param goalPath the optional goal document
	 * @return the actionable status projection
	 * @throws IOException if retained state cannot be read
	 */
	public static ObjectNode status(JsonMapper json, Path candidateStatePath, Path goalPath) throws IOException
	{
		return status(json, candidateStatePath, goalPath, access());
	}

	/**
	 * Projects candidate status using explicitly supplied retained-evidence access.
	 *
	 * @param json the mapper that creates status records
	 * @param candidateStatePath the retained candidate state
	 * @param goalPath the optional goal document
	 * @param access the retained-evidence access
	 * @return the actionable status projection
	 * @throws IOException if retained state cannot be read
	 */
	static ObjectNode status(JsonMapper json, Path candidateStatePath, Path goalPath, Access access) throws IOException
	{
		candidateStatePath = candidateStatePath.toAbsolutePath().normalize();
		ObjectNode controllerStatus = CandidateController.activeStatus(candidateStatePath);
		if (controllerStatus != null)
			return activeController(json, candidateStatePath, controllerStatus);
		ObjectNode state = access.read(candidateStatePath, "candidate state");
		String staleGateReason = staleGateReason(state, candidateStatePath, access);
		if (staleGateReason != null)
			return staleGate(json, candidateStatePath, staleGateReason);
		Checkpoint checkpoint = currentCheckpoint(state, candidateStatePath, access);
		ObjectNode result = json.createObjectNode();
		result.put("candidate_state", candidateStatePath.toString());
		ArrayNode unavailableHistory = unavailableHistory(json, state, candidateStatePath, access);
		if (!unavailableHistory.isEmpty())
			result.set("unavailable_historical_records", unavailableHistory);
		if (checkpoint == null)
			result.put("next_stage", "run-regression-check");
		else
		{
			result.put("checkpoint_kind", checkpoint.kind());
			result.put("current_record", checkpoint.record().toString());
			result.put("completed_at_epoch_millis", checkpoint.completedAtEpochMillis());
			result.put("next_stage", checkpoint.nextStage());
			if (goalPath != null)
				validateGoalCheckpoint(goalPath, checkpoint);
		}
		return result;
	}

	/**
	 * Projects an active controller-owned candidate workflow.
	 *
	 * @param json the mapper that creates the status projection
	 * @param candidateStatePath the retained candidate state
	 * @param controllerStatus the active controller record
	 * @return the active status projection
	 * @throws IOException if the controller record is malformed
	 */
	private static ObjectNode activeController(JsonMapper json, Path candidateStatePath, ObjectNode controllerStatus)
		throws IOException
	{
		ObjectNode result = json.createObjectNode();
		result.put("candidate_state", candidateStatePath.toString());
		result.put("active_controller", true);
		result.put("status_revision", controllerStatus.path("revision").asLong());
		result.put("current_phase", text(controllerStatus, "current_phase",
			CandidateController.statusPath(candidateStatePath)));
		result.put("next_stage", "wait_for_active_controller");
		result.set("active_workers", controllerStatus.path("active_workers").deepCopy());
		result.set("evaluation_identity", controllerStatus.path("evaluation_identity").deepCopy());
		return result;
	}

	/**
	 * Projects a stale gate without starting recovery.
	 *
	 * @param json the mapper that creates the status projection
	 * @param candidateStatePath the retained candidate state
	 * @param reason the stale-gate explanation
	 * @return the stale status projection
	 */
	private static ObjectNode staleGate(JsonMapper json, Path candidateStatePath, String reason)
	{
		ObjectNode result = json.createObjectNode();
		result.put("candidate_state", candidateStatePath.toString());
		result.put("gate_status", "stale");
		result.put("stale_reason", reason);
		result.put("next_stage", "run-regression-check");
		return result;
	}

	/**
	 * Returns the configured coordinator operations.
	 *
	 * @return the coordinator operations
	 * @throws AssertionError if the coordinator did not configure this status projection
	 */
	private static Access access()
	{
		Access result = access;
		if (result == null)
			throw new AssertionError("PromptEvaluation did not configure goal-status access");
		return result;
	}

	/**
	 * Returns why the current retained gate no longer authorizes a workflow step.
	 *
	 * @param state the candidate state to interpret
	 * @param statePath the candidate-state location used in diagnostics
	 * @param access the retained-evidence access
	 * @return the stale-gate reason, or {@code null} when the gate is current
	 * @throws IOException if retained evidence cannot be read
	 */
	private static String staleGateReason(ObjectNode state, Path statePath, Access access) throws IOException
	{
		Path checkPath;
		if (state.path("current_promotion").isObject())
			checkPath = Path.of(text(object(state.path("current_promotion"), "current promotion"), "check_run", statePath));
		else if (state.hasNonNull("latest_check"))
			checkPath = Path.of(text(state, "latest_check", statePath));
		else
			return null;
		if (!Files.isRegularFile(checkPath, NOFOLLOW_LINKS))
			return "Regression-check receipt is unavailable: " + checkPath;
		ObjectNode checkRun = access.read(checkPath, "regression-check receipt");
		if (!access.hasCompletedRegressionCheck(checkRun) ||
			!text(object(checkRun.path("regression_check"), "regression check"), "status", checkPath).equals("passed"))
		{
			return null;
		}
		try
		{
			access.validateRunInputs(checkRun, checkPath);
		}
		catch (IOException e)
		{
			return e.getMessage();
		}
		return staleSourceReason(checkRun, checkPath, access);
	}

	/**
	 * Returns the retained checkpoint that selects the next workflow stage.
	 *
	 * @param state the candidate state to interpret
	 * @param statePath the candidate-state location used in diagnostics
	 * @param access the retained-evidence access
	 * @return the current checkpoint, or {@code null} when none exists
	 * @throws IOException if retained checkpoint evidence is malformed
	 */
	private static Checkpoint currentCheckpoint(ObjectNode state, Path statePath, Access access) throws IOException
	{
		List<CheckpointReference> checkpoints = checkpointReferences(state, statePath, access);
		CheckpointReference current = currentReference(checkpoints);
		if (current == null)
			return null;
		if (!Files.isRegularFile(current.record(), NOFOLLOW_LINKS))
		{
			throw new IOException("Candidate state names an unavailable " + current.kind().externalName + " record: " +
				current.record() + " in " + statePath);
		}
		return new Checkpoint(current.kind().externalName, current.record(), current.completedAtEpochMillis(),
			current.kind().nextStage);
	}

	/**
	 * Returns historical checkpoint records that are no longer available.
	 *
	 * @param json the mapper that creates the unavailable-record list
	 * @param state the candidate state to interpret
	 * @param statePath the candidate-state location used in diagnostics
	 * @param access the retained-evidence access
	 * @return unavailable historical records
	 * @throws IOException if retained checkpoint evidence is malformed
	 */
	private static ArrayNode unavailableHistory(JsonMapper json, ObjectNode state, Path statePath, Access access)
		throws IOException
	{
		List<CheckpointReference> checkpoints = checkpointReferences(state, statePath, access);
		CheckpointReference current = currentReference(checkpoints);
		ArrayNode result = json.createArrayNode();
		for (CheckpointReference checkpoint : checkpoints)
		{
			if (!checkpoint.equals(current) && !Files.isRegularFile(checkpoint.record(), NOFOLLOW_LINKS))
				result.addObject().put("kind", checkpoint.kind().externalName).put("record", checkpoint.record().toString());
		}
		return result;
	}

	/**
	 * Rejects a goal document that duplicates a conflicting current checkpoint.
	 *
	 * @param goalPath the human-authored goal document
	 * @param checkpoint the retained checkpoint that selects the current stage
	 * @throws IOException if the document contains conflicting checkpoint prose
	 */
	private static void validateGoalCheckpoint(Path goalPath, Checkpoint checkpoint) throws IOException
	{
		boolean inCheckpoint = false;
		for (String line : Files.readAllLines(goalPath, StandardCharsets.UTF_8))
		{
			if (line.equals("#### Current tuning checkpoint"))
			{
				inCheckpoint = true;
				continue;
			}
			if (inCheckpoint && line.startsWith("#"))
				break;
			if (inCheckpoint && (line.contains("Current retained run:") || line.contains("Current record:") ||
				line.contains("Next required stage:")))
			{
				throw new IOException("Goal checkpoint prose declares '" + line.strip() +
					"', but candidate state selects " + checkpoint.record() + " and " + checkpoint.nextStage() +
					". Remove the handwritten checkpoint claim and use goal-status: " + goalPath);
			}
		}
	}

	/**
	 * Returns why a completed check's current inputs differ from its retained evidence.
	 *
	 * @param checkRun the completed regression-check receipt
	 * @param checkPath the receipt path
	 * @param access the retained-evidence access
	 * @return the changed-input reason, or {@code null} when every current input matches
	 * @throws IOException if a source identity cannot be read
	 */
	private static String staleSourceReason(ObjectNode checkRun, Path checkPath, Access access) throws IOException
	{
		if (checkRun.hasNonNull("suite_source"))
		{
			Path suite = Path.of(text(checkRun, "suite_source", checkPath));
			if (!Files.isDirectory(suite, NOFOLLOW_LINKS))
				return "Suite is unavailable after the regression check: " + suite;
			if (!access.suiteFingerprint(suite).equals(text(checkRun, "suite_sha256", checkPath)))
				return "Suite changed after the regression check: " + suite;
		}
		for (String[] input : List.of(new String[]{"prompt_source", "prompt_sha256", "Prompt artifact"},
			new String[]{"execution_context_source", "execution_context_sha256", "Execution context"}))
		{
			if (!checkRun.hasNonNull(input[0]))
				continue;
			Path source = Path.of(text(checkRun, input[0], checkPath));
			if (!Files.isRegularFile(source, NOFOLLOW_LINKS))
				return input[2] + " is unavailable after the regression check: " + source;
			if (!access.sha256(source).equals(text(checkRun, input[1], checkPath)))
				return input[2] + " changed after the regression check: " + source;
		}
		return null;
	}

	/**
	 * Collects every candidate-state transition that can determine the next workflow stage.
	 *
	 * @param state the candidate state
	 * @param statePath the candidate-state path
	 * @param access the retained-evidence access
	 * @return the retained transition references
	 * @throws IOException if a retained transition is malformed
	 */
	private static List<CheckpointReference> checkpointReferences(ObjectNode state, Path statePath, Access access)
		throws IOException
	{
		List<CheckpointReference> result = new ArrayList<>();
		addReferences(result, state.path("formal_failures"), "formal failure", "run", "observed_at_epoch_millis",
			CheckpointKind.FORMAL_FAILURE, statePath);
		addCompletedReferences(result, state.path("recurrence_reviews"), "recurrence review",
			CheckpointKind.RECURRENCE_REVIEW, statePath);
		addCompletedReferences(result, state.path("revision_reviews"), "revision review", CheckpointKind.REVISION_REVIEW,
			statePath);
		addReferences(result, state.path("promotions"), "promotion", "check_run", "promoted_at_epoch_millis",
			CheckpointKind.PROMOTION, statePath);
		if (state.has("latest_check"))
		{
			Path check = Path.of(text(state, "latest_check", statePath));
			ObjectNode checkRun = access.read(check, "regression-check run");
			ObjectNode regression = object(checkRun.path("regression_check"), "regression check");
			result.add(new CheckpointReference(CheckpointKind.REGRESSION_CHECK, check,
				regression.path("completed_at_epoch_millis").asLong()));
		}
		return List.copyOf(result);
	}

	/**
	 * Adds every retained transition reference of one category.
	 *
	 * @param target the transition references
	 * @param records the retained category records
	 * @param description the diagnostic record description
	 * @param recordField the record-path field
	 * @param timeField the completion-time field
	 * @param kind the transition category
	 * @param statePath the candidate-state path
	 * @throws IOException if a retained reference is malformed
	 */
	private static void addReferences(List<CheckpointReference> target, tools.jackson.databind.JsonNode records,
		String description, String recordField, String timeField, CheckpointKind kind, Path statePath) throws IOException
	{
		if (!records.isArray())
			return;
		for (tools.jackson.databind.JsonNode node : records)
		{
			ObjectNode record = object(node, description);
			target.add(new CheckpointReference(kind, Path.of(text(record, recordField, statePath)),
				record.path(timeField).asLong()));
		}
	}

	/**
	 * Adds completed review references from one retained review category.
	 *
	 * @param target the transition references
	 * @param records the retained review records
	 * @param description the diagnostic record description
	 * @param kind the transition category
	 * @param statePath the candidate-state path
	 * @throws IOException if a retained reference is malformed
	 */
	private static void addCompletedReferences(List<CheckpointReference> target, tools.jackson.databind.JsonNode records,
		String description, CheckpointKind kind, Path statePath) throws IOException
	{
		if (!records.isArray())
			return;
		for (tools.jackson.databind.JsonNode node : records)
		{
			ObjectNode record = object(node, description);
			if (record.path("status").asString().equals("completed"))
			{
				target.add(new CheckpointReference(kind, Path.of(text(record, "result", statePath)),
					record.path("completed_at_epoch_millis").asLong()));
			}
		}
	}

	/**
	 * Selects the newest retained transition.
	 *
	 * @param checkpoints the retained candidate transitions
	 * @return the transition selecting the next stage, or {@code null} when no transition exists
	 */
	private static CheckpointReference currentReference(List<CheckpointReference> checkpoints)
	{
		return checkpoints.stream().max(Comparator.comparingLong(CheckpointReference::completedAtEpochMillis).
			thenComparing(checkpoint -> checkpoint.kind().ordinal())).orElse(null);
	}
}

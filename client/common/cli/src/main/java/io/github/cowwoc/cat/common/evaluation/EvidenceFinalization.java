package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Path;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finalizes the retained evidence for one terminal prompt evaluation.
 */
public final class EvidenceFinalization
{
	/**
	 * Performs evaluator operations that finalization orders but does not own.
	 */
	public interface Operations
	{
		/**
		 * Reads the terminal status that authorizes finalization.
		 *
		 * @param runPath the persistent evaluation record
		 * @param workspaceRoot the evaluator-owned worker workspace
		 * @return the current status projection
		 * @throws IOException if the record or workspace cannot be read
		 */
		ObjectNode status(Path runPath, Path workspaceRoot) throws IOException;

		/**
		 * Selects the durable location for retained evidence when the caller did not supply one.
		 *
		 * @param workRoot the CAT work root
		 * @param runPath the terminal evaluation record
		 * @return the initially absent evidence destination
		 * @throws IOException if the destination cannot be derived
		 */
		Path defaultDestination(Path workRoot, Path runPath) throws IOException;

		/**
		 * Moves terminal evidence to durable storage and rewrites its retained paths.
		 *
		 * @param runPath the terminal evaluation record
		 * @param workspaceRoot the evaluator-owned worker workspace
		 * @param durableDirectory the initially absent evidence destination
		 * @param temporaryRoot the CAT work temporary root
		 * @param workRoot the CAT work root
		 * @return the durable handoff receipt
		 * @throws IOException if evidence cannot be moved and verified
		 */
		ObjectNode handoff(Path runPath, Path workspaceRoot, Path durableDirectory, Path temporaryRoot, Path workRoot)
			throws IOException;

		/**
		 * Reads the retained evidence root from a verified handoff receipt.
		 *
		 * @param handoff the durable handoff receipt
		 * @param runPath the terminal evaluation record used in a malformed-receipt diagnostic
		 * @return the retained evidence root
		 * @throws IOException if the receipt has no valid evidence-root path
		 */
		Path retainedEvidenceRoot(ObjectNode handoff, Path runPath) throws IOException;

		/**
		 * Finds the evaluator-owned root that contains the selected record.
		 *
		 * @param temporaryRoot the CAT work temporary root
		 * @param runPath the terminal evaluation record
		 * @return the owned evidence root
		 * @throws IOException if the record is not owned by this evaluator
		 */
		Path evidenceRoot(Path temporaryRoot, Path runPath) throws IOException;

		/**
		 * Counts regular-file bytes under one owned root.
		 *
		 * @param root the owned evidence root
		 * @return the retained or deleted byte count
		 * @throws IOException if an entry cannot be inspected
		 */
		long byteCount(Path root) throws IOException;

		/**
		 * Replaces a candidate-state reference before its source record is removed.
		 *
		 * @param runPath the record that will be deleted
		 * @param recordRoot the owned root that contains the record
		 * @return the replacement tombstone, or {@code null} when none is needed
		 * @throws IOException if a referenced candidate state cannot be preserved
		 */
		Path replaceCandidateReference(Path runPath, Path recordRoot) throws IOException;

		/**
		 * Deletes a workspace that is outside the record root.
		 *
		 * @param temporaryRoot the CAT work temporary root
		 * @param workspaceRoot the workspace to remove
		 * @param recordRoot the evaluation record root being removed
		 * @throws IOException if the workspace cannot be removed
		 */
		void deleteDetachedWorkspace(Path temporaryRoot, Path workspaceRoot, Path recordRoot) throws IOException;

		/**
		 * Deletes the record root after every required durable replacement has completed.
		 *
		 * @param recordRoot the owned evaluator evidence root
		 * @throws IOException if the root cannot be removed
		 */
		void deleteRecord(Path recordRoot) throws IOException;
	}

	private final Operations operations;

	/**
	 * Creates the terminal-evidence lifecycle owner.
	 *
	 * @param operations the evaluator operations that read, retain, and remove durable records
	 */
	public EvidenceFinalization(Operations operations)
	{
		this.operations = operations;
	}

	/**
	 * Retains or deletes the complete evidence for one terminal evaluation.
	 *
	 * @param json the mapper that creates the finalization receipt
	 * @param temporaryRoot the CAT work temporary root
	 * @param workRoot the CAT work root
	 * @param runPath the persistent terminal result record
	 * @param workspaceRoot the evaluator-owned worker workspace
	 * @param action {@code retain} to keep evidence or {@code delete} to remove it
	 * @param durableDirectory the destination for {@code retain}, or {@code null} for {@code delete}
	 * @return the verified status and the resulting retained or deleted evidence receipt
	 * @throws IOException if the evaluation is live, evidence is invalid, or the requested action is malformed
	 */
	public ObjectNode finalizeEvaluation(JsonMapper json, Path temporaryRoot, Path workRoot, Path runPath,
		Path workspaceRoot,
		String action, Path durableDirectory) throws IOException
	{
		ObjectNode status = operations.status(runPath, workspaceRoot);
		if (!status.path("evaluation_state").asString().equals("terminal"))
		{
			throw new IOException("Finalization requires a terminal evaluation: " + runPath + " (state: " +
				status.path("evaluation_state").asString() + ")");
		}

		ObjectNode result = json.createObjectNode();
		result.set("status", status);
		if (action.equals("retain"))
		{
			if (durableDirectory == null)
				durableDirectory = operations.defaultDestination(workRoot, runPath);
			ObjectNode handoff = operations.handoff(runPath, workspaceRoot, durableDirectory, temporaryRoot, workRoot);
			Path retained = operations.retainedEvidenceRoot(handoff, runPath);
			result.set("handoff", handoff);
			result.put("retained_bytes", operations.byteCount(retained));
			return result;
		}
		if (!action.equals("delete"))
			throw new IOException("Finalization action must be retain or delete: " + action);
		if (durableDirectory != null)
			throw new IOException("Finalizing with delete does not accept a destination directory");

		Path recordRoot = operations.evidenceRoot(temporaryRoot, runPath);
		long deletedBytes = operations.byteCount(recordRoot);
		Path tombstone = operations.replaceCandidateReference(runPath, recordRoot);
		operations.deleteDetachedWorkspace(temporaryRoot, workspaceRoot, recordRoot);
		operations.deleteRecord(recordRoot);
		result.put("deleted_workspace", workspaceRoot.toAbsolutePath().normalize().toString());
		result.put("deleted_record", recordRoot.toString());
		result.put("deleted_bytes", deletedBytes);
		if (tombstone != null)
			result.put("tombstone", tombstone.toString());
		return result;
	}
}

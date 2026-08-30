package io.github.cowwoc.cat.common.evaluation;

import io.github.cowwoc.cat.common.InvocationDirectory;
import io.github.cowwoc.cat.common.PromptEvaluation;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rewrites durable evidence after its owning invocation moves to retained storage.
 */
public final class EvidenceHandoff
{
	/**
	 * Prevents construction of a stateless evidence-rewrite owner.
	 */
	private EvidenceHandoff()
	{
	}

	/**
	 * Moves one completed evaluation to durable storage and publishes its retained-evidence receipt.
	 *
	 * @param json the operation-owned mapper for retained JSON receipts
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the owned terminal-evidence directory
	 * @param durableDirectory the initially absent retained destination
	 * @param temporaryRoot the CAT work temporary root
	 * @param workRoot the CAT work root
	 * @return the retained-evidence handoff receipt
	 * @throws IOException if terminal evidence cannot be validated, moved, retained, or recorded
	 */
	public static ObjectNode handoff(JsonMapper json, Path runPath, Path workspaceRoot, Path durableDirectory,
		Path temporaryRoot,
		Path workRoot) throws IOException
	{
		runPath = runPath.toAbsolutePath().normalize();
		workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		ObjectNode run = PromptEvaluation.object(PromptEvaluation.read(runPath), "run");
		boolean completedNonCertifyingRun = PromptEvaluation.hasCompletedSmoke(run) ||
			PromptEvaluation.hasCompletedRegressionCheck(run) || PromptEvaluation.hasCompletedRoutingMatrix(run) ||
			PromptEvaluation.hasCompletedReviewerControls(run);
		ObjectNode decision = null;
		if (!completedNonCertifyingRun)
		{
			decision = PromptEvaluation.object(run.path("decision"), "decision");
			if (decision.path("overall_decision").asString().equals("RUNNING"))
				throw new IOException("Prompt evaluation has not reached a terminal decision: " + runPath);
		}
		Path marker = EvaluationWorkspaceLifecycle.evidenceMarker(workspaceRoot);
		if (!Files.isRegularFile(marker) ||
			!Files.readString(marker, StandardCharsets.UTF_8).strip().equals(runPath.toString()))
			throw new IOException("Workspace is not owned by this prompt evaluation: " + workspaceRoot);
		if (Lifecycle.hasActiveEvaluationOwner(EvaluationWorkspaceLifecycle.activeMarker(workspaceRoot)))
			throw new IOException("Prompt evaluation still has active work: " + workspaceRoot);
		PromptEvaluation.validateEvidenceIntegrity(run, runPath);
		Path recordRoot = ArtifactRetention.ownedEvidence(temporaryRoot, runPath);
		Optional<Path> invocation = InvocationDirectory.ownedInvocation(temporaryRoot, recordRoot);
		if (!workspaceRoot.startsWith(recordRoot))
			throw new IOException("Evaluation workspace must be within its record directory before handoff: " +
				workspaceRoot);
		Path destination = moveEvidence(temporaryRoot, workRoot, recordRoot, durableDirectory);
		Path retainedRunPath = rewritePath(runPath, recordRoot, destination);
		rewriteRetainedPathValues(run, recordRoot, destination);
		rewriteRetainedPaths(run, retainedRunPath, recordRoot, destination);
		rewriteCandidateState(run, runPath, retainedRunPath, recordRoot, destination);
		rewriteOperationalRecords(destination, recordRoot, destination);
		Path retainedMarker = rewritePath(marker, recordRoot, destination);
		Files.writeString(retainedMarker, retainedRunPath + System.lineSeparator(), StandardCharsets.UTF_8);
		ArtifactRetention.markRecord(destination, retainedRunPath);
		Path retainedWorkspace = rewritePath(workspaceRoot, recordRoot, destination);
		ObjectNode evidenceCompaction = compactEvidence(json, run, retainedRunPath, destination, retainedWorkspace,
			workRoot);
		PromptEvaluation.validateEvidenceIntegrity(run, retainedRunPath);
		refreshPlanIdentity(json, run, retainedRunPath);
		PromptEvaluation.write(retainedRunPath, run);
		Path journal = publishJournal(json, run, retainedRunPath, destination, decision, evidenceCompaction);
		ObjectNode invocationCleanup = invocation.map(value ->
			removeInvocationWrapper(json, temporaryRoot, value, recordRoot)).orElseGet(() ->
				unownedInvocationCleanup(json, temporaryRoot, recordRoot));
		ObjectNode result = json.createObjectNode();
		result.put("evidence_root", destination.toString());
		result.put("run", retainedRunPath.toString());
		result.put("journal", journal.toString());
		result.set("evidence_compaction", evidenceCompaction);
		result.set("invocation_cleanup", invocationCleanup);
		return result;
	}

	/**
	 * Moves the evaluator-owned record directory to its initially absent retained destination.
	 *
	 * @param temporaryRoot the CAT work temporary root containing the source
	 * @param workRoot the CAT work root containing the destination
	 * @param recordRoot the evaluator-owned evidence directory to move
	 * @param durableDirectory the requested retained destination
	 * @return the normalized retained destination
	 * @throws IOException if either location is unsafe or the move cannot complete
	 */
	private static Path moveEvidence(Path temporaryRoot, Path workRoot, Path recordRoot, Path durableDirectory)
		throws IOException
	{
		Path temporary = temporaryRoot.toAbsolutePath().normalize();
		Path work = workRoot.toAbsolutePath().normalize();
		Path source = recordRoot.toAbsolutePath().normalize();
		Path destination = durableDirectory.toAbsolutePath().normalize();
		if (!source.startsWith(temporary) || source.equals(temporary))
			throw new IOException("Evaluation record is not beneath the CAT work temp directory: " + source);
		if (!destination.startsWith(work) || destination.equals(work))
			throw new IOException("Retained evaluation evidence must remain beneath the CAT work root: " + destination);
		if (destination.startsWith(temporary))
			throw new IOException("Retained evaluation evidence cannot remain beneath the CAT work temp directory: " +
				destination);
		if (destination.startsWith(source))
			throw new IOException("Retained evaluation destination cannot be inside its record directory: " + destination);
		if (Files.exists(destination, NOFOLLOW_LINKS))
			throw new IOException("Retained evaluation destination already exists: " + destination);
		Path parent = destination.getParent();
		if (parent == null)
			throw new IOException("Retained evaluation destination has no parent: " + destination);
		Files.createDirectories(parent);
		return Files.move(source, destination).toAbsolutePath().normalize();
	}

	/**
	 * Deletes an empty workflow invocation wrapper after its selected evaluation record has moved.
	 *
	 * @param json the operation-owned mapper for the cleanup receipt
	 * @param temporaryRoot the CAT work temporary root
	 * @param invocation the workflow-owned directory that contained the record
	 * @param recordRoot the moved evaluator record directory
	 * @return the cleanup receipt, including a retry action when deletion fails
	 */
	private static ObjectNode removeInvocationWrapper(JsonMapper json, Path temporaryRoot, Path invocation,
		Path recordRoot)
	{
		ObjectNode result = json.createObjectNode();
		if (invocation.equals(recordRoot))
		{
			result.put("status", "moved_with_evaluation");
			return result;
		}
		result.put("directory", invocation.toString());
		if (containsOtherEvaluationRecord(invocation))
		{
			result.put("status", "retained_for_sibling_records");
			result.put("next_action", "finalize_remaining_evaluations");
			return result;
		}
		try
		{
			long deletedBytes = PromptEvaluation.fileTreeBytes(invocation);
			InvocationDirectory.delete(temporaryRoot, invocation);
			result.put("status", "deleted");
			result.put("deleted_bytes", deletedBytes);
		}
		catch (IOException e)
		{
			result.put("status", "retry_required");
			result.put("error", e.getMessage());
			result.put("next_action", "retry_invocation_cleanup");
		}
		return result;
	}

	/**
	 * Indicates whether an invocation still contains evidence for another evaluator record.
	 *
	 * @param invocation the workflow-owned directory after one record moved
	 * @return whether the invocation must be retained for another record
	 */
	private static boolean containsOtherEvaluationRecord(Path invocation)
	{
		try (Stream<Path> paths = Files.walk(invocation))
		{
			return paths.anyMatch(path ->
			{
				String name = path.getFileName().toString();
				return name.equals(EvaluationWorkspaceLifecycle.EVIDENCE_MARKER) ||
					name.equals(ArtifactRetention.RECORD_MARKER);
			});
		}
		catch (IOException _)
		{
			return true;
		}
	}

	/**
	 * Retains every attempt result outside the moved workspace and removes superseded runtime files.
	 *
	 * @param json the operation-owned mapper for compaction receipts
	 * @param run the mutable terminal evaluation record
	 * @param runPath the retained record path
	 * @param evidenceRoot the retained evaluation root
	 * @param workspaceRoot the retained worker workspace
	 * @param workRoot the CAT work root that owns shared immutable evidence
	 * @return the artifact-retention and cleanup receipt
	 * @throws IOException if a required artifact cannot be retained or safely removed
	 */
	private static ObjectNode compactEvidence(JsonMapper json, ObjectNode run, Path runPath, Path evidenceRoot,
		Path workspaceRoot,
		Path workRoot) throws IOException
	{
		Map<Path, Path> retainedArtifacts = new HashMap<>();
		ArrayNode attemptRecords = PromptEvaluation.attempts(run, runPath);
		for (JsonNode node : attemptRecords)
		{
			ObjectNode attempt = PromptEvaluation.object(node, "terminal attempt");
			retainAttemptArtifact(attempt, "raw_result", runPath, workRoot, retainedArtifacts);
			retainAttemptArtifact(attempt, "validator_result", runPath, workRoot, retainedArtifacts);
			if (attempt.has("deterministic_result"))
				retainAttemptArtifact(attempt, "deterministic_result", runPath, workRoot, retainedArtifacts);
		}
		PromptEvaluation.writeAttemptLedger(run, runPath, attemptRecords);
		for (Map.Entry<Path, Path> artifact : retainedArtifacts.entrySet())
			rewriteOperationalRecords(evidenceRoot, artifact.getKey(), artifact.getValue());
		PromptEvaluation.validateEvidenceIntegrity(run, runPath);

		long deletedBytes = deleteSupersededEvidenceFiles(evidenceRoot, retainedArtifacts.keySet());
		ArrayNode removedRuntimes = json.createArrayNode();
		deletedBytes = Math.addExact(deletedBytes, removeUnreferencedRunnerRuntimes(evidenceRoot, removedRuntimes));
		ObjectNode workspaceCleanup = deleteTerminalWorkspace(json, run, attemptRecords, evidenceRoot, workspaceRoot);
		deletedBytes = Math.addExact(deletedBytes, workspaceCleanup.path("deleted_bytes").asLong());
		Set<Path> retainedCopies = new HashSet<>(retainedArtifacts.values());
		long retainedBytes = 0;
		for (Path retained : retainedCopies)
			retainedBytes = Math.addExact(retainedBytes, Files.size(retained));
		ObjectNode result = json.createObjectNode();
		result.set("retained_artifacts", json.valueToTree(retainedCopies.stream().map(Path::toString).
			sorted().toList()));
		result.set("removed_runtime_paths", removedRuntimes);
		result.set("workspace_cleanup", workspaceCleanup);
		result.put("retained_bytes", retainedBytes);
		result.put("deleted_bytes", deletedBytes);
		return result;
	}

	/**
	 * Deletes a terminal workspace only after the attempt ledger retains its results elsewhere.
	 *
	 * @param json the operation-owned mapper for the cleanup receipt
	 * @param run the mutable terminal evaluation record
	 * @param attempts the retained attempt ledger
	 * @param evidenceRoot the retained evaluation root
	 * @param workspaceRoot the worker workspace to inspect
	 * @return the workspace-cleanup receipt
	 * @throws IOException if a workspace outside the retained evaluation root would be deleted
	 */
	private static ObjectNode deleteTerminalWorkspace(JsonMapper json, ObjectNode run, ArrayNode attempts,
		Path evidenceRoot,
		Path workspaceRoot) throws IOException
	{
		ObjectNode result = json.createObjectNode();
		result.put("workspace", workspaceRoot.toString());
		if (attempts.isEmpty() || workspaceRoot.equals(evidenceRoot))
		{
			result.put("status", "retained");
			result.put("deleted_bytes", 0);
			return result;
		}
		if (!workspaceRoot.startsWith(evidenceRoot) ||
			!Files.isDirectory(workspaceRoot, NOFOLLOW_LINKS))
			throw new IOException("Terminal workspace is not a removable evaluator child: " + workspaceRoot);
		long deletedBytes = PromptEvaluation.fileTreeBytes(workspaceRoot);
		TemporaryDirectories.deleteRecursively(workspaceRoot);
		run.put("workspace_finalized", true);
		result.put("status", "deleted");
		result.put("deleted_bytes", deletedBytes);
		run.remove("workspace");
		run.remove("retained_workspace");
		run.set("terminal_workspace_cleanup", result.deepCopy());
		return result;
	}

	/**
	 * Copies one attempt artifact to immutable evidence storage and records the retained location.
	 *
	 * @param attempt the mutable attempt record
	 * @param field the artifact field to replace
	 * @param runPath the record path used in failure diagnostics
	 * @param workRoot the CAT work root that owns immutable evidence
	 * @param retainedArtifacts retained copies indexed by their source artifact
	 * @throws IOException if the recorded artifact is unavailable or cannot be retained
	 */
	private static void retainAttemptArtifact(ObjectNode attempt, String field, Path runPath, Path workRoot,
		Map<Path, Path> retainedArtifacts) throws IOException
	{
		Path source = PromptEvaluation.requiredArtifact(PromptEvaluation.text(attempt, field, runPath),
			field.replace('_', ' '));
		Path retained = retainedArtifacts.get(source);
		if (retained == null)
		{
			retained = FileStore.retainExecutionEvidence(workRoot, source);
			retainedArtifacts.put(source, retained);
		}
		attempt.put(field, retained.toString());
	}

	/**
	 * Deletes copied attempt artifacts that remain below the retained evaluation root.
	 *
	 * @param evidenceRoot the retained evaluation root
	 * @param artifacts the source artifacts copied to immutable storage
	 * @return the deleted byte count
	 * @throws IOException if a source artifact is no longer a regular file
	 */
	private static long deleteSupersededEvidenceFiles(Path evidenceRoot, Set<Path> artifacts) throws IOException
	{
		long deletedBytes = 0;
		for (Path artifact : artifacts)
		{
			if (!artifact.startsWith(evidenceRoot))
				continue;
			if (!Files.isRegularFile(artifact, NOFOLLOW_LINKS) || Files.isSymbolicLink(artifact))
				throw new IOException("Copied evidence artifact is no longer a regular file: " + artifact);
			deletedBytes = Math.addExact(deletedBytes, Files.size(artifact));
			Files.delete(artifact);
		}
		return deletedBytes;
	}

	/**
	 * Removes completed native runner directories after retained records no longer reference them.
	 *
	 * @param evidenceRoot the retained evaluation root
	 * @param removedRuntimes the receipt array receiving removed directories
	 * @return the deleted byte count
	 * @throws IOException if a runner directory cannot be inspected or removed
	 */
	private static long removeUnreferencedRunnerRuntimes(Path evidenceRoot, ArrayNode removedRuntimes)
		throws IOException
	{
		long[] deletedBytes = {0};
		Files.walkFileTree(evidenceRoot, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
			{
				if (failure != null)
					throw failure;
				if (!directory.getFileName().toString().equals("runner-runs"))
					return FileVisitResult.CONTINUE;
				deletedBytes[0] = Math.addExact(deletedBytes[0], PromptEvaluation.fileTreeBytes(directory));
				TemporaryDirectories.deleteRecursively(directory);
				removedRuntimes.add(directory.toString());
				return FileVisitResult.CONTINUE;
			}
		});
		return deletedBytes[0];
	}

	/**
	 * Refreshes fingerprints derived from operational paths after the evidence directory moves.
	 *
	 * @param json the operation-owned mapper used to serialize fingerprint inputs
	 * @param run the mutable retained evaluation record
	 * @param runPath the retained record path
	 * @throws IOException if the retained execution plan is unavailable or malformed
	 */
	private static void refreshPlanIdentity(JsonMapper json, ObjectNode run, Path runPath) throws IOException
	{
		if (!run.has("execution_plan_sha256"))
			return;

		Path executionPlan = PromptEvaluation.path(PromptEvaluation.text(run, "execution_plan", runPath));
		if (!Files.isRegularFile(executionPlan, NOFOLLOW_LINKS))
			throw new IOException("Retained execution plan is unavailable after handoff: " + executionPlan);
		run.put("execution_plan_sha256", PromptEvaluation.sha256(executionPlan));
		if (!run.has("execution_identity_sha256"))
			return;

		ObjectNode identity = Identity.executionIdentity(json, executionPlan);
		run.set("execution_identity", identity);
		run.put("execution_identity_sha256", Identity.executionFingerprint(json, identity));
		ObjectNode promptRoute = Identity.assembledRoute(json, run, identity, runPath);
		run.set("assembled_prompt_route", promptRoute);
		run.put("assembled_prompt_route_sha256", PromptEvaluation.sha256(
			json.writeValueAsString(promptRoute)));
		if (run.has("candidate_bundle_sha256"))
			run.put("candidate_identity_sha256", PromptEvaluation.text(run, "candidate_bundle_sha256", runPath));
		else
			run.put("candidate_identity_sha256", Identity.candidateIdentity(json, promptRoute));
		ObjectNode epoch = Identity.evaluationEpoch(json, run, identity, runPath);
		run.set("evaluation_epoch", epoch);
		run.put("evaluation_epoch_sha256", PromptEvaluation.sha256(json.writeValueAsString(epoch)));
		run.set("comparison_contract", epoch.deepCopy());
		run.put("comparison_contract_sha256", PromptEvaluation.sha256(json.writeValueAsString(epoch)));
	}

	/**
	 * Rewrites the retained path fields in an evaluation record and its attempt ledger.
	 *
	 * @param run the record whose paths change
	 * @param runPath the record path used to read its attempt ledger
	 * @param sourceRoot the evidence root before handoff
	 * @param destinationRoot the retained evidence root
	 * @throws IOException if the attempt ledger cannot be read or written
	 */
	static void rewriteRetainedPaths(ObjectNode run, Path runPath, Path sourceRoot, Path destinationRoot)
		throws IOException
	{
		rewriteRetainedPath(run, "attempt_ledger", sourceRoot, destinationRoot);
		ArrayNode attempts = PromptEvaluation.attempts(run, runPath);
		for (JsonNode node : attempts)
		{
			ObjectNode attempt = PromptEvaluation.object(node, "attempt");
			rewriteRetainedPath(attempt, "raw_result", sourceRoot, destinationRoot);
			rewriteRetainedPath(attempt, "validator_result", sourceRoot, destinationRoot);
			rewriteRetainedPath(attempt, "deterministic_result", sourceRoot, destinationRoot);
		}
		PromptEvaluation.writeAttemptLedger(run, runPath, attempts);
	}

	/**
	 * Replaces one exact moved path in a structured record.
	 *
	 * @param node the record or nested value to rewrite
	 * @param sourcePath the path before handoff
	 * @param destinationPath the path after handoff
	 */
	public static void rewriteExactPath(JsonNode node, Path sourcePath, Path destinationPath)
	{
		if (node instanceof ObjectNode object)
		{
			List<String> fieldNames = new ArrayList<>(object.propertyNames());
			for (String fieldName : fieldNames)
			{
				JsonNode value = object.path(fieldName);
				if (value.isString() && value.asString().equals(sourcePath.toString()))
					object.put(fieldName, destinationPath.toString());
				else
					rewriteExactPath(value, sourcePath, destinationPath);
			}
			return;
		}
		if (node instanceof ArrayNode array)
			for (JsonNode value : array)
				rewriteExactPath(value, sourcePath, destinationPath);
	}

	/**
	 * Rewrites every string value that names a location below the moved evidence root.
	 *
	 * @param node the record or nested value to rewrite
	 * @param sourceRoot the evidence root before handoff
	 * @param destinationRoot the retained evidence root
	 */
	static void rewriteRetainedPathValues(JsonNode node, Path sourceRoot, Path destinationRoot)
	{
		if (node instanceof ObjectNode object)
		{
			List<String> fieldNames = new ArrayList<>(object.propertyNames());
			for (String fieldName : fieldNames)
			{
				JsonNode value = object.path(fieldName);
				if (value.isString())
					object.put(fieldName, rewriteRetainedText(value.asString(), sourceRoot, destinationRoot));
				else
					rewriteRetainedPathValues(value, sourceRoot, destinationRoot);
			}
			return;
		}
		if (node instanceof ArrayNode array)
			for (JsonNode value : array)
				rewriteRetainedPathValues(value, sourceRoot, destinationRoot);
	}

	/**
	 * Rewrites operational records that must point to the retained evidence location.
	 *
	 * @param root the retained evidence root
	 * @param sourceRoot the evidence root before handoff
	 * @param destinationRoot the retained evidence root
	 * @throws IOException if an operational record cannot be rewritten
	 */
	static void rewriteOperationalRecords(Path root, Path sourceRoot, Path destinationRoot) throws IOException
	{
		Files.walkFileTree(root, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
			{
				String name = directory.getFileName().toString();
				if (name.equals("home") || name.equals("node_modules"))
					return FileVisitResult.SKIP_SUBTREE;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (isOperationalRecord(file))
					rewriteOperationalRecord(file, sourceRoot, destinationRoot);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Updates the candidate-state record after its referenced evaluation evidence moves.
	 *
	 * @param run the retained evaluation record
	 * @param sourceRunPath the run path before handoff
	 * @param retainedRunPath the run path after handoff
	 * @param sourceRoot the evidence root before handoff
	 * @param destinationRoot the retained evidence root
	 * @throws IOException if the declared candidate state is unavailable or cannot be rewritten
	 */
	static void rewriteCandidateState(ObjectNode run, Path sourceRunPath, Path retainedRunPath, Path sourceRoot,
		Path destinationRoot) throws IOException
	{
		if (!run.has("failure_signature_state"))
			return;
		Path statePath = PromptEvaluation.path(PromptEvaluation.text(run, "failure_signature_state", sourceRunPath));
		if (!Files.isRegularFile(statePath, NOFOLLOW_LINKS))
			throw new IOException("Evaluation names an unavailable candidate state during handoff: " + statePath);
		PromptEvaluation.withRunLock(statePath, () ->
		{
			ObjectNode state = PromptEvaluation.object(PromptEvaluation.read(statePath), "candidate state");
			rewriteRetainedPathValues(state, sourceRoot, destinationRoot);
			rewriteExactPath(state, sourceRunPath, retainedRunPath);
			PromptEvaluation.writeAtomically(statePath, state);
		});
		run.put("failure_signature_state_sha256", PromptEvaluation.sha256(statePath));
	}

	/**
	 * Publishes the journal that identifies retained terminal evidence.
	 *
	 * @param json the operation-owned mapper for the journal entry
	 * @param run the retained evaluation record
	 * @param runPath the retained record path
	 * @param evidenceRoot the retained evidence root
	 * @param decision the terminal certification decision, if this is a certifying run
	 * @param evidenceCompaction the retained-artifact and cleanup receipt
	 * @return the published journal path
	 * @throws IOException if a required terminal field is unavailable
	 */
	static Path publishJournal(JsonMapper json, ObjectNode run, Path runPath, Path evidenceRoot, ObjectNode decision,
		ObjectNode evidenceCompaction) throws IOException
	{
		Path journal = evidenceRoot.resolve("workflow-journal.json");
		ObjectNode entry = json.createObjectNode();
		boolean nonCertifying = PromptEvaluation.hasCompletedSmoke(run) ||
			PromptEvaluation.hasCompletedRegressionCheck(run) || PromptEvaluation.hasCompletedRoutingMatrix(run) ||
			PromptEvaluation.hasCompletedReviewerControls(run);
		String terminalDecision = terminalDecision(run, runPath, decision);
		String failureClass = nonCertifyingFailureClass(run);
		if (!nonCertifying && !terminalDecision.equals("PASS"))
		{
			if (decision.path("infrastructure_failure").asBoolean(false))
				failureClass = "infrastructure";
			else
				failureClass = "prompt-or-certification";
		}
		entry.put("purpose", "prompt-evaluation");
		entry.put("run", runPath.toString());
		entry.put("evidence_root", evidenceRoot.toString());
		entry.put("terminal_decision", terminalDecision);
		entry.put("failure_class", failureClass);
		entry.put("status", "current");
		entry.put("suite_sha256", PromptEvaluation.text(run, "suite_sha256", runPath));
		entry.put("prompt_sha256", PromptEvaluation.text(run, "prompt_sha256", runPath));
		entry.put("execution_context_sha256", PromptEvaluation.text(run, "execution_context_sha256", runPath));
		copyOptional(run, entry, "execution_plan_sha256", runPath);
		copyOptional(run, entry, "execution_identity_sha256", runPath);
		if (run.path("execution_identity").isObject())
		{
			ObjectNode identity = PromptEvaluation.object(run.path("execution_identity"), "execution identity");
			copyOptional(identity, entry, "runner_runtime_sha256", runPath);
		}
		copyOptional(run, entry, "assembled_prompt_route_sha256", runPath);
		entry.set("evidence_compaction", evidenceCompaction);
		PromptEvaluation.write(journal, entry);
		return journal;
	}

	/**
	 * Describes cleanup when the moved record has no workflow-owned outer invocation directory.
	 *
	 * @param json the operation-owned mapper for the cleanup receipt
	 * @param temporaryRoot the CAT work temporary root
	 * @param recordRoot the moved evaluator record directory
	 * @return the cleanup receipt
	 */
	static ObjectNode unownedInvocationCleanup(JsonMapper json, Path temporaryRoot, Path recordRoot)
	{
		ObjectNode result = json.createObjectNode();
		if (recordRoot.getParent().equals(temporaryRoot.toAbsolutePath().normalize()))
			result.put("status", "moved_with_evaluation");
		else
			result.put("status", "not_owned");
		return result;
	}

	/**
	 * Selects the terminal decision recorded by the completed operation.
	 *
	 * @param run the retained evaluation record
	 * @param runPath the retained record path
	 * @param decision the certifying decision, if applicable
	 * @return the terminal decision for the journal
	 * @throws IOException if a certifying decision is unavailable
	 */
	private static String terminalDecision(ObjectNode run, Path runPath, ObjectNode decision) throws IOException
	{
		if (PromptEvaluation.hasCompletedSmoke(run))
			return "SMOKE";
		if (PromptEvaluation.hasCompletedRegressionCheck(run))
			return "REGRESSION_CHECK";
		if (PromptEvaluation.hasCompletedRoutingMatrix(run))
			return "ROUTING_MATRIX";
		if (PromptEvaluation.hasCompletedReviewerControls(run))
			return "REVIEWER_CONTROLS";
		return PromptEvaluation.text(decision, "overall_decision", runPath);
	}

	/**
	 * Classifies a completed non-certifying operation for its journal entry.
	 *
	 * @param run the retained evaluation record
	 * @return the failure category, or {@code none} when the operation passed
	 * @throws IOException if a completed routing matrix lacks its status
	 */
	private static String nonCertifyingFailureClass(ObjectNode run) throws IOException
	{
		if (PromptEvaluation.hasCompletedReviewerControls(run))
		{
			ObjectNode controls = PromptEvaluation.object(run.path("reviewer_controls"), "reviewer controls");
			if (!PromptEvaluation.text(controls, "status", Path.of("reviewer controls")).equals("passed"))
				return "infrastructure";
		}
		if (!PromptEvaluation.hasCompletedRoutingMatrix(run))
			return "none";
		ObjectNode matrix = PromptEvaluation.object(run.path("routing_matrix"), "routing matrix");
		if (PromptEvaluation.text(matrix, "status", Path.of("routing matrix")).equals("passed"))
			return "none";
		return "routing-delivery";
	}

	/**
	 * Copies a journal field only when the retained record provides it.
	 *
	 * @param source the retained record that may provide the field
	 * @param destination the journal entry receiving the field
	 * @param field the optional field name
	 * @param runPath the retained record path used in diagnostics
	 * @throws IOException if a present field is invalid
	 */
	private static void copyOptional(ObjectNode source, ObjectNode destination, String field, Path runPath)
		throws IOException
	{
		if (source.has(field))
			destination.put(field, PromptEvaluation.text(source, field, runPath));
	}

	/**
	 * Determines whether a retained file is a mutable operational record.
	 *
	 * @param file the retained file
	 * @return whether the file must reference the retained evidence location
	 */
	private static boolean isOperationalRecord(Path file)
	{
		String name = file.getFileName().toString();
		return name.equals("execution-plan.json") || name.equals("executor.json") || name.equals("run.json") ||
			name.equals("validator.json") || name.equals("validator-prompt.md");
	}

	/**
	 * Rewrites one mutable receipt, nested run record, or validator instruction.
	 *
	 * @param file the operational record to rewrite
	 * @param sourceRoot the evidence root before handoff
	 * @param destinationRoot the retained evidence root
	 * @throws IOException if the record cannot be read or written
	 */
	private static void rewriteOperationalRecord(Path file, Path sourceRoot, Path destinationRoot)
		throws IOException
	{
		if (file.getFileName().toString().equals("validator-prompt.md"))
		{
			String original = Files.readString(file, StandardCharsets.UTF_8);
			String rewritten = rewriteRetainedText(original, sourceRoot, destinationRoot);
			if (!rewritten.equals(original))
				PromptEvaluation.writeText(file, rewritten);
			return;
		}
		ObjectNode record = PromptEvaluation.object(PromptEvaluation.read(file), "operational record");
		rewriteRetainedPathValues(record, sourceRoot, destinationRoot);
		PromptEvaluation.write(file, record);
	}

	/**
	 * Rewrites moved-root references in text without changing references already at the destination.
	 *
	 * @param text the text that may contain evidence paths
	 * @param sourceRoot the evidence root before handoff
	 * @param destinationRoot the retained evidence root
	 * @return the rewritten text
	 */
	private static String rewriteRetainedText(String text, Path sourceRoot, Path destinationRoot)
	{
		String source = sourceRoot.toString();
		String destination = destinationRoot.toString();
		StringBuilder result = new StringBuilder(text.length());
		int start = 0;
		int match = text.indexOf(source, start);
		while (match >= 0)
		{
			result.append(text, start, match).append(destination);
			int matchedLength = source.length();
			if (text.startsWith(destination, match))
				matchedLength = destination.length();
			start = match + matchedLength;
			match = text.indexOf(source, start);
		}
		return result.append(text, start, text.length()).toString();
	}

	/**
	 * Rewrites an attempt artifact only when it lies below the moved evidence root.
	 *
	 * @param attempt the attempt that names the artifact
	 * @param field the artifact field to rewrite
	 * @param sourceRoot the evidence root before handoff
	 * @param destinationRoot the retained evidence root
	 */
	private static void rewriteRetainedPath(ObjectNode attempt, String field, Path sourceRoot, Path destinationRoot)
	{
		Path value = Path.of(attempt.path(field).asString()).toAbsolutePath().normalize();
		if (value.startsWith(sourceRoot))
			attempt.put(field, rewritePath(value, sourceRoot, destinationRoot).toString());
	}

	/**
	 * Rewrites one path when it lies below the moved evidence root.
	 *
	 * @param path the original path
	 * @param sourceRoot the evidence root before handoff
	 * @param destinationRoot the retained evidence root
	 * @return the original path or its retained replacement
	 */
	static Path rewritePath(Path path, Path sourceRoot, Path destinationRoot)
	{
		if (!path.startsWith(sourceRoot))
			return path;
		return destinationRoot.resolve(sourceRoot.relativize(path));
	}
}

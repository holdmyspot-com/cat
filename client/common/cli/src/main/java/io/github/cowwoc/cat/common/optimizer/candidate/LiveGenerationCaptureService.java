package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionCodec;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Capture and restore boundary for a single pinned campaign and active worktree. */
final class LiveGenerationCaptureService
{
	private final Path project;
	private final Path campaignDirectory;
	private final CampaignBinding binding;
	private final PromptSnapshotRepository snapshots;
	private final EvidenceRef rootsRef;
	private final DeliveryWriteAuthority authority;
	private final CandidateEvidenceRepository evidence;
	private final CampaignStore store;
	private final DeliveryTransactionEngine transactions;

	/**
	 * Binds capture and restore to the same pinned worktree, campaign and write authority.
	 *
	 * @param project selected active worktree
	 * @param campaignDirectory pinned campaign directory
	 * @param binding C1 campaign binding
	 * @param snapshots immutable snapshots
	 * @param rootsRef confirmed editable roots reference
	 * @param authority matching write authority
	 * @param files journaled file operations
	 * @throws IOException if binding or authority differs
	 */
	LiveGenerationCaptureService(Path project, Path campaignDirectory, CampaignBinding binding,
		PromptSnapshotRepository snapshots, EvidenceRef rootsRef, DeliveryWriteAuthority authority,
		CandidateFileOperations files) throws IOException
	{
		this.project = Objects.requireNonNull(project, "project").toRealPath();
		this.campaignDirectory = Objects.requireNonNull(campaignDirectory, "campaignDirectory").toRealPath();
		this.binding = Objects.requireNonNull(binding, "binding");
		this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
		this.rootsRef = Objects.requireNonNull(rootsRef, "rootsRef");
		this.authority = Objects.requireNonNull(authority, "authority");
		store = CampaignStore.open(this.campaignDirectory);
		evidence = new CandidateEvidenceRepository(this.campaignDirectory, binding);
		transactions = new DeliveryTransactionEngine(this.project, this.campaignDirectory, binding,
			snapshots, rootsRef, authority, files);
	}

	/**
	 * Admits a successful and fully receipted call as a snapshot.
	 *
	 * @param checkpointRef pinned checkpoint
	 * @param toolCallIndexRef sealed tool-call index
	 * @param completionRef typed native completion
	 * @return immutable admitted capture
	 * @throws IOException if any evidence or live path differs
	 */
	LiveCandidateCapture captureLiveGeneration(EvidenceRef checkpointRef, EvidenceRef toolCallIndexRef,
		EvidenceRef completionRef) throws IOException
	{
		verifyConfirmedSources();
		Optional<LiveCandidateCapture> prior = recoverLiveCapture(checkpointRef);
		if (prior.isPresent())
		{
			LiveCandidateCapture capture = prior.orElseThrow();
			if (!capture.toolCallIndexRef().equals(toolCallIndexRef) || !capture.completionRef().equals(completionRef))
				throw new IOException("Conflicting live capture replay");
			return capture;
		}
		if (readIndex("live-generation-failure-indexes", checkpointRef).isPresent())
			throw new IOException("Failed generation cannot be admitted as a candidate");
		VerifiedCall call = verifyCall(checkpointRef, toolCallIndexRef, completionRef);
		if (call.completion().outcome() != NativeRunCompletionEvidence.Outcome.SUCCEEDED ||
			!call.denials().isEmpty() || !call.rejections().isEmpty())
			throw new IOException("Generation completion or denied edit forbids candidate capture");
		if (call.checkpoint().failedAttemptBaseRef().isPresent() && call.accepted().isEmpty())
			throw new IOException("A zero-edit retry cannot promote failed-attempt edits");
		CaptureState state = captureState(call);
		PromptSnapshot incumbent = snapshots.load(call.checkpoint().incumbentSnapshotRef());
		PromptSnapshot candidate = PromptSnapshot.capture(project, capturePaths());
		LiveGitInventory after = evidence.read(state.inventoryRef(), "git-file-inventories", LiveGitInventory.class);
		verifySnapshotInventory(candidate, after);
		if (!after.equals(LiveGitInventory.capture(project)))
			throw concurrent("Live state changed during snapshot capture", state.changedPaths());
		ConfirmedEditableRoots roots = evidence.read(rootsRef, "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(campaignDirectory, roots, snapshots);
		CandidateDiff diff = CandidateDiff.validate(incumbent, candidate, authority, baseline);
		List<Path> changed = diff.changes().stream().map(CandidateDiff.Change::path).toList();
		Set<Path> permitted = new HashSet<>(call.acceptedPaths());
		if (call.checkpoint().failedAttemptBaseRef().isPresent())
		{
			LiveGenerationFailureCapture base = evidence.read(call.checkpoint().failedAttemptBaseRef().orElseThrow(),
				"live-generation-failures", LiveGenerationFailureCapture.class);
			if (!base.currentInventoryRef().equals(call.checkpoint().beforeInventoryRef()) ||
				!base.incumbentSnapshotRef().equals(call.checkpoint().incumbentSnapshotRef()))
				throw new IOException("Retry baseline differs from retained failure");
			permitted.addAll(base.affectedPaths());
		}
		if (!permitted.containsAll(changed))
			throw concurrent("Candidate snapshot differs outside accepted edit paths", changed);
		String candidateId = candidate.id();
		if (changed.isEmpty() && call.checkpoint().failedAttemptBaseRef().isEmpty())
			candidateId = incumbent.id();
		else
			snapshots.save(candidate);
		LiveCandidateCapture capture = new LiveCandidateCapture(LiveCandidateCapture.SCHEMA_VERSION,
			UUID.randomUUID().toString(), checkpointRef, call.checkpoint().logicalCallId(),
			call.checkpoint().incumbentSnapshotRef(), candidateId, call.checkpoint().beforeInventoryRef(),
			state.inventoryRef(), toolCallIndexRef, completionRef, state.diffRef(), state.changedPaths());
		EvidenceRef ref = evidence.publish("live-generation-captures", capture);
		publishIndex("live-captures", checkpointRef, ref);
		return capture;
	}

	/**
	 * Retains verified failure evidence without restoring user-visible bytes.
	 *
	 * @param checkpointRef pinned checkpoint
	 * @param toolCallIndexRef sealed tool-call index
	 * @param completionRef typed native completion
	 * @return immutable noncandidate capture
	 * @throws IOException if failure evidence or live state is uncertain
	 */
	LiveGenerationFailureCapture captureFailedGeneration(EvidenceRef checkpointRef, EvidenceRef toolCallIndexRef,
		EvidenceRef completionRef) throws IOException
	{
		verifyConfirmedSources();
		Optional<EvidenceRef> prior = readIndex("live-generation-failure-indexes", checkpointRef);
		if (prior.isPresent())
		{
			LiveGenerationFailureCapture result = evidence.read(prior.orElseThrow(), "live-generation-failures",
				LiveGenerationFailureCapture.class);
			if (!result.checkpointRef().equals(checkpointRef) || !result.toolCallIndexRef().equals(toolCallIndexRef) ||
				!result.completionRef().equals(completionRef))
				throw new IOException("Conflicting failure capture replay");
			verifyFailureCapture(result);
			return result;
		}
		if (readIndex("live-captures", checkpointRef).isPresent())
			throw new IOException("Admitted candidate cannot become a failed capture");
		VerifiedCall call = verifyCall(checkpointRef, toolCallIndexRef, completionRef);
		if (call.completion().outcome() != NativeRunCompletionEvidence.Outcome.FAILED &&
			call.denials().isEmpty() && call.rejections().isEmpty())
			throw new IOException("Successful generation without denial has no failure capture");
		CaptureState state = captureState(call);
		LiveGenerationFailureCapture result = new LiveGenerationFailureCapture(
			LiveGenerationFailureCapture.SCHEMA_VERSION, UUID.randomUUID().toString(), checkpointRef,
			call.checkpoint().logicalCallId(), call.checkpoint().incumbentSnapshotRef(), toolCallIndexRef,
			completionRef, call.accepted(), call.denials(), call.checkpoint().beforeInventoryRef(),
			state.inventoryRef(), state.diffRef(), state.changedPaths());
		EvidenceRef ref = evidence.publish("live-generation-failures", result);
		publishIndex("live-generation-failure-indexes", checkpointRef, ref);
		return result;
	}

	/**
	 * Pins a fresh call over the exact retained failed state.
	 *
	 * @param failureCaptureRef indexed failure capture
	 * @param newLogicalCallId fresh logical call identity
	 * @return immutable retry checkpoint
	 * @throws IOException if retained state changed
	 */
	LiveGenerationCheckpoint prepareRetryGeneration(EvidenceRef failureCaptureRef, String newLogicalCallId)
		throws IOException
	{
		verifyConfirmedSources();
		LiveGenerationFailureCapture failure = evidence.read(failureCaptureRef, "live-generation-failures",
			LiveGenerationFailureCapture.class);
		EvidenceRef indexed = readIndex("live-generation-failure-indexes", failure.checkpointRef()).orElseThrow(
			() -> new IOException("Failure capture is not indexed"));
		if (!indexed.equals(failureCaptureRef))
			throw new IOException("Failure capture index differs");
		verifyFailureCapture(failure);
		LiveGenerationCheckpoint prior = checkpoint(failure.checkpointRef());
		if (newLogicalCallId == null || newLogicalCallId.isBlank() ||
			newLogicalCallId.equals(prior.logicalCallId()))
			throw new IOException("Retry needs a fresh logical call");
		LiveGitInventory expected = evidence.read(failure.currentInventoryRef(), "git-file-inventories",
			LiveGitInventory.class);
		if (!expected.equals(LiveGitInventory.capture(project)))
			throw concurrent("Live state differs from retained failed generation", failure.affectedPaths());
		VerifiedCall call = verifyCall(failure.checkpointRef(), failure.toolCallIndexRef(), failure.completionRef());
		if (!call.accepted().equals(failure.acceptedEditReceiptRefs()) ||
			!call.denials().equals(failure.denialRefs()))
			throw new IOException("Failed call evidence differs from failure capture");
		LiveGenerationCheckpoint retry = new LiveGenerationCheckpoint(LiveGenerationCheckpoint.SCHEMA_VERSION,
			UUID.randomUUID().toString(), newLogicalCallId, project, prior.incumbentSnapshotRef(), rootsRef,
			failure.currentInventoryRef(), Optional.of(failureCaptureRef));
		EvidenceRef ref = evidence.publish("live-generation-checkpoints", retry);
		if (!ref.equals(retry.checkpointRef()))
			throw new IOException("Retry checkpoint content address differs");
		return retry;
	}

	/**
	 * Recovers an indexed candidate after restart.
	 *
	 * @param checkpointRef exact checkpoint
	 * @return verified capture when indexed
	 * @throws IOException if an indexed record is corrupt or contradictory
	 */
	Optional<LiveCandidateCapture> recoverLiveCapture(EvidenceRef checkpointRef) throws IOException
	{
		verifyConfirmedSources();
		Optional<EvidenceRef> ref = readIndex("live-captures", checkpointRef);
		if (ref.isEmpty())
			return Optional.empty();
		LiveCandidateCapture capture = evidence.read(ref.orElseThrow(), "live-generation-captures",
			LiveCandidateCapture.class);
		LiveGenerationCheckpoint checkpoint = checkpoint(checkpointRef);
		if (!capture.checkpointRef().equals(checkpointRef) ||
			!capture.logicalCallId().equals(checkpoint.logicalCallId()) ||
			!capture.incumbentSnapshotRef().equals(checkpoint.incumbentSnapshotRef()) ||
			!capture.beforeInventoryRef().equals(checkpoint.beforeInventoryRef()))
			throw new IOException("Indexed live capture differs from checkpoint");
		PromptSnapshot candidate = snapshots.load(capture.candidateSnapshotRef());
		PromptSnapshot incumbent = snapshots.load(capture.incumbentSnapshotRef());
		ConfirmedEditableRoots roots = evidence.read(rootsRef, "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(campaignDirectory, roots, snapshots);
		CandidateDiff.validate(incumbent, candidate, authority, baseline);
		LiveGitInventory after = evidence.read(capture.afterInventoryRef(), "git-file-inventories",
			LiveGitInventory.class);
		verifySnapshotInventory(candidate, after);
		LiveGenerationDiff diff = evidence.read(capture.gitDiffEvidenceRef(), "live-generation-diffs",
			LiveGenerationDiff.class);
		if (!diff.beforeInventorySha256().equals(capture.beforeInventoryRef().evidenceId()) ||
			!diff.afterInventorySha256().equals(capture.afterInventoryRef().evidenceId()) ||
			!diff.changedPaths().equals(capture.affectedPaths()))
			throw new IOException("Indexed live capture diff differs from inventories");
		VerifiedCall call = verifyCall(checkpointRef, capture.toolCallIndexRef(), capture.completionRef());
		if (call.completion().outcome() != NativeRunCompletionEvidence.Outcome.SUCCEEDED ||
			!call.denials().isEmpty() || !call.rejections().isEmpty())
			throw new IOException("Indexed live capture does not have admitted completion");
		LiveGitInventory before = evidence.read(capture.beforeInventoryRef(), "git-file-inventories",
			LiveGitInventory.class);
		if (!validateTransition(call, before, after).equals(capture.affectedPaths()))
			throw new IOException("Indexed live capture path transition differs from accepted edits");
		return Optional.of(capture);
	}

	/**
	 * Rehashes all indexed noncandidate evidence without treating it as a candidate.
	 *
	 * @param failure retained failed generation
	 * @throws IOException if any linked evidence is corrupt or contradictory
	 */
	private void verifyFailureCapture(LiveGenerationFailureCapture failure) throws IOException
	{
		LiveGenerationCheckpoint prior = checkpoint(failure.checkpointRef());
		if (!failure.logicalCallId().equals(prior.logicalCallId()) ||
			!failure.incumbentSnapshotRef().equals(prior.incumbentSnapshotRef()) ||
			!failure.beforeInventoryRef().equals(prior.beforeInventoryRef()))
			throw new IOException("Failure capture differs from checkpoint");
		LiveGitInventory inventory = evidence.read(failure.currentInventoryRef(), "git-file-inventories",
			LiveGitInventory.class);
		LiveGenerationDiff diff = evidence.read(failure.gitDiffEvidenceRef(), "live-generation-diffs",
			LiveGenerationDiff.class);
		if (!diff.beforeInventorySha256().equals(prior.beforeInventoryRef().evidenceId()) ||
			!diff.afterInventorySha256().equals(failure.currentInventoryRef().evidenceId()) ||
			!diff.changedPaths().equals(failure.affectedPaths()) ||
			!inventory.projectRoot().equals(project))
			throw new IOException("Failure capture inventory or diff differs from checkpoint");
		VerifiedCall call = verifyCall(failure.checkpointRef(), failure.toolCallIndexRef(), failure.completionRef());
		LiveGitInventory before = evidence.read(failure.beforeInventoryRef(), "git-file-inventories",
			LiveGitInventory.class);
		if (!call.accepted().equals(failure.acceptedEditReceiptRefs()) ||
			!call.denials().equals(failure.denialRefs()) ||
			call.completion().outcome() != NativeRunCompletionEvidence.Outcome.FAILED && call.denials().isEmpty() ||
			!validateTransition(call, before, inventory).equals(failure.affectedPaths()))
			throw new IOException("Failure capture differs from sealed call evidence");
	}

	/**
	 * Restores the applied incumbent through the journaled transaction engine.
	 *
	 * @param checkpointRef exact checkpoint
	 * @param captureRef indexed admitted candidate
	 * @return immutable restore receipt
	 * @throws IOException if live state changed or restoration is unsafe
	 */
	LiveGenerationRestore restoreLiveGeneration(EvidenceRef checkpointRef, EvidenceRef captureRef) throws IOException
	{
		LiveCandidateCapture capture = recoverLiveCapture(checkpointRef).orElseThrow(
			() -> new IOException("No indexed live candidate capture"));
		EvidenceRef indexed = readIndex("live-captures", checkpointRef).orElseThrow();
		if (!indexed.equals(captureRef))
			throw new IOException("Restore capture differs from indexed capture");
		Optional<EvidenceRef> prior = readIndex("live-generation-restore-indexes", checkpointRef);
		if (prior.isPresent())
		{
			LiveGenerationRestore restore = evidence.read(prior.orElseThrow(), "live-generation-restores",
				LiveGenerationRestore.class);
			if (!restore.captureRef().equals(captureRef))
				throw new IOException("Conflicting live restore replay");
			return restore;
		}
		Optional<EvidenceRef> applied = transactions.findAppliedRestoreReceipt(captureRef,
			capture.candidateSnapshotRef(), capture.incumbentSnapshotRef());
		if (applied.isPresent())
			return publishRestore(checkpointRef, captureRef, capture, applied.orElseThrow());
		LiveGitInventory expected = evidence.read(capture.afterInventoryRef(), "git-file-inventories",
			LiveGitInventory.class);
		if (!expected.equals(LiveGitInventory.capture(project)))
			throw concurrent("Live state differs from captured candidate", capture.affectedPaths());
		CandidateApplication application = transactions.reconcileRestore(capture.candidateSnapshotRef(),
			capture.incumbentSnapshotRef(), captureRef);
		EvidenceRef transactionReceiptRef = new EvidenceRef("delivery-receipts", application.receiptId());
		return publishRestore(checkpointRef, captureRef, capture, transactionReceiptRef);
	}

	/**
	 * Publishes the restore receipt and checkpoint index after journal recovery or fresh reconciliation.
	 *
	 * @param checkpointRef owning checkpoint
	 * @param captureRef admitted capture
	 * @param capture rehashed capture
	 * @param transactionReceiptRef applied transaction receipt
	 * @return indexed restore receipt
	 * @throws IOException if immutable publication fails
	 */
	private LiveGenerationRestore publishRestore(EvidenceRef checkpointRef, EvidenceRef captureRef,
		LiveCandidateCapture capture, EvidenceRef transactionReceiptRef) throws IOException
	{
		LiveGenerationRestore restore = new LiveGenerationRestore(LiveGenerationRestore.SCHEMA_VERSION,
			checkpointRef, captureRef, capture.incumbentSnapshotRef(), transactionReceiptRef,
			capture.affectedPaths());
		EvidenceRef ref = evidence.publish("live-generation-restores", restore);
		publishIndex("live-generation-restore-indexes", checkpointRef, ref);
		return restore;
	}

	/**
	 * Rehashes the typed completion and every sealed gateway disposition.
	 *
	 * @param checkpointRef pinned checkpoint
	 * @param indexRef sealed tool calls
	 * @param completionRef typed completion
	 * @return verified call evidence
	 * @throws IOException if evidence differs
	 */
	private VerifiedCall verifyCall(EvidenceRef checkpointRef, EvidenceRef indexRef, EvidenceRef completionRef)
		throws IOException
	{
		LiveGenerationCheckpoint checkpoint = checkpoint(checkpointRef);
		requireIndexedCompletion(store.readNativeCompletionIndex(binding, checkpoint.logicalCallId()), completionRef);
		Path seal = GenerationEditSession.indexPath(campaignDirectory, checkpointRef,
			checkpoint.logicalCallId(), "seals", completionRef.evidenceId());
		if (!indexRef.equals(GenerationEditSession.readIndex(seal)))
			throw new IOException("Generation tool-call index differs from durable seal");
		GenerationToolCallIndex index = evidence.read(indexRef, "generation-tool-call-indexes",
			GenerationToolCallIndex.class);
		NativeRunCompletionEvidence completion = NativeRunCompletionCodec.decode(completionRef,
			store.evidenceReader(binding));
		if (!index.checkpointRef().equals(checkpointRef) ||
			!index.logicalCallId().equals(checkpoint.logicalCallId()) ||
			!completion.logicalCallId().equals(checkpoint.logicalCallId()) ||
			!completion.campaignId().equals(binding.campaignId()) ||
			!completion.runRequestRef().equals(index.runRequestRef()) ||
			!completion.slotClaimRef().equals(index.slotClaimRef()) ||
			!completion.runId().orElse("").equals(index.nativeRunId()))
			throw new IOException("Generation completion, checkpoint and tool-call index differ");
		if (!store.readCallSlotClaim(binding, checkpoint.logicalCallId()).slotClaimRef().equals(index.slotClaimRef()))
			throw new IOException("Generation call slot claim differs");
		GenerationNativeCallbackIndex callback = evidence.read(index.nativeCallbackIndexRef(),
			"generation-native-callback-indexes", GenerationNativeCallbackIndex.class);
		if (!callback.logicalCallId().equals(checkpoint.logicalCallId()) ||
			!callback.runRequestRef().equals(index.runRequestRef()) ||
			!callback.runId().equals(index.nativeRunId()) ||
			callback.orderedNativeCallIdAndRawEvidenceRefs().size() !=
				index.orderedToolCallAndDispositionRefs().size())
			throw new IOException("Native callback and C3 tool-call index differ");
		store.evidenceReader(binding).read(callback.nativeEventRootRef());
		for (EvidenceRef denial: callback.orderedNativeWriteDenialRefs())
			store.evidenceReader(binding).read(denial);
		List<EvidenceRef> accepted = new ArrayList<>();
		List<EvidenceRef> denials = new ArrayList<>(callback.orderedNativeWriteDenialRefs());
		List<EvidenceRef> rejections = new ArrayList<>();
		List<Path> paths = new ArrayList<>();
		for (int i = 0; i < index.orderedToolCallAndDispositionRefs().size(); ++i)
		{
			ToolCallDispositionPair pair = index.orderedToolCallAndDispositionRefs().get(i);
			GenerationToolCall call = evidence.read(pair.toolCallRef(), "generation-tool-calls",
				GenerationToolCall.class);
			GenerationToolDisposition disposition = evidence.read(pair.dispositionRef(),
				"generation-tool-dispositions", GenerationToolDisposition.class);
			NativeCallEvidence nativeCall = callback.orderedNativeCallIdAndRawEvidenceRefs().get(i);
			store.evidenceReader(binding).read(nativeCall.rawEvidenceRef());
			// C9 must index the exact raw argument evidence recorded by the gateway, not a separate event envelope.
			if (!call.checkpointRef().equals(checkpointRef) || !call.logicalCallId().equals(checkpoint.logicalCallId()) ||
				!call.runRequestRef().equals(index.runRequestRef()) ||
				!call.slotClaimRef().equals(index.slotClaimRef()) ||
				!call.nativeRunId().equals(index.nativeRunId()) ||
				!call.nativeCallId().equals(nativeCall.nativeCallId()) ||
				!call.rawArgumentsRef().equals(nativeCall.rawEvidenceRef()) ||
				!disposition.toolCallRef().equals(pair.toolCallRef()))
				throw new IOException("Native tool call or disposition differs from sealed index");
			switch (disposition.status())
			{
				case ACCEPTED ->
				{
					EvidenceRef receiptRef = disposition.editReceiptRef().orElseThrow();
					GenerationEditReceipt receipt = evidence.read(receiptRef, "generation-edit-receipts",
						GenerationEditReceipt.class);
					if (!receipt.checkpointRef().equals(checkpointRef) ||
						!receipt.nativeToolCallRef().equals(pair.toolCallRef()))
						throw new IOException("Accepted edit receipt differs from call");
					accepted.add(receiptRef);
					paths.addAll(receipt.affectedPaths());
				}
				case REJECTED ->
				{
					EvidenceRef rejectionRef = disposition.editRejectionRef().orElseThrow();
					GenerationEditRejection rejection = evidence.read(rejectionRef, "generation-edit-rejections",
						GenerationEditRejection.class);
					if (!rejection.checkpointRef().equals(checkpointRef) ||
						!rejection.nativeToolCallRef().equals(pair.toolCallRef()))
						throw new IOException("Rejected edit record differs from call");
					rejections.add(rejectionRef);
					denials.add(rejectionRef);
				}
				case UNRESOLVED -> throw new IOException("Unresolved generation callback forbids capture");
			}
		}
		return new VerifiedCall(checkpoint, completion, List.copyOf(accepted), List.copyOf(denials),
			List.copyOf(rejections), List.copyOf(paths));
	}

	/**
	 * Requires C1's create-once index to resolve to the exact typed completion supplied to capture.
	 *
	 * @param indexedCompletion C1 indexed completion, if present
	 * @param requestedCompletion capture caller's completion
	 * @throws IOException if the exact completion was not indexed
	 */
	static void requireIndexedCompletion(Optional<EvidenceRef> indexedCompletion, EvidenceRef requestedCompletion)
		throws IOException
	{
		if (indexedCompletion.isEmpty() || !indexedCompletion.orElseThrow().equals(requestedCompletion))
			throw new IOException("Generation completion is absent from or differs from C1's native completion index");
	}

	/**
	 * Rejects any inventory change not explained by accepted authority-bound edits.
	 *
	 * @param call verified call evidence
	 * @return retained inventory and diff
	 * @throws IOException if worktree state differs
	 */
	private CaptureState captureState(VerifiedCall call) throws IOException
	{
		LiveGitInventory before = evidence.read(call.checkpoint().beforeInventoryRef(), "git-file-inventories",
			LiveGitInventory.class);
		LiveGitInventory after = LiveGitInventory.capture(project);
		List<Path> changed = validateTransition(call, before, after);
		EvidenceRef afterRef = evidence.publish("git-file-inventories", after);
		LiveGenerationDiff diff = new LiveGenerationDiff(LiveGenerationDiff.SCHEMA_VERSION,
			before.headCommit(), before.indexSha256(), changed, call.checkpoint().beforeInventoryRef().evidenceId(),
			afterRef.evidenceId());
		EvidenceRef diffRef = evidence.publish("live-generation-diffs", diff);
		return new CaptureState(afterRef, diffRef, changed);
	}

	/**
	 * Verifies that each changed path and each final endpoint state follows accepted receipts only.
	 *
	 * @param call verified call evidence
	 * @param before before-call inventory
	 * @param after captured after-call inventory
	 * @return exact sorted changed paths
	 * @throws IOException if the transition is unsupported or unexplained
	 */
	private List<Path> validateTransition(VerifiedCall call, LiveGitInventory before, LiveGitInventory after)
		throws IOException
	{
		if (!before.projectRoot().equals(after.projectRoot()) || !before.headCommit().equals(after.headCommit()) ||
			!before.indexSha256().equals(after.indexSha256()))
			throw concurrent("Git HEAD or index changed during generation", List.of());
		Map<Path, LiveGitInventory.Entry> old = entries(before);
		Map<Path, LiveGitInventory.Entry> now = entries(after);
		Map<Path, GenerationExpectedState> expected = new HashMap<>();
		for (EvidenceRef receiptRef: call.accepted())
		{
			GenerationEditReceipt receipt = evidence.read(receiptRef, "generation-edit-receipts",
				GenerationEditReceipt.class);
			for (int i = 0; i < receipt.affectedPaths().size(); ++i)
			{
				Path path = receipt.affectedPaths().get(i);
				GenerationExpectedState prior = expected.getOrDefault(path, inventoryState(old.get(path)));
				if (!prior.equals(receipt.beforeStates().get(i).state()))
					throw concurrent("Accepted edit prestate differs from prior live state", List.of(path));
				expected.put(path, receipt.afterStates().get(i).state());
			}
		}
		for (Map.Entry<Path, GenerationExpectedState> entry: expected.entrySet())
			if (!entry.getValue().equals(inventoryState(now.get(entry.getKey()))))
				throw concurrent("Live state differs from final accepted edit receipt", List.of(entry.getKey()));
		Set<Path> all = new HashSet<>(old.keySet());
		all.addAll(now.keySet());
		List<Path> changed = all.stream().filter(path -> !Objects.equals(old.get(path), now.get(path))).
			sorted(Comparator.comparing(Path::toString)).toList();
		Set<Path> accepted = new HashSet<>(call.acceptedPaths());
		for (Path path: changed)
		{
			if (old.get(path) != null && old.get(path).kind() == LiveGitInventory.Entry.Kind.DIRECTORY ||
				now.get(path) != null && now.get(path).kind() == LiveGitInventory.Entry.Kind.DIRECTORY)
			{
				if (accepted.stream().noneMatch(leaf -> leaf.startsWith(path) && !leaf.equals(path)))
					throw concurrent("Directory changed outside confirmed roots", List.of(path));
				continue;
			}
			if (!accepted.contains(path) || !authority.permits(path) ||
				old.get(path) != null && old.get(path).kind() != LiveGitInventory.Entry.Kind.REGULAR ||
				now.get(path) != null && now.get(path).kind() != LiveGitInventory.Entry.Kind.REGULAR)
				throw concurrent("Unexpected live generation path changed", List.of(path));
		}
		return changed;
	}

	/**
	 * Converts an inventory entry to the exact gateway path state.
	 *
	 * @param entry inventory entry or null for absent
	 * @return exact expected state
	 * @throws IOException if kind is unsupported
	 */
	private static GenerationExpectedState inventoryState(LiveGitInventory.Entry entry) throws IOException
	{
		if (entry == null)
			return GenerationExpectedState.absent();
		if (entry.kind() != LiveGitInventory.Entry.Kind.REGULAR)
			throw new IOException("Gateway edit endpoint is not a regular file: " + entry.path());
		return GenerationExpectedState.regular(entry.contentSha256());
	}

	/**
	 * Checks that immutable snapshot bytes and the same captured Git inventory agree on every confirmed root file.
	 *
	 * @param snapshot immutable candidate snapshot
	 * @param inventory captured complete inventory
	 * @throws IOException if concurrent bytes or an unsupported kind were observed
	 */
	void verifySnapshotInventory(PromptSnapshot snapshot, LiveGitInventory inventory) throws IOException
	{
		Map<Path, LiveGitInventory.Entry> paths = entries(inventory);
		for (Map.Entry<Path, PromptSnapshot.Entry> entry: snapshot.entries().entrySet())
		{
			LiveGitInventory.Entry observed = paths.get(entry.getKey());
			if (observed == null || observed.kind() != LiveGitInventory.Entry.Kind.REGULAR ||
				!observed.contentSha256().equals(entry.getValue().hash()))
				throw concurrent("Candidate snapshot and Git inventory differ", List.of(entry.getKey()));
		}
		for (LiveGitInventory.Entry entry: inventory.paths())
		{
			if (entry.kind() == LiveGitInventory.Entry.Kind.DIRECTORY || !authority.permits(entry.path()))
				continue;
			if (entry.kind() != LiveGitInventory.Entry.Kind.REGULAR ||
				!snapshot.entries().containsKey(entry.path()))
				throw concurrent("Confirmed root file is absent from candidate snapshot", List.of(entry.path()));
		}
	}

	/**
	 * Reloads an exact pinned checkpoint.
	 *
	 * @param ref checkpoint reference
	 * @return pinned checkpoint
	 * @throws IOException if evidence differs
	 */
	private LiveGenerationCheckpoint checkpoint(EvidenceRef ref) throws IOException
	{
		verifyConfirmedSources();
		LiveGenerationCheckpoint value = evidence.read(ref, "live-generation-checkpoints",
			LiveGenerationCheckpoint.class);
		if (!value.checkpointRef().equals(ref) || !value.projectRoot().equals(project) ||
			!value.confirmedEditableRootsRef().equals(rootsRef))
			throw new IOException("Live generation checkpoint differs from pinned project or authority");
		return value;
	}

	/**
	 * Rechecks the roots-bound baseline before using live generation evidence.
	 *
	 * @throws IOException if authority or live known-source state differs
	 */
	private void verifyConfirmedSources() throws IOException
	{
		ConfirmedEditableRoots roots = evidence.read(rootsRef, "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		if (!roots.projectRoot().equals(project) || !roots.roots().equals(authority.roots()))
			throw new IOException("Generation confirmed roots differ from pinned authority");
		ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(campaignDirectory, roots, snapshots);
		ConfirmedSourceVerifier.verifyLive(project, baseline, authority);
	}

	/**
	 * Captures editable roots and every immutable read-only entry from the applied incumbent.
	 *
	 * @return paths needed for a full candidate snapshot
	 */
	private List<Path> capturePaths()
		throws IOException
	{
		ConfirmedEditableRoots roots = evidence.read(rootsRef, "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(campaignDirectory, roots, snapshots);
		return ConfirmedSourceVerifier.candidatePaths(roots, baseline);
	}

	/**
	 * Indexes complete inventory entries by path.
	 *
	 * @param inventory complete inventory
	 * @return path-indexed entries
	 */
	private static Map<Path, LiveGitInventory.Entry> entries(LiveGitInventory inventory)
	{
		Map<Path, LiveGitInventory.Entry> result = new HashMap<>();
		for (LiveGitInventory.Entry entry: inventory.paths())
			result.put(entry.path(), entry);
		return result;
	}

	/**
	 * Reads a create-once checkpoint index without treating absence as a capture.
	 *
	 * @param collection index collection
	 * @param checkpointRef checkpoint key
	 * @return indexed evidence reference, if any
	 * @throws IOException if index is malformed
	 */
	private Optional<EvidenceRef> readIndex(String collection, EvidenceRef checkpointRef) throws IOException
	{
		Path file = campaignDirectory.resolve(collection).resolve(checkpointRef.evidenceId() + ".json");
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Live generation index is not a regular file: " + file);
		EvidenceRef ref;
		try
		{
			ref = SnapshotJson.mapper().readValue(Files.readAllBytes(file), EvidenceRef.class);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Live generation index is malformed: " + file, e);
		}
		return Optional.of(ref);
	}

	/**
	 * Publishes the checkpoint's create-once index after immutable evidence exists.
	 *
	 * @param collection index collection
	 * @param checkpointRef checkpoint key
	 * @param valueRef immutable evidence value
	 * @throws IOException if publication conflicts
	 */
	private void publishIndex(String collection, EvidenceRef checkpointRef, EvidenceRef valueRef)
		throws IOException
	{
		Path file = campaignDirectory.resolve(collection).resolve(checkpointRef.evidenceId() + ".json");
		PromptSnapshotRepository.writeOnce(file, SnapshotJson.mapper().writeValueAsBytes(valueRef));
	}

	/**
	 * Classifies an exact live-state mismatch without changing worktree bytes.
	 *
	 * @param message precise failure context
	 * @param paths affected paths
	 * @return classified concurrent modification
	 */
	private static CandidateDeliveryException concurrent(String message, List<Path> paths)
	{
		return new CandidateDeliveryException(CandidateDeliveryException.Reason.CONCURRENT_MODIFICATION,
			message, Optional.empty(), paths);
	}

	private record VerifiedCall(LiveGenerationCheckpoint checkpoint, NativeRunCompletionEvidence completion,
		List<EvidenceRef> accepted, List<EvidenceRef> denials, List<EvidenceRef> rejections,
		List<Path> acceptedPaths)
	{
	}

	private record CaptureState(EvidenceRef inventoryRef, EvidenceRef diffRef, List<Path> changedPaths)
	{
	}
}

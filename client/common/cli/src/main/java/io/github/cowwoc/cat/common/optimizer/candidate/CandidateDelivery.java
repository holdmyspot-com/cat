package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.ClaimedSlot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Applies immutable candidates through a campaign-bound journal without changing Git history. */
public final class CandidateDelivery
{
	private final DeliveryTransactionEngine durable;
	private final LiveGenerationCaptureService capture;
	private final Path campaignDirectory;
	private final CampaignBinding expectedBinding;

	/**
	 * Creates a journaled delivery boundary bound to one pinned campaign and confirmed editable root set.
	 *
	 * @param project selected active worktree
	 * @param campaignDirectory campaign evidence directory
	 * @param expectedBinding exact C1 campaign binding
	 * @param snapshots immutable candidate snapshot repository
	 * @param confirmedRootsRef C4-confirmed roots evidence
	 * @throws IOException if campaign or roots do not match the pinned project
	 */
	public CandidateDelivery(Path project, Path campaignDirectory, CampaignBinding expectedBinding,
		PromptSnapshotRepository snapshots, EvidenceRef confirmedRootsRef) throws IOException
	{
		this(project, campaignDirectory, expectedBinding, snapshots, confirmedRootsRef,
			new JdkCandidateFileOperations());
	}

	/**
	 * Injects file operations for package-level crash and race tests.
	 *
	 * @param project selected active worktree
	 * @param campaignDirectory campaign evidence directory
	 * @param expectedBinding exact C1 campaign binding
	 * @param snapshots immutable candidate snapshot repository
	 * @param confirmedRootsRef C4-confirmed roots evidence
	 * @param files caller-observed file operation boundary
	 * @throws IOException if campaign or roots do not match the pinned project
	 */
	CandidateDelivery(Path project, Path campaignDirectory, CampaignBinding expectedBinding,
		PromptSnapshotRepository snapshots, EvidenceRef confirmedRootsRef, CandidateFileOperations files)
		throws IOException
	{
		this.campaignDirectory = campaignDirectory;
		this.expectedBinding = expectedBinding;
		ConfirmedEditableRoots roots = new CandidateEvidenceRepository(campaignDirectory, expectedBinding).read(
			confirmedRootsRef, "confirmed-editable-roots", ConfirmedEditableRoots.class);
		DeliveryWriteAuthority writeAuthority = new DeliveryWriteAuthority(roots.roots());
		durable = new DeliveryTransactionEngine(project, campaignDirectory, expectedBinding, snapshots,
			confirmedRootsRef, writeAuthority, files);
		capture = new LiveGenerationCaptureService(project, campaignDirectory, expectedBinding, snapshots,
			confirmedRootsRef, writeAuthority, files);
	}

	/**
	 * Reconciles complete saved prompt states through a durable capture-first transaction.
	 *
	 * @param baselineId initial immutable snapshot identity
	 * @param previousAppliedCandidateId complete visible predecessor identity, or null for baseline
	 * @param selectedCandidateId selected immutable snapshot identity
	 * @return archived application with transaction and receipt identities
	 * @throws IOException if state, authority, or transaction operation is unsafe
	 */
	public CandidateApplication reconcile(String baselineId, String previousAppliedCandidateId,
		String selectedCandidateId) throws IOException
	{
		return durable.reconcile(baselineId, previousAppliedCandidateId, selectedCandidateId);
	}

	/**
	 * Resolves a journaled delivery left unfinished by a prior process.
	 *
	 * @return terminal recovery result, or NONE when no transaction is pending
	 * @throws IOException if an unsafe or ambiguous journal cannot be recovered
	 */
	public DeliveryRecovery recoverPending() throws IOException
	{
		return durable.recoverPending();
	}

	/**
	 * Pins a selected active Git worktree before dispatching one generation call.
	 *
	 * @param projectRoot selected active Git worktree
	 * @param incumbentSnapshotRef saved applied incumbent snapshot
	 * @param confirmedEditableRootsRef exact confirmed edit authority
	 * @param logicalCallId fresh C1 logical call identity
	 * @return immutable published live checkpoint
	 * @throws IOException if the project, Git state, roots, or incumbent differs
	 */
	public LiveGenerationCheckpoint prepareLiveGeneration(Path projectRoot, String incumbentSnapshotRef,
		EvidenceRef confirmedEditableRootsRef, String logicalCallId) throws IOException
	{
		return durable.prepareLiveGeneration(projectRoot, incumbentSnapshotRef, confirmedEditableRootsRef,
			logicalCallId);
	}

	/**
	 * Opens the exact call-bound generation edit capability before native dispatch.
	 *
	 * @param checkpointRef pinned live checkpoint
	 * @param runRequestRef retained C0R generation request
	 * @param slotClaimRef exact C1-retained call-slot claim
	 * @param logicalCallId claimed logical call identity
	 * @return bounded generation edit session
	 * @throws IOException if any claim, request, checkpoint, or root differs
	 */
	public GenerationEditSession openGenerationEdit(EvidenceRef checkpointRef, EvidenceRef runRequestRef,
		EvidenceRef slotClaimRef, String logicalCallId) throws IOException
	{
		return new GenerationEditSession(campaignDirectory, expectedBinding, checkpointRef, runRequestRef,
			slotClaimRef, logicalCallId);
	}

	/**
	 * Seals every retained native callback after settlement using the exact C1 call claim.
	 *
	 * @param checkpointRef pinned live checkpoint
	 * @param completionRef C1-indexed typed native completion
	 * @param nativeCallbackIndexRef complete C9 callback and denial inventory
	 * @return immutable C3 tool-call and disposition index
	 * @throws IOException if the call claim, completion, or callbacks differ
	 */
	public GenerationToolCallIndex sealGenerationToolCalls(EvidenceRef checkpointRef, EvidenceRef completionRef,
		EvidenceRef nativeCallbackIndexRef) throws IOException
	{
		CandidateEvidenceRepository evidence = new CandidateEvidenceRepository(campaignDirectory, expectedBinding);
		LiveGenerationCheckpoint checkpoint = evidence.read(checkpointRef, "live-generation-checkpoints",
			LiveGenerationCheckpoint.class);
		CampaignStore store = CampaignStore.open(campaignDirectory);
		ClaimedSlot claim = store.readCallSlotClaim(expectedBinding, checkpoint.logicalCallId());
		try (GenerationEditSession session = openGenerationEdit(checkpointRef, claim.slot().requestRef(),
			claim.slotClaimRef(), checkpoint.logicalCallId()))
		{
			return session.sealGenerationToolCalls(completionRef, nativeCallbackIndexRef);
		}
	}

	/**
	 * Admits a successful indexed native generation and captures its exact worktree state.
	 *
	 * @param checkpointRef pinned live checkpoint
	 * @param toolCallIndexRef sealed complete tool-call index
	 * @param completionRef C1-indexed native completion
	 * @return immutable candidate capture
	 * @throws IOException if any native, edit, or live-state evidence differs
	 */
	public LiveCandidateCapture captureLiveGeneration(EvidenceRef checkpointRef, EvidenceRef toolCallIndexRef,
		EvidenceRef completionRef) throws IOException
	{
		return capture.captureLiveGeneration(checkpointRef, toolCallIndexRef, completionRef);
	}

	/**
	 * Captures a fully reconciled failed attempt without admitting a candidate.
	 *
	 * @param checkpointRef pinned live checkpoint
	 * @param toolCallIndexRef sealed complete tool-call index
	 * @param completionRef C1-indexed native completion
	 * @return immutable noncandidate failure record
	 * @throws IOException if the native or live state is uncertain
	 */
	public LiveGenerationFailureCapture captureFailedGeneration(EvidenceRef checkpointRef,
		EvidenceRef toolCallIndexRef, EvidenceRef completionRef) throws IOException
	{
		return capture.captureFailedGeneration(checkpointRef, toolCallIndexRef, completionRef);
	}

	/**
	 * Prepares a fresh logical call only from the exact retained failed live state.
	 *
	 * @param failureCaptureRef indexed failed-generation record
	 * @param newLogicalCallId fresh logical call identity
	 * @return immutable retry checkpoint
	 * @throws IOException if the live state or failure evidence changed
	 */
	public LiveGenerationCheckpoint prepareRetryGeneration(EvidenceRef failureCaptureRef, String newLogicalCallId)
		throws IOException
	{
		return capture.prepareRetryGeneration(failureCaptureRef, newLogicalCallId);
	}

	/**
	 * Re-reads an admitted capture after restart without rerunning native generation.
	 *
	 * @param checkpointRef exact live checkpoint
	 * @return indexed capture, or empty before admission
	 * @throws IOException if indexed capture evidence is invalid
	 */
	public Optional<LiveCandidateCapture> recoverLiveCapture(EvidenceRef checkpointRef) throws IOException
	{
		return capture.recoverLiveCapture(checkpointRef);
	}

	/**
	 * Restores a captured candidate to its incumbent through the journaled transaction engine.
	 *
	 * @param checkpointRef exact live checkpoint
	 * @param captureRef admitted live capture
	 * @return immutable restore receipt
	 * @throws IOException if the live state changed or restoration is unsafe
	 */
	public LiveGenerationRestore restoreLiveGeneration(EvidenceRef checkpointRef, EvidenceRef captureRef)
		throws IOException
	{
		return capture.restoreLiveGeneration(checkpointRef, captureRef);
	}
}

package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the indexed runner identity and owner attempt before native dispatch.
 */
public final class CallPreStartPublisher
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Publishes or retrieves the pre-start record for one claimed call.
	 *
	 * @param store the campaign evidence repository
	 * @param binding the pinned campaign
	 * @param logicalCallId the claimed call identity
	 * @param kind the owner attempt kind
	 * @param attemptRef the indexed producer attempt
	 * @param ownerRequestRef the owner request named by that attempt
	 * @param manager the native run identity source
	 * @param artifact the exact packaged runner selected for dispatch
	 * @return the immutable indexed pre-start record reference
	 * @throws IOException if the claim, owner attempt, or packaged runner is invalid
	 */
	public static EvidenceRef publish(CampaignStore store, CampaignBinding binding, String logicalCallId,
		ProducerAttemptKind kind, EvidenceRef attemptRef, EvidenceRef ownerRequestRef, HarnessRunManager manager,
		Path artifact) throws IOException
	{
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(binding, "binding");
		if (logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("logicalCallId must not be blank");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(attemptRef, "attemptRef");
		Objects.requireNonNull(ownerRequestRef, "ownerRequestRef");
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(artifact, "artifact");

		String runtimeManifestSha256 = RunnerRuntimeManifest.fingerprint(artifact);
		Optional<CallPreStartRecord> existing = store.readCallPreStartRecord(binding, logicalCallId);
		if (existing.isPresent())
		{
			CallPreStartRecord record = existing.orElseThrow();
			if (record.producerAttemptKind() != kind || !record.producerAttemptRef().equals(attemptRef) ||
				!record.ownerRequestRef().equals(ownerRequestRef) ||
				!record.runtimeManifestSha256().equals(runtimeManifestSha256))
				throw new IOException("Retained call pre-start differs from the selected owner attempt or runner");
			return store.readCallPreStartIndex(binding, logicalCallId).orElseThrow();
		}

		ClaimedSlot claimed = store.readCallSlotClaim(binding, logicalCallId);
		CallSlot slot = claimed.slot();
		if (slot.state() != CallSlot.State.CLAIMED)
			throw new IOException("Call must be claimed before pre-start publication: " + logicalCallId);
		Optional<EvidenceRef> beforeCallObservationRef =
			store.currentBeforeCallObservationRef(binding, logicalCallId);
		CallSlotClaimRecord claim = claimed.claim();
		CallPreStartRecord record = new CallPreStartRecord(3, logicalCallId, slot.requestRef(),
			slot.slotClaimRef(), claim.preparationOwnerRequestRef(), claim.preparationCycleRef(),
			beforeCallObservationRef, kind,
			attemptRef, ownerRequestRef, manager.generateRunId(), runtimeManifestSha256);
		EvidenceRef reference = store.appendImmutableJson("call-pre-starts", JSON.writeValueAsBytes(record));
		return store.publishCallPreStartIndex(binding, logicalCallId, reference);
	}

	/** Prevents construction. */
	private CallPreStartPublisher()
	{
	}
}

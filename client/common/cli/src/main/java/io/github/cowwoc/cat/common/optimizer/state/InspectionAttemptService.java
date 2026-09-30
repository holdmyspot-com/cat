package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.SnapshotJson;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes a claimed inspection's owner attempt before the pre-start boundary.
 */
public final class InspectionAttemptService
{
	private static final JsonMapper JSON = SnapshotJson.mapper();
	private static final int FIRST_ATTEMPT_ORDINAL = 0;

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds attempt publication to a registered campaign.
	 *
	 * @param locator the campaign's durable locator
	 * @param store the pinned evidence store
	 */
	public InspectionAttemptService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes or retrieves the first transport attempt for the claimed inspection.
	 *
	 * @param binding the registered campaign
	 * @param ownerRef the indexed inspection owner
	 * @return the indexed attempt reference
	 * @throws IOException if the owner or slot claim is absent or conflicts
	 */
	public EvidenceRef publish(CampaignBinding binding, EvidenceRef ownerRef) throws IOException
	{
		requireRegisteredStore(binding);
		PreparedInspectionCall prepared = new InspectionOwnerService(locator, store).load(binding, ownerRef);
		return publish(binding, prepared);
	}

	/**
	 * Publishes or retrieves an attempt using the owner already validated during the current operation.
	 *
	 * @param binding the registered campaign
	 * @param prepared the owner freshly loaded in this operation, before any external work or source change
	 * @return the indexed attempt reference
	 * @throws IOException if the owner or slot claim is absent or conflicts
	 */
	EvidenceRef publish(CampaignBinding binding, PreparedInspectionCall prepared) throws IOException
	{
		requireRegisteredStore(binding);
		EvidenceRef ownerRef = prepared.ownerRef();
		ContractInspectionOwner owner = prepared.owner();
		ClaimedSlot claimed = store.readCallSlotClaim(binding, owner.logicalCallId());
		if (!claimed.slot().requestRef().equals(owner.runRequestRef()) ||
			!claimed.claim().preparationOwnerRequestRef().equals(Optional.of(ownerRef)) ||
			!claimed.claim().preparationCycleRef().equals(Optional.of(owner.preparationCycleRef())))
			throw new IOException("Inspection slot claim conflicts with its indexed preparation owner");
		ProducerAttemptHeader header = new ProducerAttemptHeader(ProducerAttemptKind.INSPECTION,
			owner.logicalCallId(), owner.runRequestRef(), claimed.slotClaimRef(), ownerRef,
			Optional.of(owner.preparationCycleRef()));
		String key = OwnerAttemptIndexKey.deriveInspection(ownerRef, owner.runRequestRef(),
			claimed.slotClaimRef(), FIRST_ATTEMPT_ORDINAL);
		Optional<EvidenceRef> existing = store.readPreconfirmationIndex(binding,
			PreconfirmationIndexKind.CONTRACT_INSPECTION_ATTEMPT, key);
		if (existing.isPresent())
		{
			load(binding, existing.orElseThrow(), prepared);
			return existing.orElseThrow();
		}
		if (claimed.slot().state() != CallSlot.State.CLAIMED)
			throw new IOException("Inspection attempt needs a claimed, unstarted scheduler slot");
		ContractInspectionAttempt attempt = new ContractInspectionAttempt(header, owner.sourceCaptureRef(),
			FIRST_ATTEMPT_ORDINAL);
		EvidenceRef attemptRef = store.appendImmutableJson("contract-inspection-attempts",
			JSON.writeValueAsBytes(attempt));
		store.appendPreconfirmationIndex(binding, PreconfirmationIndexKind.CONTRACT_INSPECTION_ATTEMPT,
			key, attemptRef);
		load(binding, attemptRef, prepared);
		return attemptRef;
	}

	/**
	 * Reads and validates a claimed inspection attempt through its original index.
	 *
	 * @param binding the registered campaign
	 * @param attemptRef the immutable attempt
	 * @return the validated attempt
	 * @throws IOException if its owner, claim, or index is invalid
	 */
	public ContractInspectionAttempt load(CampaignBinding binding, EvidenceRef attemptRef) throws IOException
	{
		return load(binding, attemptRef, Optional.empty());
	}

	/**
	 * Validates an attempt against an owner already validated during the current operation.
	 *
	 * @param binding the registered campaign
	 * @param attemptRef the immutable attempt
	 * @param prepared the owner freshly loaded in this operation, before any external work or source change
	 * @return the validated attempt
	 * @throws IOException if its owner, claim, or index is invalid
	 */
	ContractInspectionAttempt load(CampaignBinding binding, EvidenceRef attemptRef,
		PreparedInspectionCall prepared) throws IOException
	{
		return load(binding, attemptRef, Optional.of(prepared));
	}

	/**
	 * Reads an attempt and validates its canonical identity, indexed owner, and current slot claim.
	 *
	 * @param binding the registered campaign
	 * @param attemptRef the immutable attempt
	 * @param retainedOwner the owner validated in this operation, or empty to load and validate it now
	 * @return the validated attempt
	 * @throws IOException if its owner, claim, or index is invalid
	 */
	private ContractInspectionAttempt load(CampaignBinding binding, EvidenceRef attemptRef,
		Optional<PreparedInspectionCall> retainedOwner) throws IOException
	{
		requireRegisteredStore(binding);
		Objects.requireNonNull(attemptRef, "attemptRef");
		if (!attemptRef.collection().equals("contract-inspection-attempts"))
			throw new IOException("Inspection attempt has the wrong evidence collection");
		try
		{
			byte[] bytes = store.readImmutableJson(attemptRef);
			ContractInspectionAttempt attempt = JSON.readValue(bytes, ContractInspectionAttempt.class);
			if (attempt == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(attempt)))
				throw new IOException("Inspection attempt is noncanonical");
			ProducerAttemptHeader header = attempt.header();
			PreparedInspectionCall prepared;
			if (retainedOwner.isPresent())
				prepared = retainedOwner.orElseThrow();
			else
				prepared = new InspectionOwnerService(locator, store).load(binding, header.ownerRequestRef());
			if (!prepared.ownerRef().equals(header.ownerRequestRef()))
				throw new IOException("Inspection attempt conflicts with its validated owner");
			ContractInspectionOwner owner = prepared.owner();
			ClaimedSlot claimed = store.readCallSlotClaim(binding, owner.logicalCallId());
			if (!header.logicalCallId().equals(owner.logicalCallId()) ||
				!header.runRequestRef().equals(owner.runRequestRef()) ||
				!header.slotClaimRef().equals(claimed.slotClaimRef()) ||
				!header.preparationCycleRef().equals(Optional.of(owner.preparationCycleRef())) ||
				!attempt.requestRef().equals(owner.sourceCaptureRef()) ||
				!claimed.claim().preparationOwnerRequestRef().equals(Optional.of(header.ownerRequestRef())) ||
				!claimed.claim().preparationCycleRef().equals(header.preparationCycleRef()))
				throw new IOException("Inspection attempt conflicts with its owner or slot claim");
			String key = OwnerAttemptIndexKey.deriveInspection(header.ownerRequestRef(),
				header.runRequestRef(), header.slotClaimRef(), attempt.attemptOrdinal());
			if (!store.readPreconfirmationIndex(binding, PreconfirmationIndexKind.CONTRACT_INSPECTION_ATTEMPT,
				key).equals(Optional.of(attemptRef)))
				throw new IOException("Inspection attempt has no matching create-once index");
			return attempt;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Inspection attempt is malformed", exception);
		}
	}

	/**
	 * Requires the locator and store to name the same registered campaign.
	 *
	 * @param binding the requested campaign
	 * @throws IOException if registration or pinned state differs
	 */
	private void requireRegisteredStore(CampaignBinding binding) throws IOException
	{
		CampaignStore registered = locator.requireRegisteredBinding(binding);
		if (!registered.directory().equals(store.directory()))
			throw new IOException("Inspection attempt store is not the registered campaign");
		store.lockedSnapshot(binding);
	}
}

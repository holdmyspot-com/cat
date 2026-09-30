package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Acquires exclusive campaign scheduling ownership and binds an existing invocation to its fence.
 */
public final class CampaignSchedulerLease
{
	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds lease acquisition to a locator-verified campaign.
	 *
	 * @param locator trusted workflow locator
	 * @param store pinned campaign store
	 */
	CampaignSchedulerLease(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Acquires the owner lock and durably binds an open invocation to a new fence.
	 *
	 * @param binding exact campaign binding
	 * @param invocationId existing command invocation
	 * @return live exclusive lease
	 * @throws IOException if the invocation lacks observation or pending stop, or binding fails
	 */
	public SchedulerLease acquire(CampaignBinding binding, String invocationId) throws IOException
	{
		locator.requireRegisteredBinding(binding);
		HostObservationRegistry registry = new HostObservationRegistry(locator.workflowRoot());
		registry.recover();
		Campaign campaign = store.lockedSnapshot(binding);
		InvocationStopRegistration registration = campaign.scheduler().invocations().stream().filter(value ->
			value.binding().equals(binding) && value.invocationId().equals(invocationId) &&
			value.status() == InvocationStopRegistration.Status.OPEN).findFirst().orElseThrow(() ->
			new IOException("Open invocation registration is missing: " + invocationId));
		if (registration.hostObservationRef().isPresent())
		{
			InvocationStopRegistration indexed = registry.find(store, binding,
				registration.hostObservationRef().orElseThrow());
			if (!indexed.registrationRef().equals(registration.registrationRef()))
				throw new IOException("Host observation is indexed to another invocation");
		}
		return new SchedulerLease(store, binding, invocationId);
	}

	/**
	 * Claims or resumes the exact immutable call claim under a live scheduler lease.
	 *
	 * @param lease exact live lease
	 * @param logicalCallId logical native call identity
	 * @param runRequestRef immutable C0R run request
	 * @param preparationOwnerRequestRef C4 preparation owner, when integrated
	 * @param preparationCycleRef C4 preparation cycle, when integrated
	 * @return retained claim and slot
	 * @throws IOException if authority or evidence is absent or conflicting
	 */
	public ClaimedSlot claimOrResumeCall(SchedulerLease lease, String logicalCallId, EvidenceRef runRequestRef,
		Optional<EvidenceRef> preparationOwnerRequestRef, Optional<EvidenceRef> preparationCycleRef)
		throws IOException
	{
		requireOwnedLease(lease);
		Objects.requireNonNull(preparationOwnerRequestRef, "preparationOwnerRequestRef");
		Objects.requireNonNull(preparationCycleRef, "preparationCycleRef");
		if (preparationOwnerRequestRef.isPresent() != preparationCycleRef.isPresent())
			throw new IOException("Preparation owner and cycle must be supplied together");
		Optional<PreparedInspectionCall> preparation = Optional.empty();
		if (preparationOwnerRequestRef.isPresent())
		{
			PreparedInspectionCall loaded = new InspectionOwnerService(locator, store).load(lease.binding(),
				preparationOwnerRequestRef.orElseThrow());
			if (!loaded.owner().preparationCycleRef().equals(preparationCycleRef.orElseThrow()) ||
				!loaded.owner().runRequestRef().equals(runRequestRef) ||
				!loaded.owner().logicalCallId().equals(logicalCallId))
				throw new IOException("Inspection owner does not authorize this logical call or preparation cycle");
			preparation = Optional.of(loaded);
		}
		return lease.claimOrResumeCall(logicalCallId, runRequestRef, preparation);
	}

	/**
	 * Reads only the immutable claim retained by this lease's current-fence slot.
	 *
	 * @param lease exact live lease
	 * @param logicalCallId retained logical call
	 * @return verified retained claim
	 * @throws IOException if the lease or retained claim is stale or corrupt
	 */
	public ClaimedSlot readClaim(SchedulerLease lease, String logicalCallId) throws IOException
	{
		requireOwnedLease(lease);
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), logicalCallId);
		if (claimed.slot().fence() != lease.fence())
			throw new IOException("Call slot is not bound to the current lease fence");
		return claimed;
	}

	/**
	 * Publishes the verified runner identity and enters the revocable start boundary for a claimed call.
	 *
	 * @param lease the current exclusive campaign scheduler owner
	 * @param logicalCallId the claimed native call
	 * @param kind the owner attempt kind
	 * @param attemptRef the indexed producer attempt
	 * @param ownerRequestRef the request named by the producer attempt
	 * @param manager the native run identity source
	 * @param artifact the runner artifact selected for dispatch
	 * @return the starting call slot with its indexed pre-start reference
	 * @throws IOException if the owner, attempt, runner identity, or stop state is invalid
	 */
	public CallSlot prepareCallStart(SchedulerLease lease, String logicalCallId, ProducerAttemptKind kind,
		EvidenceRef attemptRef, EvidenceRef ownerRequestRef, HarnessRunManager manager, Path artifact)
		throws IOException
	{
		ClaimedSlot claimed = readClaim(lease, logicalCallId);
		if (claimed.slot().state() != CallSlot.State.CLAIMED)
			throw new IOException("Call start must reconcile an existing boundary: " + logicalCallId);
		EvidenceRef preStart = CallPreStartPublisher.publish(store, lease.binding(), logicalCallId, kind,
			attemptRef, ownerRequestRef, manager, artifact);
		return lease.prepareStart(logicalCallId, preStart);
	}

	/**
	 * Revalidates index ownership and the live lease before an authoritative call operation.
	 *
	 * @param lease requested lease
	 * @throws IOException if the lease or locator is stale
	 */
	private void requireOwnedLease(SchedulerLease lease) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		lease.requireLive();
		if (!lease.binding().realDirectory().equals(store.directory()))
			throw new IOException("Scheduler lease belongs to another pinned campaign");
		locator.requireRegisteredBinding(lease.binding());
	}
}

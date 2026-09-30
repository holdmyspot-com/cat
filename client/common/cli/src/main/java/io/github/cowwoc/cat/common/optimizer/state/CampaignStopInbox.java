package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable registration and stop inbox scoped to one pinned campaign.
 */
public final class CampaignStopInbox
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final CampaignLocator locator;
	private final CampaignStore store;
	private final HostObservationRegistry hostRegistry;

	/**
	 * Binds the inbox to one locator-verified campaign and workflow root.
	 *
	 * @param locator trusted workflow locator
	 * @param store pinned campaign store
	 */
	CampaignStopInbox(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
		hostRegistry = new HostObservationRegistry(locator.workflowRoot());
	}

	/**
	 * Publishes one canonical C9 host observation and binds it to the original OPEN registration.
	 *
	 * @param binding exact campaign binding
	 * @param invocationId command invocation identity
	 * @param registrationRef original registration evidence
	 * @param canonicalObservationBytes canonical C9 observation bytes
	 * @return immutable observation reference
	 * @throws IOException if the registration, bytes, or one-use identity is invalid
	 */
	public EvidenceRef publishHostObservation(CampaignBinding binding, String invocationId,
		EvidenceRef registrationRef, byte[] canonicalObservationBytes) throws IOException
	{
		locator.requireRegisteredBinding(binding);
		return hostRegistry.publish(store, binding, invocationId, registrationRef,
			canonicalObservationBytes);
	}

	/**
	 * Finds only the verified OPEN registration originally bound to an observation.
	 *
	 * @param binding exact campaign binding
	 * @param observationRef immutable observation reference
	 * @return original OPEN registration
	 * @throws IOException if the association is absent, closed, ambiguous, or corrupt
	 */
	public InvocationStopRegistration findByHostObservation(CampaignBinding binding,
		EvidenceRef observationRef) throws IOException
	{
		locator.requireRegisteredBinding(binding);
		return hostRegistry.find(store, binding, observationRef);
	}

	/**
	 * Persists an exact command invocation before any host listener can be exposed.
	 *
	 * @param binding exact campaign binding
	 * @param invocationId command invocation identity
	 * @return original immutable registration reference
	 * @throws IOException if state or registration publication fails
	 */
	public EvidenceRef beginInvocation(CampaignBinding binding, String invocationId) throws IOException
	{
		if (invocationId == null || invocationId.isBlank())
			throw new IllegalArgumentException("Invocation id must be non-blank");
		locator.requireRegisteredBinding(binding);
		Campaign snapshot = store.lockedSnapshot(binding);
		requireActionable(snapshot);
		for (InvocationStopRegistration existing : snapshot.scheduler().invocations())
			if (existing.invocationId().equals(invocationId))
				throw new IOException("Invocation id is already registered: " + invocationId);
		EvidenceRef reference = store.appendImmutableJson("invocation-stop-registrations",
			JSON.writeValueAsBytes(new RegistrationEvidence(1, binding.campaignId(),
				binding.realDirectory().toString(), invocationId, snapshot.transitionCounter())));
		SchedulerState next = store.updateScheduler(binding, campaign ->
		{
			requireActionable(campaign);
			if (campaign.transitionCounter() != snapshot.transitionCounter() ||
				campaign.scheduler().invocations().stream().anyMatch(existing ->
					existing.invocationId().equals(invocationId)))
				throw new IOException("Invocation registration is stale or duplicated: " + invocationId);
			Optional<Long> observed = Optional.empty();
			if (campaign.scheduler().fence() > 0)
				observed = Optional.of(campaign.scheduler().fence());
			InvocationStopRegistration registration = new InvocationStopRegistration(reference, binding,
				invocationId, campaign.transitionCounter(), observed, Optional.empty(),
				InvocationStopRegistration.Status.OPEN, Optional.empty());
			List<InvocationStopRegistration> invocations = new ArrayList<>(campaign.scheduler().invocations());
			invocations.add(registration);
			return new SchedulerState(campaign.scheduler().fence(), invocations, campaign.scheduler().stops(),
				campaign.scheduler().slots(), campaign.scheduler().barrier());
		});
		return next.invocations().getLast().registrationRef();
	}

	/**
	 * Lists original references for open registrations without transferring their authority.
	 *
	 * @param binding exact campaign binding
	 * @return original open registration references
	 * @throws IOException if campaign state is unavailable
	 */
	public List<EvidenceRef> listOpenInvocations(CampaignBinding binding) throws IOException
	{
		locator.requireRegisteredBinding(binding);
		return hostRegistry.locked(() -> store.lockedSnapshot(binding).scheduler().invocations().stream().filter(
			registration -> registration.status() == InvocationStopRegistration.Status.OPEN).map(
			InvocationStopRegistration::registrationRef).toList());
	}

	/**
	 * Durably requests one interruption for an exact open invocation.
	 *
	 * @param binding exact campaign binding
	 * @param invocationId exact command invocation
	 * @param reason first authoritative stop reason
	 * @return original idempotent stop request
	 * @throws IOException if the invocation is closed, foreign, or stale
	 */
	public StopRequest submit(CampaignBinding binding, String invocationId, String reason) throws IOException
	{
		if (invocationId == null || invocationId.isBlank() || reason == null || reason.isBlank())
			throw new IllegalArgumentException("Stop invocation and reason must be non-blank");
		Campaign snapshot = store.lockedSnapshot(binding);
		requireActionable(snapshot);
		InvocationStopRegistration registration = openRegistration(snapshot.scheduler(), binding, invocationId);
		verifyCurrentFence(snapshot.scheduler(), registration);
		for (StopRequest stop : snapshot.scheduler().stops())
			if (stop.sourceInvocationId().equals(invocationId))
				return stop;
		EvidenceRef reference = store.appendImmutableJson("stop-requests", JSON.writeValueAsBytes(
			new StopEvidence(1, binding.campaignId(), invocationId, reason)));
		SchedulerState next = store.updateScheduler(binding, campaign ->
		{
			requireActionable(campaign);
			InvocationStopRegistration current = openRegistration(campaign.scheduler(), binding, invocationId);
			verifyCurrentFence(campaign.scheduler(), current);
			for (StopRequest stop : campaign.scheduler().stops())
				if (stop.sourceInvocationId().equals(invocationId))
					return campaign.scheduler();
			StopRequest stop = new StopRequest(reference, invocationId, current.boundFence(), reason,
				StopRequest.Status.PENDING, Optional.empty(), Optional.empty(), Optional.empty());
			List<StopRequest> stops = new ArrayList<>(campaign.scheduler().stops());
			stops.add(stop);
			return new SchedulerState(campaign.scheduler().fence(), campaign.scheduler().invocations(), stops,
				campaign.scheduler().slots(), campaign.scheduler().barrier());
		});
		for (StopRequest stop : next.stops())
			if (stop.sourceInvocationId().equals(registration.invocationId()))
				return stop;
		throw new AssertionError("Committed stop is missing");
	}

	/**
	 * Verifies the original durable stop bytes before a lifecycle transition consumes them.
	 *
	 * @param store pinned evidence store
	 * @param binding exact campaign identity
	 * @param stop adopted scheduler stop
	 * @throws IOException if the source evidence differs from the retained stop
	 */
	static void verifyStopEvidence(CampaignStore store, CampaignBinding binding, StopRequest stop)
		throws IOException
	{
		if (!stop.stopRef().collection().equals("stop-requests"))
			throw new IOException("Interruption stop has the wrong evidence collection");
		byte[] bytes = store.readImmutableJson(stop.stopRef());
		try
		{
			StopEvidence evidence = JSON.readValue(bytes, StopEvidence.class);
			if (evidence == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(evidence)) ||
				evidence.schemaVersion() != 1 || !evidence.campaignId().equals(binding.campaignId()) ||
				!evidence.invocationId().equals(stop.sourceInvocationId()) ||
				!evidence.reason().equals(stop.reason()))
				throw new IOException("Interruption stop differs from its original evidence");
		}
		catch (RuntimeException failure)
		{
			throw new IOException("Interruption stop evidence is malformed", failure);
		}
	}

	/**
	 * Claims one pending original or predecessor-fence stop without creating a second stop.
	 *
	 * @param lease live scheduler owner
	 * @return adopted stop, or empty when no eligible stop exists
	 * @throws IOException if the lease, source registration, or stop fence is stale
	 */
	public Optional<StopRequest> adoptPending(SchedulerLease lease) throws IOException
	{
		if (lease == null)
			throw new NullPointerException("lease");
		lease.requireLive();
		Campaign retained = store.lockedSnapshot(lease.binding());
		if (retained.scheduler().fence() != lease.fence())
			throw new IOException("Scheduler owner fence is stale: " + lease.fence());
		openRegistration(retained.scheduler(), lease.binding(), lease.invocationId());
		Optional<StopRequest> committed = committedPendingStop(retained);
		if (committed.isPresent())
			return committed;
		SchedulerState next = store.updateScheduler(lease.binding(), campaign ->
		{
			if (campaign.scheduler().fence() != lease.fence())
				throw new IOException("Scheduler owner fence is stale: " + lease.fence());
			openRegistration(campaign.scheduler(), lease.binding(), lease.invocationId());
			List<StopRequest> stops = new ArrayList<>(campaign.scheduler().stops());
			int selected = -1;
			for (int index = 0; index < stops.size(); ++index)
			{
				StopRequest stop = stops.get(index);
				if (stop.status() != StopRequest.Status.PENDING)
					continue;
				InvocationStopRegistration source = openRegistration(campaign.scheduler(), lease.binding(),
					stop.sourceInvocationId());
				if (stop.sourceFence().isEmpty())
				{
					// Another waiting invocation's token-only stop cannot interrupt this lease.
					if (!source.invocationId().equals(lease.invocationId()))
						continue;
					if (source.registeredCounter() != campaign.transitionCounter() ||
						source.observedOwnerFence().isPresent() || lease.fence() != 1)
						continue;
				}
				else if (stop.sourceFence().orElseThrow() > lease.fence())
					throw new IOException("Pending stop has a future source fence");
				if (source.registeredCounter() != campaign.transitionCounter() &&
					(stop.sourceFence().isEmpty() || campaign.scheduler().slots().stream().noneMatch(slot ->
						slot.fence() == stop.sourceFence().orElseThrow())))
					continue;
				if (selected >= 0)
					throw new IOException("Multiple pending stops are ambiguous for one owner");
				selected = index;
			}
			if (selected < 0)
				return campaign.scheduler();
			StopRequest selectedStop = stops.get(selected);
			if (selectedStop.adoptingFence().equals(Optional.of(lease.fence())))
				return campaign.scheduler();
			stops.set(selected, new StopRequest(selectedStop.stopRef(), selectedStop.sourceInvocationId(),
				selectedStop.sourceFence(), selectedStop.reason(), selectedStop.status(), Optional.of(lease.fence()),
				selectedStop.logicalCallId(), selectedStop.interruptionReceiptId()));
			return new SchedulerState(lease.fence(), campaign.scheduler().invocations(), stops,
				campaign.scheduler().slots(), campaign.scheduler().barrier());
		});
		return next.stops().stream().filter(stop -> stop.status() == StopRequest.Status.PENDING &&
			stop.adoptingFence().equals(Optional.of(lease.fence()))).findFirst();
	}

	/**
	 * Finds a committed interruption awaiting ACK without changing its original adopting fence.
	 *
	 * @param campaign current interrupted campaign
	 * @return original pending stop, if its embedded transition already committed
	 * @throws IOException if the retained producer conflicts with the scheduler stop
	 */
	private Optional<StopRequest> committedPendingStop(Campaign campaign) throws IOException
	{
		if (campaign.status() != CampaignStatus.INTERRUPTED)
			return Optional.empty();
		for (TransitionReceipt receipt : campaign.transitionReceipts())
		{
			if (receipt.kind() != TransitionKind.INTERRUPTION ||
				receipt.resultingCounter() != campaign.transitionCounter())
				continue;
			InterruptionRecord producer = InterruptionRecord.decode(store.readImmutableJson(
				receipt.producerRecordRef()));
			if (producer.reason() != InterruptionRecord.Reason.HOST_OR_TOKEN_STOP)
				return Optional.empty();
			for (StopRequest stop : campaign.scheduler().stops())
			{
				if (!stop.stopRef().equals(producer.stopRef().orElseThrow()) ||
					!stop.adoptingFence().equals(Optional.of(producer.adoptingFence())))
					continue;
				if (stop.status() == StopRequest.Status.PENDING)
					return Optional.of(stop);
				if (stop.status() == StopRequest.Status.ACKNOWLEDGED)
				{
					verifyCommittedInterruption(store, receipt.binding(), campaign, stop,
						receipt.result().receiptId(), producer.logicalCallId());
					if (!stop.interruptionReceiptId().equals(Optional.of(receipt.result().receiptId())) ||
						!stop.logicalCallId().equals(producer.logicalCallId()))
						throw new IOException("Acknowledged interruption stop conflicts with its receipt");
					return Optional.empty();
				}
			}
			throw new IOException("Committed interruption lost its pending stop");
		}
		return Optional.empty();
	}

	/**
	 * Acknowledges only the exact committed host/token interruption embedded in pinned campaign state.
	 *
	 * @param lease live scheduler owner
	 * @param stopRef original stop evidence reference
	 * @param interruptionReceiptId embedded transition receipt identity
	 * @param logicalCallId reconciled call slot, or absent for a no-call stop
	 * @return committed acknowledgment
	 * @throws IOException if any identity or committed evidence is missing or inconsistent
	 */
	public StopRequest acknowledge(SchedulerLease lease, EvidenceRef stopRef, String interruptionReceiptId,
		Optional<String> logicalCallId) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(stopRef, "stopRef");
		if (interruptionReceiptId == null || interruptionReceiptId.isBlank())
			throw new IllegalArgumentException("Interruption receipt ID must be non-blank");
		Objects.requireNonNull(logicalCallId, "logicalCallId");
		lease.requireLive();
		SchedulerState next = store.updateAcknowledgedInterruptionStop(lease.binding(), stopRef, campaign ->
		{
			SchedulerState scheduler = campaign.scheduler();
			if (scheduler.fence() != lease.fence())
				throw new IOException("Scheduler owner fence is stale: " + lease.fence());
			int stopIndex = -1;
			for (int index = 0; index < scheduler.stops().size(); ++index)
				if (scheduler.stops().get(index).stopRef().equals(stopRef))
				{
					if (stopIndex >= 0)
						throw new IOException("Duplicate stop reference in campaign state");
					stopIndex = index;
				}
			if (stopIndex < 0)
				throw new IOException("Stop reference is missing or foreign");
			StopRequest stop = scheduler.stops().get(stopIndex);
			if (stop.adoptingFence().isEmpty() || stop.adoptingFence().orElseThrow() > lease.fence())
				throw new IOException("Stop has no adopting fence or a future owner fence");
			verifyCommittedInterruption(store, lease.binding(), campaign, stop, interruptionReceiptId, logicalCallId);
			if (stop.status() == StopRequest.Status.ACKNOWLEDGED)
			{
				if (!stop.interruptionReceiptId().equals(Optional.of(interruptionReceiptId)) ||
					!stop.logicalCallId().equals(logicalCallId))
					throw new IOException("Acknowledged stop has a different receipt or slot");
				return scheduler;
			}
			if (stop.logicalCallId().isPresent() && !stop.logicalCallId().equals(logicalCallId))
				throw new IOException("Stop slot identity conflicts with the interruption");
			List<StopRequest> stops = new ArrayList<>(scheduler.stops());
			stops.set(stopIndex, new StopRequest(stop.stopRef(), stop.sourceInvocationId(), stop.sourceFence(),
				stop.reason(), StopRequest.Status.ACKNOWLEDGED, stop.adoptingFence(), logicalCallId,
				Optional.of(interruptionReceiptId)));
			return new SchedulerState(scheduler.fence(), scheduler.invocations(), stops, scheduler.slots(),
				scheduler.barrier());
		});
		return next.stops().stream().filter(stop -> stop.stopRef().equals(stopRef)).findFirst().orElseThrow();
	}

	/**
	 * Rehashes and validates the sole embedded receipt and its C7 host/token producer under the state lock.
	 *
	 * @param store pinned campaign store used to rehash producer evidence
	 * @param binding exact campaign binding already verified by the caller
	 * @param campaign locked campaign
	 * @param stop adopted stop
	 * @param receiptId requested embedded receipt identity
	 * @param logicalCallId exact reconciled slot identity
	 * @throws IOException if the receipt or producer does not prove this stop
	 */
	static void verifyCommittedInterruption(CampaignStore store, CampaignBinding binding, Campaign campaign,
		StopRequest stop, String receiptId, Optional<String> logicalCallId) throws IOException
	{
		TransitionReceipt matched = null;
		for (TransitionReceipt receipt : campaign.transitionReceipts())
			if (receipt.result().receiptId().equals(receiptId))
			{
				if (matched != null)
					throw new IOException("Ambiguous embedded interruption receipt ID");
				matched = receipt;
			}
		if (matched == null || matched.kind() != TransitionKind.INTERRUPTION ||
			!matched.binding().equals(binding) || matched.resultingCounter() != campaign.transitionCounter() ||
			matched.result().phase() != CampaignStatus.INTERRUPTED ||
			campaign.status() != CampaignStatus.INTERRUPTED)
			throw new IOException("Matching committed interruption receipt is missing");
		if (!matched.producerRecordRef().collection().equals("campaign-interruption-records"))
			throw new IOException("Interruption producer has the wrong collection");
		InterruptionRecord producer = InterruptionRecord.decode(store.readImmutableJson(
			matched.producerRecordRef()));
		if (!Objects.equals(producer.binding(), matched.binding()) ||
			producer.expectedCounter() != matched.expectedCounter() ||
			producer.reason() != InterruptionRecord.Reason.HOST_OR_TOKEN_STOP ||
			!Objects.equals(producer.stopRef(), Optional.of(stop.stopRef())) ||
			!Optional.empty().equals(producer.sourceReviewRef()) ||
			producer.adoptingFence() != stop.adoptingFence().orElseThrow() ||
			!Objects.equals(producer.logicalCallId(), logicalCallId))
			throw new IOException("Interruption producer does not prove the adopted host/token stop");
		if (logicalCallId.isPresent())
		{
			if (campaign.scheduler().slots().stream().noneMatch(slot ->
				slot.logicalCallId().equals(logicalCallId.orElseThrow()) && slot.fence() <=
				stop.adoptingFence().orElseThrow()))
				throw new IOException("Interruption call slot is missing or foreign");
		}
		else if (campaign.scheduler().slots().stream().anyMatch(slot ->
			slot.fence() == stop.adoptingFence().orElseThrow()))
			throw new IOException("No-call interruption conflicts with an owner call slot");
	}

	/**
	 * Rejects a callback from a displaced bound invocation before idempotent stop lookup.
	 *
	 * @param scheduler current scheduler state
	 * @param registration source invocation registration
	 * @throws IOException if its owner fence is stale
	 */
	private static void verifyCurrentFence(SchedulerState scheduler,
		InvocationStopRegistration registration) throws IOException
	{
		if (registration.boundFence().isPresent() &&
			registration.boundFence().orElseThrow() != scheduler.fence())
			throw new IOException("Invocation has a stale owner fence: " + registration.invocationId());
	}

	/**
	 * Rejects new invocation and stop authority after cancellation or terminal closure.
	 *
	 * @param campaign locked campaign snapshot
	 * @throws IOException if the campaign has no actionable command phase
	 */
	private static void requireActionable(Campaign campaign) throws IOException
	{
		if (campaign.status() == CampaignStatus.CANCELLED || campaign.status() == CampaignStatus.CERTIFIED ||
			campaign.status() == CampaignStatus.NOT_CERTIFIED_PLATEAU)
			throw new IOException("Cancelled or terminal campaign cannot accept an invocation or stop");
	}

	/**
	 * Closes only the exact registration after the command has settled.
	 *
	 * @param binding exact campaign binding
	 * @param invocationId exact command invocation
	 * @throws IOException if the invocation is foreign or absent
	 */
	public void finishInvocation(CampaignBinding binding, String invocationId) throws IOException
	{
		hostRegistry.locked(() -> store.updateScheduler(binding, campaign ->
		{
			List<InvocationStopRegistration> invocations = new ArrayList<>(campaign.scheduler().invocations());
			for (int index = 0; index < invocations.size(); ++index)
			{
				InvocationStopRegistration current = invocations.get(index);
				if (current.invocationId().equals(invocationId) && current.binding().equals(binding))
				{
					if (current.status() == InvocationStopRegistration.Status.CLOSED)
						return campaign.scheduler();
					if (campaign.scheduler().stops().stream().anyMatch(stop ->
						stop.sourceInvocationId().equals(invocationId) &&
						stop.status() == StopRequest.Status.PENDING))
						throw new IOException("Invocation has a pending stop: " + invocationId);
					if (current.boundFence().isPresent() && campaign.scheduler().slots().stream().anyMatch(slot ->
						slot.fence() <= current.boundFence().orElseThrow() &&
						slot.state() != CallSlot.State.ADOPTED))
						throw new IOException("Invocation has an unadopted native call: " + invocationId);
					invocations.set(index, new InvocationStopRegistration(current.registrationRef(), binding,
						invocationId, current.registeredCounter(), current.observedOwnerFence(),
						current.hostObservationRef(), InvocationStopRegistration.Status.CLOSED,
						current.boundFence()));
					return new SchedulerState(campaign.scheduler().fence(), invocations, campaign.scheduler().stops(),
						campaign.scheduler().slots(), campaign.scheduler().barrier());
				}
			}
			throw new IOException("Invocation registration is missing: " + invocationId);
		}));
	}

	/**
	 * Resolves one open registration for a bound invocation.
	 *
	 * @param scheduler current scheduler state
	 * @param binding exact campaign binding
	 * @param invocationId command invocation identity
	 * @return open registration
	 * @throws IOException if it is missing, closed, or foreign
	 */
	private static InvocationStopRegistration openRegistration(SchedulerState scheduler,
		CampaignBinding binding, String invocationId) throws IOException
	{
		for (InvocationStopRegistration registration : scheduler.invocations())
			if (registration.invocationId().equals(invocationId) && registration.binding().equals(binding) &&
				registration.status() == InvocationStopRegistration.Status.OPEN)
				return registration;
		throw new IOException("Open invocation registration is missing or foreign: " + invocationId);
	}

	private record RegistrationEvidence(int schemaVersion, String campaignId, String campaignDirectory,
		String invocationId, long registeredCounter)
	{
	}

	private record StopEvidence(int schemaVersion, String campaignId, String invocationId, String reason)
	{
	}
}

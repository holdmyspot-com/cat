package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Publishes and commits a host stop as one recoverable campaign interruption.
 */
public final class CampaignInterruptionService
{
	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds interruption publication to a registered campaign repository.
	 *
	 * @param locator trusted campaign locator
	 * @param store pinned campaign store
	 */
	public CampaignInterruptionService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Commits and acknowledges one adopted host stop, including a committed stop after owner recovery.
	 *
	 * @param lease live scheduler owner
	 * @param stop original adopted host stop
	 * @param logicalCallId affected call, or empty when no call was admitted
	 * @return original committed interruption result
	 * @throws IOException if the stop, call, or transition authority conflicts
	 */
	public TransitionResult commitHostStop(SchedulerLease lease, StopRequest stop,
		Optional<String> logicalCallId) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(stop, "stop");
		Objects.requireNonNull(logicalCallId, "logicalCallId");
		locator.requireRegisteredBinding(lease.binding());
		if (!store.directory().equals(lease.binding().realDirectory()))
			throw new IOException("Interruption lease belongs to another campaign store");
		lease.requireLive();
		Campaign current = store.lockedSnapshot(lease.binding());
		StopRequest retained = current.scheduler().stops().stream().filter(candidate ->
			candidate.stopRef().equals(stop.stopRef())).findFirst().orElseThrow(() ->
			new IOException("Adopted interruption stop is missing"));
		if (!retained.sourceInvocationId().equals(stop.sourceInvocationId()) ||
			!retained.sourceFence().equals(stop.sourceFence()) ||
			!retained.reason().equals(stop.reason()) ||
			!retained.adoptingFence().equals(stop.adoptingFence()) ||
			retained.adoptingFence().isEmpty() ||
			retained.adoptingFence().orElseThrow() > lease.fence())
			throw new IOException("Interruption stop conflicts with its original adopting fence");
		long expectedCounter = current.transitionCounter();
		EvidenceRef producerRef;
		if (current.status() == CampaignStatus.INTERRUPTED)
		{
			TransitionReceipt receipt = current.transitionReceipts().stream().filter(candidate ->
				candidate.kind() == TransitionKind.INTERRUPTION &&
				candidate.resultingCounter() == current.transitionCounter()).findFirst().orElseThrow(() ->
				new IOException("Interrupted campaign has no matching committed receipt"));
			InterruptionRecord producer = InterruptionRecord.decode(store.readImmutableJson(
				receipt.producerRecordRef()));
			if (producer.reason() != InterruptionRecord.Reason.HOST_OR_TOKEN_STOP ||
				!producer.stopRef().equals(Optional.of(stop.stopRef())) ||
				!producer.logicalCallId().equals(logicalCallId) ||
				producer.adoptingFence() != retained.adoptingFence().orElseThrow())
				throw new IOException("Committed interruption belongs to another stop or call");
			expectedCounter = receipt.expectedCounter();
			producerRef = receipt.producerRecordRef();
		}
		else
		{
			if (!retained.adoptingFence().equals(Optional.of(lease.fence())))
				throw new IOException("New interruption requires this scheduler owner fence");
			InterruptionRecord producer = new InterruptionRecord(1, lease.binding(), expectedCounter,
				InterruptionRecord.Reason.HOST_OR_TOKEN_STOP, Optional.of(stop.stopRef()), Optional.empty(),
				lease.fence(), logicalCallId);
			producerRef = store.appendImmutableJson("campaign-interruption-records", producer.encode());
		}
		TransitionResult result = new CampaignTransitions(store).apply(new TransitionRequest(lease.binding(),
			expectedCounter, TransitionKind.INTERRUPTION, producerRef));
		locator.openStopInbox(lease.binding()).acknowledge(lease, stop.stopRef(), result.receiptId(),
			logicalCallId);
		return result;
	}
}

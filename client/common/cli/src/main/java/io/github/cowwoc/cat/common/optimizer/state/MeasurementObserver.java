package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes complete model-call boundary conditions and their durable admission decision.
 */
public final class MeasurementObserver
{
	private final CampaignLocator locator;
	private final CampaignStore store;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Binds observations to one locator-verified campaign.
	 *
	 * @param locator owning workflow locator
	 * @param store pinned campaign store
	 */
	MeasurementObserver(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes known and explicitly limited conditions before the first unconfirmed inspection.
	 *
	 * @param lease live exclusive scheduler owner
	 * @param prepared indexed inspection owner and preparation cycle
	 * @return indexed complete observation for the inspection call
	 * @throws IOException if preparation authority or retained source evidence conflicts
	 */
	public EvidenceRef beforeInspectionCall(SchedulerLease lease, PreparedInspectionCall prepared)
		throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(prepared, "prepared");
		requireLiveCampaignLease(lease);
		PreparedInspectionCall retained = new InspectionOwnerService(locator, store).load(lease.binding(),
			prepared.ownerRef());
		if (!retained.equals(prepared))
			throw new IOException("Inspection observation identifies another prepared call");
		return publishInspectionObservation(lease, retained);
	}

	/**
	 * Publishes the before-call observation using preparation validated in the current operation.
	 *
	 * @param lease live exclusive scheduler owner
	 * @param prepared the owner freshly loaded in this operation, before any external work or source change
	 * @return indexed complete observation for the inspection call
	 * @throws IOException if the lease or current preparation authority conflicts
	 */
	EvidenceRef recordInspectionObservation(SchedulerLease lease, PreparedInspectionCall prepared)
		throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(prepared, "prepared");
		requireLiveCampaignLease(lease);
		return publishInspectionObservation(lease, prepared);
	}

	/**
	 * Retains the complete before-call conditions for an already validated inspection and live campaign lease.
	 *
	 * @param lease the verified current scheduler owner
	 * @param prepared the indexed inspection validated during the current operation
	 * @return indexed complete observation for the inspection call
	 * @throws IOException if the current preparation authority conflicts or evidence cannot be retained
	 */
	private EvidenceRef publishInspectionObservation(SchedulerLease lease, PreparedInspectionCall prepared)
		throws IOException
	{
		Campaign campaign = store.lockedSnapshot(lease.binding());
		PreparationAuthority authority = prepared.cycle().authority();
		if (campaign.status() != CampaignStatus.AWAITING_CONTRACT_CONFIRMATION ||
			campaign.activeLineageId() != null ||
			authority.transitionCounter() != campaign.transitionCounter() ||
			authority.preparationGeneration() != campaign.preparationScope().generation() ||
			!authority.sourceChangeRef().equals(campaign.preparationScope().sourceChangeRef()))
			throw new IOException("Inspection observation has stale preparation authority");
		EvidenceRef modelRef = store.appendImmutableJson("measurement-values",
			JSON.writeValueAsBytes(prepared.request().modelProfile()));
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> values =
			new EnumMap<>(MeasurementConditions.Kind.class);
		for (MeasurementConditions.Kind kind : MeasurementConditions.Kind.values())
			values.put(kind, new MeasurementConditions.Value.Unknown(
				"Condition has not been observed at the unconfirmed inspection boundary"));
		values.put(MeasurementConditions.Kind.MODEL_PROFILE,
			new MeasurementConditions.Value.Known(modelRef));
		values.put(MeasurementConditions.Kind.PROMPT_SOURCES,
			new MeasurementConditions.Value.Known(
				prepared.request().context().snapshot().snapshotManifestRef()));
		PreparationMeasurementObservation observation = PreparationMeasurementObservation.from(
			campaign.campaignId(), prepared.owner().preparationCycleRef(), prepared.ownerRef(),
			prepared.owner().logicalCallId(), MeasurementObservation.Stage.BEFORE_CALL,
			new MeasurementConditions(values));
		EvidenceRef observationRef = store.appendImmutableJson("preparation-measurement-observations",
			JSON.writeValueAsBytes(observation));
		return store.appendPreconfirmationIndex(lease.binding(),
			PreconfirmationIndexKind.CONTRACT_INSPECTION_BEFORE_OBSERVATION,
			PreparationMeasurementObservation.indexKey(observation.cycleRef(), observation.logicalCallId()),
			observationRef);
	}

	/**
	 * Compares and retains complete conditions immediately before one confirmed model call.
	 *
	 * @param lease live exclusive scheduler owner
	 * @param logicalCallId exact call about to seek admission
	 * @param conditions known values or explicit limitations for every material condition
	 * @return accepted observation or a durable material-change pause
	 * @throws IOException if the claim, epoch, owner, or retained evidence is invalid
	 */
	public MeasurementObservationDecision beforeCall(SchedulerLease lease, String logicalCallId,
		MeasurementConditions conditions) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		requireLiveCampaignLease(lease);
		Campaign campaign = store.lockedSnapshot(lease.binding());
		MeasurementState measurement = campaign.measurementState();
		if (measurement == null || campaign.activeLineageId() == null)
			throw new IOException("Before-call measurement requires a confirmed campaign");
		MeasurementObservation observation = MeasurementObservation.from(campaign.campaignId(),
			campaign.activeLineageId(), measurement.epochRef(), logicalCallId,
			MeasurementObservation.Stage.BEFORE_CALL, conditions);
		EvidenceRef observationRef = store.appendImmutableJson("dynamic-baseline-roots",
			JSON.writeValueAsBytes(observation));
		MeasurementObservationDecision decision = store.commitBeforeCallObservation(lease.binding(), observationRef);
		if (decision.status() == MeasurementObservationDecision.Status.ACCEPTED)
		{
			MeasurementEpoch epoch = JSON.readValue(store.readImmutableJson(measurement.epochRef()),
				MeasurementEpoch.class);
			LineageKey lineage = new LineageKey(observation.lineageId(), epoch.contractRef().evidenceId());
			store.appendCreateOnceIndex("measurement-before-call-index",
				MeasurementObservation.callIndexKey(measurement.epochRef(), logicalCallId),
				new LineageEvidenceRef(lineage, observationRef));
		}
		return decision;
	}

	/**
	 * Compares complete conditions after an adopted confirmed model call.
	 *
	 * @param lease live exclusive scheduler owner
	 * @param logicalCallId adopted call whose completion was observed
	 * @param conditions known values or explicit limitations for every material condition
	 * @return retained observation and any durable material-change pause
	 * @throws IOException if the call, epoch, or retained evidence is invalid
	 */
	public MeasurementObservationDecision afterCall(SchedulerLease lease, String logicalCallId,
		MeasurementConditions conditions) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		requireLiveCampaignLease(lease);
		Campaign campaign = store.lockedSnapshot(lease.binding());
		MeasurementState measurement = campaign.measurementState();
		if (measurement == null || campaign.activeLineageId() == null)
			throw new IOException("After-call measurement requires a confirmed campaign");
		MeasurementObservation observation = MeasurementObservation.from(campaign.campaignId(),
			campaign.activeLineageId(), measurement.epochRef(), logicalCallId,
			MeasurementObservation.Stage.AFTER_CALL, conditions);
		EvidenceRef observationRef = store.appendImmutableJson("dynamic-baseline-roots",
			JSON.writeValueAsBytes(observation));
		MeasurementObservationDecision decision = store.commitAfterCallObservation(lease.binding(), observationRef);
		if (decision.status() == MeasurementObservationDecision.Status.ACCEPTED)
		{
			MeasurementEpoch epoch = JSON.readValue(store.readImmutableJson(measurement.epochRef()),
				MeasurementEpoch.class);
			LineageKey lineage = new LineageKey(observation.lineageId(), epoch.contractRef().evidenceId());
			store.appendCreateOnceIndex("measurement-after-call-index",
				MeasurementObservation.callIndexKey(measurement.epochRef(), logicalCallId),
				new LineageEvidenceRef(lineage, observationRef));
		}
		return decision;
	}

	/**
	 * Restores a paused confirmed campaign when current conditions match its retained baseline.
	 *
	 * @param lease live exclusive scheduler owner
	 * @param logicalCallId pending call to admit under restored conditions
	 * @param conditions complete current observation
	 * @return accepted restored before-call observation
	 * @throws IOException if the pause, conditions, or retained authority prevents restoration
	 */
	public MeasurementObservationDecision restoreBeforeCall(SchedulerLease lease, String logicalCallId,
		MeasurementConditions conditions) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		requireLiveCampaignLease(lease);
		Campaign campaign = store.lockedSnapshot(lease.binding());
		MeasurementState measurement = campaign.measurementState();
		if (measurement == null || campaign.activeLineageId() == null)
			throw new IOException("Restoration requires a confirmed campaign");
		MeasurementObservation observation = MeasurementObservation.from(campaign.campaignId(),
			campaign.activeLineageId(), measurement.epochRef(), logicalCallId,
			MeasurementObservation.Stage.BEFORE_CALL, conditions);
		EvidenceRef observationRef = store.appendImmutableJson("dynamic-baseline-roots",
			JSON.writeValueAsBytes(observation));
		MeasurementObservationDecision decision = store.commitRestoredObservation(lease.binding(), observationRef);
		MeasurementEpoch epoch = JSON.readValue(store.readImmutableJson(measurement.epochRef()),
			MeasurementEpoch.class);
		LineageKey lineage = new LineageKey(observation.lineageId(), epoch.contractRef().evidenceId());
		store.appendCreateOnceIndex("measurement-before-call-index",
			MeasurementObservation.callIndexKey(measurement.epochRef(), logicalCallId),
			new LineageEvidenceRef(lineage, observationRef));
		return decision;
	}

	/**
	 * Starts a distinct measurement identity for a paused confirmed campaign.
	 *
	 * @param lease live exclusive scheduler owner
	 * @param logicalCallId pending call to admit under the new identity
	 * @param conditions complete current before-call observation
	 * @return accepted observation under the new identity
	 * @throws IOException if the pause, call, or retained evidence prevents a new identity
	 */
	public MeasurementObservationDecision startNewMeasurement(SchedulerLease lease, String logicalCallId,
		MeasurementConditions conditions) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(conditions, "conditions");
		requireLiveCampaignLease(lease);
		MeasurementObservationDecision decision = store.commitNewMeasurement(lease.binding(), logicalCallId,
			conditions);
		MeasurementObservation observation = store.readMeasurementObservation(decision.observationRef());
		MeasurementEpoch epoch = JSON.readValue(store.readImmutableJson(observation.epochRef()),
			MeasurementEpoch.class);
		LineageKey lineage = new LineageKey(observation.lineageId(), epoch.contractRef().evidenceId());
		store.appendCreateOnceIndex("measurement-before-call-index",
			MeasurementObservation.callIndexKey(observation.epochRef(), logicalCallId),
			new LineageEvidenceRef(lineage, decision.observationRef()));
		return decision;
	}

	/**
	 * Requires the live scheduler owner to belong to this registered campaign store.
	 *
	 * @param lease the scheduler owner whose lifetime and campaign binding are checked
	 * @throws IOException if the lease is inactive or its campaign is foreign or unregistered
	 */
	private void requireLiveCampaignLease(SchedulerLease lease) throws IOException
	{
		lease.requireLive();
		if (!lease.binding().realDirectory().equals(store.directory()))
			throw new IOException("Measurement observer belongs to another campaign store");
		locator.requireRegisteredBinding(lease.binding());
	}
}

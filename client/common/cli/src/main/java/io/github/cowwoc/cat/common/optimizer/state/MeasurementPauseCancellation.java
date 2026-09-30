package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Retains the caller's decision to close a campaign paused by a known measurement change.
 *
 * @param schemaVersion supported cancellation schema
 * @param binding campaign chosen for cancellation
 * @param expectedCounter paused campaign revision chosen by the caller
 * @param sourcePhase active phase to retain for later resumption
 * @param lineageId confirmed lineage retained after cancellation
 * @param epochRef measurement identity retained after cancellation
 * @param changeRef material change that paused scheduling
 */
public record MeasurementPauseCancellation(int schemaVersion, CampaignBinding binding,
	long expectedCounter, CampaignStatus sourcePhase, String lineageId, EvidenceRef epochRef,
	EvidenceRef changeRef)
{
	/** Supported cancellation evidence schema. */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the paused confirmed-campaign identity.
	 *
	 * @param schemaVersion supported cancellation schema
	 * @param binding campaign chosen for cancellation
	 * @param expectedCounter paused campaign revision chosen by the caller
	 * @param sourcePhase active phase to retain for later resumption
	 * @param lineageId confirmed lineage retained after cancellation
	 * @param epochRef measurement identity retained after cancellation
	 * @param changeRef material change that paused scheduling
	 */
	public MeasurementPauseCancellation
	{
		Objects.requireNonNull(binding, "binding");
		Objects.requireNonNull(epochRef, "epochRef");
		Objects.requireNonNull(changeRef, "changeRef");
		if (schemaVersion != SCHEMA_VERSION || expectedCounter < 0 || lineageId == null ||
			lineageId.isBlank() || sourcePhase != CampaignStatus.DEVELOPING &&
				sourcePhase != CampaignStatus.FORMAL_TESTING ||
			!epochRef.collection().equals("measurement-epochs") ||
			!changeRef.collection().equals("measurement-changes"))
			throw new IllegalArgumentException("Measurement-pause cancellation identity is invalid");
	}
}

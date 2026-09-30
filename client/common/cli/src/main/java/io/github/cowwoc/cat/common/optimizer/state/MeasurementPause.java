package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Retains the change that paused one measured development or formal phase.
 *
 * @param changeRef immutable observation that caused the pause
 * @param origin trial execution or certification finalization
 * @param savedPhase phase to restore after the change is resolved
 */
public record MeasurementPause(EvidenceRef changeRef, Origin origin, CampaignStatus savedPhase)
{
	/**
	 * Identifies the boundary that observed the change.
	 */
	public enum Origin
	{
		ADMISSION,
		TRIAL,
		FINALIZATION
	}

	/**
	 * Validates the paused phase and the change evidence collection.
	 *
	 * @param changeRef immutable observation that caused the pause
	 * @param origin trial execution or certification finalization
	 * @param savedPhase phase to restore after the change is resolved
	 */
	public MeasurementPause
	{
		Objects.requireNonNull(changeRef, "changeRef");
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(savedPhase, "savedPhase");
		String collection = switch (origin)
		{
			case ADMISSION, TRIAL -> "measurement-changes";
			case FINALIZATION -> "finalization-measurement-changes";
		};
		if (!changeRef.collection().equals(collection))
			throw new IllegalArgumentException("Measurement change has the wrong collection: " + changeRef);
		if (savedPhase != CampaignStatus.DEVELOPING && savedPhase != CampaignStatus.FORMAL_TESTING ||
			origin == Origin.FINALIZATION && savedPhase != CampaignStatus.FORMAL_TESTING)
		{
			throw new IllegalArgumentException("Measurement pause has an invalid saved phase");
		}
	}
}

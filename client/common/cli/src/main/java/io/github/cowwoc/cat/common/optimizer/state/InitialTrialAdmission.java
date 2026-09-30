package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/** One assigned initial trial's retained call admission or measurement pause. */
public sealed interface InitialTrialAdmission permits InitialTrialAdmission.Claimed,
	InitialTrialAdmission.Paused
{
	/**
	 * A call claimed after its complete before-call observation was accepted.
	 *
	 * @param requestRef retained native request
	 * @param observationRef indexed before-call observation
	 * @param claim retained scheduler claim
	 */
	record Claimed(EvidenceRef requestRef, EvidenceRef observationRef, ClaimedSlot claim)
		implements InitialTrialAdmission
	{
		/**
		 * Validates the accepted request and claim.
		 *
		 * @param requestRef retained native request
		 * @param observationRef indexed before-call observation
		 * @param claim retained scheduler claim
		 */
		public Claimed
		{
			Objects.requireNonNull(requestRef, "requestRef");
			Objects.requireNonNull(observationRef, "observationRef");
			Objects.requireNonNull(claim, "claim");
		}
	}

	/**
	 * A known material condition changed before the call could be claimed.
	 *
	 * @param requestRef retained native request
	 * @param observationRef changed before-call observation
	 * @param changeRef durable comparison that paused the campaign
	 */
	record Paused(EvidenceRef requestRef, EvidenceRef observationRef, EvidenceRef changeRef)
		implements InitialTrialAdmission
	{
		/**
		 * Validates the retained pause evidence.
		 *
		 * @param requestRef retained native request
		 * @param observationRef changed before-call observation
		 * @param changeRef durable comparison that paused the campaign
		 */
		public Paused
		{
			Objects.requireNonNull(requestRef, "requestRef");
			Objects.requireNonNull(observationRef, "observationRef");
			Objects.requireNonNull(changeRef, "changeRef");
		}
	}
}

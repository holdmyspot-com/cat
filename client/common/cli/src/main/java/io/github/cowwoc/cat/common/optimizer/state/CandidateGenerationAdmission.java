package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * A private generation call's retained admission or measurement pause.
 */
public sealed interface CandidateGenerationAdmission permits CandidateGenerationAdmission.Claimed,
	CandidateGenerationAdmission.Paused
{
	/**
	 * A generation call claimed after its before-call observation was accepted.
	 *
	 * @param requestRef retained native request
	 * @param observationRef indexed before-call observation
	 * @param claim retained scheduler claim
	 */
	record Claimed(EvidenceRef requestRef, EvidenceRef observationRef, ClaimedSlot claim)
		implements CandidateGenerationAdmission
	{
		/**
		 * Validates the retained admission evidence.
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
	 * A known material change that paused generation before claim.
	 *
	 * @param requestRef retained native request
	 * @param observationRef changed before-call observation
	 * @param changeRef durable comparison that paused the campaign
	 */
	record Paused(EvidenceRef requestRef, EvidenceRef observationRef, EvidenceRef changeRef)
		implements CandidateGenerationAdmission
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

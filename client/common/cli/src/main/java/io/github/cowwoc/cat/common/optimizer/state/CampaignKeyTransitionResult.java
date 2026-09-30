package io.github.cowwoc.cat.common.optimizer.state;

import java.util.Objects;

/** Result of a recoverable per-key cancellation or resumption transaction. */
public sealed interface CampaignKeyTransitionResult
	permits CampaignKeyTransitionResult.Committed, CampaignKeyTransitionResult.KeyOccupied
{
	/**
	 * The exact campaign transition was committed and its key publication completed.
	 *
	 * @param result committed transition receipt result
	 */
	record Committed(TransitionResult result) implements CampaignKeyTransitionResult
	{
		/**
		 * Requires the embedded committed transition result.
		 *
		 * @param result committed transition result
		 */
		public Committed
		{
			Objects.requireNonNull(result, "result");
		}
	}

	/**
	 * Resumption lost to another active campaign for the same canonical key.
	 *
	 * @param key occupied project/target key
	 * @param incumbentCampaignId other active campaign identity
	 */
	record KeyOccupied(CampaignLookupKey key, String incumbentCampaignId) implements CampaignKeyTransitionResult
	{
		/**
		 * Requires an exact incumbent identity.
		 *
		 * @param key occupied campaign key
		 * @param incumbentCampaignId other active campaign identity
		 */
		public KeyOccupied
		{
			Objects.requireNonNull(key, "key");
			if (incumbentCampaignId == null || incumbentCampaignId.isBlank())
				throw new IllegalArgumentException("Incumbent campaign id must be non-blank");
		}
	}
}

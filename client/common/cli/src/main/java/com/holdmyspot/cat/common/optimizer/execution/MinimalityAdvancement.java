package com.holdmyspot.cat.common.optimizer.execution;

import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.Minimality;
import com.holdmyspot.cat.common.optimizer.campaign.Promotion;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.inventory.Hunks;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.util.Set;

/**
 * Saves the next reduction overlay before advancing minimality, including after a driver restarts.
 */
final class MinimalityAdvancement
{
	/**
	 * Prevents instantiation.
	 */
	private MinimalityAdvancement()
	{
	}

	/**
	 * Advances a completed promotion decision to its next reduction or final minimal candidate. Promotion requires
	 * reductions to preserve every triggering fix from the last accepted runs.
	 *
	 * @param context the execution ports
	 * @param campaign the state after completed outcomes are adopted
	 * @return the advanced state, or the same campaign when a decision or trial remains
	 * @throws IOException if an overlay cannot be read or saved
	 */
	static Campaign advance(DriverContext context, Campaign campaign) throws IOException
	{
		PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
		if (campaign.lifecycle() != Lifecycle.ACTIVE || !campaign.isConfirmed() || campaign.pending() != null ||
			campaign.revisionRequested() || promptAuthor == null ||
			campaign.trials().stream().anyMatch(trial -> trial.key().purpose() == Purpose.PROMOTION))
		{
			return campaign;
		}
		Promotion.Decision decision = Promotion.decide(promptAuthor, campaign);
		if (decision == Promotion.Decision.PENDING)
			return campaign;
		Minimality next = promptAuthor.minimality().advance(promptAuthor.snapshot(), promptAuthor.runs(), decision);
		Sha256 snapshot = next.accepted();
		if (next.testing() != null)
		{
			Overlay working = context.overlays().require(campaign.development().working().snapshot());
			Overlay reduced = Hunks.apply(working, next.files(), Set.copyOf(next.testing()));
			context.overlays().save(reduced);
			snapshot = reduced.identity();
		}
		return Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(snapshot));
	}
}

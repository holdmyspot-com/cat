package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.AuthorAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignTransitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.Minimality;
import io.github.cowwoc.cat.common.optimizer.campaign.Promotion;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunks;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

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
	 * Advances a completed promotion decision to its next reduction or final minimal candidate.
	 *
	 * @param context the execution ports
	 * @param campaign the state after completed outcomes are adopted
	 * @return the advanced state, or the same campaign when a decision or trial remains
	 * @throws IOException if an overlay cannot be read or saved
	 */
	static Campaign advance(DriverContext context, Campaign campaign) throws IOException
	{
		AuthorAttempt author = campaign.development().author();
		if (campaign.lifecycle() != Lifecycle.ACTIVE || !campaign.isConfirmed() || campaign.pending() != null ||
			campaign.revisionRequested() || author == null ||
			campaign.trials().stream().anyMatch(trial -> trial.key().purpose() == Purpose.PROMOTION))
		{
			return campaign;
		}
		Promotion.Decision decision = Promotion.decide(author, campaign.lineage().regressionSet());
		if (decision == Promotion.Decision.PENDING)
			return campaign;
		Minimality next = author.minimality().advance(author.snapshot(), author.runs(), decision);
		Sha256 snapshot = next.accepted();
		if (next.testing() != null)
		{
			Overlay working = context.overlays().require(campaign.development().working().snapshot());
			Overlay reduced = Hunks.apply(working, next.files(), Set.copyOf(next.testing()));
			context.overlays().save(reduced);
			snapshot = reduced.identity();
		}
		return CampaignTransitions.apply(campaign, new CampaignEvent.AdvanceMinimality(snapshot));
	}
}

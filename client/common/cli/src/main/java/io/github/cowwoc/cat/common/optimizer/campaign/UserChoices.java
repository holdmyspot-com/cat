package io.github.cowwoc.cat.common.optimizer.campaign;

import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the choices that a campaign offers while it waits for a decision or is cancelled.
 */
final class UserChoices
{
	/**
	 * Prevents instantiation.
	 */
	private UserChoices()
	{
	}

	/**
	 * Applies a choice.
	 *
	 * @param draft the state
	 * @param campaign the state before the choice
	 * @param choose the event
	 * @throws IllegalArgumentException if the campaign does not offer the choice, or the choice names an attempt,
	 *   review point or target that the state does not offer
	 */
	static void apply(CampaignDraft draft, Campaign campaign, CampaignEvent.Choose choose)
	{
		Choice choice = choose.choice();
		if (!campaign.choices().contains(choice))
		{
			throw new IllegalArgumentException("The campaign does not offer " + choice + "; it offers " +
				campaign.choices());
		}
		PendingDecision pending = draft.pending();
		switch (choice)
		{
			case RESTORE -> draft.resolve();
			case NEW_IDENTITY -> newIdentity(draft, (PendingDecision.MeasurementChanged) pending);
			case NEW_CAMPAIGN -> draft.header = draft.header.withLifecycle(Lifecycle.CLOSED);
			case RESUME ->
			{
				if (draft.header.lifecycle() == Lifecycle.CANCELLED)
					draft.header = draft.header.withLifecycle(Lifecycle.ACTIVE);
				else
					draft.resolve();
			}
			case RETRY -> retry(draft, pending);
			case STOP -> draft.header = draft.header.withLifecycle(Lifecycle.CANCELLED);
			case MORE_EVIDENCE -> moreEvidence(draft, (PendingDecision.CheckpointReached) pending,
				choose.reviewPoint());
			case TARGETED_OPTIMIZATION ->
			{
				PendingDecision.CheckpointReached checkpoint = (PendingDecision.CheckpointReached) pending;
				draft.round = TrialAdoption.failingRound(draft.developmentCounts, draft.contract().cellIds(),
					checkpoint.category());
				draft.resolve();
			}
			case KEEP_IMPROVEMENT -> keepImprovement(draft, campaign, choose.attempt());
			case OPTIMIZE_FURTHER ->
			{
				PendingDecision.ImprovementProven proven = (PendingDecision.ImprovementProven) pending;
				draft.resolve();
				TrialAdoption.returnToDevelopment(draft, draft.attempt(proven.attempt()));
			}
			case ACCEPT_TRADE_OFF ->
			{
				CertificationAttempt attempt = draft.attempt(((PendingDecision.RegressionTradeOff) pending).attempt());
				finish(draft, new DeliveryRequest(attempt.candidate(), attempt.index(), Outcome.CERTIFIED));
			}
			case RETAIN_INCUMBENT -> finish(draft, null);
			case ACCEPT_WITHOUT_CERTIFICATION ->
			{
				draft.outcome = Outcome.NOT_CERTIFIED;
				finish(draft, new DeliveryRequest(draft.working, 0, Outcome.NOT_CERTIFIED));
			}
			case AUTHORIZE_TARGET -> authorize(draft, (PendingDecision.AuthorityWarnings) pending, choose.target());
			case USE_AUTHORIZED_SOURCE -> draft.resolve();
		}
	}

	/**
	 * Authorizes the external target that pending warnings name. The warnings about the target are answered; the
	 * decision stays pending while warnings about other edits remain.
	 *
	 * @param draft the state
	 * @param warnings the pending decision
	 * @param target the target to authorize
	 * @throws IllegalArgumentException if no pending warning offers authorizing {@code target}
	 */
	private static void authorize(CampaignDraft draft, PendingDecision.AuthorityWarnings warnings, Path target)
	{
		List<AuthorityWarning> remaining = new ArrayList<>();
		boolean offered = false;
		for (AuthorityWarning warning : warnings.warnings())
		{
			if (warning.target().equals(target))
			{
				if (!warning.choices().contains(AuthorityWarning.Choice.AUTHORIZE_TARGET))
				{
					throw new IllegalArgumentException("Authorizing " + target + " cannot permit the " +
						warning.limit() + " edit that requirement " + warning.requirement() + " needs");
				}
				offered = true;
			}
			else
				remaining.add(warning);
		}
		if (!offered)
			throw new IllegalArgumentException("No pending authority warning names the target " + target);
		draft.authorizedTargets.add(target);
		draft.resolve();
		if (!remaining.isEmpty())
			draft.pendingDecisions.addFirst(new PendingDecision.AuthorityWarnings(remaining));
	}

	/**
	 * Adopts the observed measurement conditions as the campaign's identity. Evidence gathered under the old
	 * identity no longer counts toward the working candidate or the incumbent baseline.
	 *
	 * @param draft the state
	 * @param changed the pending decision
	 */
	private static void newIdentity(CampaignDraft draft, PendingDecision.MeasurementChanged changed)
	{
		draft.identity = draft.identity.replaceWith(changed.observed());
		draft.endRunningAttempt(Outcome.TESTING_ENVIRONMENT_CHANGED);
		draft.startDevelopment(draft.working);
		draft.startBaseline();
		draft.slowStart = SlowStart.INITIAL;
		draft.resolve();
	}

	/**
	 * Retries after failures: resets the failing trial's count, or allows the commit again.
	 *
	 * @param draft the state
	 * @param pending the pending decision
	 */
	private static void retry(CampaignDraft draft, PendingDecision pending)
	{
		if (pending instanceof PendingDecision.TrialFailures(TrialKey key))
		{
			for (int i = 0; i < draft.trials.size(); ++i)
			{
				LogicalTrial trial = draft.trials.get(i);
				if (trial.key().equals(key))
				{
					draft.trials.set(i, new LogicalTrial(trial.key(), trial.instance(), trial.identity(),
						trial.allocation(), trial.state(), trial.verdict(), trial.rationale(), 0,
						trial.infrastructureAttempts(), trial.observations()));
				}
			}
		}
		draft.resolve();
	}

	/**
	 * Continues development of a category to a later review point.
	 *
	 * @param draft the state
	 * @param checkpoint the pending decision
	 * @param reviewPoint the next review point
	 * @throws IllegalArgumentException if {@code reviewPoint} does not exceed the category's outcomes
	 */
	private static void moreEvidence(CampaignDraft draft, PendingDecision.CheckpointReached checkpoint,
		int reviewPoint)
	{
		String category = checkpoint.category();
		int outcomes = draft.developmentCount(category).outcomes();
		if (reviewPoint <= outcomes)
		{
			throw new IllegalArgumentException("The next review point of " + category + " must exceed its " +
				outcomes + " outcomes: " + reviewPoint);
		}
		draft.reviewPoints.put(category, reviewPoint);
		draft.resolve();
	}

	/**
	 * Delivers the frozen candidate of an offered keep-improvement attempt.
	 *
	 * @param draft the state
	 * @param campaign the state before the choice
	 * @param index the attempt's certification index
	 * @throws IllegalArgumentException if the attempt is not offered
	 */
	private static void keepImprovement(CampaignDraft draft, Campaign campaign, int index)
	{
		boolean offered = false;
		for (ImprovementOffer offer : KeepImprovement.offers(campaign))
		{
			if (offer.attempt() == index)
				offered = true;
		}
		if (!offered)
			throw new IllegalArgumentException("Certification attempt " + index + " is not offered");
		CertificationAttempt attempt = draft.attempt(index);
		draft.outcome = Outcome.IMPROVED_NOT_CERTIFIED;
		finish(draft, new DeliveryRequest(attempt.candidate(), index, Outcome.IMPROVED_NOT_CERTIFIED));
	}

	/**
	 * Finishes the campaign. An active campaign's choice answers its current decision; a cancelled campaign's
	 * choice answers none, so its pending decisions stay recorded.
	 *
	 * @param draft the state
	 * @param delivery the candidate to deliver, or {@code null} to keep the incumbent
	 */
	private static void finish(CampaignDraft draft, DeliveryRequest delivery)
	{
		if (draft.header.lifecycle() == Lifecycle.ACTIVE)
			draft.resolve();
		draft.header = draft.header.withLifecycle(Lifecycle.FINISHED);
		draft.delivery = delivery;
	}
}

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
	 * Updates the draft to record the offered choice and its resulting campaign state.
	 *
	 * @param draft the mutable campaign state to update
	 * @param campaign the state before the choice
	 * @param choose the event
	 * @throws IllegalArgumentException if the campaign does not offer the choice, or the choice names an attempt,
	 *   review point or target that the state does not offer
	 */
	static void apply(Draft draft, Campaign campaign, CampaignEvent.Choose choose)
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
				draft.round = TrialAdoption.failingRound(draft.developmentCounts, draft.contract(),
					checkpoint.cell());
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
	 * Records authorization of the external target that pending warnings name. Warnings about that target are
	 * answered; warnings about other targets remain pending.
	 *
	 * @param draft the state
	 * @param warnings the pending decision
	 * @param target the target to authorize
	 * @throws IllegalArgumentException if no pending warning offers authorizing {@code target}
	 */
	private static void authorize(Draft draft, PendingDecision.AuthorityWarnings warnings, Path target)
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
	 * Replaces recorded measurement conditions with observed values. Ends any running certification attempt and
	 * clears the working candidate's development counts and the incumbent's baseline counts; evidence recorded in
	 * earlier attempts remains there.
	 *
	 * @param draft the state
	 * @param changed the pending decision
	 */
	private static void newIdentity(Draft draft, PendingDecision.MeasurementChanged changed)
	{
		draft.identity = draft.identity.replaceWith(changed.observed());
		draft.endRunningAttempt(Outcome.TESTING_ENVIRONMENT_CHANGED);
		draft.startDevelopment(draft.working);
		draft.startBaseline();
		draft.slowStart = SlowStart.INITIAL;
		draft.resolve();
	}

	/**
	 * Answers the pending retry decision, clearing a failing trial's consecutive infrastructure-failure count when
	 * the decision names that trial.
	 *
	 * @param draft the state
	 * @param pending the pending decision
	 */
	private static void retry(Draft draft, PendingDecision pending)
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
						trial.infrastructureAttempts(), trial.observations(), trial.responses()));
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
	private static void moreEvidence(Draft draft, PendingDecision.CheckpointReached checkpoint,
		int reviewPoint)
	{
		String cell = checkpoint.cell();
		int outcomes = draft.developmentCount(cell).outcomes();
		if (reviewPoint <= outcomes)
		{
			throw new IllegalArgumentException("The next review point of " + cell + " must exceed its " +
				outcomes + " outcomes: " + reviewPoint);
		}
		draft.reviewPoints.put(cell, reviewPoint);
		draft.resolve();
	}

	/**
	 * Requests delivery of the frozen candidate of an offered improvement attempt, without certification.
	 *
	 * @param draft the state
	 * @param campaign the state before the choice
	 * @param index the attempt's certification index
	 * @throws IllegalArgumentException if the attempt is not offered
	 */
	private static void keepImprovement(Draft draft, Campaign campaign, int index)
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
	 * Marks the campaign finished and records whether a candidate should be delivered. An active campaign's choice
	 * answers its current decision; a cancelled campaign's choice answers none, so its pending decisions stay recorded.
	 *
	 * @param draft the state
	 * @param delivery the candidate to deliver, or {@code null} to keep the incumbent
	 */
	private static void finish(Draft draft, DeliveryRequest delivery)
	{
		if (draft.header.lifecycle() == Lifecycle.ACTIVE)
			draft.resolve();
		draft.header = draft.header.withLifecycle(Lifecycle.FINISHED);
		draft.delivery = delivery;
	}
}

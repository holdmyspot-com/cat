package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignTransitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.RunFailure;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;
import io.github.cowwoc.cat.common.optimizer.execution.LeftoverRuns.RecordedProcess;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Brings a campaign that an earlier driver left behind to a consistent state before a new driver advances it
 * (design §3.8).
 */
final class Reconciler
{
	/**
	 * Why a trial that was running when the earlier driver ended reruns.
	 */
	static final String ABANDONED = "The driver that ran the trial ended before it recorded an outcome";

	/**
	 * Prevents instantiation.
	 */
	private Reconciler()
	{
	}

	/**
	 * Reconciles a campaign. In order, this:
	 * <ol>
	 *   <li>terminates the processes of runner runs that an earlier driver left running;</li>
	 *   <li>adopts every recorded outcome that the snapshot does not hold yet;</li>
	 *   <li>returns every trial that started without an outcome to the queue, to rerun as the same logical
	 *   trial;</li>
	 *   <li>verifies the optimization worktree of an active campaign;</li>
	 *   <li>records the measurement conditions that the driver observes outside trials;</li>
	 *   <li>replaces the snapshot.</li>
	 * </ol>
	 *
	 * @param context the driver's context
	 * @param campaign the persisted snapshot
	 * @param now the current time
	 * @return the reconciled campaign, which is persisted
	 * @throws IOException if the store, the worktree or the run records cannot be read or written
	 */
	static Campaign reconcile(DriverContext context, Campaign campaign, Instant now) throws IOException
	{
		for (RecordedProcess process : context.leftoverRuns().find())
			context.terminator().terminate(process.pid(), process.startedAt());

		Candidate previous = campaign.development().working();
		Campaign reconciled = adoptRecordedOutcomes(context, campaign);
		reconciled = requeueStartedTrials(reconciled, now);
		if (reconciled.lifecycle() == Lifecycle.ACTIVE)
			reconciled = WorktreeCheck.verify(context, reconciled, previous);
		if (reconciled.lifecycle() == Lifecycle.ACTIVE)
		{
			reconciled = CampaignTransitions.apply(reconciled,
				new CampaignEvent.ChangeMeasurement(context.environment()));
		}
		context.store().replaceSnapshot(reconciled);
		return reconciled;
	}

	/**
	 * Adopts the outcomes that were recorded after the snapshot was last replaced.
	 *
	 * @param context the driver's context
	 * @param campaign the persisted snapshot
	 * @return the campaign after adoption
	 * @throws IOException if the store cannot be read
	 */
	private static Campaign adoptRecordedOutcomes(DriverContext context, Campaign campaign) throws IOException
	{
		Campaign adopted = campaign;
		for (CampaignEvent.Complete outcome : context.store().listUnadoptedOutcomes(campaign))
		{
			Optional<LogicalTrial> trial = find(adopted, outcome.key());
			// An earlier adoption may have made the trial stale and removed it
			if (trial.isEmpty())
				continue;
			if (trial.get().state() == TrialState.ASSIGNED)
				adopted = CampaignTransitions.apply(adopted, new CampaignEvent.Start(outcome.key()));
			adopted = CampaignTransitions.apply(adopted, outcome);
		}
		return adopted;
	}

	/**
	 * Returns every trial whose session started without a recorded outcome to the queue.
	 *
	 * @param campaign the campaign
	 * @param now the current time
	 * @return the campaign without running trials
	 */
	private static Campaign requeueStartedTrials(Campaign campaign, Instant now)
	{
		List<TrialKey> running = new ArrayList<>();
		for (LogicalTrial trial : campaign.trials())
		{
			if (trial.state() == TrialState.RUNNING)
				running.add(trial.key());
		}
		Campaign requeued = campaign;
		for (TrialKey key : running)
		{
			requeued = CampaignTransitions.apply(requeued,
				new CampaignEvent.Fail(key, RunFailure.INTERRUPTED, ABANDONED, now, null));
		}
		return requeued;
	}

	/**
	 * Looks up a trial.
	 *
	 * @param campaign the campaign
	 * @param key the trial's key
	 * @return the trial, or empty if the campaign does not hold it
	 */
	static Optional<LogicalTrial> find(Campaign campaign, TrialKey key)
	{
		for (LogicalTrial trial : campaign.trials())
		{
			if (trial.key().equals(key))
				return Optional.of(trial);
		}
		return Optional.empty();
	}
}

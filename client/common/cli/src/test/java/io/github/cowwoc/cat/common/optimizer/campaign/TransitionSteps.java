package io.github.cowwoc.cat.common.optimizer.campaign;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

/**
 * Applies the event sequences that tests repeat.
 */
final class TransitionSteps
{
	/**
	 * Prevents instantiation.
	 */
	private TransitionSteps()
	{
	}

	/**
	 * Returns the series and snapshot that a representative trial of a purpose uses now.
	 *
	 * @param campaign the campaign
	 * @param purpose a representative purpose
	 * @param category the trial's category
	 * @return the admission
	 */
	static Admission.Assign admission(Campaign campaign, Purpose purpose, String category)
	{
		return switch (purpose)
		{
			case DEVELOPMENT -> new Admission.Assign(purpose, campaign.development().series(),
				campaign.development().working().snapshot(), category, null);
			case BASELINE -> new Admission.Assign(purpose, campaign.baseline().index(),
				campaign.incumbent().snapshot(), category, null);
			case FORMAL ->
			{
				CertificationAttempt attempt = campaign.runningAttempt().orElseThrow();
				yield new Admission.Assign(purpose, attempt.index(), attempt.candidate().snapshot(), category, null);
			}
			case DIAGNOSTIC, PROMOTION -> throw new IllegalArgumentException("Targeted purpose: " + purpose);
		};
	}

	/**
	 * Assigns a representative trial.
	 *
	 * @param campaign the campaign
	 * @param purpose a representative purpose
	 * @param category the trial's category
	 * @return the updated campaign
	 */
	static Campaign assign(Campaign campaign, Purpose purpose, String category)
	{
		return CampaignTransitions.apply(campaign, new CampaignEvent.Assign(admission(campaign, purpose, category)));
	}

	/**
	 * Assigns a targeted trial.
	 *
	 * @param campaign the campaign
	 * @param purpose a targeted purpose
	 * @param series the trial's series
	 * @param snapshot the snapshot that the trial runs
	 * @param instance the targeted instance
	 * @return the updated campaign
	 */
	static Campaign assign(Campaign campaign, Purpose purpose, int series, Sha256 snapshot, TrialInstance instance)
	{
		return CampaignTransitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(purpose, series,
			snapshot, instance.categoryId(), instance)));
	}

	/**
	 * Returns the key of the most recently assigned trial.
	 *
	 * @param campaign the campaign
	 * @return the key
	 */
	static TrialKey last(Campaign campaign)
	{
		if (campaign.trials().isEmpty())
			throw new IllegalStateException("No trial is unadopted");
		return campaign.trials().getLast().key();
	}

	/**
	 * Starts and completes a trial under the campaign's measurement identity.
	 *
	 * @param campaign the campaign
	 * @param key the trial
	 * @param verdict the judged outcome
	 * @return the updated campaign
	 */
	static Campaign run(Campaign campaign, TrialKey key, Verdict verdict)
	{
		Campaign started = CampaignTransitions.apply(campaign, new CampaignEvent.Start(key));
		return complete(started, key, verdict);
	}

	/**
	 * Completes a running trial under the campaign's measurement identity.
	 *
	 * @param campaign the campaign
	 * @param key the trial
	 * @param verdict the judged outcome
	 * @return the updated campaign
	 */
	static Campaign complete(Campaign campaign, TrialKey key, Verdict verdict)
	{
		return CampaignTransitions.apply(campaign, new CampaignEvent.Complete(key, verdict, campaign.identity()));
	}

	/**
	 * Assigns, runs and completes one representative trial.
	 *
	 * @param campaign the campaign
	 * @param purpose a representative purpose
	 * @param category the trial's category
	 * @param verdict the judged outcome
	 * @return the updated campaign
	 */
	static Campaign adopt(Campaign campaign, Purpose purpose, String category, Verdict verdict)
	{
		Campaign assigned = assign(campaign, purpose, category);
		return run(assigned, last(assigned), verdict);
	}

	/**
	 * Assigns, runs and completes one targeted trial.
	 *
	 * @param campaign the campaign
	 * @param purpose a targeted purpose
	 * @param series the trial's series
	 * @param snapshot the snapshot that the trial runs
	 * @param instance the targeted instance
	 * @param verdict the judged outcome
	 * @return the updated campaign
	 */
	static Campaign adopt(Campaign campaign, Purpose purpose, int series, Sha256 snapshot, TrialInstance instance,
		Verdict verdict)
	{
		Campaign assigned = assign(campaign, purpose, series, snapshot, instance);
		return run(assigned, last(assigned), verdict);
	}
}

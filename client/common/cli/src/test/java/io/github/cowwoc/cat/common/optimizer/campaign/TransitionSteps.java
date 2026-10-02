package io.github.cowwoc.cat.common.optimizer.campaign;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.time.Instant;

/**
 * Applies the campaign event sequences that tests repeat. Representative trials are sampled and counted;
 * targeted trials reuse specific instances and do not enter those counts.
 */
final class TransitionSteps
{
	/**
	 * The time at which the steps assign trials.
	 */
	static final Instant ASSIGNED_AT = Instant.parse("2026-10-01T11:00:00Z");

	/**
	 * Prevents instantiation.
	 */
	private TransitionSteps()
	{
	}

	/**
	 * Returns an allocation decision for the current evidence revision, recording
	 * {@link AllocationRule#LOWEST_EXPLORATORY_BOUND} for development trials.
	 *
	 * @param campaign the campaign
	 * @param purpose the trial's purpose
	 * @return the allocation decision
	 */
	static Allocation allocation(Campaign campaign, Purpose purpose)
	{
		AllocationRule rule = switch (purpose)
		{
			case DEVELOPMENT -> AllocationRule.LOWEST_EXPLORATORY_BOUND;
			case BASELINE -> AllocationRule.BASELINE_ARM;
			case FORMAL -> AllocationRule.FORMAL;
			case DIAGNOSTIC, PROMOTION -> AllocationRule.TARGETED;
		};
		return new Allocation(rule, campaign.evidenceRevision(), ASSIGNED_AT);
	}

	/**
	 * Returns the series and snapshot that a representative trial of a purpose uses now.
	 *
	 * @param campaign the campaign
	 * @param purpose a representative purpose
	 * @param category the trial's cell identifier
	 * @return the admission
	 */
	static Admission.Assign admission(Campaign campaign, Purpose purpose, String category)
	{
		Allocation allocation = allocation(campaign, purpose);
		return switch (purpose)
		{
			case DEVELOPMENT -> new Admission.Assign(purpose, campaign.development().series(),
				campaign.development().working().snapshot(), category, null, allocation);
			case BASELINE -> new Admission.Assign(purpose, campaign.baseline().index(),
				campaign.incumbent().snapshot(), category, null, allocation);
			case FORMAL ->
			{
				CertificationAttempt attempt = campaign.runningAttempt().orElseThrow();
				yield new Admission.Assign(purpose, attempt.index(), attempt.candidate().snapshot(), category, null,
					allocation);
			}
			case DIAGNOSTIC, PROMOTION -> throw new IllegalArgumentException("Targeted purpose: " + purpose);
		};
	}

	/**
	 * Assigns a representative trial.
	 *
	 * @param campaign the campaign
	 * @param purpose a representative purpose
	 * @param category the trial's cell identifier
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
			snapshot, instance.cellId(), instance, allocation(campaign, purpose))));
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
	 * Completes a running trial under the campaign's measurement identity, without a rationale.
	 *
	 * @param campaign the campaign
	 * @param key the trial
	 * @param verdict the judged outcome
	 * @return the updated campaign
	 */
	static Campaign complete(Campaign campaign, TrialKey key, Verdict verdict)
	{
		return complete(campaign, key, verdict, "");
	}

	/**
	 * Completes a running trial under the campaign's measurement identity.
	 *
	 * @param campaign the campaign
	 * @param key the trial
	 * @param verdict the judged outcome
	 * @param rationale the judge's explanation of the outcome
	 * @return the updated campaign
	 */
	static Campaign complete(Campaign campaign, TrialKey key, Verdict verdict, String rationale)
	{
		return CampaignTransitions.apply(campaign, new CampaignEvent.Complete(key, verdict, rationale,
			campaign.identity()));
	}

	/**
	 * Assigns, runs and completes one representative trial.
	 *
	 * @param campaign the campaign
	 * @param purpose a representative purpose
	 * @param category the trial's cell identifier
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

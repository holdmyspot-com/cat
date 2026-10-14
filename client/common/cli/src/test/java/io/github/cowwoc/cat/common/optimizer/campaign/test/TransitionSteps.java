package io.github.cowwoc.cat.common.optimizer.campaign.test;

import io.github.cowwoc.cat.common.optimizer.campaign.Admission;
import io.github.cowwoc.cat.common.optimizer.campaign.Allocation;
import io.github.cowwoc.cat.common.optimizer.campaign.AllocationRule;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.FailedAssertion;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.Reference;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Triage;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.time.Instant;
import java.util.List;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;

/**
 * Applies the campaign event sequences that tests repeat. Representative trials are sampled and counted;
 * targeted trials reuse specific test cases and do not enter those counts.
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
	 * Reserves and submits one valid prompt author report using the same durable transitions as the driver.
	 *
	 * @param campaign the campaign
	 * @param cell the addressed cell
	 * @param snapshot the authored snapshot
	 * @param changes the authored changes
	 * @return the updated campaign
	 */
	static Campaign submit(Campaign campaign, String cell, Sha256 snapshot, List<FileDelta> changes)
	{
		Campaign reserved = Transitions.apply(campaign, new CampaignEvent.StartPromptAuthorAttempt(cell));
		int number = reserved.development().round().reservations().keySet().stream().mapToInt(Integer::intValue).
			max().orElseThrow();
		return Transitions.apply(reserved, new CampaignEvent.SubmitPromptAuthorAttempt(number, cell, snapshot,
			changes));
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
			case BASELINE -> AllocationRule.BASELINE;
			case FORMAL -> AllocationRule.FORMAL;
			case CONTROL, INCUMBENT -> AllocationRule.PAIRED;
			case DIAGNOSTIC, PROMOTION, REPRODUCTION -> AllocationRule.TARGETED;
		};
		return new Allocation(rule, campaign.evidenceRevision(), ASSIGNED_AT);
	}

	/**
	 * Returns the series and snapshot that a representative trial of a purpose uses now.
	 *
	 * @param campaign the campaign
	 * @param purpose a representative purpose
	 * @param cell the trial's cell identifier
	 * @return the admission
	 */
	static Admission.Assign admission(Campaign campaign, Purpose purpose, String cell)
	{
		Allocation allocation = allocation(campaign, purpose);
		return switch (purpose)
		{
			case DEVELOPMENT -> new Admission.Assign(purpose, campaign.development().series(),
				campaign.development().working().snapshot(), cell, null, allocation);
			case BASELINE -> new Admission.Assign(purpose, campaign.baseline().index(),
				campaign.incumbent().snapshot(), cell, null, allocation);
			case FORMAL ->
			{
				CertificationAttempt attempt = campaign.runningAttempt().orElseThrow();
				yield new Admission.Assign(purpose, attempt.index(), attempt.candidate().snapshot(), cell, null,
					allocation);
			}
			case CONTROL, INCUMBENT, DIAGNOSTIC, PROMOTION, REPRODUCTION -> throw new IllegalArgumentException(
				"Purpose that replays a test case: " + purpose);
		};
	}

	/**
	 * Assigns a representative trial.
	 *
	 * @param campaign the campaign
	 * @param purpose a representative purpose
	 * @param cell the trial's cell identifier
	 * @return the updated campaign
	 */
	static Campaign assign(Campaign campaign, Purpose purpose, String cell)
	{
		return Transitions.apply(campaign, new CampaignEvent.Assign(admission(campaign, purpose, cell)));
	}

	/**
	 * Assigns a targeted trial.
	 *
	 * @param campaign the campaign
	 * @param purpose a targeted purpose
	 * @param series the trial's series
	 * @param snapshot the snapshot that the trial runs
	 * @param testCase the targeted test case
	 * @return the updated campaign
	 */
	static Campaign assign(Campaign campaign, Purpose purpose, int series, Sha256 snapshot, DrawnTestCase testCase)
	{
		return Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(purpose, series,
			snapshot, testCase.cellId(), testCase, allocation(campaign, purpose))));
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
		Campaign started = Transitions.apply(campaign, new CampaignEvent.Start(key));
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
		return Transitions.apply(campaign, new CampaignEvent.Complete(key, verdict, rationale, List.of(), null,
			campaign.identity(), null, List.of(), Builder.FILES));
	}

	/**
	 * Completes a running development trial as a failure with an impact, under the campaign's measurement identity.
	 *
	 * @param campaign the campaign
	 * @param key the trial
	 * @param triage the failure's impact
	 * @return the updated campaign
	 */
	static Campaign fail(Campaign campaign, TrialKey key, Triage triage)
	{
		return Transitions.apply(campaign, new CampaignEvent.Complete(key, Verdict.FAIL, "", List.of(), triage,
			campaign.identity(), null, List.of(), Builder.FILES));
	}

	/**
	 * Assigns, runs and fails one development trial with an impact.
	 *
	 * @param campaign the campaign
	 * @param cell the trial's cell identifier
	 * @param triage the failure's impact
	 * @return the updated campaign
	 */
	static Campaign fail(Campaign campaign, String cell, Triage triage)
	{
		Campaign assigned = assign(campaign, Purpose.DEVELOPMENT, cell);
		TrialKey key = last(assigned);
		return fail(Transitions.apply(assigned, new CampaignEvent.Start(key)), key, triage);
	}

	/**
	 * Assigns, runs and completes one representative trial.
	 *
	 * @param campaign the campaign
	 * @param purpose a representative purpose
	 * @param cell the trial's cell identifier
	 * @param verdict the judged outcome
	 * @return the updated campaign
	 */
	static Campaign adopt(Campaign campaign, Purpose purpose, String cell, Verdict verdict)
	{
		Campaign assigned = assign(campaign, purpose, cell);
		return run(assigned, last(assigned), verdict);
	}

	/**
	 * Assigns, runs and completes one targeted trial.
	 *
	 * @param campaign the campaign
	 * @param purpose a targeted purpose
	 * @param series the trial's series
	 * @param snapshot the snapshot that the trial runs
	 * @param testCase the targeted test case
	 * @param verdict the judged outcome
	 * @return the updated campaign
	 */
	static Campaign adopt(Campaign campaign, Purpose purpose, int series, Sha256 snapshot, DrawnTestCase testCase,
		Verdict verdict)
	{
		Campaign assigned = assign(campaign, purpose, series, snapshot, testCase);
		return run(assigned, last(assigned), verdict);
	}

	/**
	 * Assigns the control or incumbent trial that pairs with the running attempt's next candidate formal trial of a
	 * cell that has no trial of that kind yet.
	 *
	 * @param campaign the campaign
	 * @param reference the kind of reference trial
	 * @param cell the trial's cell identifier
	 * @return the updated campaign
	 */
	static Campaign assignReference(Campaign campaign, Reference reference, String cell)
	{
		CertificationAttempt attempt = campaign.runningAttempt().orElseThrow();
		int inFlight = 0;
		for (LogicalTrial trial : campaign.trials())
		{
			TrialKey key = trial.key();
			if (key.purpose() == reference.purpose() && key.series() == attempt.index() && key.cell().equals(cell))
				++inFlight;
		}
		DrawnTestCase testCase = attempt.pairing(reference, cell).unpaired().get(inFlight).testCase();
		Sha256 snapshot = switch (reference)
		{
			case CONTROL -> attempt.control();
			case INCUMBENT -> campaign.incumbent().snapshot();
		};
		return assign(campaign, reference.purpose(), attempt.index(), snapshot, testCase);
	}

	/**
	 * Assigns, runs and completes the control or incumbent trial that pairs with the running attempt's next candidate
	 * formal trial of a cell that has no trial of that kind yet.
	 *
	 * @param campaign the campaign
	 * @param reference the kind of reference trial
	 * @param cell the trial's cell identifier
	 * @param verdict the judged outcome
	 * @param failedAssertions the assertions that a failed trial failed
	 * @return the updated campaign
	 */
	static Campaign adoptReference(Campaign campaign, Reference reference, String cell, Verdict verdict,
		List<FailedAssertion> failedAssertions)
	{
		Campaign assigned = assignReference(campaign, reference, cell);
		TrialKey key = last(assigned);
		Campaign started = Transitions.apply(assigned, new CampaignEvent.Start(key));
		return Transitions.apply(started, new CampaignEvent.Complete(key, verdict, "", failedAssertions, null,
			started.identity(), null, List.of(), Builder.FILES));
	}
}

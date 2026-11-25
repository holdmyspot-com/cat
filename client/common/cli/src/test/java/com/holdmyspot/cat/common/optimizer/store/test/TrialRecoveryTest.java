package com.holdmyspot.cat.common.optimizer.store.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.store.TrialRecovery;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.UUID;

/**
 * Tests the check that decides whether a retained session's completed turns can continue a recorded trial attempt.
 */
public final class TrialRecoveryTest
{
	/**
	 * A retained prefix is claimable only when it holds no fewer turns than the observer decided, at most one more,
	 * no more than the trial's turns, and no turn beyond an early end.
	 */
	@Test
	public void claimsOnlyFittingPrefixes()
	{
		// Requirement: R-I12
		LogicalTrial trial = CampaignStoreContract.trial(1, TrialState.RUNNING);
		int turns = trial.testCase().turns().size();
		requireThat(turns, "turns").isGreaterThanOrEqualTo(2);
		UUID requestId = UUID.randomUUID();
		TrialRecovery undecided = new TrialRecovery(trial.key(), 1, requestId, 0, Map.of(), false);
		TrialRecovery decidedOne = new TrialRecovery(trial.key(), 1, requestId, 1, Map.of(), false);

		requireThat(undecided.canResumePrefix(trial, 0), "noTurns").isTrue();
		requireThat(undecided.canResumePrefix(trial, 1), "oneAwaitingObserver").isTrue();
		requireThat(undecided.canResumePrefix(trial, 2), "twoAwaitingObserver").isFalse();
		requireThat(decidedOne.canResumePrefix(trial, 1), "exactlyDecided").isTrue();
		requireThat(decidedOne.canResumePrefix(trial, 0), "fewerThanDecided").isFalse();

		TrialRecovery decidedAll = new TrialRecovery(trial.key(), 1, requestId, turns, Map.of(), false);
		requireThat(decidedAll.canResumePrefix(trial, turns), "allTurns").isTrue();
		requireThat(decidedAll.canResumePrefix(trial, turns + 1), "moreThanTrialTurns").isFalse();

		TrialRecovery ended = new TrialRecovery(trial.key(), 1, requestId, 1, Map.of(), true);
		requireThat(ended.canResumePrefix(trial, 1), "endedAtDecision").isTrue();
		requireThat(ended.canResumePrefix(trial, 2), "turnAfterEnd").isFalse();
	}
}

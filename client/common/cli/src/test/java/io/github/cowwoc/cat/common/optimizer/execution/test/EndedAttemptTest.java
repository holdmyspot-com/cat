package io.github.cowwoc.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Pairing;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.test.Boundaries;
import io.github.cowwoc.cat.common.optimizer.campaign.test.Builder;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.execution.PromptAuthorBrief.BelowReference;
import io.github.cowwoc.cat.common.optimizer.execution.PromptAuthorBrief.EndedAttempt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link EndedAttempt}.
 */
public final class EndedAttemptTest
{
	/**
	 * Creates the test.
	 */
	public EndedAttemptTest()
	{
	}

	/**
	 * Verifies that an attempt that ended below control names each assertion below control with its pairs, and no
	 * assertion whose comparison is undecided.
	 */
	@Test
	public void namesAssertionsBelowControl()
	{
		// Requirement: R-F7
		PairCounts below = new PairCounts(0, 0, 8, 0);
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		comparisons.put("asks_before_deleting", new PairCounts(1, 7, 0, 0));
		comparisons.put(TestCase.EXPECTED_OUTCOME, below);
		Campaign campaign = new Builder().confirmed().certificationAttempt(1).
			boundaries(Boundaries.standard(3, Boundaries.PAIRED_MINIMUM_PASS_RATE)).cell("formatting", 100, 0).
			cell("plain_list", 100, 0).cell("safety", 100, 0).
			controlPairing("safety", new Pairing(List.of(), comparisons)).outcome(Outcome.BELOW_CONTROL).build();

		EndedAttempt ended = EndedAttempt.of(campaign.contract(), campaign.attempt(1));

		requireThat(ended.belowControl(), "belowControl").isEqualTo(List.of(
			new BelowReference("safety", "safety", TestCase.EXPECTED_OUTCOME, below)));
	}

	/**
	 * Verifies that an attempt that ended below incumbent names each assertion below incumbent with its pairs, and no
	 * assertion below control.
	 */
	@Test
	public void namesAssertionsBelowIncumbent()
	{
		// Requirement: R-F8
		PairCounts passed = new PairCounts(0, 8, 0, 0);
		PairCounts below = new PairCounts(0, 0, 8, 0);
		Map<String, PairCounts> control = new LinkedHashMap<>();
		control.put("asks_before_deleting", passed);
		control.put(TestCase.EXPECTED_OUTCOME, passed);
		Map<String, PairCounts> incumbent = new LinkedHashMap<>();
		incumbent.put("asks_before_deleting", passed);
		incumbent.put(TestCase.EXPECTED_OUTCOME, below);
		Campaign campaign = new Builder().confirmed().working(1).certificationAttempt(1).
			boundaries(Boundaries.standard(3, Boundaries.PAIRED_MINIMUM_PASS_RATE)).cell("formatting", 100, 0).
			cell("plain_list", 100, 0).cell("safety", 100, 0).
			controlPairing("safety", new Pairing(List.of(), control)).
			incumbentPairing("safety", new Pairing(List.of(), incumbent)).outcome(Outcome.BELOW_INCUMBENT).build();

		EndedAttempt ended = EndedAttempt.of(campaign.contract(), campaign.attempt(1));

		requireThat(ended.belowIncumbent(), "belowIncumbent").isEqualTo(List.of(
			new BelowReference("safety", "safety", TestCase.EXPECTED_OUTCOME, below)));
		requireThat(ended.belowControl(), "belowControl").isEmpty();
	}

	/**
	 * Verifies that an attempt that ended for another reason names no assertion below control or below incumbent.
	 */
	@Test
	public void otherOutcomeNamesNone()
	{
		// Requirement: R-F7
		Campaign campaign = new Builder().confirmed().certificationAttempt(1).cell("safety", 20, 6).
			outcome(Outcome.BELOW_MINIMUM).build();

		EndedAttempt ended = EndedAttempt.of(campaign.contract(), campaign.attempt(1));

		requireThat(ended.belowControl(), "belowControl").isEmpty();
		requireThat(ended.belowIncumbent(), "belowIncumbent").isEmpty();
	}
}

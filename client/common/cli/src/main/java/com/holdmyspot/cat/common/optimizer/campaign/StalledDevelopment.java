package com.holdmyspot.cat.common.optimizer.campaign;

import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.statistics.ExploratoryBounds;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects stalled development. For each prompt test, it counts the consecutive completed development rounds that
 * make no {@link StallProgress progress}, and raises {@link PendingDecision.DevelopmentStalled} when
 * {@value StallProgress#ROUNDS} rounds in a row make none.
 */
final class StalledDevelopment
{
	/**
	 * Prevents instantiation.
	 */
	private StalledDevelopment()
	{
	}

	/**
	 * Records the best values of each prompt test that the draft's new round addresses and that has none yet, so that
	 * the rounds counted from this one are compared with the values that the tests had before the round began.
	 *
	 * @param draft the state, whose round has just started
	 */
	static void roundStarted(Draft draft)
	{
		for (String test : addressedTests(draft, draft.round))
		{
			if (!draft.stalls.containsKey(test))
				draft.stalls.put(test, observe(draft, test).asBest());
		}
	}

	/**
	 * Counts a completed round for each prompt test that it addressed, and pauses the campaign for the user's choice
	 * when a test stalled. A test with no recorded best values, such as one whose round began before they were
	 * recorded, gets them from its development estimates now and counts no round.
	 *
	 * @param draft the state after the round ended
	 * @param round the completed round
	 */
	static void roundCompleted(Draft draft, Round round)
	{
		Set<String> stalled = new LinkedHashSet<>();
		for (String test : addressedTests(draft, round))
		{
			Observation now = observe(draft, test);
			StallProgress before = draft.stalls.get(test);
			StallProgress after = now.asBest();
			if (before != null)
				after = before.afterRound(now.estimates(), now.metCells());
			draft.stalls.put(test, after);
			if (after.isStalled())
				stalled.add(test);
		}
		if (!stalled.isEmpty() && draft.header.lifecycle() == Lifecycle.ACTIVE)
			raise(draft, stalled);
	}

	/**
	 * Treats a round that ended in a promotion as progress for each prompt test that it addressed: the promotion rule
	 * already requires the promoted candidate to beat the working one, so those tests start counting rounds again from
	 * zero, with the promoted candidate's development estimates as their best values. The other tests that have
	 * counted rounds keep their count, measured against the promoted candidate from now on.
	 *
	 * @param draft the state after the promotion, whose working candidate has no development outcomes
	 * @param round the promoting round
	 */
	static void roundPromoted(Draft draft, Round round)
	{
		Set<String> addressed = addressedTests(draft, round);
		for (String test : new ArrayList<>(draft.stalls.keySet()))
		{
			Observation now = observe(draft, test);
			StallProgress after = now.asBest();
			if (!addressed.contains(test))
				after = new StallProgress(now.estimates(), now.metCells(), draft.stalls.get(test).rounds());
			draft.stalls.put(test, after);
		}
		for (String test : addressed)
		{
			if (!draft.stalls.containsKey(test))
				draft.stalls.put(test, observe(draft, test).asBest());
		}
	}

	/**
	 * Answers a stalled-development decision with the choice to continue: the stalled tests start counting rounds
	 * again from zero, and the decision is resolved.
	 *
	 * @param draft the state
	 * @param stalled the pending decision
	 */
	static void continueDevelopment(Draft draft, PendingDecision.DevelopmentStalled stalled)
	{
		for (String test : stalled.tests())
		{
			StallProgress progress = draft.stalls.get(test);
			if (progress != null)
				draft.stalls.put(test, progress.withoutRounds());
		}
		draft.resolve();
	}

	/**
	 * Raises the stalled-development decision for the stalled tests, joining them to the one that is already pending.
	 *
	 * @param draft the state
	 * @param stalled the stalled tests
	 */
	private static void raise(Draft draft, Set<String> stalled)
	{
		Set<String> tests = new LinkedHashSet<>(stalled);
		int pending = -1;
		for (int i = 0; i < draft.pendingDecisions.size(); ++i)
		{
			if (draft.pendingDecisions.get(i) instanceof PendingDecision.DevelopmentStalled existing)
			{
				tests.addAll(existing.tests());
				pending = i;
				break;
			}
		}
		List<String> inContractOrder = new ArrayList<>();
		for (Cell cell : draft.developmentCells())
		{
			if (tests.contains(cell.testId()) && !inContractOrder.contains(cell.testId()))
				inContractOrder.add(cell.testId());
		}
		PendingDecision.DevelopmentStalled decision = new PendingDecision.DevelopmentStalled(inContractOrder);
		if (pending < 0)
			draft.pendingDecisions.add(decision);
		else
			draft.pendingDecisions.set(pending, decision);
	}

	/**
	 * Returns the prompt tests that a round addressed.
	 *
	 * @param draft the state
	 * @param round the round
	 * @return the tests' identifiers, in contract order
	 */
	private static Set<String> addressedTests(Draft draft, Round round)
	{
		Set<String> tests = new LinkedHashSet<>();
		for (Cell cell : draft.developmentCells())
		{
			if (round.attempts().containsKey(cell.id()))
				tests.add(cell.testId());
		}
		return tests;
	}

	/**
	 * Measures a prompt test's development cells for the working candidate.
	 *
	 * @param draft the state
	 * @param test the test's identifier
	 * @return the test's development estimates and met cells
	 */
	private static Observation observe(Draft draft, String test)
	{
		BigDecimal minimumPassRate = draft.contract().minimumPassRate();
		Map<String, Double> estimates = new LinkedHashMap<>();
		int metCells = 0;
		for (Cell cell : draft.developmentCells())
		{
			if (!cell.testId().equals(test))
				continue;
			CellCounts counts = draft.developmentCount(cell.id());
			estimates.put(cell.id(), ExploratoryBounds.wilson(counts.passes(), counts.outcomes()).lower());
			if (CommitGate.isMetInCell(counts, minimumPassRate))
				++metCells;
		}
		return new Observation(estimates, metCells);
	}

	/**
	 * A prompt test's development cells at one moment.
	 *
	 * @param estimates the Wilson 95% lower bound of each development cell's pass rate, by cell identifier
	 * @param metCells the number of cells that meet the commit gate's count conditions
	 */
	private record Observation(Map<String, Double> estimates, int metCells)
	{
		/**
		 * Returns the progress that takes this observation as its best values, with no round counted.
		 *
		 * @return the progress
		 */
		StallProgress asBest()
		{
			return new StallProgress(estimates, metCells, List.of());
		}
	}
}

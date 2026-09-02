package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests deterministic candidate-race elimination and survivor limits.
 */
public final class CandidateRaceTest
{
	/**
	 * Creates the test fixture.
	 */
	public CandidateRaceTest()
	{
	}

	/**
	 * Verifies deterministic failures and regressions are eliminated before the strongest bounded survivors advance.
	 */
	@Test
	public void selectsBoundedStrongestSurvivors()
	{
		DevelopmentPolicy policy = new DevelopmentPolicy("development-v1", 2, 1, 3, 2, 1, 2, 2, 0, 2, 0.01);
		CandidateRace.Result result = CandidateRace.select(policy, List.of(
			entry("deterministic-failure", false, DevelopmentComparisonOperation.Action.BROADEN_COMPARISON, 2, 0),
			entry("regression", true, DevelopmentComparisonOperation.Action.REPAIR_REGRESSION, 0, 2),
			entry("strongest", true, DevelopmentComparisonOperation.Action.BROADEN_COMPARISON, 2, 0),
			entry("second", true, DevelopmentComparisonOperation.Action.BROADEN_COMPARISON, 1, 0),
			entry("third", true, DevelopmentComparisonOperation.Action.BROADEN_COMPARISON, 1, 1)));

		requireThat(result.status(), "status").isEqualTo(CandidateRace.Status.SURVIVORS);
		requireThat(result.survivors().stream().map(
			DevelopmentComparisonOperation.CandidateReference::identifier).toList(), "survivors").
			containsExactly(List.of("strongest", "second"));
		requireThat(result.eliminated().stream().map(CandidateRace.Eliminated::reason).toList(), "reasons").
			contains(CandidateRace.EliminationReason.DETERMINISTIC_FAILURE).
			contains(CandidateRace.EliminationReason.MATERIAL_REGRESSION).
			contains(CandidateRace.EliminationReason.SURVIVOR_LIMIT);
	}

	/**
	 * Verifies tied paired advantages are ranked by the challenger's weakest-family pass rate rather than identifier.
	 */
	@Test
	public void prefersHigherWeakestFamilyPassRate()
	{
		DevelopmentPolicy policy = new DevelopmentPolicy("development-v1", 2, 1, 3, 1, 1, 2, 2, 0, 2, 0.01);

		CandidateRace.Result result = CandidateRace.select(policy, List.of(
			passRateEntry("a-low", false), passRateEntry("z-high", true)));

		requireThat(result.survivors().stream().map(
			DevelopmentComparisonOperation.CandidateReference::identifier).toList(), "survivors").
			containsExactly(List.of("z-high"));
	}

	/**
	 * Creates one race entry.
	 *
	 * @param identifier the candidate identifier
	 * @param deterministicPass whether mechanical checks passed
	 * @param action the focused comparison action
	 * @param wins challenger wins
	 * @param losses challenger losses
	 * @return the race entry
	 */
	private static CandidateRace.Entry entry(String identifier, boolean deterministicPass,
		DevelopmentComparisonOperation.Action action, int wins, int losses)
	{
		DevelopmentComparisonOperation.CandidateReference champion = candidate("champion");
		DevelopmentComparisonOperation.CandidateReference challenger = candidate(identifier);
		int ties = 2 - wins - losses;
		DevelopmentPolicy.FamilyPairs family = new DevelopmentPolicy.FamilyPairs("clarity", wins, losses, ties);
		List<String> affectedFamilies = List.of();
		if (action == DevelopmentComparisonOperation.Action.REPAIR_REGRESSION)
			affectedFamilies = List.of("clarity");
		DevelopmentComparisonOperation.Result comparison = new DevelopmentComparisonOperation.Result(action, champion,
			List.of(family), pairs(identifier, wins, losses, ties), 0, affectedFamilies);
		return new CandidateRace.Entry(challenger, deterministicPass, comparison);
	}

	/**
	 * Creates exact paired results matching one aggregate summary.
	 *
	 * @param identifier the candidate identifier
	 * @param wins challenger-only passes
	 * @param losses champion-only passes
	 * @param ties both-pass ties
	 * @return the paired results
	 */
	private static List<DevelopmentComparisonOperation.PairResult> pairs(String identifier, int wins, int losses,
		int ties)
	{
		List<DevelopmentComparisonOperation.PairResult> result = new ArrayList<>();
		for (int index = 0; index < wins + losses + ties; ++index)
		{
			boolean championPassed = index >= wins;
			boolean challengerPassed = index < wins || index >= wins + losses;
			result.add(pair(identifier, index, championPassed, challengerPassed));
		}
		return List.copyOf(result);
	}

	/**
	 * Creates one paired prompt-test result.
	 *
	 * @param identifier the candidate identifier
	 * @param index the prompt-test index
	 * @param championPassed whether the champion passed
	 * @param challengerPassed whether the challenger passed
	 * @return the paired result
	 */
	private static DevelopmentComparisonOperation.PairResult pair(String identifier, int index,
		boolean championPassed, boolean challengerPassed)
	{
		String promptTest = identifier + "-prompt_test-" + index;
		return new DevelopmentComparisonOperation.PairResult("clarity", promptTest, String.valueOf('e').repeat(64),
			identifier, outcome(championPassed, identifier + "-champion-" + index, 'c'),
			outcome(challengerPassed, identifier + "-challenger-" + index, 'd'));
	}

	/**
	 * Creates a focused comparison whose paired advantage is a tie but whose absolute challenger outcome differs.
	 *
	 * @param identifier the candidate identifier
	 * @param passed whether the challenger and champion both pass
	 * @return the race entry
	 */
	private static CandidateRace.Entry passRateEntry(String identifier, boolean passed)
	{
		DevelopmentComparisonOperation.CandidateReference champion = candidate("champion");
		DevelopmentComparisonOperation.CandidateReference challenger = candidate(identifier);
		DevelopmentPolicy.FamilyPairs family = new DevelopmentPolicy.FamilyPairs("clarity", 0, 0, 1);
		DevelopmentComparisonOperation.TrialOutcome championOutcome = outcome(passed, identifier + "-champion", 'c');
		DevelopmentComparisonOperation.TrialOutcome challengerOutcome = outcome(passed, identifier + "-challenger", 'd');
		DevelopmentComparisonOperation.PairResult pair = new DevelopmentComparisonOperation.PairResult("clarity",
			identifier + "-prompt_test", String.valueOf('e').repeat(64), identifier, championOutcome, challengerOutcome);
		DevelopmentComparisonOperation.Result comparison = new DevelopmentComparisonOperation.Result(
			DevelopmentComparisonOperation.Action.BROADEN_COMPARISON, champion, List.of(family), List.of(pair), 0,
			List.of());
		return new CandidateRace.Entry(challenger, true, comparison);
	}

	/**
	 * Creates one retained trial outcome.
	 *
	 * @param passed whether the trial passed
	 * @param sessionIdentifier the unique session
	 * @param fingerprintCharacter the evidence fingerprint character
	 * @return the outcome
	 */
	private static DevelopmentComparisonOperation.TrialOutcome outcome(boolean passed, String sessionIdentifier,
		char fingerprintCharacter)
	{
		return new DevelopmentComparisonOperation.TrialOutcome(passed, sessionIdentifier,
			String.valueOf(fingerprintCharacter).repeat(64), 0, 0, 1);
	}

	/**
	 * Creates one identity-only candidate fixture.
	 *
	 * @param identifier the candidate identifier
	 * @return the candidate reference
	 */
	private static DevelopmentComparisonOperation.CandidateReference candidate(String identifier)
	{
		char fingerprintCharacter = 'b';
		if (identifier.equals("champion"))
			fingerprintCharacter = 'a';
		return new DevelopmentComparisonOperation.CandidateReference(identifier, Path.of(identifier),
			String.valueOf(fingerprintCharacter).repeat(64));
	}
}

package io.github.cowwoc.cat.common.optimization.development;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Eliminates failed development challengers and selects the policy-bounded strongest survivors for broad comparison.
 */
public final class CandidateRace
{
	/**
	 * Prevents construction of this static candidate-race owner.
	 */
	private CandidateRace()
	{
	}

	/**
	 * Applies deterministic-check, focused-comparison, and survivor-limit decisions.
	 *
	 * @param policy the frozen development policy
	 * @param entries every challenger in one generated set
	 * @return the selected survivors and every elimination reason
	 */
	public static Result select(DevelopmentPolicy policy, List<Entry> entries)
	{
		Objects.requireNonNull(policy, "policy must not be null");
		Objects.requireNonNull(entries, "entries must not be null");
		if (entries.isEmpty())
			throw new IllegalArgumentException("entries must not be empty");
		Set<String> identifiers = new HashSet<>();
		List<Scored> eligible = new ArrayList<>();
		List<Eliminated> eliminated = new ArrayList<>();
		for (Entry entry : entries)
		{
			Objects.requireNonNull(entry, "entries must not contain null elements");
			String identifier = entry.candidate().identifier();
			if (!identifiers.add(identifier))
				throw new IllegalArgumentException("Duplicate race candidate: " + identifier);
			EliminationReason reason = eliminationReason(entry);
			if (reason != null)
			{
				eliminated.add(new Eliminated(entry.candidate(), reason));
				continue;
			}
			eligible.add(score(entry));
		}
		eligible.sort(Comparator.comparingDouble(Scored::weakestFamilyPassRate).reversed().
			thenComparing(Comparator.comparingDouble(Scored::aggregatePassRate).reversed()).
			thenComparing(Comparator.comparingInt(Scored::totalNetWins).reversed()).
			thenComparing(scored -> scored.candidate().identifier()));
		List<DevelopmentComparisonOperation.CandidateReference> survivors = new ArrayList<>();
		for (int index = 0; index < eligible.size(); ++index)
		{
			Scored scored = eligible.get(index);
			if (index < policy.maximumSurvivors())
				survivors.add(scored.candidate());
			else
				eliminated.add(new Eliminated(scored.candidate(), EliminationReason.SURVIVOR_LIMIT));
		}
		eliminated.sort(Comparator.comparing(value -> value.candidate().identifier()));
		Status status = Status.SURVIVORS;
		if (survivors.isEmpty())
			status = Status.NO_ELIGIBLE_CHALLENGER;
		return new Result(status, survivors, eliminated);
	}

	/**
	 * Identifies a terminal elimination reason, or returns {@code null} for an eligible focused winner.
	 *
	 * @param entry the race entry
	 * @return the reason, or {@code null}
	 */
	private static EliminationReason eliminationReason(Entry entry)
	{
		if (!entry.deterministicChecksPassed())
			return EliminationReason.DETERMINISTIC_FAILURE;
		return switch (entry.focusedComparison().action())
		{
			case BROADEN_COMPARISON -> null;
			case REPAIR_REGRESSION -> EliminationReason.MATERIAL_REGRESSION;
			case REJECT -> EliminationReason.TERMINAL_REJECTION;
			case MORE_PAIRS -> EliminationReason.INCONCLUSIVE_COMPARISON;
			case PROMOTE -> EliminationReason.INVALID_COMPARISON_STAGE;
		};
	}

	/**
	 * Scores one focused winner by its weakest-family and aggregate challenger pass rates, then paired advantage.
	 *
	 * @param entry the eligible race entry
	 * @return the score
	 */
	private static Scored score(Entry entry)
	{
		Map<String, int[]> outcomeCounts = new TreeMap<>();
		for (DevelopmentComparisonOperation.PairResult pair : entry.focusedComparison().pairs())
		{
			int[] counts = outcomeCounts.computeIfAbsent(pair.familyIdentifier(), ignored -> new int[2]);
			++counts[1];
			if (pair.challenger().passed())
				++counts[0];
		}
		if (outcomeCounts.isEmpty())
			throw new IllegalArgumentException("Focused comparison contains no paired outcomes");

		double weakest = 1;
		int passes = 0;
		int trials = 0;
		int total = 0;
		for (DevelopmentPolicy.FamilyPairs family : entry.focusedComparison().familyPairs())
		{
			int[] counts = outcomeCounts.remove(family.familyIdentifier());
			if (counts == null || counts[1] != family.pairCount())
			{
				throw new IllegalArgumentException("Focused comparison pair summary differs from retained outcomes: " +
					family.familyIdentifier());
			}
			weakest = Math.min(weakest, (double) counts[0] / counts[1]);
			passes = Math.addExact(passes, counts[0]);
			trials = Math.addExact(trials, counts[1]);
			int netWins = family.challengerWins() - family.challengerLosses();
			total = Math.addExact(total, netWins);
		}
		if (!outcomeCounts.isEmpty() || trials == 0)
			throw new IllegalArgumentException("Focused comparison outcomes name an unreported family");
		return new Scored(entry.candidate(), weakest, (double) passes / trials, total);
	}

	/**
	 * Names whether the race produced any broad-comparison survivor.
	 */
	public enum Status
	{
		SURVIVORS,
		NO_ELIGIBLE_CHALLENGER
	}

	/**
	 * Names one deterministic elimination cause.
	 */
	public enum EliminationReason
	{
		DETERMINISTIC_FAILURE,
		MATERIAL_REGRESSION,
		TERMINAL_REJECTION,
		INCONCLUSIVE_COMPARISON,
		INVALID_COMPARISON_STAGE,
		SURVIVOR_LIMIT
	}

	/**
	 * Holds one candidate's pre-broad-comparison evidence.
	 *
	 * @param candidate the challenger
	 * @param deterministicChecksPassed whether every mechanical control passed
	 * @param focusedComparison the terminal focused comparison
	 */
	public record Entry(DevelopmentComparisonOperation.CandidateReference candidate,
		boolean deterministicChecksPassed, DevelopmentComparisonOperation.Result focusedComparison)
	{
		/**
		 * Validates one race entry.
		 *
		 * @param candidate the challenger
		 * @param deterministicChecksPassed whether every mechanical control passed
		 * @param focusedComparison the terminal focused comparison
		 */
		public Entry
		{
			Objects.requireNonNull(candidate, "candidate must not be null");
			Objects.requireNonNull(focusedComparison, "focusedComparison must not be null");
		}
	}

	/**
	 * Describes one eliminated challenger.
	 *
	 * @param candidate the challenger
	 * @param reason the deterministic elimination reason
	 */
	public record Eliminated(DevelopmentComparisonOperation.CandidateReference candidate, EliminationReason reason)
	{
		/**
		 * Validates one elimination.
		 *
		 * @param candidate the challenger
		 * @param reason the deterministic elimination reason
		 */
		public Eliminated
		{
			Objects.requireNonNull(candidate, "candidate must not be null");
			Objects.requireNonNull(reason, "reason must not be null");
		}
	}

	/**
	 * Holds the complete race result.
	 *
	 * @param status whether survivors remain
	 * @param survivors candidates selected for broad comparison in rank order
	 * @param eliminated every eliminated candidate and reason
	 */
	public record Result(Status status, List<DevelopmentComparisonOperation.CandidateReference> survivors,
		List<Eliminated> eliminated)
	{
		/**
		 * Validates and snapshots one race result.
		 *
		 * @param status whether survivors remain
		 * @param survivors candidates selected for broad comparison in rank order
		 * @param eliminated every eliminated candidate and reason
		 */
		public Result
		{
			Objects.requireNonNull(status, "status must not be null");
			survivors = List.copyOf(survivors);
			eliminated = List.copyOf(eliminated);
			if ((status == Status.SURVIVORS) != !survivors.isEmpty())
				throw new IllegalArgumentException("Only a survivor result must contain survivors");
		}
	}

	/**
	 * Holds one internal race score.
	 *
	 * @param candidate the candidate
	 * @param weakestFamilyPassRate the minimum challenger pass rate across tested families
	 * @param aggregatePassRate the challenger pass rate across all paired outcomes
	 * @param totalNetWins aggregate challenger-only passes minus champion-only passes
	 */
	private record Scored(DevelopmentComparisonOperation.CandidateReference candidate, double weakestFamilyPassRate,
		double aggregatePassRate, int totalNetWins)
	{
	}
}

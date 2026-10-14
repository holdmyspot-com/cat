package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Baseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Pairing;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable;
import io.github.cowwoc.cat.common.optimizer.campaign.IncumbentBaseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.Reference;
import io.github.cowwoc.cat.common.optimizer.campaign.CommitGate;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.statistics.PairedComparison;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.PairTally;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The minimum remaining trials: the number still required if every remaining trial
 * passes.
 * <p>
 * The count is a lower bound on the work that remains. It is never a token total, a time estimate or a completion
 * probability.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param phase the phase that the count describes
 * @param entries the trials still required, by cell and reason
 * @param total the sum of the entries, or {@code null} if the count is unavailable
 * @param unavailable why {@code total} is unavailable, or {@code null} if it is available
 */
public record RemainingWork(Phase phase, List<Entry> entries, Integer total, String unavailable)
{
	/**
	 * What the trials of an entry are required for.
	 */
	public enum Kind
	{
		/**
		 * Development trials that the commit gate still requires of a cell.
		 */
		COMMIT_GATE,
		/**
		 * Trials that the incumbent's baseline still requires of a cell.
		 */
		BASELINE,
		/**
		 * The all-pass minimum that the next certification attempt requires of a cell.
		 */
		NEXT_ATTEMPT,
		/**
		 * Formal trials that a running certification attempt still requires of an unresolved cell.
		 */
		CERTIFICATION,
		/**
		 * Control trials, and the formal trials that they pair with beyond the cell's other formal trials, that a
		 * running certification attempt still requires of a cell whose control comparisons are undecided.
		 */
		CONTROL,
		/**
		 * Incumbent trials, and the formal trials that they pair with beyond the cell's other formal trials and those
		 * that its control entry counts, that a running certification attempt still requires of a cell whose
		 * incumbent comparisons are undecided.
		 */
		INCUMBENT
	}

	/**
	 * The trials that one cell still requires for one reason.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param cell the cell's identifier
	 * @param kind what the trials are required for
	 * @param trials the trials still required, or {@code null} if the count is unavailable
	 * @param unavailable why {@code trials} is unavailable, or {@code null} if it is available
	 */
	public record Entry(String cell, Kind kind, Integer trials, String unavailable)
	{
		/**
		 * Creates an entry.
		 *
		 * @param cell the cell's identifier
		 * @param kind what the trials are required for
		 * @param trials the trials still required, or {@code null} if the count is unavailable
		 * @param unavailable why {@code trials} is unavailable, or {@code null} if it is available
		 * @throws NullPointerException if {@code cell} or {@code kind} are null
		 * @throws IllegalArgumentException if {@code trials} is negative, or {@code trials} is null exactly when
		 *   {@code unavailable} is null
		 */
		public Entry
		{
			requireThat(cell, "cell").isNotNull();
			requireThat(kind, "kind").isNotNull();
			if (trials != null)
				requireThat(trials, "trials").isNotNegative();
			requireThat(trials == null, "trialsUnavailable").isEqualTo(unavailable != null, "hasReason");
		}
	}

	/**
	 * Creates a count.
	 *
	 * @param phase the phase that the count describes
	 * @param entries the trials still required, by cell and reason
	 * @param total the sum of the entries, or {@code null} if the count is unavailable
	 * @param unavailable why {@code total} is unavailable, or {@code null} if it is available
	 * @throws NullPointerException if {@code phase} or {@code entries} are null
	 * @throws IllegalArgumentException if {@code total} is negative, or {@code total} is null exactly when
	 *   {@code unavailable} is null
	 */
	public RemainingWork
	{
		requireThat(phase, "phase").isNotNull();
		requireThat(entries, "entries").isNotNull();
		if (total != null)
			requireThat(total, "total").isNotNegative();
		requireThat(total == null, "totalUnavailable").isEqualTo(unavailable != null, "hasReason");
		entries = List.copyOf(entries);
	}

	/**
	 * Computes the minimum remaining trials of a campaign.
	 * <p>
	 * <b>Development:</b> for every development cell, inside the certification scope or not, the fewest further
	 * all-pass trials after which the commit gate's count conditions hold (at least
	 * {@code max(0, max(100, 100·f) − n)} with {@code f} failures in {@code n} outcomes, and more when a recent batch
	 * holds 2 or more failures), plus, for every cell of the certification scope, the incumbent baseline's remaining
	 * trials if every one passes and the next certification attempt's all-pass minimum.
	 * <p>
	 * <b>Formal:</b> for every unresolved cell, the smallest {@code s} such that {@code E↑(S + s, F)} exceeds
	 * {@code 1/α_i} while its minimum-pass-rate part is undecided, plus the fewest further pairs after which every
	 * undecided control comparison's {@code E_ctl↑} exceeds {@code 1/α_i} if both trials of every pair pass, the same
	 * for every undecided incumbent comparison unless the candidate is the incumbent, plus the formal trials that
	 * those pairs need beyond the cell's other formal trials. Both kinds pair with the same formal trials, so those
	 * formal trials count once. A comparison that has not started counts from no pairs. A cell that cannot certify
	 * within its cap has no minimum.
	 *
	 * @param campaign the campaign
	 * @return the count
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static RemainingWork of(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		Phase phase = Phase.of(campaign);
		if (phase == Phase.UNCONFIRMED)
			return new RemainingWork(phase, List.of(), null, "No contract is confirmed, so no trial is required yet.");
		if (campaign.lifecycle() == Lifecycle.FINISHED || campaign.lifecycle() == Lifecycle.CLOSED ||
			campaign.delivery() != null)
		{
			return new RemainingWork(phase, List.of(), null, "The campaign has ended, so no trial is required.");
		}
		Optional<CertificationAttempt> running = campaign.runningAttempt();
		if (running.isPresent())
			return formal(campaign, running.get());
		return development(campaign);
	}

	/**
	 * Computes the minimum remaining trials of the development phase: the commit gate in each development cell, and the
	 * incumbent baseline and the next attempt in each cell of the certification scope.
	 *
	 * @param campaign the campaign
	 * @return the count
	 */
	private static RemainingWork development(Campaign campaign)
	{
		List<String> scope = CellTier.scopeCellIds(campaign.contract());
		Set<String> scopeIds = Set.copyOf(scope);
		Set<String> development = Set.copyOf(CellTier.developmentCellIds(campaign));
		BigDecimal minimumPassRate = campaign.contract().minimumPassRate();
		int nextAttempt = DecisionBoundaries.allPassMinimum(
			ErrorBudget.certificationLevel(campaign.nextCertificationIndex()), minimumPassRate);
		List<Entry> entries = new ArrayList<>();
		int total = 0;
		for (String id : CellTier.withDevelopmentCellIds(campaign, scope))
		{
			if (development.contains(id))
			{
				int commitGate = commitGateRemaining(campaign.development().count(id), minimumPassRate);
				entries.add(new Entry(id, Kind.COMMIT_GATE, commitGate, null));
				total += commitGate;
			}
			if (scopeIds.contains(id))
			{
				int baseline = baselineRemaining(campaign.baseline(), id, scope.size(), minimumPassRate);
				entries.add(new Entry(id, Kind.BASELINE, baseline, null));
				entries.add(new Entry(id, Kind.NEXT_ATTEMPT, nextAttempt, null));
				total += baseline + nextAttempt;
			}
		}
		return new RemainingWork(Phase.DEVELOPMENT, entries, total, null);
	}

	/**
	 * Returns the fewest further all-pass trials after which a cell meets the commit gate's count conditions: at
	 * least 100 trials, the minimum observed pass rate, and the latest two complete batches within the failure limit.
	 *
	 * @param counts the cell's development counts
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return the trials
	 */
	private static int commitGateRemaining(CellCounts counts, BigDecimal minimumPassRate)
	{
		int trials = 0;
		while (!CommitGate.isMetInCell(counts, minimumPassRate))
		{
			counts = counts.add(Verdict.PASS);
			++trials;
		}
		return trials;
	}

	/**
	 * Returns the incumbent baseline trials that a cell still requires if every one passes.
	 *
	 * @param baseline the incumbent baseline
	 * @param cell the cell's identifier
	 * @param cells the number of cells in the certification scope
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return the trials
	 */
	private static int baselineRemaining(IncumbentBaseline baseline, String cell, int cells,
		BigDecimal minimumPassRate)
	{
		CellCounts counts = baseline.count(cell);
		int trials = 0;
		while (counts.outcomes() < Baseline.target(counts, baseline.index(), cells, minimumPassRate))
		{
			counts = counts.add(Verdict.PASS);
			++trials;
		}
		return trials;
	}

	/**
	 * Computes the minimum remaining trials of a running certification attempt.
	 *
	 * @param campaign the campaign
	 * @param attempt the attempt
	 * @return the count
	 */
	private static RemainingWork formal(Campaign campaign, CertificationAttempt attempt)
	{
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		List<Reference> references;
		if (PairedComparison.isImpliedBy(boundaries.minimumPassRate()))
		{
			// The comparisons follow from the minimum pass rate, so they need no pairs
			references = List.of();
		}
		else if (attempt.candidate().snapshot().equals(campaign.incumbent().snapshot()))
			references = List.of(Reference.CONTROL);
		else
			references = List.of(Reference.CONTROL, Reference.INCUMBENT);
		List<Entry> entries = new ArrayList<>();
		int total = 0;
		boolean available = true;
		for (String cell : attempt.procedure().cells())
		{
			if (DecisionTable.cellDecision(attempt, cell) != DecisionTable.CellDecision.UNDECIDED)
				continue;
			CellCounts counts = attempt.count(cell);
			int certification = 0;
			if (DecisionTable.minimumDecision(boundaries, counts) == DecisionTable.CellDecision.UNDECIDED)
			{
				Entry entry = certification(boundaries, cell, counts);
				entries.add(entry);
				if (entry.trials() == null)
				{
					// The paired comparisons start only once the minimum-pass-rate part certifies
					available = false;
					continue;
				}
				certification = entry.trials();
				total += certification;
			}
			// The formal trials that an earlier kind's pairs lack, which a later kind's pairs share
			int counted = 0;
			for (Reference reference : references)
			{
				PairsNeeded needed = pairsNeeded(attempt, cell, certification, reference);
				Kind kind = switch (reference)
				{
					case CONTROL -> Kind.CONTROL;
					case INCUMBENT -> Kind.INCUMBENT;
				};
				if (needed.unavailable() != null)
				{
					entries.add(new Entry(cell, kind, null, needed.unavailable()));
					available = false;
					continue;
				}
				int trials = needed.pairs() + Math.max(0, needed.lacking() - counted);
				counted = Math.max(counted, needed.lacking());
				entries.add(new Entry(cell, kind, trials, null));
				total += trials;
			}
		}
		if (available)
			return new RemainingWork(Phase.FORMAL, entries, total, null);
		return new RemainingWork(Phase.FORMAL, entries, null,
			"A cell cannot certify within its cap, so no minimum exists.");
	}

	/**
	 * Returns the formal trials that a cell's minimum-pass-rate part still requires if every one passes.
	 *
	 * @param boundaries the attempt's boundaries
	 * @param cell the cell's identifier
	 * @param counts the cell's adopted counts, whose minimum-pass-rate part is undecided
	 * @return the entry
	 */
	private static Entry certification(DecisionBoundaries boundaries, String cell, CellCounts counts)
	{
		int minimum = boundaries.minimumCertifyingPasses().get(counts.failures());
		if (minimum > boundaries.cap() - counts.failures())
		{
			return new Entry(cell, Kind.CERTIFICATION, null,
				"No state with " + counts.failures() + " failures certifies within the cap of " + boundaries.cap() +
					" outcomes.");
		}
		return new Entry(cell, Kind.CERTIFICATION, minimum - counts.passes(), null);
	}

	/**
	 * The further pairs that a cell's comparisons with one kind of reference trial require if both trials of every
	 * pair pass.
	 *
	 * @param pairs the reference trials still required
	 * @param lacking the formal trials that those pairs need beyond the cell's other formal trials
	 * @param unavailable why the comparisons cannot pass within the cap, or {@code null} if they can
	 */
	private record PairsNeeded(int pairs, int lacking, String unavailable)
	{
	}

	/**
	 * Returns the reference trials of one kind that a cell still requires if both trials of every further pair pass,
	 * and the formal trials that those pairs need beyond the cell's other formal trials. The pairs are shared by the
	 * cell's assertions, so the cell requires the pairs of its slowest undecided comparison.
	 *
	 * @param attempt the attempt
	 * @param cell the cell's identifier
	 * @param certification the formal trials that the cell's minimum-pass-rate part still requires
	 * @param reference the kind of reference trial
	 * @return the pairs needed
	 */
	private static PairsNeeded pairsNeeded(CertificationAttempt attempt, String cell, int certification,
		Reference reference)
	{
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		SignificanceLevel level = boundaries.level();
		Pairing pairing = attempt.pairing(reference, cell);
		String name = EnumNames.lowerCase(reference);
		int further = boundaries.cap() - pairing.pairs();
		if (!pairing.isComparing())
		{
			int needed = PairedComparison.fewestEvenPairsToPass(PairCounts.EMPTY.tally(), level, further);
			if (needed > further)
			{
				return new PairsNeeded(0, 0, "The " + name + " comparisons cannot pass within the cap of " +
					boundaries.cap() + " pairs.");
			}
			// Every formal outcome, including those that the minimum-pass-rate part still requires, pairs with a
			// reference trial once the comparisons start
			int formal = attempt.count(cell).outcomes() + certification;
			return new PairsNeeded(needed, Math.max(0, needed - formal), null);
		}

		int pairs = 0;
		for (Map.Entry<String, PairCounts> comparison : pairing.comparisons().entrySet())
		{
			PairTally tally = comparison.getValue().tally();
			if (PairedComparison.passes(tally, level))
				continue;
			int needed = PairedComparison.fewestEvenPairsToPass(tally, level, further);
			if (needed > further)
			{
				return new PairsNeeded(0, 0, "The " + name + " comparison of " + comparison.getKey() +
					" cannot pass within the cap of " + boundaries.cap() + " pairs.");
			}
			pairs = Math.max(pairs, needed);
		}
		int formal = pairing.unpaired().size();
		return new PairsNeeded(pairs, Math.max(0, pairs - formal), null);
	}
}

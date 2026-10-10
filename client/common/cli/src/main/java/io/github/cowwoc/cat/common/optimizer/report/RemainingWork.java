package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Baseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.ControlPairing;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable;
import io.github.cowwoc.cat.common.optimizer.campaign.IncumbentBaseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.ReadinessGate;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.statistics.ControlComparison;
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
		 * Development trials that the readiness gate still requires of a cell.
		 */
		READINESS,
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
		CONTROL
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
	 * all-pass trials after which the readiness gate's count conditions hold (at least
	 * {@code max(0, max(100, 100·f) − n)} with {@code f} failures in {@code n} outcomes, and more when a recent batch
	 * holds 2 or more failures), plus, for every cell of the certification scope, the incumbent baseline's remaining
	 * trials if every one passes and the next certification attempt's all-pass minimum.
	 * <p>
	 * <b>Formal:</b> for every unresolved cell, the smallest {@code s} such that {@code E↑(S + s, F)} exceeds
	 * {@code 1/α_i} while its minimum-pass-rate part is undecided, plus the fewest further pairs after which every
	 * undecided control comparison's {@code E_ctl↑} exceeds {@code 1/α_i} if both trials of every pair pass, plus the
	 * formal trials that those pairs need beyond the cell's other formal trials. A comparison that has not started
	 * counts from no pairs. A cell that cannot certify within its cap has no minimum.
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
			return formal(running.get());
		return development(campaign);
	}

	/**
	 * Computes the minimum remaining trials of the development phase: readiness in each development cell, and the
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
				int readiness = readinessRemaining(campaign.development().count(id), minimumPassRate);
				entries.add(new Entry(id, Kind.READINESS, readiness, null));
				total += readiness;
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
	 * Returns the fewest further all-pass trials after which a cell meets the readiness gate's count conditions: at
	 * least 100 trials, the minimum observed pass rate, and the latest two complete batches within the failure limit.
	 *
	 * @param counts the cell's development counts
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return the trials
	 */
	private static int readinessRemaining(CellCounts counts, BigDecimal minimumPassRate)
	{
		int trials = 0;
		while (!ReadinessGate.isReady(counts, minimumPassRate))
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
	 * @param attempt the attempt
	 * @return the count
	 */
	private static RemainingWork formal(CertificationAttempt attempt)
	{
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
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
					// The control comparisons start only once the minimum-pass-rate part certifies
					available = false;
					continue;
				}
				certification = entry.trials();
				total += certification;
			}
			Entry control = control(attempt, cell, certification);
			entries.add(control);
			if (control.trials() == null)
				available = false;
			else
				total += control.trials();
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
	 * Returns the control trials that a cell still requires if both trials of every further pair pass, and the formal
	 * trials that those pairs need beyond the cell's other formal trials. The pairs are shared by the cell's
	 * assertions, so the cell requires the pairs of its slowest undecided comparison.
	 *
	 * @param attempt the attempt
	 * @param cell the cell's identifier
	 * @param certification the formal trials that the cell's minimum-pass-rate part still requires
	 * @return the entry
	 */
	private static Entry control(CertificationAttempt attempt, String cell, int certification)
	{
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		SignificanceLevel level = boundaries.level();
		ControlPairing pairing = attempt.pairing(cell);
		int further = boundaries.cap() - pairing.pairs();
		if (!pairing.isComparing())
		{
			int needed = ControlComparison.fewestEvenPairsToPass(PairCounts.EMPTY.tally(), level, further);
			if (needed > further)
			{
				return new Entry(cell, Kind.CONTROL, null,
					"The control comparisons cannot pass within the cap of " + boundaries.cap() + " pairs.");
			}
			// Every formal outcome, including those that the minimum-pass-rate part still requires, pairs with a
			// control trial once the comparisons start
			int formal = attempt.count(cell).outcomes() + certification;
			return new Entry(cell, Kind.CONTROL, needed + Math.max(0, needed - formal), null);
		}

		int pairs = 0;
		for (Map.Entry<String, PairCounts> comparison : pairing.comparisons().entrySet())
		{
			PairTally tally = comparison.getValue().tally();
			if (ControlComparison.passes(tally, level))
				continue;
			int needed = ControlComparison.fewestEvenPairsToPass(tally, level, further);
			if (needed > further)
			{
				return new Entry(cell, Kind.CONTROL, null, "The control comparison of " + comparison.getKey() +
					" cannot pass within the cap of " + boundaries.cap() + " pairs.");
			}
			pairs = Math.max(pairs, needed);
		}
		int formal = pairing.unpaired().size();
		return new Entry(cell, Kind.CONTROL, pairs + Math.max(0, pairs - formal), null);
	}
}

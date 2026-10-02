package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Baseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable;
import io.github.cowwoc.cat.common.optimizer.campaign.IncumbentBaseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.ReadinessGate;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
		CERTIFICATION
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
	 * <b>Development:</b> for every cell, the fewest further all-pass trials after which the readiness gate's count
	 * conditions hold (at least {@code max(0, max(100, 100·f) − n)} with {@code f} failures in {@code n} outcomes, and
	 * more when a recent batch holds 2 or more failures), plus the incumbent baseline's remaining trials if every one
	 * passes, plus the next certification attempt's all-pass minimum.
	 * <p>
	 * <b>Formal:</b> for every unresolved cell, the smallest {@code s} such that {@code E↑(S + s, F)} exceeds
	 * {@code 1/α_i}. A cell that cannot certify within its cap has no minimum.
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
	 * Computes the minimum remaining trials of the development phase.
	 *
	 * @param campaign the campaign
	 * @return the count
	 */
	private static RemainingWork development(Campaign campaign)
	{
		Contract contract = campaign.contract();
		List<String> cells = contract.cellIds();
		int nextAttempt = DecisionBoundaries.allPassMinimum(
			ErrorBudget.certificationLevel(campaign.nextCertificationIndex()));
		List<Entry> entries = new ArrayList<>();
		int total = 0;
		for (String id : cells)
		{
			CellCounts counts = campaign.development().count(id);
			int readiness = readinessRemaining(counts);
			int baseline = baselineRemaining(campaign.baseline(), id, cells.size());
			entries.add(new Entry(id, Kind.READINESS, readiness, null));
			entries.add(new Entry(id, Kind.BASELINE, baseline, null));
			entries.add(new Entry(id, Kind.NEXT_ATTEMPT, nextAttempt, null));
			total += readiness + baseline + nextAttempt;
		}
		return new RemainingWork(Phase.DEVELOPMENT, entries, total, null);
	}

	/**
	 * Returns the fewest further all-pass trials after which a cell meets the readiness gate's count conditions: at
	 * least 100 trials, at least 99% passes, and the latest two complete batches within the failure limit.
	 *
	 * @param counts the cell's development counts
	 * @return the trials
	 */
	private static int readinessRemaining(CellCounts counts)
	{
		int trials = 0;
		while (!ReadinessGate.isReady(counts))
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
	 * @param cells the number of cells
	 * @return the trials
	 */
	private static int baselineRemaining(IncumbentBaseline baseline, String cell, int cells)
	{
		CellCounts counts = baseline.count(cell);
		int trials = 0;
		while (counts.outcomes() < Baseline.target(counts, baseline.index(), cells))
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
			CellCounts counts = attempt.count(cell);
			if (DecisionTable.cellDecision(boundaries, counts) != DecisionTable.CellDecision.UNDECIDED)
				continue;
			int minimum = boundaries.minimumCertifyingPasses().get(counts.failures());
			if (minimum > boundaries.cap() - counts.failures())
			{
				entries.add(new Entry(cell, Kind.CERTIFICATION, null,
					"No state with " + counts.failures() + " failures certifies within the cap of " +
						boundaries.cap() + " outcomes."));
				available = false;
				continue;
			}
			int trials = minimum - counts.passes();
			entries.add(new Entry(cell, Kind.CERTIFICATION, trials, null));
			total += trials;
		}
		if (available)
			return new RemainingWork(Phase.FORMAL, entries, total, null);
		return new RemainingWork(Phase.FORMAL, entries, null,
			"A cell cannot certify within its cap, so no minimum exists.");
	}
}

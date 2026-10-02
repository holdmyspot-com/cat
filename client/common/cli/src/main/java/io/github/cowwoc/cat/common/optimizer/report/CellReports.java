package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Baseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Comparability;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable;
import io.github.cowwoc.cat.common.optimizer.campaign.IncumbentBaseline;
import io.github.cowwoc.cat.common.optimizer.campaign.ReadinessGate;
import io.github.cowwoc.cat.common.optimizer.statistics.Comparison;
import io.github.cowwoc.cat.common.optimizer.statistics.ComparisonBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ConfidenceBound;
import io.github.cowwoc.cat.common.optimizer.statistics.ConfidenceBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.Interval;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the estimated pass rate, improvement over the incumbent, comparison with 95% and error bars for each cell.
 */
final class CellReports
{
	/**
	 * The label of a development pass rate.
	 */
	static final String DEVELOPMENT_ESTIMATE = "development estimate";
	/**
	 * The label of a formal pass rate.
	 */
	static final String FORMAL_ESTIMATE = "formal estimate";
	/**
	 * The comparison state of every development cell.
	 */
	static final String NOT_CERTIFIED = "not certified";
	/**
	 * The state of a cell that is proven better than the incumbent.
	 */
	static final String PROVEN_BETTER = "proven better";
	/**
	 * The state of a cell that is proven worse than the incumbent.
	 */
	static final String PROVEN_WORSE = "proven worse";
	/**
	 * The state of a cell that is neither proven better nor proven worse.
	 */
	static final String UNDECIDED = "undecided";
	/**
	 * The method of the descriptive error bar.
	 */
	private static final String WILSON = "Wilson 95% interval";

	/**
	 * Prevents instantiation.
	 */
	private CellReports()
	{
	}

	/**
	 * Builds the reports of a campaign's cells.
	 *
	 * @param campaign the campaign, which has a confirmed contract
	 * @param attempt the certification attempt to report, or {@code null} to report the working candidate's
	 *   development results
	 * @return the reports, in contract order
	 */
	static List<CellReport> of(Campaign campaign, CertificationAttempt attempt)
	{
		List<CellReport> reports = new ArrayList<>();
		for (String cell : campaign.contract().cellIds())
		{
			if (attempt == null)
				reports.add(development(campaign, cell));
			else
				reports.add(formal(campaign, attempt, cell));
		}
		return reports;
	}

	/**
	 * Builds the report of a cell from the working candidate's development counts.
	 *
	 * @param campaign the campaign
	 * @param cell the cell's identifier
	 * @return the report
	 */
	private static CellReport development(Campaign campaign, String cell)
	{
		CellCounts counts = campaign.development().count(cell);
		int trials = counts.outcomes();
		CellReport.PassRate passRate = passRate(DEVELOPMENT_ESTIMATE, counts, "no adopted development outcomes");
		List<CellReport.ErrorBar> errorBars = new ArrayList<>();
		if (trials > 0)
			errorBars.add(wilson(counts));
		return new CellReport(cell, counts.passes(), trials, passRate,
			developmentImprovement(campaign, cell, counts), developmentComparison(counts), errorBars);
	}

	/**
	 * Returns the exploratory improvement of the working candidate over the incumbent baseline.
	 *
	 * @param campaign the campaign
	 * @param cell the cell's identifier
	 * @param counts the working candidate's development counts
	 * @return the improvement
	 */
	private static CellReport.Improvement developmentImprovement(Campaign campaign, String cell,
		CellCounts counts)
	{
		if (campaign.development().working().isIncumbent())
			return CellReport.Improvement.unavailable("The working candidate is the incumbent.");
		if (counts.outcomes() == 0)
			return CellReport.Improvement.unavailable("The working candidate has no development outcomes.");
		IncumbentBaseline baseline = campaign.baseline();
		Comparability comparability = Comparability.of(campaign.identity(), baseline.identity());
		if (!comparability.isComparable())
			return CellReport.Improvement.unavailable(comparability.unavailable());
		CellCounts incumbent = baseline.count(cell);
		if (incumbent.outcomes() == 0)
			return CellReport.Improvement.unavailable("The incumbent baseline has no outcomes.");
		Interval candidate = ExploratoryBounds.wilson(counts.passes(), counts.outcomes());
		Interval other = ExploratoryBounds.wilson(incumbent.passes(), incumbent.outcomes());
		Interval difference = new Interval(candidate.lower() - other.upper(), candidate.upper() - other.lower());
		return new CellReport.Improvement(null, points(counts, incumbent), incumbentCounts(baseline, incumbent),
			deficiency(baseline, incumbent, campaign.contract().cells().size()), difference,
			"exploratory difference of the two 95% Wilson intervals", null, null, null);
	}

	/**
	 * Returns the readiness assessment of a development cell.
	 *
	 * @param counts the cell's development counts
	 * @return the comparison
	 */
	private static CellReport.Threshold developmentComparison(CellCounts counts)
	{
		List<String> reasons = new ArrayList<>();
		int trials = counts.outcomes();
		if (trials < ReadinessGate.MINIMUM_TRIALS)
			reasons.add("fewer than " + ReadinessGate.MINIMUM_TRIALS + " outcomes");
		if (!ReadinessGate.meetsPassRateThreshold(counts))
			reasons.add("fewer than 99% passes");
		List<Integer> batches = counts.completedBatchFailures();
		if (batches.size() < 2)
			reasons.add("fewer than two complete batches of " + CellCounts.BATCH_SIZE + " outcomes");
		else
		{
			int latest = batches.getLast();
			int previous = batches.get(batches.size() - 2);
			if (Math.max(latest, previous) > ReadinessGate.BATCH_FAILURE_LIMIT)
			{
				reasons.add("a latest complete batch has more than " + ReadinessGate.BATCH_FAILURE_LIMIT +
					" failure");
			}
		}
		String readiness = "not ready";
		if (ReadinessGate.isReady(counts))
			readiness = "ready";
		return new CellReport.Threshold(NOT_CERTIFIED, readiness, reasons, null, null, null);
	}

	/**
	 * Builds the report of a cell from a certification attempt's counts.
	 *
	 * @param campaign the campaign
	 * @param attempt the attempt
	 * @param cell the cell's identifier
	 * @return the report
	 */
	private static CellReport formal(Campaign campaign, CertificationAttempt attempt, String cell)
	{
		CellCounts counts = attempt.count(cell);
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		SignificanceLevel alpha = boundaries.level();
		ConfidenceBound lower = ConfidenceBounds.lower(counts.passes(), counts.failures(), alpha);
		ConfidenceBound upper = ConfidenceBounds.upper(counts.passes(), counts.failures(), alpha);
		String state = switch (DecisionTable.cellDecision(boundaries, counts))
		{
			case CERTIFIED -> "certified";
			case CLEARLY_BELOW -> "clearly below";
			case CAP_REACHED -> "inconclusive";
			case UNDECIDED -> UNDECIDED;
		};
		CellReport.Threshold comparison = new CellReport.Threshold(state, null, List.of(), lower.value(),
			upper.value(), alpha.probability());
		List<CellReport.ErrorBar> errorBars = new ArrayList<>();
		errorBars.add(new CellReport.ErrorBar("one-sided lower confidence bound at alpha_i", true,
			alpha.probability(), false, counts.outcomes(), new Interval(lower.value(), 1.0)));
		errorBars.add(wilson(counts));
		return new CellReport(cell, counts.passes(), counts.outcomes(),
			passRate(FORMAL_ESTIMATE, counts, "no adopted formal outcomes"),
			formalImprovement(campaign, attempt, cell, counts, lower, upper), comparison, errorBars);
	}

	/**
	 * Returns the improvement of an attempt's candidate over the incumbent.
	 *
	 * @param campaign the campaign
	 * @param attempt the attempt
	 * @param cell the cell's identifier
	 * @param counts the attempt's counts for the cell
	 * @param lower the certification lower bound
	 * @param upper the certification upper bound
	 * @return the improvement
	 */
	private static CellReport.Improvement formalImprovement(Campaign campaign, CertificationAttempt attempt,
		String cell, CellCounts counts, ConfidenceBound lower, ConfidenceBound upper)
	{
		Comparability comparability = Comparability.of(attempt.identity(), attempt.procedure().baselineIdentity());
		if (!comparability.isComparable())
			return CellReport.Improvement.unavailable(comparability.unavailable());
		ComparisonBoundaries boundaries = comparison(attempt, cell);
		Comparison frozen = boundaries.comparison();
		String state = improvementState(boundaries, counts);
		Double points = null;
		CellReport.IncumbentCounts incumbentCounts = null;
		String deficiency = null;
		IncumbentBaseline baseline = campaign.baseline();
		if (baseline != null && baseline.index() == attempt.procedure().baselineIndex() &&
			Comparability.of(attempt.identity(), baseline.identity()).isComparable())
		{
			CellCounts incumbent = baseline.count(cell);
			incumbentCounts = incumbentCounts(baseline, incumbent);
			deficiency = deficiency(baseline, incumbent, attempt.procedure().cells().size());
			if (counts.outcomes() > 0 && incumbent.outcomes() > 0)
				points = points(counts, incumbent);
		}
		return new CellReport.Improvement(null, points, incumbentCounts, deficiency,
			frozen.difference(lower, upper), "[LC - UI(k), UC - LI(k)] at the attempt's certification level", state,
			frozen.incumbentUpper().doubleValue(), frozen.incumbentLower().doubleValue());
	}

	/**
	 * Returns the comparison boundaries that an attempt froze for a cell.
	 *
	 * @param attempt the attempt
	 * @param cell the cell's identifier
	 * @return the boundaries
	 */
	static ComparisonBoundaries comparison(CertificationAttempt attempt, String cell)
	{
		int position = attempt.procedure().cells().indexOf(cell);
		requireThat(position, "position").withContext(cell, "cell").isNotNegative();
		return attempt.procedure().boundaries().comparisons().get(position);
	}

	/**
	 * Returns whether an attempt's candidate is proven better or worse than the incumbent in a cell.
	 *
	 * @param boundaries the comparison boundaries that the attempt froze for the cell
	 * @param counts the attempt's counts for the cell
	 * @return {@code proven better}, {@code proven worse} or {@code undecided}
	 */
	static String improvementState(ComparisonBoundaries boundaries, CellCounts counts)
	{
		if (boundaries.isProvenBetter(counts.passes(), counts.failures()))
			return PROVEN_BETTER;
		if (boundaries.isProvenWorse(counts.passes(), counts.failures()))
			return PROVEN_WORSE;
		return UNDECIDED;
	}

	/**
	 * Returns a pass rate.
	 *
	 * @param label the label
	 * @param counts the counts
	 * @param missing the reason that there is no rate if there are no outcomes
	 * @return the pass rate
	 */
	private static CellReport.PassRate passRate(String label, CellCounts counts, String missing)
	{
		if (counts.outcomes() == 0)
			return new CellReport.PassRate(label, null, missing);
		return new CellReport.PassRate(label, (double) counts.passes() / counts.outcomes(), null);
	}

	/**
	 * Returns the descriptive Wilson interval of a cell.
	 *
	 * @param counts the counts, which hold at least one outcome
	 * @return the error bar
	 */
	private static CellReport.ErrorBar wilson(CellCounts counts)
	{
		return new CellReport.ErrorBar(WILSON, false, null, true, counts.outcomes(),
			ExploratoryBounds.wilson(counts.passes(), counts.outcomes()));
	}

	/**
	 * Returns the candidate's pass rate minus the incumbent's, in percentage points.
	 *
	 * @param candidate the candidate's counts, which hold at least one outcome
	 * @param incumbent the incumbent's counts, which hold at least one outcome
	 * @return the difference
	 */
	private static double points(CellCounts candidate, CellCounts incumbent)
	{
		return 100.0 * ((double) candidate.passes() / candidate.outcomes() -
			(double) incumbent.passes() / incumbent.outcomes());
	}

	/**
	 * Returns the incumbent's counts in its baseline.
	 *
	 * @param baseline the baseline
	 * @param counts the cell's counts
	 * @return the counts
	 */
	private static CellReport.IncumbentCounts incumbentCounts(IncumbentBaseline baseline, CellCounts counts)
	{
		return new CellReport.IncumbentCounts(baseline.index(), counts.passes(), counts.outcomes());
	}

	/**
	 * Returns whether the incumbent is proven deficient in a cell.
	 *
	 * @param baseline the baseline
	 * @param counts the cell's counts
	 * @param cells the number of cells
	 * @return {@code proven deficient} or {@code deficiency unproven}
	 */
	private static String deficiency(IncumbentBaseline baseline, CellCounts counts, int cells)
	{
		if (Baseline.isProvenDeficient(counts, baseline.index(), cells))
			return "proven deficient";
		return "deficiency unproven";
	}
}

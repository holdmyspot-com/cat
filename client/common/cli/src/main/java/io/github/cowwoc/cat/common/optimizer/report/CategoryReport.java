package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.Interval;

import java.util.List;

/**
 * The four outputs of {@code design.md} section 2.9 for one cell: the estimated pass rate, the improvement over
 * the incumbent, the comparison with 95%, and the error bars.
 * <p>
 * Every output that cannot be shown carries the reason.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cell the cell's identifier
 * @param passes the candidate's adopted passes in the reported phase
 * @param trials the candidate's adopted outcomes in the reported phase
 * @param passRate the estimated pass rate
 * @param improvement the improvement over the incumbent
 * @param comparison the comparison with 95%
 * @param errorBars the error bars; the one-sided certification bound is kept apart from descriptive two-sided
 *   intervals
 */
public record CategoryReport(String cell, int passes, int trials, PassRate passRate, Improvement improvement,
	Threshold comparison, List<ErrorBar> errorBars)
{
	/**
	 * The candidate's observed pass rate under the frozen judge.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param label {@code development estimate} for development trials, otherwise {@code formal estimate}
	 * @param rate the fraction of adopted outcomes that passed, or {@code null} if there are none
	 * @param unavailable why {@code rate} is unavailable, or {@code null} if it is available
	 */
	public record PassRate(String label, Double rate, String unavailable)
	{
		/**
		 * Creates a pass rate.
		 *
		 * @param label {@code development estimate} for development trials, otherwise {@code formal estimate}
		 * @param rate the fraction of adopted outcomes that passed, or {@code null} if there are none
		 * @param unavailable why {@code rate} is unavailable, or {@code null} if it is available
		 * @throws NullPointerException if {@code label} is null
		 * @throws IllegalArgumentException if {@code rate} is null exactly when {@code unavailable} is null
		 */
		public PassRate
		{
			requireThat(label, "label").isNotNull();
			requireThat(rate == null, "rateUnavailable").isEqualTo(unavailable != null, "hasReason");
		}
	}

	/**
	 * The incumbent's counts in the baseline that a comparison used.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param baseline the baseline's index
	 * @param passes the incumbent's passes
	 * @param trials the incumbent's outcomes
	 */
	public record IncumbentCounts(int baseline, int passes, int trials)
	{
		/**
		 * Creates counts.
		 *
		 * @param baseline the baseline's index
		 * @param passes the incumbent's passes
		 * @param trials the incumbent's outcomes
		 * @throws IllegalArgumentException if {@code baseline} is not positive, or {@code passes} is outside
		 *   [0, {@code trials}]
		 */
		public IncumbentCounts
		{
			requireThat(baseline, "baseline").isPositive();
			requireThat(trials, "trials").isNotNegative();
			requireThat(passes, "passes").isBetween(0, true, trials, true);
		}
	}

	/**
	 * The candidate's improvement over the incumbent.
	 * <p>
	 * If {@code unavailable} is not null, every other component is null. Otherwise a component is null when its
	 * evidence is absent: the incumbent's counts for a baseline that the campaign no longer holds, the state and the
	 * frozen bounds outside a certification attempt.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param unavailable why no improvement is shown, or {@code null} if one is
	 * @param points the candidate's pass rate minus the incumbent's, in percentage points
	 * @param incumbent the incumbent's identity and counts
	 * @param deficiency {@code proven deficient} or {@code deficiency unproven}, from the incumbent's baseline
	 * @param difference the interval for the candidate's pass probability minus the incumbent's
	 * @param differenceMethod how the interval was computed
	 * @param state {@code proven better}, {@code proven worse} or {@code undecided} at the attempt's
	 *   per-category improvement level {@code β_i / K}
	 * @param incumbentUpper the incumbent's frozen upper bound {@code UI(k)}
	 * @param incumbentLower the incumbent's frozen lower bound {@code LI(k)}
	 */
	public record Improvement(String unavailable, Double points, IncumbentCounts incumbent, String deficiency,
		Interval difference, String differenceMethod, String state, Double incumbentUpper, Double incumbentLower)
	{
		/**
		 * Creates an improvement.
		 *
		 * @param unavailable why no improvement is shown, or {@code null} if one is
		 * @param points the candidate's pass rate minus the incumbent's, in percentage points
		 * @param incumbent the incumbent's identity and counts
		 * @param deficiency {@code proven deficient} or {@code deficiency unproven}
		 * @param difference the interval for the candidate's pass probability minus the incumbent's
		 * @param differenceMethod how the interval was computed
		 * @param state {@code proven better}, {@code proven worse} or {@code undecided}
		 * @param incumbentUpper the incumbent's frozen upper bound {@code UI(k)}
		 * @param incumbentLower the incumbent's frozen lower bound {@code LI(k)}
		 * @throws IllegalArgumentException if {@code unavailable} is not null and another component is not null
		 */
		public Improvement
		{
			if (unavailable != null)
			{
				requireThat(points == null && incumbent == null && deficiency == null && difference == null &&
					differenceMethod == null && state == null && incumbentUpper == null && incumbentLower == null,
					"othersAbsent").isTrue();
			}
		}

		/**
		 * Returns an improvement that cannot be shown.
		 *
		 * @param reason why no improvement is shown
		 * @return the improvement
		 * @throws NullPointerException if {@code reason} is null
		 */
		public static Improvement unavailable(String reason)
		{
			requireThat(reason, "reason").isNotNull();
			return new Improvement(reason, null, null, null, null, null, null, null, null);
		}
	}

	/**
	 * The comparison with the 95% requirement.
	 * <p>
	 * In development, {@code state} is always {@code not certified} and {@code readiness} holds the readiness
	 * assessment. In a certification attempt, {@code state} is {@code certified}, {@code clearly below},
	 * {@code inconclusive} or {@code undecided}, and the bounds hold the attempt's level.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param state the state of the comparison
	 * @param readiness {@code ready} or {@code not ready} for development, otherwise {@code null}
	 * @param reasons why a development category is not ready, empty if it is ready or the phase is formal
	 * @param lowerBound the one-sided lower confidence bound {@code LC} at {@code α_i}, or {@code null} in development
	 * @param upperBound the one-sided upper confidence bound at {@code α_i}, or {@code null} in development
	 * @param alpha the attempt's certification level {@code α_i}, or {@code null} in development
	 */
	public record Threshold(String state, String readiness, List<String> reasons, Double lowerBound,
		Double upperBound, Double alpha)
	{
		/**
		 * Creates a comparison.
		 *
		 * @param state the state of the comparison
		 * @param readiness {@code ready} or {@code not ready} for development, otherwise {@code null}
		 * @param reasons why a development category is not ready
		 * @param lowerBound the one-sided lower confidence bound {@code LC} at {@code α_i}
		 * @param upperBound the one-sided upper confidence bound at {@code α_i}
		 * @param alpha the attempt's certification level {@code α_i}
		 * @throws NullPointerException if {@code state} or {@code reasons} are null
		 */
		public Threshold
		{
			requireThat(state, "state").isNotNull();
			requireThat(reasons, "reasons").isNotNull();
			reasons = List.copyOf(reasons);
		}
	}

	/**
	 * One error bar.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param method how the endpoints were computed
	 * @param oneSided {@code true} if the bar bounds the pass probability from one side only
	 * @param errorProbability the probability that the bound is wrong, or {@code null} for an exploratory interval
	 * @param exploratory {@code true} if the interval is descriptive and supports no claim
	 * @param sampleSize the number of adopted outcomes
	 * @param interval the endpoints
	 */
	public record ErrorBar(String method, boolean oneSided, Double errorProbability, boolean exploratory,
		int sampleSize, Interval interval)
	{
		/**
		 * Creates an error bar.
		 *
		 * @param method how the endpoints were computed
		 * @param oneSided {@code true} if the bar bounds the pass probability from one side only
		 * @param errorProbability the probability that the bound is wrong, or {@code null} for an exploratory interval
		 * @param exploratory {@code true} if the interval is descriptive and supports no claim
		 * @param sampleSize the number of adopted outcomes
		 * @param interval the endpoints
		 * @throws NullPointerException if {@code method} or {@code interval} are null
		 * @throws IllegalArgumentException if {@code sampleSize} is negative, or {@code errorProbability} is null
		 *   exactly when {@code exploratory} is {@code true}
		 */
		public ErrorBar
		{
			requireThat(method, "method").isNotNull();
			requireThat(sampleSize, "sampleSize").isNotNegative();
			requireThat(interval, "interval").isNotNull();
			requireThat(errorProbability == null, "noErrorProbability").isEqualTo(exploratory, "exploratory");
		}
	}

	/**
	 * Creates a cell's report.
	 *
	 * @param cell the cell's identifier
	 * @param passes the candidate's adopted passes in the reported phase
	 * @param trials the candidate's adopted outcomes in the reported phase
	 * @param passRate the estimated pass rate
	 * @param improvement the improvement over the incumbent
	 * @param comparison the comparison with 95%
	 * @param errorBars the error bars
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code trials} is negative or {@code passes} is outside
	 *   [0, {@code trials}]
	 */
	public CategoryReport
	{
		requireThat(cell, "cell").isNotNull();
		requireThat(trials, "trials").isNotNegative();
		requireThat(passes, "passes").isBetween(0, true, trials, true);
		requireThat(passRate, "passRate").isNotNull();
		requireThat(improvement, "improvement").isNotNull();
		requireThat(comparison, "comparison").isNotNull();
		requireThat(errorBars, "errorBars").isNotNull();
		errorBars = List.copyOf(errorBars);
	}
}

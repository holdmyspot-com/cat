package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;

import java.util.List;

/**
 * The statistical assumption review that a status shows.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param passRatesByConcurrency the descriptive pass rate at each observed concurrency level, in increasing level
 *   order; the rates support no claim
 * @param dependenceDetected {@code true} if a trial accessed state that links it to other trials
 * @param consequence {@link Outcome#INVALID_STATISTICAL_ASSUMPTIONS} if dependence was detected, otherwise
 *   {@code null}
 */
public record AssumptionReport(List<AssumptionCheck.LevelRate> passRatesByConcurrency, boolean dependenceDetected,
	Outcome consequence)
{
	/**
	 * Creates a report.
	 *
	 * @param passRatesByConcurrency the descriptive pass rate at each observed concurrency level
	 * @param dependenceDetected {@code true} if a trial accessed state that links it to other trials
	 * @param consequence the outcome that follows from the review, or {@code null} if none does
	 * @throws NullPointerException if {@code passRatesByConcurrency} is null
	 * @throws IllegalArgumentException if {@code consequence} is not null exactly when {@code dependenceDetected}
	 *   is {@code true}, or it is not {@link Outcome#INVALID_STATISTICAL_ASSUMPTIONS}
	 */
	public AssumptionReport
	{
		requireThat(passRatesByConcurrency, "passRatesByConcurrency").isNotNull();
		requireThat(consequence != null, "hasConsequence").isEqualTo(dependenceDetected, "dependenceDetected");
		if (consequence != null)
			requireThat(consequence, "consequence").isEqualTo(Outcome.INVALID_STATISTICAL_ASSUMPTIONS);
		passRatesByConcurrency = List.copyOf(passRatesByConcurrency);
	}

	/**
	 * Reviews the assumptions from the evidence.
	 *
	 * @param evidence the evidence
	 * @return the review
	 * @throws NullPointerException if {@code evidence} is null
	 */
	public static AssumptionReport of(ReportEvidence evidence)
	{
		requireThat(evidence, "evidence").isNotNull();
		List<AssumptionCheck.LevelRate> rates = AssumptionCheck.passRatesByConcurrency(evidence.observations());
		boolean dependent = AssumptionCheck.isDependent(evidence.accessedPaths(), evidence.protectedRoots());
		Outcome consequence = null;
		if (dependent)
			consequence = Outcome.INVALID_STATISTICAL_ASSUMPTIONS;
		return new AssumptionReport(rates, dependent, consequence);
	}
}

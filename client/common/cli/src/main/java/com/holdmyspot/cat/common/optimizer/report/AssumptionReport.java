package com.holdmyspot.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.AssumptionCheck;

import java.util.List;

/**
 * The descriptive pass rates by concurrency level that a status shows.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param passRatesByConcurrency the descriptive pass rate at each observed concurrency level, in increasing level
 *   order; the rates support no claim
 */
public record AssumptionReport(List<AssumptionCheck.LevelRate> passRatesByConcurrency)
{
	/**
	 * Creates a report.
	 *
	 * @param passRatesByConcurrency the descriptive pass rate at each observed concurrency level
	 * @throws NullPointerException if {@code passRatesByConcurrency} is null
	 */
	public AssumptionReport
	{
		requireThat(passRatesByConcurrency, "passRatesByConcurrency").isNotNull();
		passRatesByConcurrency = List.copyOf(passRatesByConcurrency);
	}

	/**
	 * Describes pass rates by concurrency level from the evidence.
	 *
	 * @param evidence the evidence
	 * @return the descriptive rates
	 * @throws NullPointerException if {@code evidence} is null
	 */
	public static AssumptionReport of(ReportEvidence evidence)
	{
		requireThat(evidence, "evidence").isNotNull();
		List<AssumptionCheck.LevelRate> rates = AssumptionCheck.passRatesByConcurrency(evidence.observations());
		return new AssumptionReport(rates);
	}
}

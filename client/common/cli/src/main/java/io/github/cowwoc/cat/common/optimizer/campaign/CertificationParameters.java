package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The parameters of a certification's claim: each certified cell's pass probability exceeds
 * {@code minimumPassRate}, with an error probability of at most the certification level {@code α_i}.
 * <p>
 * A certification proves its claim and every weaker one. A claim implies another when its minimum pass rate is at
 * least the other's and its certification level is at most the other's.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param minimumPassRate the pass probability that each certified cell exceeds
 * @param level the certification level {@code α_i} of the attempt
 */
public record CertificationParameters(BigDecimal minimumPassRate, SignificanceLevel level)
{
	/**
	 * Creates the parameters.
	 *
	 * @param minimumPassRate the pass probability that each certified cell exceeds
	 * @param level the certification level {@code α_i} of the attempt
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code minimumPassRate} is not between 0 and 1, exclusive
	 */
	public CertificationParameters
	{
		requireThat(minimumPassRate, "minimumPassRate").isGreaterThan(BigDecimal.ZERO).
			isLessThan(BigDecimal.ONE);
		requireThat(level, "level").isNotNull();
	}

	/**
	 * Returns the parameters that this version of CAT certifies an attempt under.
	 *
	 * @param certificationIndex the attempt's certification index, numbered from 1 across the campaign
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return the parameters
	 * @throws NullPointerException if {@code minimumPassRate} is null
	 * @throws IllegalArgumentException if {@code certificationIndex} is not positive, or if {@code minimumPassRate}
	 *   is not between 0 and 1, exclusive
	 */
	public static CertificationParameters current(int certificationIndex, BigDecimal minimumPassRate)
	{
		return new CertificationParameters(minimumPassRate, ErrorBudget.certificationLevel(certificationIndex));
	}

	/**
	 * Returns the parameters whose value in this claim does not imply their value in another claim.
	 *
	 * @param other the other claim's parameters
	 * @return the parameters, in declaration order; empty if this claim implies {@code other}
	 * @throws NullPointerException if {@code other} is null
	 */
	public List<CertificationParameter> notImplying(CertificationParameters other)
	{
		requireThat(other, "other").isNotNull();
		List<CertificationParameter> parameters = new ArrayList<>();
		if (minimumPassRate.compareTo(other.minimumPassRate) < 0)
			parameters.add(CertificationParameter.MINIMUM_PASS_RATE);
		// A larger threshold is a smaller level
		if (level.threshold().compareTo(other.level.threshold()) < 0)
			parameters.add(CertificationParameter.CERTIFICATION_LEVEL);
		return parameters;
	}
}

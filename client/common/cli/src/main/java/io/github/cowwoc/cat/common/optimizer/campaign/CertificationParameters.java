package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The parameters of a certification's claim: each certified cell's pass probability exceeds {@code requiredPassRate},
 * with an error probability of at most the certification level {@code α_i}.
 * <p>
 * A certification proves its claim and every weaker one. A claim implies another when its required pass rate is at
 * least the other's and its certification level is at most the other's.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param requiredPassRate the pass probability that each certified cell exceeds
 * @param level the certification level {@code α_i} of the attempt
 */
public record CertificationParameters(BigDecimal requiredPassRate, SignificanceLevel level)
{
	/**
	 * Creates the parameters.
	 *
	 * @param requiredPassRate the pass probability that each certified cell exceeds
	 * @param level the certification level {@code α_i} of the attempt
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code requiredPassRate} is not between 0 and 1, exclusive
	 */
	public CertificationParameters
	{
		requireThat(requiredPassRate, "requiredPassRate").isGreaterThan(BigDecimal.ZERO).isLessThan(BigDecimal.ONE);
		requireThat(level, "level").isNotNull();
	}

	/**
	 * Returns the parameters that this version of CAT certifies an attempt under.
	 *
	 * @param certificationIndex the attempt's certification index, numbered from 1 across the campaign
	 * @return the parameters
	 * @throws IllegalArgumentException if {@code certificationIndex} is not positive
	 */
	public static CertificationParameters current(int certificationIndex)
	{
		return new CertificationParameters(BettingProcess.REQUIRED_PASS_RATE,
			ErrorBudget.certificationLevel(certificationIndex));
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
		if (requiredPassRate.compareTo(other.requiredPassRate) < 0)
			parameters.add(CertificationParameter.REQUIRED_PASS_RATE);
		// A larger threshold is a smaller level
		if (level.threshold().compareTo(other.level.threshold()) < 0)
			parameters.add(CertificationParameter.CERTIFICATION_LEVEL);
		return parameters;
	}
}

package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigInteger;

/**
 * The campaign's joint 5% error budget and the level that each decision spends from it.
 * <p>
 * The budget is split into 0.04 for certification, 0.005 for improvement claims and 0.005 for the incumbent's bounds.
 * Each part {@code x} is spent by the series {@code x / (n(n + 1))}, which sums to {@code x} over an unlimited number
 * of terms, so the union bound over every attempt, baseline and category stays at most 0.05.
 * <p>
 * Callers own the indexes: a certification index is assigned when an attempt is committed, and a baseline index when
 * the incumbent is measured for a new lineage or measurement identity. Work that never receives an index spends
 * nothing.
 */
public final class ErrorBudget
{
	/**
	 * The reciprocal of the certification budget 0.04.
	 */
	private static final BigInteger CERTIFICATION_RECIPROCAL = BigInteger.valueOf(25);
	/**
	 * The reciprocal of the improvement budget and of the incumbent budget, each 0.005.
	 */
	private static final BigInteger CLAIM_RECIPROCAL = BigInteger.valueOf(200);

	/**
	 * Returns the certification level {@code alpha_i = 0.04 / (i(i + 1))} of one certification attempt.
	 * <p>
	 * The level applies to every category of the attempt, because certification is a conjunction.
	 *
	 * @param certificationIndex the attempt's certification index, numbered from 1 across the campaign
	 * @return the level, whose threshold is {@code 25 i(i + 1)}
	 * @throws IllegalArgumentException if {@code certificationIndex} is not positive
	 */
	public static SignificanceLevel certificationLevel(int certificationIndex)
	{
		requireThat(certificationIndex, "certificationIndex").isPositive();
		return new SignificanceLevel(CERTIFICATION_RECIPROCAL.multiply(seriesDenominator(certificationIndex)));
	}

	/**
	 * Returns the per-category improvement level {@code beta_i / K} of one certification attempt, where
	 * {@code beta_i = 0.005 / (i(i + 1))}.
	 * <p>
	 * Both the proven-better and the proven-worse decisions of each category use this level.
	 *
	 * @param certificationIndex the attempt's certification index, numbered from 1 across the campaign
	 * @param categories         the number of categories {@code K} in the attempt's contract
	 * @return the level, whose threshold is {@code 200 K i(i + 1)}
	 * @throws IllegalArgumentException if {@code certificationIndex} or {@code categories} are not positive
	 */
	public static SignificanceLevel improvementLevel(int certificationIndex, int categories)
	{
		requireThat(certificationIndex, "certificationIndex").isPositive();
		requireThat(categories, "categories").isPositive();
		return new SignificanceLevel(CLAIM_RECIPROCAL.multiply(BigInteger.valueOf(categories)).
			multiply(seriesDenominator(certificationIndex)));
	}

	/**
	 * Returns the per-category incumbent level {@code gamma_b / K} of one incumbent baseline, where
	 * {@code gamma_b = 0.005 / (b(b + 1))}.
	 * <p>
	 * The deficiency decision and the incumbent bounds {@code UI(k)} and {@code LI(k)} use this level.
	 *
	 * @param baselineIndex the baseline's index, numbered from 1 across the campaign
	 * @param categories    the number of categories {@code K} in the baseline's contract
	 * @return the level, whose threshold is {@code 200 K b(b + 1)}
	 * @throws IllegalArgumentException if {@code baselineIndex} or {@code categories} are not positive
	 */
	public static SignificanceLevel incumbentLevel(int baselineIndex, int categories)
	{
		requireThat(baselineIndex, "baselineIndex").isPositive();
		requireThat(categories, "categories").isPositive();
		return new SignificanceLevel(CLAIM_RECIPROCAL.multiply(BigInteger.valueOf(categories)).
			multiply(seriesDenominator(baselineIndex)));
	}

	/**
	 * Returns the denominator {@code n(n + 1)} of a series term without overflowing.
	 *
	 * @param index the term's index
	 * @return {@code index * (index + 1)}
	 */
	private static BigInteger seriesDenominator(int index)
	{
		BigInteger value = BigInteger.valueOf(index);
		return value.multiply(value.add(BigInteger.ONE));
	}

	/**
	 * Prevents construction.
	 */
	private ErrorBudget()
	{
	}
}

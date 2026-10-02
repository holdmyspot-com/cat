package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.util.List;

/**
 * The statistical settings frozen when a certification attempt is admitted, before its first trial is sampled.
 * <p>
 * The boundaries use the attempt's certification error limit {@code α_i} and trial cap. Each cell (category and
 * trial profile) is compared with the incumbent's upper and lower compliance bounds, {@code UI(k)} and {@code LI(k)},
 * using the improvement error limit {@code β_i / K}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param improvementLevel the per-cell error limit {@code β_i / K} for incorrectly declaring improvement
 * @param baselineIndex the incumbent baseline that the comparisons were frozen from
 * @param categories the contract's cell identifiers, in the order of the boundaries' comparisons
 * @param boundaries the decision boundaries
 * @param bets the version of the bets and weights
 * @param allocationPolicy the version of the formal allocation policy
 */
public record CertificationProcedure(SignificanceLevel improvementLevel, int baselineIndex, List<String> categories,
	DecisionBoundaries boundaries, String bets, String allocationPolicy)
{
	/**
	 * The fixed bets and weights for certification and below-minimum tests.
	 */
	public static final String BETS = "certification (0.4, 0.1) (0.6, 0.2) (0.8, 0.5) (1.0, 0.2); " +
		"below minimum 9/19 3/19 1/19 with equal weights";
	/**
	 * The policy for prioritizing formal trials and limiting in-flight trials.
	 */
	public static final String ALLOCATION_POLICY = "one trial per category, then the lowest lower bound at " +
		"alpha_i, ties to fewer outcomes then category order; in-flight trials limited by the passes still required";

	/**
	 * Creates a procedure.
	 *
	 * @param improvementLevel the per-cell error limit {@code β_i / K} for incorrectly declaring improvement
	 * @param baselineIndex the incumbent baseline that the comparisons were frozen from
	 * @param categories the contract's cell identifiers, in the order of the boundaries' comparisons
	 * @param boundaries the decision boundaries
	 * @param bets the version of the bets and weights
	 * @param allocationPolicy the version of the formal allocation policy
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code baselineIndex} is not positive, {@code categories} is empty, the
	 *   {@code boundaries} do not hold one comparison per cell, or {@code bets} or {@code allocationPolicy} are blank
	 */
	public CertificationProcedure
	{
		requireThat(improvementLevel, "improvementLevel").isNotNull();
		requireThat(baselineIndex, "baselineIndex").isPositive();
		requireThat(categories, "categories").isNotEmpty();
		requireThat(boundaries, "boundaries").isNotNull();
		requireThat(boundaries.comparisons().size(), "boundaries.comparisons().size()").
			isEqualTo(categories.size(), "categories.size()");
		requireThat(bets, "bets").isNotBlank();
		requireThat(allocationPolicy, "allocationPolicy").isNotBlank();
		categories = List.copyOf(categories);
	}

	/**
	 * Returns the position of a cell in the boundaries' comparisons.
	 *
	 * @param category the cell's identifier
	 * @return the position
	 * @throws IllegalArgumentException if {@code category} is not one of the procedure's cell identifiers
	 */
	int position(String category)
	{
		int position = categories.indexOf(category);
		requireThat(position, "position").withContext(category, "category").isNotNegative();
		return position;
	}
}

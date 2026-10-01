package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.util.List;

/**
 * Everything that a commit freezes before a certification attempt's first formal draw.
 * <p>
 * The boundaries hold {@code α_i}, the cap and, per category, the comparison with {@code UI(k)}, {@code LI(k)} and
 * {@code β_i / K}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param improvementLevel the per-category improvement level {@code β_i / K}
 * @param baselineIndex the incumbent baseline that the comparisons were frozen from
 * @param categories the contract's categories, in the order of the boundaries' comparisons
 * @param boundaries the decision boundaries
 * @param bets the version of the bets and weights
 * @param allocationPolicy the version of the formal allocation policy
 */
public record CertificationProcedure(SignificanceLevel improvementLevel, int baselineIndex, List<String> categories,
	DecisionBoundaries boundaries, String bets, String allocationPolicy)
{
	/**
	 * The bets and weights of {@code design.md} section 2.3.
	 */
	public static final String BETS = "certification (0.4, 0.1) (0.6, 0.2) (0.8, 0.5) (1.0, 0.2); " +
		"below minimum 9/19 3/19 1/19 with equal weights";
	/**
	 * The formal allocation policy of {@code design.md} section 3.3.
	 */
	public static final String ALLOCATION_POLICY = "one trial per category, then the lowest lower bound at " +
		"alpha_i, ties to fewer outcomes then category order; in-flight trials limited by the passes still required";

	/**
	 * Creates a procedure.
	 *
	 * @param improvementLevel the per-category improvement level {@code β_i / K}
	 * @param baselineIndex the incumbent baseline that the comparisons were frozen from
	 * @param categories the contract's categories, in the order of the boundaries' comparisons
	 * @param boundaries the decision boundaries
	 * @param bets the version of the bets and weights
	 * @param allocationPolicy the version of the formal allocation policy
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code baselineIndex} is not positive, {@code categories} is empty, the
	 *   boundaries do not hold one comparison per category, or a text is blank
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
	 * Returns the position of a category in the boundaries' comparisons.
	 *
	 * @param category the category
	 * @return the position
	 * @throws IllegalArgumentException if the procedure has no such category
	 */
	int position(String category)
	{
		int position = categories.indexOf(category);
		requireThat(position, "position").withContext(category, "category").isNotNegative();
		return position;
	}
}

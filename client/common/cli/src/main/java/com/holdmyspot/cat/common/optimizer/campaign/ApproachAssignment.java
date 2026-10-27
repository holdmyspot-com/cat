package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The approach assigned to a reserved prompt author attempt.
 *
 * @param approach the assigned editing approach
 * @param source   how the round selected the approach order
 */
public record ApproachAssignment(PromptAuthorApproach approach, Source source)
{
	/**
	 * Creates an assignment.
	 *
	 * @param approach the editing approach
	 * @param source   the order's origin
	 * @throws NullPointerException if any argument is null
	 */
	public ApproachAssignment
	{
		requireThat(approach, "approach").isNotNull();
		requireThat(source, "source").isNotNull();
	}

	/**
	 * The origin of a round's approach order.
	 */
	public enum Source
	{
		/**
		 * A session ranked the approaches by fit.
		 */
		RANKING,
		/**
		 * Ranking exhausted its retries.
		 */
		FALLBACK,
		/**
		 * The round uses its saved declaration-order rotation.
		 */
		DECLARATION
	}
}

package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * The working candidate's state retained at promotion for a possible restoration.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param development the predecessor's counts and test case evidence without pending work or another predecessor
 * @param round the round that promoted the current working candidate
 * @param cell the selected attempt's addressed cell
 * @param regressionSet the predecessor's recorded verdicts on the lineage's regression test cases
 */
public record Predecessor(Development development, Round round, String cell,
	List<RegressionTestCase> regressionSet)
{
	/**
	 * Creates retained promotion state.
	 *
	 * @param development the predecessor's counts and test case evidence without pending work or another predecessor
	 * @param round the promoting round
	 * @param cell the selected attempt's addressed cell
	 * @param regressionSet the predecessor's recorded regression verdicts
	 * @throws NullPointerException if a required argument or regression test case is null
	 * @throws IllegalArgumentException if {@code cell} is blank or absent from {@code round}, or development contains
	 *   pending work, a predecessor or revert history
	 */
	public Predecessor
	{
		requireThat(development, "development").isNotNull();
		requireThat(round, "round").isNotNull();
		requireThat(cell, "cell").isNotBlank();
		requireThat(round.attempts().containsKey(cell), "roundAddressesCell").isTrue();
		requireThat(development.isPaused(), "development.isPaused()").isFalse();
		requireThat(development.predecessor(), "development.predecessor()").isNull();
		requireThat(development.reverts(), "development.reverts()").isEmpty();
		regressionSet = List.copyOf(regressionSet);
	}
}

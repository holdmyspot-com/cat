package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

/**
 * What a prompt test's choice of development judge rests on, besides the near-miss labels: the test's definition and
 * the version of CAT's judge. A change to either discards a qualification that a later campaign would carry over.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param testVersion  the identity of the prompt test's definition in the contract, which covers its near misses and
 *                     the frozen judge's instructions
 * @param judgeVersion the version of CAT's judge that the frozen judge runs under
 */
public record JudgeBasis(Sha256 testVersion, int judgeVersion)
{
	/**
	 * Creates a basis.
	 *
	 * @param testVersion  the identity of the prompt test's definition
	 * @param judgeVersion the version of CAT's judge
	 * @throws NullPointerException if {@code testVersion} is null
	 */
	public JudgeBasis
	{
		requireThat(testVersion, "testVersion").isNotNull();
	}
}

package io.github.cowwoc.cat.common.optimizer.statistics.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;
import org.testng.annotations.Test;

import java.math.BigInteger;

/**
 * Tests {@link SignificanceLevel}.
 */
public final class SignificanceLevelTest
{
	/**
	 * Creates a test instance.
	 */
	public SignificanceLevelTest()
	{
	}

	/**
	 * Verifies that the error probability is the threshold's reciprocal.
	 */
	@Test
	public void probabilityIsReciprocal()
	{
		requireThat(new SignificanceLevel(BigInteger.valueOf(400)).probability(), "probability").isEqualTo(0.0025);
	}

	/**
	 * Verifies that a threshold of 1 is rejected, because every valid e-value can reach it.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsThresholdOne()
	{
		new SignificanceLevel(BigInteger.ONE);
	}
}

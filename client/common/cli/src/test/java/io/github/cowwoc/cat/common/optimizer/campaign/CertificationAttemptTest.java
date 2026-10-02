package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

/**
 * Tests a certification attempt's terminal outcome boundary.
 */
public final class CertificationAttemptTest
{
	/**
	 * Creates the test.
	 */
	public CertificationAttemptTest()
	{
	}

	/**
	 * Verifies that ending an attempt requires an outcome, so callers cannot leave it running.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void endingRequiresOutcome()
	{
		// Requirement: R-F2
		CertificationAttempt attempt = new Builder().confirmed().certificationAttempt(1).build().attempt(1);
		attempt.end(null);
	}

	/**
	 * Verifies that ending an attempt records its outcome and leaves it no longer running.
	 */
	@Test
	public void endingRecordsOutcome()
	{
		// Requirement: R-F2
		CertificationAttempt attempt = new Builder().confirmed().certificationAttempt(1).build().attempt(1);
		CertificationAttempt ended = attempt.end(Outcome.INCONCLUSIVE);
		requireThat(ended.outcome(), "outcome").isEqualTo(Outcome.INCONCLUSIVE);
		requireThat(ended.isRunning(), "running").isFalse();
	}
}

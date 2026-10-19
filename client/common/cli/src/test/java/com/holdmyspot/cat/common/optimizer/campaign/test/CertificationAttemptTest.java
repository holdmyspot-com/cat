package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.CertificationAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.secrets.CertificationAttemptSecrets;
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
		CertificationAttemptSecrets.access().end(attempt, null);
	}

	/**
	 * Verifies that ending an attempt records its outcome and leaves it no longer running.
	 */
	@Test
	public void endingRecordsOutcome()
	{
		// Requirement: R-F2
		CertificationAttempt attempt = new Builder().confirmed().certificationAttempt(1).build().attempt(1);
		CertificationAttempt ended = CertificationAttemptSecrets.access().end(attempt, Outcome.INCONCLUSIVE);
		requireThat(ended.outcome(), "outcome").isEqualTo(Outcome.INCONCLUSIVE);
		requireThat(ended.isRunning(), "running").isFalse();
	}
}

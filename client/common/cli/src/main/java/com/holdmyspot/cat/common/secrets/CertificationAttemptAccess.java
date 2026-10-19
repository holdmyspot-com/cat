package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.campaign.CertificationAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;

/**
 * Exposes the ending of a certification attempt to the module's tests.
 */
@FunctionalInterface
public interface CertificationAttemptAccess
{
	/**
	 * Returns an attempt with an outcome. An ended attempt never reopens. An attempt that ends
	 * {@link Outcome#BELOW_MINIMUM below minimum} or {@link Outcome#INCONCLUSIVE inconclusive} groups its formal
	 * failures for reproduction.
	 *
	 * @param attempt the attempt to end
	 * @param ending the outcome
	 * @return the ended attempt
	 * @throws NullPointerException if {@code ending} is null
	 * @throws IllegalStateException if the attempt has ended
	 */
	CertificationAttempt end(CertificationAttempt attempt, Outcome ending);
}

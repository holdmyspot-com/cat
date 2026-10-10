package io.github.cowwoc.cat.common.secrets;

import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;

/**
 * Exposes the round operations that drive prompt author attempts to the module's tests.
 */
public interface RoundAccess
{
	/**
	 * Reserves a session before the driver launches it.
	 *
	 * @param round the round to update
	 * @param number the campaign's next prompt author attempt number
	 * @param cell the addressed cell
	 * @return the updated round
	 */
	Round reserve(Round round, int number, String cell);

	/**
	 * Inserts or updates a received attempt while preserving launch order.
	 *
	 * @param round the round to update
	 * @param attempt the received or updated attempt
	 * @return the updated round
	 */
	Round withAttempt(Round round, PromptAuthorAttempt attempt);
}

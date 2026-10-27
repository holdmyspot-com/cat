package com.holdmyspot.cat.common.optimizer.execution;

import com.holdmyspot.cat.common.optimizer.campaign.ApproachOrder;
import com.holdmyspot.cat.common.optimizer.campaign.OptimizationEvidence;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;

import java.util.Optional;

/**
 * Builds approach-ranking requests and validates their decisions for the driver.
 */
public interface ApproachRankingProtocol
{
	/**
	 * Creates a ranking request without trial evidence sections.
	 *
	 * @param brief    the same failure context used by this cell's prompt authors
	 * @param evidence the campaign's earlier attempts and promotion results
	 * @return a request under the role profile, without plugins
	 */
	SessionRequest request(PromptAuthorBrief brief, OptimizationEvidence evidence);

	/**
	 * Validates a complete ranking and resolves adjacent equal-fit groups by the cell's promotion record.
	 *
	 * @param result   the completed ranking session
	 * @param cell     the addressed cell
	 * @param evidence the campaign's earlier attempts
	 * @return the frozen order, or empty if the output violates the result schema
	 */
	Optional<ApproachOrder> order(SessionResult.Completed result, String cell, OptimizationEvidence evidence);
}

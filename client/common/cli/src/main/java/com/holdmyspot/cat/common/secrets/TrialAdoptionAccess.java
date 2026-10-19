package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.campaign.CellCounts;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.contract.Cell;

import java.util.List;
import java.util.Map;

/**
 * Exposes how trial adoption opens a round to the module's tests.
 */
@FunctionalInterface
public interface TrialAdoptionAccess
{
	/**
	 * Creates a round from the currently failing development cells and freezes its weak-candidate policy.
	 *
	 * @param counts the current development counts
	 * @param developmentCells the development cells, in contract order
	 * @param required a development cell to address even without failures, or null
	 * @return the round
	 */
	Round failingRound(Map<String, CellCounts> counts, List<Cell> developmentCells, String required);
}

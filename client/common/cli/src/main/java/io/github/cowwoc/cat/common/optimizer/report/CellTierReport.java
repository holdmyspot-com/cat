package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The development cell tier of a confirmed campaign ({@code design.md} section 6.6): each category's active cells and
 * whether development has widened to every cell.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param activeCells the identifiers of each category's active cells, by category in contract order
 * @param widened {@code true} if every cell is eligible for the working candidate's representative development
 *   trials
 */
public record CellTierReport(Map<String, List<String>> activeCells, boolean widened)
{
	/**
	 * Creates a report.
	 *
	 * @param activeCells the identifiers of each category's active cells, by category in contract order
	 * @param widened {@code true} if every cell is eligible for the working candidate's representative development
	 *   trials
	 * @throws NullPointerException if {@code activeCells} is null
	 */
	public CellTierReport
	{
		requireThat(activeCells, "activeCells").isNotNull();
		Map<String, List<String>> copy = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : activeCells.entrySet())
			copy.put(entry.getKey(), List.copyOf(entry.getValue()));
		activeCells = Collections.unmodifiableMap(copy);
	}

	/**
	 * Returns the cell tier of a campaign.
	 *
	 * @param campaign the campaign
	 * @return the report, or {@code null} before a contract is confirmed
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static CellTierReport of(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		if (!campaign.isConfirmed())
			return null;
		return new CellTierReport(CellTier.activeCellsByCategory(campaign.contract(), campaign.failedCells()),
			campaign.development().widened());
	}
}

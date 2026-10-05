package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * One category under one model and reasoning-effort combination. CAT measures compliance separately for each
 * such combination, called a cell.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param id the identifier that is unique within the contract: the category's identifier when the category has one
 *   cell, otherwise the category's identifier followed by {@code @} and the profile's label. A cell whose profile
 *   limits tool output appends {@code /tool-output-} and the token count to its contract cell's identifier
 * @param categoryId the identifier of the category whose tests the cell draws
 * @param profile the profile that the cell's trial sessions start under
 */
public record Cell(String id, String categoryId, TrialProfile profile)
{
	/**
	 * Creates a cell.
	 *
	 * @param id the identifier that is unique within the contract
	 * @param categoryId the identifier of the category whose tests the cell draws
	 * @param profile the profile that the cell's trial sessions start under
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code id} or {@code categoryId} are blank
	 */
	public Cell
	{
		requireThat(id, "id").isNotBlank();
		requireThat(categoryId, "categoryId").isNotBlank();
		requireThat(profile, "profile").isNotNull();
	}

	/**
	 * Returns this cell under a tool-output token limit: a distinct cell of the same category whose sessions keep at
	 * most {@code tokens} tokens of each tool output.
	 *
	 * @param tokens the most tokens of each tool output that the cell's sessions keep
	 * @return the limited cell
	 * @throws IllegalArgumentException if {@code tokens} is not positive
	 * @throws IllegalStateException if this cell already limits tool output
	 */
	public Cell withToolOutputTokenLimit(int tokens)
	{
		if (profile.toolOutputTokenLimit() != 0)
			throw new IllegalStateException("The cell already limits tool output: " + this);
		return new Cell(id + "/" + TrialProfile.TOOL_OUTPUT_LABEL + tokens, categoryId,
			profile.withToolOutputTokenLimit(tokens));
	}
}

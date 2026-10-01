package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * One category under one trial profile, which is the statistical unit (design &sect;2.1).
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param id the identifier that is unique within the contract: the category's identifier when the category has one
 *   cell, otherwise the category's identifier followed by {@code @} and the profile's label
 * @param categoryId the category
 * @param profile the profile that the cell's trial sessions start under
 */
public record Cell(String id, String categoryId, TrialProfile profile)
{
	/**
	 * Creates a cell.
	 *
	 * @param id the identifier that is unique within the contract
	 * @param categoryId the category
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
}

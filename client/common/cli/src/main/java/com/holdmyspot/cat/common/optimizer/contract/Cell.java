package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * One prompt test under one model and reasoning-effort combination. CAT measures the pass rate separately for each
 * such combination, called a cell.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param id the identifier that is unique within the contract: the test's identifier when the test has one cell,
 *   otherwise the test's identifier followed by {@code @} and the profile's label. A cell whose profile limits tool
 *   output appends {@code /tool-output-} and the token count to its contract cell's identifier
 * @param testId the identifier of the prompt test whose test cases the cell draws
 * @param profile the profile that the cell's trial sessions start under
 */
public record Cell(String id, String testId, TrialProfile profile)
{
	/**
	 * Creates a cell.
	 *
	 * @param id the identifier that is unique within the contract
	 * @param testId the identifier of the prompt test whose test cases the cell draws
	 * @param profile the profile that the cell's trial sessions start under
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code id} or {@code testId} are blank
	 */
	public Cell
	{
		requireThat(id, "id").isNotBlank();
		requireThat(testId, "testId").isNotBlank();
		requireThat(profile, "profile").isNotNull();
	}

	/**
	 * Returns this cell under a tool-output token limit: a distinct cell of the same test whose sessions keep at
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
		return new Cell(id + "/" + TrialProfile.TOOL_OUTPUT_LABEL + tokens, testId,
			profile.withToolOutputTokenLimit(tokens));
	}
}

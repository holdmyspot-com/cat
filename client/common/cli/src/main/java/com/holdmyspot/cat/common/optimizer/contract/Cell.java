package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import java.util.Optional;

/**
 * One prompt test under one evaluator, model and reasoning-effort combination. CAT measures its pass rate
 * separately.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param id        the durable address unique within the contract, prefixed by the evaluator in a shared contract
 * @param testId    the identifier of the prompt test whose test cases the cell draws
 * @param profile   the profile that the cell's trial sessions start under
 * @param evaluator the assigned harness, or empty before the profiles are assigned to an evaluator
 */
public record Cell(String id, String testId, TrialProfile profile, Optional<Harness> evaluator)
{
	/**
	 * Creates a cell.
	 *
	 * @param id        the identifier that is unique within the contract
	 * @param testId    the identifier of the prompt test whose test cases the cell draws
	 * @param profile   the profile that the cell's trial sessions start under
	 * @param evaluator the assigned harness, or empty before assignment
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code id} or {@code testId} are blank, or {@code profile} does not name both
	 *   a model and an effort
	 */
	public Cell
	{
		requireThat(id, "id").isNotBlank();
		requireThat(testId, "testId").isNotBlank();
		requireThat(profile, "profile").isNotNull();
		requireThat(profile.isComplete(), "profile.isComplete()").withContext(profile, "profile").isTrue();
		requireThat(evaluator, "evaluator").isNotNull();
	}

	/**
	 * Creates a cell in a profile set before assignment to a harness.
	 *
	 * @param id      the identifier that is unique within the profile set
	 * @param testId  the identifier of the test
	 * @param profile the profile that the cell's trial sessions start under
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if either identifier is blank or the profile is incomplete
	 */
	public Cell(String id, String testId, TrialProfile profile)
	{
		this(id, testId, profile, Optional.empty());
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
			profile.withToolOutputTokenLimit(tokens), evaluator);
	}
}

package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * The editing approach assigned to an author by its reservation's position in the cell's round.
 */
public enum AuthorApproach
{
	/**
	 * Corrects the instruction in the file that states it.
	 */
	CORRECT_INSTRUCTION("Correct the instruction where it is stated: edit the file that states the instruction " +
		"the failing replies violated or misapplied, `AGENTS.md` or `CLAUDE.md` included."),
	/**
	 * Rewrites existing instructions before adding guidance.
	 */
	REWRITE_INSTRUCTIONS("Rewrite rather than add: reword, merge or remove existing instructions, adding new text " +
		"only where no existing instruction covers the failing case."),
	/**
	 * Adds missing guidance where the sessions make the decision.
	 */
	ADD_GUIDANCE("Add the missing guidance where the failing sessions make the decision, in an existing or new file.");

	private final String text;

	/**
	 * Creates an approach with its author instruction.
	 *
	 * @param text the exact instruction rendered into the author request
	 */
	AuthorApproach(String text)
	{
		this.text = text;
	}

	/**
	 * Returns the instruction for this approach.
	 *
	 * @return the exact author instruction
	 */
	public String text()
	{
		return text;
	}
}

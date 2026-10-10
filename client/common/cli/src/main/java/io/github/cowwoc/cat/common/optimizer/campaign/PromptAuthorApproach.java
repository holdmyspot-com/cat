package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * The editing approaches that a cell's prompt author attempts take in turn, in declaration order.
 */
public enum PromptAuthorApproach
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
	ADD_GUIDANCE("Add the missing guidance where the failing sessions make the decision, in an existing or new file."),
	/**
	 * Removes the text that the failing replies copied.
	 */
	REMOVE_COPIED_TEXT("Remove what the failing replies copied: when they reproduce an example, a sample command or " +
		"an action that a prohibition names, delete that text or make it general rather than adding an instruction " +
		"against it."),
	/**
	 * States the consequence of breaking an instruction beside it.
	 */
	EXPLAIN_CONSEQUENCE("Explain the consequence where the decision is made: beside the instruction the failing " +
		"replies broke, state what the agent loses or breaks when it does not follow it, rather than repeating the " +
		"instruction or adding emphasis."),
	/**
	 * Keeps one form of an action that the instructions show in several forms.
	 */
	ONE_FORM("Offer one form of each action: where the instructions show several ways to perform the action that " +
		"the failing replies got wrong, keep the form that the passing replies follow and remove the others."),
	/**
	 * Places text that the agent must copy at the end of its line.
	 */
	COPY_TEXT_LAST("Put text to be copied last on its line: when the failing replies miscopy a command, path or " +
		"value, place it at the end of its line with nothing after it."),
	/**
	 * Defines each misread term before its first use and keeps its definitions in agreement.
	 */
	DEFINE_BEFORE_USE("Define before use and keep definitions in agreement: state each term, path or placeholder " +
		"that the failing replies misread before its first use, and make every statement of it agree rather than " +
		"removing the ones that already agree."),
	/**
	 * States a finished command, path or value instead of how to build it.
	 */
	GIVE_EXACT_TEXT("Give the exact text rather than how to build it: when the failing replies assemble a command, " +
		"path or value wrongly, state the finished command, path or value instead of the parts or steps that produce " +
		"it.");

	private final String text;

	/**
	 * Creates an approach with its prompt author instruction.
	 *
	 * @param text the exact instruction rendered into the prompt author request
	 */
	PromptAuthorApproach(String text)
	{
		this.text = text;
	}

	/**
	 * Returns the instruction for this approach.
	 *
	 * @return the exact prompt author instruction
	 */
	public String text()
	{
		return text;
	}
}

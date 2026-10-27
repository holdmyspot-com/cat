package com.holdmyspot.cat.common.optimizer.campaign;

/**
 * The editing approaches available to a cell's prompt author attempts.
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
		"it."),
	/**
	 * States how compatible instructions apply together at their meeting point.
	 */
	COMBINE_INSTRUCTIONS("State how instructions combine: where two instructions that do not contradict each other " +
		"apply in the same turn and the failing replies followed only one, state at the point where they meet which " +
		"one wins, or in what order to apply both."),
	/**
	 * Corrects the condition that loads or selects an otherwise correct instruction.
	 */
	FIX_LOAD_TRIGGER("Fix the trigger rather than the instruction: when the failing sessions never loaded or " +
		"applied an instruction that would have been correct, reword the condition that loads or selects it so that " +
		"it matches what the agent sees in the failing situation.");

	/**
	 * The declaration-order vocabulary size used by campaigns saved in format 43.
	 */
	public static final int LEGACY_COUNT = 9;
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

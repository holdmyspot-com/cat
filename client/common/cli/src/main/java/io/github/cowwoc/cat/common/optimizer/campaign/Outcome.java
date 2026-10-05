package io.github.cowwoc.cat.common.optimizer.campaign;

/**
 * The outcome of a certification attempt or a campaign, with the label that the user sees.
 */
public enum Outcome
{
	/**
	 * Every cell (category and trial profile) certified.
	 */
	CERTIFIED("certified"),
	/**
	 * The user kept a candidate proven better than the original prompt system in at least one cell, with none proven
	 * worse and every other cell certified or measured on at least 100 outcomes.
	 */
	IMPROVED_NOT_CERTIFIED("improved, not certified"),
	/**
	 * A cell is proven below 95% compliance in the certification attempt.
	 */
	BELOW_MINIMUM("not certified: below minimum"),
	/**
	 * A cell reached its trial cap without certifying or being proven below 95% compliance.
	 */
	INCONCLUSIVE("not certified: inconclusive"),
	/**
	 * A full development round produced no stronger candidate while the working candidate has more than
	 * {@value EditOrContinue#FAILURE_LIMIT} failures in a cell.
	 */
	PLATEAU("not certified: plateau"),
	/**
	 * The user stopped a certification attempt to revise the contract.
	 */
	STOPPED_FOR_CONTRACT_REVISION("not certified: stopped for contract revision"),
	/**
	 * The user started a new measurement identity during a certification attempt.
	 */
	TESTING_ENVIRONMENT_CHANGED("not certified: testing environment changed"),
	/**
	 * A remote or MCP response supplies governing instructions.
	 */
	UNSUPPORTED_INSTRUCTION_SOURCE("certification unavailable: unsupported instruction source"),
	/**
	 * A required trial path writes persistent state outside the trial copy, or lacks read-only credentials or a
	 * disposable fixture.
	 */
	INELIGIBLE_TRIAL_EFFECT("certification unavailable: ineligible trial effect"),
	/**
	 * A material requirement produces image, audio or video output.
	 */
	UNSUPPORTED_OUTPUT("certification unavailable: unsupported output"),
	/**
	 * A material obligation needs a follow-up turn chosen from the agent's reply.
	 */
	UNSUPPORTED_TEST_INTERACTION("certification unavailable: unsupported test interaction"),
	/**
	 * A detected dependence between trials invalidates the formal procedure.
	 */
	INVALID_STATISTICAL_ASSUMPTIONS("certification unavailable: invalid statistical assumptions"),
	/**
	 * The user explicitly accepted a candidate without certification.
	 */
	NOT_CERTIFIED("not certified");

	private final String label;

	/**
	 * Creates an outcome.
	 *
	 * @param label the text that the user sees
	 */
	Outcome(String label)
	{
		this.label = label;
	}

	/**
	 * Returns the text that the user sees.
	 *
	 * @return the label
	 */
	public String label()
	{
		return label;
	}

	/**
	 * Indicates whether this outcome reports that certification is unavailable.
	 *
	 * @return {@code true} for the five {@code certification unavailable} outcomes
	 */
	public boolean isUnavailable()
	{
		return switch (this)
		{
			case UNSUPPORTED_INSTRUCTION_SOURCE, INELIGIBLE_TRIAL_EFFECT, UNSUPPORTED_OUTPUT,
				UNSUPPORTED_TEST_INTERACTION, INVALID_STATISTICAL_ASSUMPTIONS -> true;
			case CERTIFIED, IMPROVED_NOT_CERTIFIED, BELOW_MINIMUM, INCONCLUSIVE, PLATEAU,
				STOPPED_FOR_CONTRACT_REVISION, TESTING_ENVIRONMENT_CHANGED, NOT_CERTIFIED -> false;
		};
	}
}

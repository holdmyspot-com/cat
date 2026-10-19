package com.holdmyspot.cat.common.optimizer.contract;

import java.util.List;

/**
 * Thrown when the agent that derives the contract returns an invalid proposal. The caller records a failure to
 * derive the contract rather than a failed trial.
 */
public final class InvalidProposalException extends Exception
{
	private static final long serialVersionUID = 1L;
	private final String[] problems;

	/**
	 * Creates an exception.
	 *
	 * @param problems every violation found, each naming the JSON location it concerns
	 * @throws NullPointerException if {@code problems} is null
	 */
	public InvalidProposalException(List<String> problems)
	{
		super("Invalid proposal: " + String.join("; ", problems));
		this.problems = problems.toArray(String[]::new);
	}

	/**
	 * Returns every violation.
	 *
	 * @return each violation, naming the JSON location it concerns
	 */
	public List<String> problems()
	{
		return List.of(problems);
	}
}

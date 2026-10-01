package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.List;

/**
 * Thrown when deriver output is not a valid proposal. The caller treats this as an infrastructure failure, not as
 * evidence.
 */
public final class InvalidProposalException extends Exception
{
	private static final long serialVersionUID = 1L;
	private final String[] problems;

	/**
	 * Creates an exception.
	 *
	 * @param problems every violation found, each naming the JSON location it concerns
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

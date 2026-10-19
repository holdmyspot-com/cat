package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * Thrown when a prompt test configuration does not follow its format. Each problem begins with the JSON path of the
 * value it concerns.
 */
public final class PromptConfigurationException extends Exception
{
	private static final long serialVersionUID = 1L;
	private final String[] problems;

	/**
	 * Creates an exception.
	 *
	 * @param problems the problems, each beginning with a JSON path
	 * @throws NullPointerException if {@code problems} is null
	 * @throws IllegalArgumentException if {@code problems} is empty
	 */
	public PromptConfigurationException(List<String> problems)
	{
		super(String.join("\n", problems));
		requireThat(problems, "problems").isNotEmpty();
		this.problems = problems.toArray(String[]::new);
	}

	/**
	 * Returns the problems.
	 *
	 * @return each problem, beginning with the JSON path of the value it concerns, in file order
	 */
	public List<String> problems()
	{
		return List.of(problems);
	}
}

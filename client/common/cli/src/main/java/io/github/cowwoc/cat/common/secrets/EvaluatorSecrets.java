package io.github.cowwoc.cat.common.secrets;

import io.github.cowwoc.cat.common.PromptEvaluation;

/**
 * Initializes the evaluator composition boundary before an extracted evaluator owner uses its coordinator access.
 */
public final class EvaluatorSecrets
{
	/**
	 * Marks that the prompt-evaluation composition boundary registered its evaluator owners.
	 */
	@FunctionalInterface
	public interface Access
	{
		/**
		 * Confirms that the evaluator composition boundary completed registration.
		 */
		void initialize();
	}

	private static volatile Access access;

	/**
	 * Prevents construction of the evaluator secret façade.
	 */
	private EvaluatorSecrets()
	{
	}

	/**
	 * Registers the evaluator composition boundary after it configures extracted owners.
	 *
	 * @param access the completion marker for evaluator composition
	 */
	public static void configure(Access access)
	{
		EvaluatorSecrets.access = java.util.Objects.requireNonNull(access, "access may not be null");
	}

	/**
	 * Initializes and returns the evaluator composition boundary.
	 *
	 * @return the registered evaluator composition marker
	 */
	public static Access access()
	{
		Access result = access;
		if (result == null)
		{
			initialize(PromptEvaluation.class);
			result = access;
		}
		if (result == null)
			throw new AssertionError("PromptEvaluation did not register evaluator composition");
		return result;
	}

	/**
	 * Loads the sole evaluator composition owner.
	 *
	 * @param owner the composition owner that registers evaluator access
	 */
	private static void initialize(Class<?> owner)
	{
		try
		{
			Class.forName(owner.getName(), true, owner.getClassLoader());
		}
		catch (ClassNotFoundException e)
		{
			throw new AssertionError("Unable to initialize evaluator composition owner: " + owner.getName(), e);
		}
	}
}

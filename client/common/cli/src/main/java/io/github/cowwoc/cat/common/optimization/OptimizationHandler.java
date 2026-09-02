package io.github.cowwoc.cat.common.optimization;

import java.io.IOException;

/**
 * Executes one typed optimization operation.
 *
 * @param <I> the accepted immutable input type
 */
public interface OptimizationHandler<I extends OptimizationInput>
{
	/**
	 * Returns the operation owned by this handler.
	 *
	 * @return the owning operation
	 */
	OperationKind operationKind();

	/**
	 * Returns the input type accepted by this handler.
	 *
	 * @return the accepted input type
	 */
	Class<I> inputType();

	/**
	 * Executes the operation.
	 *
	 * @param context the operation context
	 * @param input the validated operation input
	 * @return the operation result
	 * @throws IOException if operation evidence cannot be produced
	 */
	OptimizationResult execute(OptimizationContext context, I input) throws IOException;
}

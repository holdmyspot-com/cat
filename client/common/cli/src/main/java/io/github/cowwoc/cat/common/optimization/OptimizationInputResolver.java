package io.github.cowwoc.cat.common.optimization;

import java.io.IOException;
import java.util.Optional;

/**
 * Resolves typed input for one certification-process transition.
 */
@FunctionalInterface
public interface OptimizationInputResolver
{
	/**
	 * Resolves the complete input for one operation transition.
	 *
	 * @param context the immutable operation context
	 * @param transitionSequence the positive one-based transition sequence
	 * @param predecessor the verified preceding result, when one exists
	 * @return the typed input for the operation selected by the context
	 * @throws IOException if predecessor evidence is missing, malformed, or incompatible
	 */
	OptimizationInput resolve(OptimizationContext context, long transitionSequence,
		Optional<OptimizationResult> predecessor) throws IOException;
}

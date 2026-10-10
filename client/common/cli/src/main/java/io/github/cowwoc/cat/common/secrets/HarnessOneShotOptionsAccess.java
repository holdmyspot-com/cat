package io.github.cowwoc.cat.common.secrets;


/**
 * Exposes the prompt limit of a one-shot harness turn to the module's tests.
 */
@FunctionalInterface
public interface HarnessOneShotOptionsAccess
{
	/**
	 * Returns the largest prompt file, in bytes, that a one-shot turn accepts.
	 *
	 * @return the number of bytes
	 */
	int maximumPromptBytes();
}

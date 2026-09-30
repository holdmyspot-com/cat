package io.github.cowwoc.cat.common.runner;

import java.io.IOException;

/**
 * Retains runner-owned artifacts after a native session closes and before its run state settles.
 */
@FunctionalInterface
public interface HarnessRunFinalizer
{
	/**
	 * Finishes one closed native run before completion, failure, or pruning changes its retained artifacts.
	 *
	 * @param run the run whose native session has closed
	 * @throws IOException if required artifacts cannot be retained
	 */
	void finish(HarnessRun run) throws IOException;
}

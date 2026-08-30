package io.github.cowwoc.cat.common.runner;

import java.io.IOException;

/**
 * Runs one typed harness evaluation and returns its retained evidence.
 */
@FunctionalInterface
public interface HarnessEvaluationRunner
{
	/**
	 * Runs one isolated harness evaluation.
	 *
	 * @param request the complete harness evaluation request
	 * @return the retained evidence and native-process timing
	 * @throws IOException if the evaluation cannot retain its result
	 */
	HarnessEvaluationExecution execute(HarnessEvaluationRequest request) throws IOException;
}

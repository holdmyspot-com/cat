package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Identifies the retained evidence produced by one completed harness execution.
 *
 * <p>
 * The evaluator can use this value without knowing how a harness started its harness process, carried turns, or wrote
 * its protocol stream. The harness runner owns those details and returns only the project and final response that the
 * next evaluation boundary needs.
 *
 * @param retainedProject the completed project available for deterministic and semantic checks
 *
 * @param rawResult the final harness response for the completed task
 */
public record HarnessEvaluationResult(Path retainedProject, Path rawResult)
{
	/**
	 * Normalizes one harness result after its producer has validated the evidence.
	 *
	 * @param retainedProject the completed project available for deterministic and semantic checks
	 *
	 * @param rawResult the final harness response for the completed task
	 */
	public HarnessEvaluationResult
	{
		requireThat(retainedProject, "retainedProject").isNotNull();
		requireThat(rawResult, "rawResult").isNotNull();
		retainedProject = retainedProject.toAbsolutePath().normalize();
		rawResult = rawResult.toAbsolutePath().normalize();
	}

	/**
	 * Creates a result after confirming that the runner retained the required evaluator evidence.
	 *
	 * @param retainedProject the completed project available for deterministic and semantic checks
	 *
	 * @param rawResult the final harness response for the completed task
	 *
	 * @return the checked retained result
	 *
	 * @throws IOException if either required retained artifact is unavailable
	 */
	public static HarnessEvaluationResult retained(Path retainedProject, Path rawResult) throws IOException
	{
		HarnessEvaluationResult result = new HarnessEvaluationResult(retainedProject, rawResult);
		if (!Files.isDirectory(result.retainedProject()) || !Files.isRegularFile(result.rawResult()))
		{
			throw new IOException("Harness runner did not retain its completed project and final response");
		}
		return result;
	}
}

package io.github.cowwoc.cat.common;

import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.ITestContext;
import org.testng.annotations.Test;

/**
 * Supplies a conventionally discovered scenario check for the evaluator integration test.
 */
public final class JavaCheckFailureScenarioTest
{
	/**
	 * Creates the test fixture.
	 */
	public JavaCheckFailureScenarioTest()
	{
	}

	/**
	 * Fails only when the evaluator supplies a retained project with the failure marker.
	 *
	 * @param context the TestNG context that may contain evaluator-owned evidence paths
	 */
	@Test
	public void recordsMarkedFailure(ITestContext context)
	{
		Object retainedProject = context.getAttribute("retainedProject");
		if (retainedProject == null)
			return;
		if (Files.exists(Path.of((String) retainedProject).resolve("expected-failure")))
			throw new AssertionError("The retained project requests this Java-check failure");
	}

	/**
	 * Verifies the evaluator supplies the retained executor-result path to a second Java check.
	 *
	 * @param context the TestNG context that may contain evaluator-owned evidence paths
	 */
	@Test
	public void recordsRetainedResult(ITestContext context)
	{
		Object executorResult = context.getAttribute("executorResult");
		if (executorResult == null)
			return;
		if (!Files.isRegularFile(Path.of((String) executorResult)))
			throw new AssertionError("The evaluator supplied no retained executor result");
	}
}

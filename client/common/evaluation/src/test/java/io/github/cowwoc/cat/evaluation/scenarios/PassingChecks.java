package io.github.cowwoc.cat.evaluation.scenarios;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.ITestContext;
import org.testng.annotations.Test;

/**
 * Supplies one successful scenario check for evaluator-boundary tests.
 */
public final class PassingChecks
{
	/**
	 * Verifies the evaluator supplied retained evidence to this Java check.
	 *
	 * @param context the TestNG context populated by the evaluator
	 */
	@Test
	public void readsRetainedEvidence(ITestContext context)
	{
		requireThat(context.getAttribute("retainedProject"), "retainedProject").isEqualTo("/retained/project");
		requireThat(context.getAttribute("executorResult"), "executorResult").
			isEqualTo("/retained/executor-result.json");
	}
}

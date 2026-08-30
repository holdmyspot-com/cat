package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;

import org.testng.annotations.Test;

/**
 * Tests persisted attempt-status parsing.
 */
public final class AttemptStatusTest
{
	/**
	 * Creates the test fixture.
	 */
	public AttemptStatusTest()
	{
	}

	/**
	 * Verifies a persisted status spelling maps to its constrained domain value.
	 *
	 * @throws IOException if parsing fails
	 */
	@Test
	public void acceptsPersistedStatus() throws IOException
	{
		requireThat(AttemptStatus.parse("passed"), "status").isEqualTo(AttemptStatus.PASSED);
	}

	/**
	 * Verifies an unsupported persisted status is rejected at the domain boundary.
	 *
	 * @throws IOException if parsing fails as expected
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Invalid trial status: unknown.*")
	public void rejectsUnsupportedStatus() throws IOException
	{
		AttemptStatus.parse("unknown");
	}
}

package io.github.cowwoc.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link HarnessEvaluationResult}.
 */
public final class HarnessEvaluationResultTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessEvaluationResultTest()
	{
	}

	/**
	 * Verifies a runner result exposes only existing retained evaluator evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void exposesRetainedEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-evaluation-result-"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path response = Files.writeString(root.resolve("response.txt"), "completed response");

			HarnessEvaluationResult result = HarnessEvaluationResult.retained(project, response);

			requireThat(result.retainedProject(), "retainedProject").isEqualTo(project.toAbsolutePath().normalize());
			requireThat(result.rawResult(), "rawResult").isEqualTo(response.toAbsolutePath().normalize());
		}
	}

	/**
	 * Verifies a runner cannot claim evidence that it did not retain.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsMissingEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-evaluation-result-"));
			Path project = Files.createDirectory(root.resolve("project"));

			HarnessEvaluationResult.retained(project, root.resolve("missing-response.txt"));
		}
	}
}

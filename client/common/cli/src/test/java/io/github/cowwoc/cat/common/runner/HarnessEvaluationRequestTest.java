package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessEvaluationRequest}.
 */
public final class HarnessEvaluationRequestTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessEvaluationRequestTest()
	{
	}

	/**
	 * Verifies a request rejects a read-only evidence entry that is not a directory.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsNonDirectoryEvidence() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("harness-evaluation-request-"));
			Path project = Files.createDirectory(root.resolve("project"));
			Path evidence = Files.writeString(root.resolve("evidence.txt"), "evidence");

			new HarnessEvaluationRequest(root.resolve("runner"), root.resolve("artifact"), project, "model-a", "medium",
				"{\"type\":\"complete\"}\n", root.resolve("output.jsonl"), root.resolve("receipt.json"), false,
				List.of(evidence));
		}
	}
}

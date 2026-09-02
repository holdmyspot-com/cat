package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.TemporaryDirectories;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
	 * Verifies the typed profile preserves the launch Codex model and medium reasoning effort.
	 */
	@Test
	public void acceptsCodexLaunchProfile()
	{
		HarnessModelProfile profile = HarnessModelProfile.fromExternalValues("gpt-5.6-luna", "medium");

		requireThat(profile.model(), "model").isEqualTo("gpt-5.6-luna");
		requireThat(profile.effort(), "effort").isEqualTo(ReasoningEffort.MEDIUM);
		requireThat(profile.externalEffort(), "externalEffort").isEqualTo("medium");
	}

	/**
	 * Verifies an untyped ingress rejects an effort outside the supported domain.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported reasoning effort: extreme.*")
	public void rejectsUnsupportedReasoningEffort()
	{
		HarnessModelProfile.fromExternalValues("gpt-5.6-luna", "extreme");
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

			HarnessModelProfile profile = HarnessModelProfile.fromExternalValues("model-a", "medium");
			new HarnessEvaluationRequest(root.resolve("runner"), root.resolve("artifact"), project, profile,
				"{\"type\":\"complete\"}\n", root.resolve("output.jsonl"), root.resolve("receipt.json"), false,
				List.of(evidence));
		}
	}
}

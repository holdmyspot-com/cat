package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the packaged-runner lifecycle-soak boundary of {@link RuntimeVerification}.
 */
public final class LifecycleSoakTest
{
	/**
	 * Creates the test fixture.
	 */
	public LifecycleSoakTest()
	{
	}

	/**
	 * Verifies the model-free lifecycle soak retains 100 terminal scripted outcomes and is invalidated when the
	 * packaged runner changes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void bindsLifecycleSoakToPackagedRuntime() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation-lifecycle-soak"));
			Path artifactFile = write(root, "runner-artifact/client/runner.txt", "runner artifact\n");
			Path artifact = artifactFile.getParent().getParent();
			RunnerRuntimeManifest.write(artifact);
			Path temporary = root.resolve(".cat/work/temp");

			ObjectNode receipt = RuntimeVerification.lifecycleSoakForArtifact(temporary, artifact);

			requireThat(receipt.path("status").asString(), "soakStatus").isEqualTo("passed");
			requireThat(receipt.path("completed_suites").asInt(), "completedSuites").isEqualTo(100);
			requireThat(receipt.path("outcomes").toString(), "outcomes").contains("workspace_collision").
				contains("missing_check_receipt").contains("owner_loss").doesNotContain("inherited_output_descriptor").
				contains("stale_runtime").contains("retained_path_rewrite");
			requireThat(Path.of(receipt.path("receipt").asString()), "receipt").isRegularFile();
			requireThat(Path.of(receipt.path("receipt").asString()).startsWith(root.resolve(".cat/work")),
				"receiptInWorkRoot").isTrue();
			requireThat(Path.of(receipt.path("receipt").asString()).startsWith(temporary),
				"receiptOutsideTemporaryRoot").isFalse();
			RuntimeVerification.verifyLifecycleSoak(temporary, artifact);

			Files.writeString(artifactFile, "changed runner artifact\n");
			Files.delete(artifact.resolve(RunnerRuntimeManifest.PATH));
			RunnerRuntimeManifest.write(artifact);
			try
			{
				RuntimeVerification.verifyLifecycleSoak(temporary, artifact);
				throw new AssertionError("Expected runtime drift to invalidate the lifecycle soak receipt");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("requires a passing lifecycle check");
			}
		}
	}

	/**
	 * Verifies a lifecycle check resolves the executable runner declared within the selected artifact.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void resolvesRunnerFromBuildPlan() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation-lifecycle-soak"));
			Path artifact = Files.createDirectories(root.resolve("runner-artifact"));
			Path runner = write(artifact, "client/bin/fixture-runner", """
				#!/bin/sh
				if [ "$1" = "--verify-artifact" ]; then
				  printf '%s\\n' "$2"
				fi
				""");
			requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
			RunnerRuntimeManifest.write(artifact);
			Path plan = write(root, "build-plan.json", """
				{
				  "working_directory": ".",
				  "runner_artifact": "runner-artifact",
				  "runner_launcher": "client/bin/fixture-runner"
				}
				""");
			Path temporary = root.resolve(".cat/work/temp");

			ObjectNode receipt = RuntimeVerification.lifecycleSoakFromPlan(temporary, plan);

			requireThat(receipt.path("status").asString(), "soakStatus").isEqualTo("passed");
		}
	}

	/**
	 * Verifies a lifecycle check becomes stale when the packaged runtime changes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void invalidatesLifecycleCheckForRuntimeDrift() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation-lifecycle-soak"));
			Path artifactFile = write(root, "runner-artifact/client/lib/modules", "runtime v1\n");
			Path artifact = artifactFile.getParent().getParent().getParent();
			RunnerRuntimeManifest.write(artifact);
			Path temporary = root.resolve(".cat/work/temp");

			RuntimeVerification.lifecycleSoakForArtifact(temporary, artifact);
			RuntimeVerification.verifyLifecycleSoak(temporary, artifact);
			Files.writeString(artifactFile, "runtime v2\n");
			Files.delete(artifact.resolve(RunnerRuntimeManifest.PATH));
			RunnerRuntimeManifest.write(artifact);

			try
			{
				RuntimeVerification.verifyLifecycleSoak(temporary, artifact);
				throw new AssertionError("Expected changed packaged runtime to invalidate the lifecycle check");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("requires a passing lifecycle check");
			}
		}
	}

	/**
	 * Verifies a lifecycle check cannot authorize formal work after one retained fault outcome is altered.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsAlteredLifecycleOutcome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation-lifecycle-soak"));
			Path artifactFile = write(root, "runner-artifact/client/runner.txt", "runner artifact\n");
			Path artifact = artifactFile.getParent().getParent();
			RunnerRuntimeManifest.write(artifact);
			Path temporary = root.resolve(".cat/work/temp");
			ObjectNode receipt = RuntimeVerification.lifecycleSoakForArtifact(temporary, artifact);
			Path receiptPath = Path.of(receipt.path("receipt").asString());
			ObjectNode altered = (ObjectNode) scope.json().readTree(receiptPath.toFile());
			((ObjectNode) altered.path("outcomes").get(1)).put("terminal_state", "completed");
			Files.writeString(receiptPath, scope.json().writeValueAsString(altered));

			try
			{
				RuntimeVerification.verifyLifecycleSoak(temporary, artifact);
				throw new AssertionError("Expected an altered lifecycle outcome to invalidate the check");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("incomplete terminal outcome");
			}
		}
	}

	/**
	 * Writes one fixture file below its root.
	 *
	 * @param root the fixture root
	 * @param relativePath the root-relative file path
	 * @param contents the file contents
	 * @return the written file
	 * @throws IOException if fixture I/O fails
	 */
	private static Path write(Path root, String relativePath, String contents) throws IOException
	{
		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, contents);
		return file;
	}
}

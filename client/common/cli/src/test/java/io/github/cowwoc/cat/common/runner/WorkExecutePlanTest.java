package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the runner-enforced work-execute plan boundary.
 */
public final class WorkExecutePlanTest
{
	/**
	 * Creates the test fixture.
	 */
	public WorkExecutePlanTest()
	{
	}

	/**
	 * Accepts a manifest bound to its detailed plan and verifies its declared output.
	 *
	 * @throws IOException if test evidence cannot be created or read
	 */
	@Test
	public void validatesBoundPlanAndOutput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("work-execute-plan-"));
			Path plan = project.resolve("plan.md");
			Files.writeString(plan, "# Plan\n");
			Path output = project.resolve("result.txt");
			Files.writeString(output, "implemented\n");
			Path manifest = project.resolve("work-execute-plan.json");
			Files.writeString(manifest, manifest(Fingerprints.ofFile(plan), "step-1", "result.txt",
				Fingerprints.ofFile(output)));

			WorkExecutePlan.Manifest result = WorkExecutePlan.read(new JsonMapper(), manifest, project);
			requireThat(result.workspaceMode(), "workspaceMode").isEqualTo(WorkExecutePlan.WorkspaceMode.EXCLUSIVE_SHARED);
			requireThat(result.writes().getFirst().path(), "writePath").isEqualTo(output);
			result.verifyWrites(project);
		}
	}

	/**
	 * Rejects two detailed-plan steps that require different bytes for the same output path.
	 *
	 * @throws IOException if test evidence cannot be created or read
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsContradictoryWrites() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("work-execute-plan-"));
			Path plan = project.resolve("plan.md");
			Files.writeString(plan, "# Plan\n");
			Path manifest = project.resolve("work-execute-plan.json");
			Files.writeString(manifest, "{\n" +
				"  \"schema_version\": \"cat-work-execute-plan-v1\",\n" +
				"  \"plan_path\": \"plan.md\",\n" +
				"  \"plan_sha256\": \"" + Fingerprints.ofFile(plan) + "\",\n" +
				"  \"workspace_mode\": \"exclusive_shared\",\n" +
				"  \"writes\": [\n" +
				"    {\"step\": \"step-1\", \"path\": \"result.txt\", \"sha256\": \"" +
					Fingerprints.ofUtf8("first\n") + "\"},\n" +
				"    {\"step\": \"step-2\", \"path\": \"result.txt\", \"sha256\": \"" +
					Fingerprints.ofUtf8("second\n") + "\"}\n" +
				"  ]\n" +
				"}\n");

			WorkExecutePlan.read(new JsonMapper(), manifest, project);
		}
	}

	/**
	 * Creates a complete one-write manifest.
	 *
	 * @param planFingerprint the detailed-plan fingerprint
	 * @param step the required write step
	 * @param path the project-relative write path
	 * @param outputFingerprint the required output fingerprint
	 * @return the manifest JSON
	 */
	private static String manifest(String planFingerprint, String step, String path, String outputFingerprint)
	{
		return "{\n" +
			"  \"schema_version\": \"cat-work-execute-plan-v1\",\n" +
			"  \"plan_path\": \"plan.md\",\n" +
			"  \"plan_sha256\": \"" + planFingerprint + "\",\n" +
			"  \"workspace_mode\": \"exclusive_shared\",\n" +
			"  \"writes\": [\n" +
			"    {\"step\": \"" + step + "\", \"path\": \"" + path + "\", \"sha256\": \"" +
				outputFingerprint + "\"}\n" +
			"  ]\n" +
			"}\n";
	}
}

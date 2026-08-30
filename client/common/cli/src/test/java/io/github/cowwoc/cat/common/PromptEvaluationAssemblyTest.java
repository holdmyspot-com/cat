package io.github.cowwoc.cat.common;

import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the build-plan assembly boundary of {@link PromptEvaluation}.
 */
public final class PromptEvaluationAssemblyTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationAssemblyTest()
	{
	}

	/**
	 * Verifies a selected, version-controlled build plan creates and verifies its declared runner artifact.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void assemblesDeclaredRunnerArtifact() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path runner = write(root, "fixture-runner", """
				#!/bin/sh
				printf 'native launcher warning\\n'
				case "$1" in
				  --verify-artifact) printf '%s\\n' "$2" ;;
				esac
				""");
			requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
			Path build = write(root, "build-artifact.sh", """
				#!/bin/sh
				mkdir -p runner-artifact
				printf artifact > runner-artifact/marker.txt
				""");
			requireThat(build.toFile().setExecutable(true), "buildExecutable").isTrue();
			Path plan = write(root, "build-plan.json", """
				{
				  "working_directory": ".",
				  "command": ["%s"],
				  "inputs": ["build-artifact.sh"],
				  "runner_artifact": "runner-artifact"
				}
				""".formatted(build));

			ObjectNode result = PromptEvaluation.assemble(root.resolve(".cat/work/temp"), plan, runner);

			requireThat(Path.of(result.path("runner_artifact").asString()), "runnerArtifact").isDirectory();
			requireThat(result.path("runner_artifact_sha256").asString(), "fingerprint").isNotBlank();
			requireThat(result.path("build_plan").asString(), "buildPlan").isEqualTo(plan.toString());
			requireThat(root.resolve(".cat/work/prompt-evaluation-assemblies"), "assemblyReceiptDirectory").
				isDirectory();
			requireThat(Files.exists(root.resolve(".cat/work/temp/prompt-evaluation-assemblies")),
				"temporaryAssemblyReceiptDirectory").isFalse();
		}
	}

	/**
	 * Verifies a build plan rejects a non-positive duration before it starts its build command.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*timeout_seconds must be a positive whole number.*")
	public void rejectsInvalidBuildTimeout() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path runner = write(root, "fixture-runner", "#!/bin/sh\n");
			requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
			Path plan = write(root, "build-plan.json", """
				{
				  "working_directory": ".",
				  "timeout_seconds": 0,
				  "command": ["/bin/true"],
				  "inputs": ["fixture-runner"],
				  "runner_artifact": "runner-artifact"
				}
				""");

			PromptEvaluation.assemble(root.resolve(".cat/work/temp"), plan, runner);
		}
	}

	/**
	 * Verifies a build plan cannot fingerprint a directory that contains the runtime artifact it creates.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsInputContainingArtifact() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path runner = write(root, "fixture-runner", """
				#!/bin/sh
				case "$1" in
				  --verify-artifact) printf '%s\\n' "$2" ;;
				esac
				""");
			requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
			Path build = write(root, "build-artifact.sh", "#!/bin/sh\ntouch build-ran\n");
			requireThat(build.toFile().setExecutable(true), "buildExecutable").isTrue();
			Path plan = write(root, "build-plan.json", """
				{
				  "working_directory": ".",
				  "command": ["%s"],
				  "inputs": ["."],
				  "runner_artifact": "runner-artifact"
				}
				""".formatted(build));

			boolean rejected = false;
			try
			{
				PromptEvaluation.assemble(root.resolve(".cat/work/temp"), plan, runner);
			}
			catch (IOException e)
			{
				rejected = e.getMessage().contains("contains its runner artifact");
			}
			requireThat(rejected, "rejected").isTrue();
			requireThat(Files.exists(root.resolve("build-ran")), "buildWasNotRun").isFalse();
		}
	}

	/**
	 * Verifies an assembly receipt skips an unchanged declared build and rebuilds when a declared input changes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reusesAssemblyForSameInputs() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path runner = write(root, "fixture-runner", """
				#!/bin/sh
				case "$1" in
				  --verify-artifact) printf '%s\\n' "$2" ;;
				esac
				""");
			requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
			Path build = write(root, "build-artifact.sh", """
				#!/bin/sh
				count=$(cat build-count 2>/dev/null || printf 0)
				printf '%s' "$((count + 1))" > build-count
				mkdir -p runner-artifact
				printf original > runner-artifact/marker.txt
				""");
			requireThat(build.toFile().setExecutable(true), "buildExecutable").isTrue();
			Path plan = write(root, "build-plan.json", """
				{
				  "working_directory": ".",
				  "command": ["%s"],
				  "inputs": ["build-artifact.sh"],
				  "runner_artifact": "runner-artifact"
				}
				""".formatted(build));
			Path temporary = root.resolve(".cat/work/temp");

			ObjectNode first = PromptEvaluation.assemble(temporary, plan, runner);
			ObjectNode second = PromptEvaluation.assemble(temporary, plan, runner);
			requireThat(first.path("reused").asBoolean(), "firstReused").isFalse();
			requireThat(second.path("reused").asBoolean(), "secondReused").isTrue();
			requireThat(Files.readString(root.resolve("build-count")), "buildCount").isEqualTo("1");

			Files.writeString(build, Files.readString(build).replace("original", "changed"));
			ObjectNode rebuilt = PromptEvaluation.assemble(temporary, plan, runner);
			requireThat(rebuilt.path("reused").asBoolean(), "rebuiltReused").isFalse();
			requireThat(Files.readString(root.resolve("build-count")), "rebuiltCount").isEqualTo("2");
			requireThat(Files.readString(root.resolve("runner-artifact/marker.txt")), "rebuiltMarker").isEqualTo("changed");
		}
	}

	/**
	 * Verifies a prompt-only build input rebuilds the package without changing the execution environment identity.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void separatesBuildAndExecutionInputs() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path runner = write(root, "fixture-runner", """
				#!/bin/sh
				case "$1" in
				  --verify-artifact) printf '%s\\n' "$2" ;;
				esac
				""");
			requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
			Path build = write(root, "build-artifact.sh", """
				#!/bin/sh
				count=$(cat runtime-count 2>/dev/null || printf 0)
				printf '%s' "$((count + 1))" > runtime-count
				mkdir -p runner-artifact/rules
				printf artifact > runner-artifact/marker.txt
				printf original > runner-artifact/rules/prompt.md
				""");
			requireThat(build.toFile().setExecutable(true), "buildExecutable").isTrue();
			Path repackage = write(root, "repackage-prompt-bundle.sh", """
				#!/bin/sh
				count=$(cat prompt-count 2>/dev/null || printf 0)
				printf '%s' "$((count + 1))" > prompt-count
				printf repackaged > runner-artifact/rules/prompt.md
				""");
			requireThat(repackage.toFile().setExecutable(true), "repackageExecutable").isTrue();
			Path prompt = write(root, "prompt.md", "first prompt\n");
			Path plan = write(root, "build-plan.json", """
				{
				  "working_directory": ".",
				  "command": ["%s"],
				  "prompt_command": ["%s"],
				  "inputs": ["build-artifact.sh", "prompt.md"],
				  "execution_inputs": ["build-artifact.sh"],
				  "runner_artifact": "runner-artifact"
				}
				""".formatted(build, repackage));
			Path temporary = root.resolve(".cat/work/temp");

			ObjectNode original = PromptEvaluation.assemble(temporary, plan, runner);
			Files.writeString(prompt, "revised prompt\n");
			ObjectNode promptRevision = PromptEvaluation.assemble(temporary, plan, runner);
			requireThat(promptRevision.path("reused").asBoolean(), "promptRevisionReused").isFalse();
			requireThat(promptRevision.path("repackaged_prompt_bundle").asBoolean(), "promptRevisionRepackaged").isTrue();
			requireThat(promptRevision.path("execution_inputs_sha256").asString(), "promptRevisionExecutionInputs").
				isEqualTo(original.path("execution_inputs_sha256").asString());
			requireThat(promptRevision.path("prompt_bundle_sha256").asString(), "promptRevisionBundle").
				isNotEqualTo(original.path("prompt_bundle_sha256").asString());
			requireThat(Files.readString(root.resolve("runtime-count")), "runtimeBuildCount").isEqualTo("1");
			requireThat(Files.readString(root.resolve("prompt-count")), "promptBundleCount").isEqualTo("1");
			requireThat(Files.readString(root.resolve("runner-artifact/rules/prompt.md")), "installedPrompt").
				isEqualTo("repackaged");

			Files.writeString(build, Files.readString(build).replace("printf artifact", "printf revised-artifact"));
			ObjectNode runnerRevision = PromptEvaluation.assemble(temporary, plan, runner);
			requireThat(runnerRevision.path("repackaged_prompt_bundle").asBoolean(), "runnerRevisionRepackaged").isFalse();
			requireThat(runnerRevision.path("execution_inputs_sha256").asString(), "runnerRevisionExecutionInputs").
				isNotEqualTo(original.path("execution_inputs_sha256").asString());
			requireThat(Files.readString(root.resolve("runtime-count")), "rebuiltRuntimeCount").isEqualTo("2");
		}
	}
}

package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests {@link ProcessExecution}.
 */
public final class ProcessExecutionTest
{
	/**
	 * Creates the test fixture.
	 */
	public ProcessExecutionTest()
	{
	}

	/**
	 * Verifies a build that exceeds its declared duration is terminated instead of leaving its owning workflow running.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*timed out.*")
	public void terminatesTimedOutBuild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("process-execution"));
			Path build = root.resolve("build.sh");
			Files.writeString(build, "#!/bin/sh\nsleep 5\n");
			build.toFile().setExecutable(true);

			try
			{
				ProcessExecution.runBuild(root, List.of(build.toString()), root, Duration.ofMillis(100));
			}
			finally
			{
				try (Stream<Path> children = Files.list(root))
				{
					requireThat(children.filter(path -> path.getFileName().toString().startsWith(
						"prompt-evaluation-build-")).toList(), "remainingBuildInvocations").isEmpty();
				}
			}
		}
	}

	/**
	 * Verifies a completed runner query removes its invocation directory after returning its output.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void removesCompletedRunnerQueryWorkspace() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("process-execution"));

			String output = ProcessExecution.runnerOutput(Path.of("/bin/echo"), root, "runner metadata");

			requireThat(output, "output").isEqualTo("runner metadata" + System.lineSeparator());
			try (Stream<Path> children = Files.list(root))
			{
				requireThat(children.filter(path -> path.getFileName().toString().startsWith(
					"prompt-evaluation-runner-query-")).toList(), "remainingRunnerQueries").isEmpty();
			}
		}
	}

	/**
	 * Verifies a script runner works through its declared interpreter without an operating-system execute bit.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsScriptWithoutExecuteBit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("process-execution"));
			Path script = root.resolve("runner");
			Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' runner\n");

			String output = ProcessExecution.runnerOutput(script, root);

			requireThat(output, "output").isEqualTo("runner" + System.lineSeparator());
		}
	}

	/**
	 * Verifies a completed direct command does not wait for a background child that retains its diagnostic descriptor.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void returnsAfterCommandExitWithOpenChild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path output = root.resolve("diagnostics.txt");
			Path childPid = root.resolve("child.pid");
			long startedAt = System.nanoTime();
			try
			{
				ProcessExecution.run(List.of("sh", "-c",
					"sleep 30 >&1 & printf '%s' \"$!\" > child.pid; printf parent"), root, "", output);
				long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
				assertTrue(elapsedMillis < 2_000,
					"A retained child descriptor delayed completion for " + elapsedMillis + " ms");
				assertTrue(Files.readString(output).contains("parent"));
			}
			finally
			{
				if (Files.exists(childPid))
					ProcessHandle.of(Long.parseLong(Files.readString(childPid).strip())).ifPresent(
						ProcessHandle::destroyForcibly);
			}
		}
	}

	/**
	 * Verifies a short runner query cannot hang when an exited launcher leaves a descendant holding its output
	 * descriptor.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void returnsRunnerOutputWithOpenChild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path childPid = root.resolve("child.pid");
			Path runner = write(root, "fixture-runner", """
				#!/bin/sh
				sleep 30 >&1 &
				printf '%%s' "$!" > "%s"
				printf 'owner=fixture\\nmodel=fixture-model\\neffort=medium\\n'
				""".formatted(childPid));
			requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
			long startedAt = System.nanoTime();
			try
			{
				String output = ProcessExecution.runnerOutput(runner, root, "--resolve-profile");
				long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
				assertTrue(elapsedMillis < 2_000,
					"A retained child descriptor delayed the runner query for " + elapsedMillis + " ms");
				requireThat(output, "output").contains("owner=fixture");
			}
			finally
			{
				if (Files.exists(childPid))
				{
					ProcessHandle.of(Long.parseLong(Files.readString(childPid).strip())).ifPresent(
						ProcessHandle::destroyForcibly);
				}
			}
		}
	}
}

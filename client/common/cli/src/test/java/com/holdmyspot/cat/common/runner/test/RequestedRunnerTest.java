package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

/**
 * Tests {@link RequestedRunner}.
 */
public final class RequestedRunnerTest
{
	/**
	 * Creates the test.
	 */
	public RequestedRunnerTest()
	{
	}

	/**
	 * Verifies that a requested session's runner is started with only the broker's options, so that the runner, which
	 * refuses {@code --artifact}, runs the artifact that holds it. The fake process launcher stands for the operating
	 * system: it records the command that would start the runner and starts nothing.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsRunnerWithoutArtifactOption() throws IOException
	{
		AtomicReference<List<String>> command = new AtomicReference<>();
		ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(() -> {},
			new ManualClock(Instant.EPOCH), builder ->
			{
				command.set(builder.command());
				throw new IOException("The fake launcher starts no process");
			});
		try (TestJvmScope build = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope(build.temporaryDirectory(), executables))
		{
			Path response = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "requested-runner-"));
			Path artifact = response.resolve("artifact");
			Path launcher = artifact.resolve("bin/harness-b-runner");
			RequestedRunner runner = RequestedRunner.resolved(jvm, "artifact=" + artifact + "\nrunner=" + launcher +
				"\nsource=installed\n", List.of("serve"));
			try
			{
				runner.start(List.of("--project", "project", "--model", "model-b", "--effort", "effort-b"), response,
					Map.of());
				throw new AssertionError("The fake launcher started a process");
			}
			catch (IOException _)
			{
				requireThat(command.get(), "command").isEqualTo(List.of(launcher.toString(), "serve", "--project",
					"project", "--model", "model-b", "--effort", "effort-b"));
			}
		}
	}
}

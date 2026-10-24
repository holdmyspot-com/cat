package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.HarnessProcessStarter;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
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
 * Tests the process requests that {@link HarnessProcessStarter} prepares without starting an external program.
 */
public final class HarnessProcessStarterTest
{
	/**
	 * Creates a test instance.
	 */
	public HarnessProcessStarterTest()
	{
	}

	/**
	 * Resolves a harness name against its child's PATH and preserves arguments, the project directory and only the
	 * supplied environment in the process request.
	 *
	 * @throws IOException if the lookup fixture cannot be written or the process request fails
	 */
	@Test
	public void preparesHarnessFromItsOwnPath() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeHarnessCli process = new FakeHarnessCli(_ -> {}))
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "starter-programs-"));
			Path programs = Files.createDirectory(root.resolve("programs")).toAbsolutePath();
			Path project = Files.createDirectory(root.resolve("project"));
			String filename = "greet";
			if (Platform.current().isWindows())
				filename += ".cmd";
			Path program = Files.createFile(programs.resolve(filename));
			requireThat(program.toFile().setExecutable(true, false), "executable").isTrue();
			Map<String, String> environment = Map.of("PATH", programs.toString(), "PROBE_MARKER", "only this value");
			AtomicReference<ProcessBuilder> request = new AtomicReference<>();
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(
				() -> {}, new ManualClock(Instant.EPOCH), builder ->
				{
					request.set(builder);
					return process;
				});

			try (Process result = HarnessProcessStarter.start(List.of("greet", "--literal", "two words", ""), project,
				environment, executables))
			{
				requireThat(request.get().command(), "command").isEqualTo(
					List.of(program.toString(), "--literal", "two words", ""));
				requireThat(request.get().directory().toPath(), "directory").isEqualTo(project);
				requireThat(request.get().environment().keySet(), "environmentKeys").isEqualTo(environment.keySet());
				requireThat(request.get().environment().get("PATH"), "path").isEqualTo(programs.toString());
				requireThat(request.get().environment().get("PROBE_MARKER"), "marker").isEqualTo("only this value");
				requireThat(result, "startedProcess").isEqualTo(process);
			}
		}
	}
}

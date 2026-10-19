package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessProcessStarter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessProcessStarter}.
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
	 * Verifies that a harness named without a path starts from a folder of the harness's own {@code PATH}, which the
	 * parent's lacks, and on Windows from a batch file, as npm installs Codex there.
	 *
	 * @throws IOException          if the test's files cannot be written or the harness cannot be started
	 * @throws InterruptedException if the thread is interrupted while the harness runs
	 */
	@Test
	public void startsHarnessFromItsOwnPath() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path programs = cleanup.add(Files.createTempDirectory(Path.of("target"), "starter-programs-")).
				toAbsolutePath();
			if (Platform.current().isWindows())
				Files.writeString(programs.resolve("greet.cmd"), "@echo hello\r\n");
			else
			{
				Path script = Files.writeString(programs.resolve("greet"), "#!/bin/sh\necho hello\n");
				requireThat(script.toFile().setExecutable(true, false), "executable").isTrue();
			}

			try (Process process = HarnessProcessStarter.start(List.of("greet"), programs,
				Map.of("PATH", programs.toString())))
			{
				process.getOutputStream().close();
				String output = new String(process.getInputStream().readAllBytes(), UTF_8).strip();
				requireThat(process.waitFor(), "status").isEqualTo(0);
				requireThat(output, "output").isEqualTo("hello");
			}
		}
	}
}

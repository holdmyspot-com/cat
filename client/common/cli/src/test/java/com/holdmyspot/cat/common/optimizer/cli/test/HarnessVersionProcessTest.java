package com.holdmyspot.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.cli.HarnessVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests the launcher that {@link HarnessVersion#process()} returns, which starts a real process.
 */
public final class HarnessVersionProcessTest
{
	/**
	 * Creates the test.
	 */
	public HarnessVersionProcessTest()
	{
	}

	/**
	 * Verifies that the launcher starts a harness by a path without an extension, which on Windows names the batch file
	 * that npm installs, such as {@code codex.cmd}.
	 *
	 * @throws IOException if the test's files cannot be written
	 */
	@Test
	public void startsHarnessByPathWithoutExtension() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path programs = cleanup.add(Files.createTempDirectory(Path.of("target"), "version-programs-")).
				toAbsolutePath();
			if (Platform.current().isWindows())
				Files.writeString(programs.resolve("harness.cmd"), "@echo harness 1.2.3\r\n");
			else
			{
				Path script = Files.writeString(programs.resolve("harness"), "#!/bin/sh\necho harness 1.2.3\n");
				requireThat(script.toFile().setExecutable(true, false), "executable").isTrue();
			}

			String version = HarnessVersion.read(programs.resolve("harness").toString(), HarnessVersion.process());
			requireThat(version, "version").isEqualTo("harness 1.2.3");
		}
	}
}

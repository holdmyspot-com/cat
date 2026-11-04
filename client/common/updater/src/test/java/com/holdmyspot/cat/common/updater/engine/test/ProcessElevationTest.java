package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.engine.ProcessElevation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

/**
 * Tests telling whether this process runs with administrative rights.
 */
public final class ProcessElevationTest
{
	/**
	 * Creates the test.
	 */
	public ProcessElevationTest()
	{
	}

	/**
	 * The check agrees with an independent one: on Windows, whether {@code net session}, which only an elevated
	 * process may run, succeeds; elsewhere, whether the process's user is {@code root}.
	 *
	 * @throws IOException          if the fixture cannot be created or the independent check cannot run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void agreesWithIndependentCheck() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(testDirectory("process-elevation-"));

			boolean elevated = ProcessElevation.isElevated(directory.resolve("probe"));

			requireThat(elevated, "elevated").isEqualTo(independentlyElevated());
			requireThat(directory.resolve("probe").toFile().list(), "probeEntries").isEmpty();
		}
	}

	/**
	 * Returns whether this process runs with administrative rights, by a check that does not share the production
	 * check's mechanism.
	 *
	 * @return {@code true} if the process runs with administrative rights
	 * @throws IOException          if the check cannot run
	 * @throws InterruptedException if the test is interrupted
	 */
	private static boolean independentlyElevated() throws IOException, InterruptedException
	{
		if (!Platform.current().isWindows())
			return ProcessHandle.current().info().user().orElseThrow().equals("root");
		ProcessBuilder builder = new ProcessBuilder("net", "session").
			redirectOutput(ProcessBuilder.Redirect.DISCARD).
			redirectError(ProcessBuilder.Redirect.DISCARD);
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			if (!process.waitFor(1, TimeUnit.MINUTES))
			{
				process.destroyForcibly();
				throw new IOException("net session did not end");
			}
			return process.exitValue() == 0;
		}
	}
}

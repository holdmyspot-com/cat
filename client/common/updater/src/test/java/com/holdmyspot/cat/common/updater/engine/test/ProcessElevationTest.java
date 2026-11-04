package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.updater.engine.ProcessElevation;
import com.holdmyspot.cat.common.updater.secrets.ProcessElevationSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.DataProvider;
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
	 * Returns how fake {@code id} programs behave and whether the POSIX check reports administrative rights for each.
	 * Each fake fails unless its single argument is {@code -u}, as the real program prints the effective user ID
	 * only for that argument.
	 *
	 * @return the POSIX script body, the Windows script body, and the expected result
	 */
	@DataProvider
	public static Object[][] idPrograms()
	{
		return new Object[][]
		{
			{"echo 0", "@echo 0", true},
			{"echo 1000", "@echo 1000", false},
			{"echo 0; exit 1", "@echo 0\r\n@exit /b 1", true},
			{"echo root", "@echo root", true},
			{"exec sleep 60", "@ping -n 60 127.0.0.1 >nul", true}
		};
	}

	/**
	 * The POSIX check reports administrative rights when the {@code id} program prints the user ID 0, and also when
	 * it fails, prints something else or does not end in time, so that a failed check never counts as unprivileged.
	 *
	 * @param posix    the fake program's body on POSIX systems
	 * @param windows  the fake program's body on Windows
	 * @param expected whether the check reports administrative rights
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "idPrograms")
	public void readsEffectiveUserId(String posix, String windows, boolean expected) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(testDirectory("process-elevation-id-"));
			Path id;
			if (Platform.current().isWindows())
			{
				id = Files.writeString(directory.resolve("id.cmd"), "@if not \"%~1\"==\"-u\" exit /b 2\r\n" +
					"@if not \"%~2\"==\"\" exit /b 2\r\n" + windows + "\r\n");
			}
			else
			{
				id = Files.writeString(directory.resolve("id"), "#!/bin/sh\n[ $# = 1 ] && [ \"$1\" = -u ] || exit 2\n" +
					posix + "\n");
				requireThat(id.toFile().setExecutable(true), "executable").isTrue();
			}

			boolean elevated = ProcessElevationSecrets.getProcessElevationAccess().runsAsRoot(id,
				Duration.ofMillis(500), directory.resolve("probe"));

			requireThat(elevated, "elevated").isEqualTo(expected);
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

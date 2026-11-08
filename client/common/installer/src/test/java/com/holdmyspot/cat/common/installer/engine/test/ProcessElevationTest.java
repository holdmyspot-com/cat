package com.holdmyspot.cat.common.installer.engine.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installer.engine.ProcessElevation;
import com.holdmyspot.cat.common.installer.secrets.ProcessElevationSecrets;
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
	 * @throws IOException          if the independent check cannot run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void agreesWithIndependentCheck() throws IOException, InterruptedException
	{
		boolean elevated = ProcessElevation.isElevated();

		requireThat(elevated, "elevated").isEqualTo(independentlyElevated());
	}

	/**
	 * Returns how fake {@code id} programs behave and whether the POSIX check reports administrative rights for each.
	 * Each fake fails unless its single argument is {@code -u}, as the real program prints the effective user ID
	 * only for that argument. A program that ends is given the installer's own 10 seconds, because a Windows
	 * runner can take longer than a second to start a batch file; only the program that does not end in time is
	 * given a shorter limit.
	 *
	 * @return the POSIX script body, the Windows script body, the time limit, and the expected result
	 */
	@DataProvider
	public static Object[][] idPrograms()
	{
		Duration ends = Duration.ofSeconds(10);
		return new Object[][]
		{
			{"echo 0", "@echo 0", ends, true},
			{"echo 1000", "@echo 1000", ends, false},
			{"echo 0; exit 1", "@echo 0\r\n@exit /b 1", ends, true},
			{"echo root", "@echo root", ends, true},
			{"exec sleep 60", "@ping -n 60 127.0.0.1 >nul", Duration.ofMillis(500), true}
		};
	}

	/**
	 * The POSIX check reports administrative rights when the {@code id} program prints the user ID 0, and also when
	 * it fails, prints something else or does not end in time, so that a failed check never counts as unprivileged.
	 *
	 * @param posix    the fake program's body on POSIX systems
	 * @param windows  the fake program's body on Windows
	 * @param limit    how long the check waits for the program
	 * @param expected whether the check reports administrative rights
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "idPrograms")
	public void readsEffectiveUserId(String posix, String windows, Duration limit, boolean expected)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(testDirectory("process-elevation-id-"));
			Path id = fakeId(directory, posix, windows);

			boolean elevated = ProcessElevationSecrets.getProcessElevationAccess().runsAsRoot(id, limit);

			requireThat(elevated, "elevated").isEqualTo(expected);
		}
	}

	/**
	 * The check stops every process that a program which does not end in time started, and waits until they have
	 * ended, so that no process is left running. The fake {@code id} program starts a child that outlives the check's
	 * limit, as a batch file does with the {@code ping} that it runs on Windows. The check still reports
	 * administrative rights, because a program that does not end in time never counts as unprivileged.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void stopsEveryProcessOfLateProgram() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(testDirectory("process-elevation-tree-"));
			Path childIdFile = directory.resolve("child-id.txt");
			Path id = fakeId(directory, "sleep 60 &\necho $! > '" + childIdFile + "'\nwait",
				"@ping -n 60 127.0.0.1 >nul");

			boolean elevated = ProcessElevationSecrets.getProcessElevationAccess().runsAsRoot(id,
				Duration.ofMillis(500));

			requireThat(elevated, "elevated").isTrue();
			if (!Platform.current().isWindows())
			{
				long childId = Long.parseLong(Files.readString(childIdFile).strip());
				boolean childRuns = ProcessHandle.of(childId).map(ProcessHandle::isAlive).orElse(false);
				requireThat(childRuns, "childRuns").isFalse();
			}
		}
	}

	/**
	 * The check reports within its limit the output of a program that has ended while a process that it started still
	 * holds the program's output open. The fake {@code id} program prints an unprivileged user ID and ends, leaving a
	 * background child that keeps the pipe of its output open for longer than the limit. A check that waited for the
	 * end of the output would hang until the child ended, and one that left a thread blocked on the pipe would leave
	 * it there.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void reportsOutputWhileLeftoverChildHoldsIt() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(testDirectory("process-elevation-leftover-"));
			Path childIdFile = directory.resolve("child-id.txt");
			Path id = fakeId(directory, "echo 1000\nsleep 60 &\necho $! > '" + childIdFile + "'",
				"@echo 1000\r\n@start /b ping -n 30 127.0.0.1 >nul");
			try
			{
				long start = System.nanoTime();
				boolean elevated = ProcessElevationSecrets.getProcessElevationAccess().runsAsRoot(id,
					Duration.ofSeconds(10));
				Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

				requireThat(elevated, "elevated").isFalse();
				// The child holds the output for at least 30 seconds, so a check that waited for it would take longer
				requireThat(elapsed, "elapsed").isLessThan(Duration.ofSeconds(10));
			}
			finally
			{
				if (!Platform.current().isWindows() && Files.exists(childIdFile))
				{
					long childId = Long.parseLong(Files.readString(childIdFile).strip());
					ProcessHandle.of(childId).ifPresent(ProcessHandle::destroyForcibly);
				}
			}
		}
	}

	/**
	 * The check counts a program that prints more than it accepts as one that failed, so that output which hides the
	 * user ID after a long stretch of blanks never counts as unprivileged. The fake {@code id} program prints
	 * {@code 1000} after more blanks than the check accepts, which a check that read all of it would report as an
	 * unprivileged user ID.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsTooLongOutput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(testDirectory("process-elevation-long-"));
			Path printed = Files.writeString(directory.resolve("printed.txt"), " ".repeat(100_000) + "1000\n",
				ISO_8859_1);
			Path id = fakeId(directory, "exec cat '" + printed + "'", "@type \"" + printed + "\"");

			boolean elevated = ProcessElevationSecrets.getProcessElevationAccess().runsAsRoot(id,
				Duration.ofSeconds(10));

			requireThat(elevated, "elevated").isTrue();
		}
	}

	/**
	 * Writes a fake {@code id} program that fails unless its single argument is {@code -u}, as the real program
	 * prints the effective user ID only for that argument, and otherwise runs a body.
	 *
	 * @param directory the directory that receives the program
	 * @param posix     the program's body on POSIX systems
	 * @param windows   the program's body on Windows
	 * @return the program
	 * @throws IOException if the program cannot be written
	 */
	private static Path fakeId(Path directory, String posix, String windows) throws IOException
	{
		if (Platform.current().isWindows())
		{
			return Files.writeString(directory.resolve("id.cmd"), "@if not \"%~1\"==\"-u\" exit /b 2\r\n" +
				"@if not \"%~2\"==\"\" exit /b 2\r\n" + windows + "\r\n");
		}
		Path id = Files.writeString(directory.resolve("id"), "#!/bin/sh\n[ $# = 1 ] && [ \"$1\" = -u ] || exit 2\n" +
			posix + "\n");
		requireThat(id.toFile().setExecutable(true), "executable").isTrue();
		return id;
	}

	/**
	 * Returns lists of an access token's groups, as {@code whoami /groups /fo csv /nh} prints them, and whether the
	 * Windows check reports administrative rights for each. Each line names a group, its type, its SID and its
	 * attributes; the token's integrity level is the group of type Label whose SID is a mandatory label. The program's
	 * options and output formats are documented at
	 * <a href="https://learn.microsoft.com/windows-server/administration/windows-commands/whoami">whoami</a>, and the
	 * mandatory label SIDs, {@code S-1-16-} followed by the integrity level's relative ID, at
	 * <a href="https://learn.microsoft.com/windows/win32/secauthz/well-known-sids">Well-known SIDs</a>: medium
	 * integrity is 0x2000 ({@code S-1-16-8192}), high 0x3000 ({@code S-1-16-12288}), system 0x4000
	 * ({@code S-1-16-16384}) and protected process 0x5000 ({@code S-1-16-20480}). The group names are localized, so
	 * only the SIDs identify the levels.
	 *
	 * @return the groups, and whether the check reports administrative rights
	 */
	@DataProvider
	public static Object[][] tokenGroups()
	{
		String everyone = "\"Everyone\",\"Well-known group\",\"S-1-1-0\"," +
			"\"Mandatory group, Enabled by default, Enabled group\"\r\n";
		String users = "\"BUILTIN\\Users\",\"Alias\",\"S-1-5-32-545\"," +
			"\"Mandatory group, Enabled by default, Enabled group\"\r\n";
		// An administrator's token without elevation holds the Administrators group for deny only
		String filteredAdministrators = "\"BUILTIN\\Administrators\",\"Alias\",\"S-1-5-32-544\"," +
			"\"Group used for deny only\"\r\n";
		String administrators = "\"BUILTIN\\Administrators\",\"Alias\",\"S-1-5-32-544\"," +
			"\"Mandatory group, Enabled by default, Enabled group, Group owner\"\r\n";
		return new Object[][]
		{
			{"standard user", everyone + users +
				"\"Mandatory Label\\Medium Mandatory Level\",\"Label\",\"S-1-16-8192\",\"\"\r\n", false},
			{"administrator without elevation", everyone + filteredAdministrators + users +
				"\"Mandatory Label\\Medium Mandatory Level\",\"Label\",\"S-1-16-8192\",\"\"\r\n", false},
			{"elevated administrator", everyone + administrators + users +
				"\"Mandatory Label\\High Mandatory Level\",\"Label\",\"S-1-16-12288\",\"\"\r\n", true},
			{"elevated administrator on a German system", everyone + administrators + users +
				"\"Verbindliche Bezeichnung\\Hohe Verbindlichkeitsstufe\",\"Bezeichnung\",\"S-1-16-12288\",\"\"\r\n",
				true},
			{"local system", everyone + administrators +
				"\"Mandatory Label\\System Mandatory Level\",\"Label\",\"S-1-16-16384\",\"\"\r\n", true},
			{"protected process", everyone + administrators +
				"\"Mandatory Label\\Protected Process Mandatory Level\",\"Label\",\"S-1-16-20480\",\"\"\r\n", true},
			// A list without an integrity level is not an answer, and a failed check counts as administrative rights
			{"list without an integrity level", everyone + administrators + users, true},
			{"empty list", "", true}
		};
	}

	/**
	 * The Windows check reports administrative rights when the token's groups name an integrity level of high or
	 * above, whatever language names the groups and whether or not the token holds the Administrators group, and
	 * also when they name no integrity level, so that a failed check never counts as unprivileged.
	 *
	 * @param token    what the token stands for
	 * @param groups   the token's groups, as {@code whoami /groups /fo csv /nh} prints them
	 * @param expected whether the check reports administrative rights
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "tokenGroups")
	public void readsTokenIntegrityLevel(String token, String groups, boolean expected) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(testDirectory("process-elevation-groups-"));
			Path whoami = fakeWhoami(directory, groups);

			boolean elevated = ProcessElevationSecrets.getProcessElevationAccess().holdsElevatedToken(whoami,
				Duration.ofSeconds(30));

			requireThat(elevated, "elevated").withContext(token, "token").isEqualTo(expected);
		}
	}

	/**
	 * Writes a fake {@code whoami} program that prints a token's groups when it receives the arguments
	 * {@code /groups /fo csv /nh}, and fails otherwise.
	 *
	 * @param directory the directory that receives the program
	 * @param groups    what the program prints
	 * @return the program
	 * @throws IOException if the program cannot be written
	 */
	private static Path fakeWhoami(Path directory, String groups) throws IOException
	{
		Path printed = Files.writeString(directory.resolve("groups.csv"), groups, ISO_8859_1);
		if (Platform.current().isWindows())
		{
			return Files.writeString(directory.resolve("whoami.cmd"), "@if not \"%*\"==\"/groups /fo csv /nh\" " +
				"exit /b 2\r\n@type \"" + printed + "\"\r\n");
		}
		Path whoami = Files.writeString(directory.resolve("whoami"), "#!/bin/sh\n" +
			"[ \"$*\" = '/groups /fo csv /nh' ] || exit 2\nexec cat '" + printed + "'\n");
		requireThat(whoami.toFile().setExecutable(true), "executable").isTrue();
		return whoami;
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

package com.holdmyspot.cat.common.updater.engine.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.testDirectory;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

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
	 * Returns lists of an access token's groups, as {@code whoami /groups /fo csv /nh} prints them, and whether the
	 * Windows check reports administrative rights for each. Each line names a group, its type, its SID and its
	 * attributes; the token's integrity level is the group of type Label whose SID is a mandatory label. The program's
	 * options and output formats are documented at
	 * <a href="https://learn.microsoft.com/windows-server/administration/windows-commands/whoami">whoami</a>, and the
	 * mandatory label SIDs, {@code S-1-16-} followed by the integrity level's relative ID, at
	 * <a href="https://learn.microsoft.com/windows/win32/secauthz/well-known-sids">Well-known SIDs</a>: medium
	 * integrity is 0x2000 ({@code S-1-16-8192}), high 0x3000 ({@code S-1-16-12288}) and system 0x4000
	 * ({@code S-1-16-16384}). The group names are localized, so only the SIDs identify the levels.
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
				"\"Mandatory Label\\System Mandatory Level\",\"Label\",\"S-1-16-16384\",\"\"\r\n", true}
		};
	}

	/**
	 * The Windows check reports administrative rights exactly when the token's groups name the high or system
	 * integrity level, whatever language names the groups and whether or not the token holds the Administrators group.
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
				Duration.ofSeconds(30), directory.resolve("probe"));

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

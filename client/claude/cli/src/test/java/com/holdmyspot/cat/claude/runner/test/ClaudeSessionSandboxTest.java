package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeSandboxSettings.Setting;
import com.holdmyspot.cat.claude.runner.ClaudeSessionSandbox;
import com.holdmyspot.cat.claude.runner.ClaudeSessionSandbox.ProbeResult;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Verifies the decision to refuse a launch whose Claude Code sandbox would not start: for each cause, an input that has
 * it is refused with a message that names the check, the value found and the fix, and the same input without it
 * passes. No test starts a harness or a model, and none depends on the host's programs or temporary folder.
 */
public final class ClaudeSessionSandboxTest
{
	/**
	 * The most bytes that a temporary folder may have on Linux.
	 */
	private static final int LINUX_LIMIT = 72;
	/**
	 * The most bytes that a temporary folder may have on macOS.
	 */
	private static final int MACOS_LIMIT = 68;
	private static final ProbeResult PROBE_PASSED = new ProbeResult(0, "");

	/**
	 * Creates the test.
	 */
	public ClaudeSessionSandboxTest()
	{
	}

	/**
	 * Returns the platforms that Claude Code's sandbox starts on, one per operating system and architecture.
	 *
	 * @return the platforms
	 */
	@DataProvider(name = "linuxPlatforms")
	public Object[][] linuxPlatforms()
	{
		return new Object[][]{{Platform.LINUX_X64}, {Platform.LINUX_ARM64}};
	}

	/**
	 * Returns the macOS platforms.
	 *
	 * @return the platforms
	 */
	@DataProvider(name = "macosPlatforms")
	public Object[][] macosPlatforms()
	{
		return new Object[][]{{Platform.MACOS_X64}, {Platform.MACOS_ARM64}};
	}

	/**
	 * Verifies that an input with every cause absent passes on each platform that has a sandbox, so that no check
	 * refuses a machine that Claude Code's sandbox starts on.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void passesWhenNoCauseIsPresent() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			for (Platform platform : List.of(Platform.LINUX_X64, Platform.LINUX_ARM64, Platform.MACOS_X64,
				Platform.MACOS_ARM64))
			{
				requireThat(programs.refusal(platform), platform.name()).isEmpty();
			}
		}
	}

	/**
	 * Verifies that a temporary folder of 73 bytes is refused on Linux, naming the folder, its length and the fix, and
	 * one of 72 bytes passes.
	 *
	 * @param platform the platform
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "linuxPlatforms")
	public void refusesLongTemporaryFolderOnLinux(Platform platform) throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			Path tooLong = folderOfLength(LINUX_LIMIT + 1);

			String refusal = programs.withFolder(platform, tooLong, null, null).orElseThrow();

			requireThat(refusal, "refusal").contains("temporary folder").contains(tooLong.toString()).
				contains("73 bytes").contains("72").contains("shorter");
			requireThat(programs.withFolder(platform, folderOfLength(LINUX_LIMIT), null, null),
				"atLimit").isEmpty();
		}
	}

	/**
	 * Verifies that a temporary folder of 69 bytes is refused on macOS, naming the folder, its length and the fix, and
	 * one of 68 bytes passes.
	 *
	 * @param platform the platform
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "macosPlatforms")
	public void refusesLongTemporaryFolderOnMacos(Platform platform) throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			Path tooLong = folderOfLength(MACOS_LIMIT + 1);

			String refusal = programs.withFolder(platform, tooLong, null, null).orElseThrow();

			requireThat(refusal, "refusal").contains("temporary folder").contains(tooLong.toString()).
				contains("69 bytes").contains("68").contains("shorter");
			requireThat(programs.withFolder(platform, folderOfLength(MACOS_LIMIT), null, null),
				"atLimit").isEmpty();
		}
	}

	/**
	 * Verifies that a {@code TMPDIR} of 73 bytes that a settings file's {@code env} sets is refused on Linux whichever
	 * file sets it, naming the file, the value and the fix, and that a value of 72 bytes passes.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesLongTmpdirSettingOnLinux() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			for (String name : List.of("project/.claude/settings.json", "managed/managed-settings.json"))
			{
				Path file = Path.of(name);
				Setting tooLong = new Setting(file, folderOfLength(LINUX_LIMIT + 1).toString());
				Setting atLimit = new Setting(file, folderOfLength(LINUX_LIMIT).toString());

				String refusal = programs.withFolder(Platform.LINUX_X64, Path.of("/tmp"), tooLong, null).orElseThrow();

				requireThat(refusal, "refusal").contains("env.TMPDIR").contains(file.toString()).contains(tooLong.value()).
					contains("73 bytes").contains("72").contains("Remove");
				requireThat(programs.withFolder(Platform.LINUX_X64, Path.of("/tmp"), atLimit, null),
					"atLimit").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a {@code TMPDIR} of 69 bytes that a settings file's {@code env} sets is refused on macOS and one of
	 * 68 bytes passes.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesLongTmpdirSettingOnMacos() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			Path file = Path.of("project/.claude/settings.local.json");
			Setting tooLong = new Setting(file, folderOfLength(MACOS_LIMIT + 1).toString());

			String refusal = programs.withFolder(Platform.MACOS_ARM64, Path.of("/tmp"), tooLong, null).orElseThrow();

			requireThat(refusal, "refusal").contains("env.TMPDIR").contains(file.toString()).contains("69 bytes");
			requireThat(programs.withFolder(Platform.MACOS_ARM64, Path.of("/tmp"),
				new Setting(file, folderOfLength(MACOS_LIMIT).toString()), null), "atLimit").isEmpty();
		}
	}

	/**
	 * Verifies that the length of a {@code TMPDIR} setting counts the bytes of its UTF-8 form, as the socket path's limit
	 * does, not its characters.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void countsBytesOfTemporaryFolderSetting() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			// The leading separator and 36 two-byte characters are 73 bytes, in 37 characters
			Setting tooLong = new Setting(Path.of("settings.json"), "/" + "\u00e9".repeat(36));
			Setting atLimit = new Setting(Path.of("settings.json"), "/" + "\u00e9".repeat(35) + "t");

			requireThat(programs.withFolder(Platform.LINUX_X64, Path.of("/tmp"), tooLong, null).orElseThrow(), "refusal").
				contains("73 bytes");
			requireThat(programs.withFolder(Platform.LINUX_X64, Path.of("/tmp"), atLimit, null), "atLimit").isEmpty();
		}
	}

	/**
	 * Verifies that a {@code PATH} without {@code bwrap} is refused on Linux, naming the {@code PATH} that was searched
	 * and the package that fixes it, and that a {@code PATH} with an executable one passes.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesPathWithoutBwrap() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			String path = programs.emptyDirectory + File.pathSeparator + programs.socatDirectory;

			String refusal = programs.withPath(Platform.LINUX_X64, path, null).orElseThrow();

			requireThat(refusal, "refusal").contains("bwrap").contains(path).contains("executable").
				contains("bubblewrap package");
			requireThat(programs.refusal(Platform.LINUX_X64), "withBwrap").isEmpty();
		}
	}

	/**
	 * Verifies that a {@code sandbox.bwrapPath} that names a missing file, a directory or a file that is not
	 * executable is refused, naming the file that sets it and the value, although the {@code PATH} holds {@code bwrap},
	 * and that one that names an executable file passes even if the {@code PATH} does not hold {@code bwrap}.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesUnusableBwrapPathSetting() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			Path settings = programs.root.resolve("project/.claude/settings.json");
			List<Path> unusable = new ArrayList<>();
			unusable.add(programs.root.resolve("missing-bwrap"));
			unusable.add(programs.emptyDirectory);
			if (PortableFiles.hasModes(programs.root))
			{
				unusable.add(Files.createFile(programs.root.resolve("not-executable-bwrap"),
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"))));
			}
			for (Path file : unusable)
			{
				String refusal = programs.withPath(Platform.LINUX_X64, programs.path(),
					new Setting(settings, file.toString())).orElseThrow();

				requireThat(refusal, file.getFileName().toString()).contains("sandbox.bwrapPath").
					contains(settings.toString()).contains(file.toString()).contains("not an executable file").
					contains("Remove");
			}
			String withoutBwrap = programs.emptyDirectory + File.pathSeparator + programs.socatDirectory;

			requireThat(programs.withPath(Platform.LINUX_X64, withoutBwrap,
				new Setting(settings, programs.bwrapDirectory.resolve("bwrap").toString())), "executableSetting").isEmpty();
		}
	}

	/**
	 * Verifies that a {@code PATH} without {@code socat} is refused on Linux with the message that names the check,
	 * the {@code PATH} and the fix, and that a {@code PATH} with an executable {@code socat} passes.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesPathWithoutSocat() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			String path = programs.emptyDirectory + File.pathSeparator + programs.bwrapDirectory;

			String refusal = programs.withPath(Platform.LINUX_ARM64, path, null).orElseThrow();

			requireThat(refusal, "refusal").isEqualTo("CAT does not start the session: Claude Code's sandbox needs " +
				"socat, but none of the PATH directories " + path + " holds an executable socat file. Install your " +
				"distribution's socat package, then start the session again.");
			requireThat(programs.refusal(Platform.LINUX_ARM64), "withSocat").isEmpty();
		}
	}

	/**
	 * Verifies that a probe of bubblewrap that exits 1 with its user-namespace error is refused on Linux, quoting the
	 * exit status and the error and naming the AppArmor fix, as is a probe that never finished, and that a probe that
	 * exits 0 passes.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesFailedNamespaceProbe() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			String error = "bwrap: setting up uid map: Permission denied";

			String refusal = programs.withProbe(Platform.LINUX_X64, new ProbeResult(1, error)).orElseThrow();

			requireThat(refusal, "refusal").contains("bwrap --unshare-user --dev-bind / / true").contains("exited 1").
				contains(error).contains("user namespaces").contains("AppArmor");
			String unfinished = programs.withProbe(Platform.LINUX_X64,
				new ProbeResult(ProbeResult.NOT_FINISHED, "Timed out after 30 seconds")).orElseThrow();
			requireThat(unfinished, "unfinished").contains("did not finish").contains("Timed out after 30 seconds").
				contains("AppArmor");
			requireThat(programs.withProbe(Platform.LINUX_X64, PROBE_PASSED), "passed").
				isEmpty();
		}
	}

	/**
	 * Verifies that a temporary folder in which no file could be created, or that does not exist, is refused on Linux,
	 * naming the folder, the failure and the fix, and that one in which a file could be created passes.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesTemporaryFolderWithoutFiles() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			for (String failure : List.of("No such file or directory", "Permission denied"))
			{
				String refusal = programs.withFailure(Platform.LINUX_X64, new IOException(failure)).orElseThrow();

				requireThat(refusal, "refusal").contains("temporary folder").contains(programs.folder.toString()).
					contains(failure).contains("create a file").contains("Create");
			}
			requireThat(programs.refusal(Platform.LINUX_X64), "passes").isEmpty();
		}
	}

	/**
	 * Verifies that a missing {@code sandbox-exec} is refused on macOS, naming the file and that the system lacks it, and
	 * that an existing one passes, and that macOS needs neither {@code bwrap} nor {@code socat}.
	 *
	 * @param platform the platform
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "macosPlatforms")
	public void refusesMissingSandboxExecutable(Platform platform) throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			Path missing = programs.root.resolve("usr/bin/sandbox-exec");

			String refusal = programs.withExecutable(platform, programs.emptyDirectory.toString(), missing,
				PROBE_PASSED).orElseThrow();

			requireThat(refusal, "refusal").contains(missing.toString()).contains("sandbox-exec").contains("missing").
				contains("macOS");
			requireThat(programs.withExecutable(platform, programs.emptyDirectory.toString(),
				programs.macosExecutable, PROBE_PASSED), "present").isEmpty();
		}
	}

	/**
	 * Verifies that a probe of {@code sandbox-exec} that exits 1 is refused on macOS, quoting the exit status and its
	 * output and naming the fix.
	 *
	 * @param platform the platform
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "macosPlatforms")
	public void refusesFailedSandboxExecutableProbe(Platform platform) throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			String error = "sandbox-exec: sandbox_apply: Operation not permitted";

			String refusal = programs.withExecutable(platform, programs.path(), programs.macosExecutable,
				new ProbeResult(1, error)).orElseThrow();

			requireThat(refusal, "refusal").contains("(version 1)(allow default)").contains("exited 1").contains(error).
				contains("Seatbelt");
		}
	}

	/**
	 * Verifies that native Windows is refused whatever else holds, as it was before the checks.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesNativeWindows() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			for (Platform platform : List.of(Platform.WINDOWS_X64, Platform.WINDOWS_ARM64))
			{
				requireThat(programs.refusal(platform), platform.name()).
					isEqualTo(ClaudeSessionSandbox.refusal(platform));
				requireThat(programs.refusal(platform).orElseThrow(), platform.name()).contains("native Windows");
			}
		}
	}

	/**
	 * Verifies that the probe is a command that tries to start a sandbox as Claude Code's does, so that a failure
	 * of the command means that the sandbox would not start.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void probeCommandStartsSandbox() throws IOException
	{
		// Requirement: R-I16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Programs programs = new Programs(cleanup);
			Path bwrap = programs.bwrapDirectory.resolve("bwrap");
			Path other = Files.createDirectory(programs.root.resolve("other"));
			Path custom = Files.createFile(other.resolve("custom-bwrap"));
			if (PortableFiles.hasModes(other))
				Files.setPosixFilePermissions(custom, PosixFilePermissions.fromString("rwxr-xr-x"));

			requireThat(ClaudeSessionSandbox.probeCommand(Platform.LINUX_X64, programs.path(), null,
				programs.macosExecutable), "onPath").isEqualTo(Optional.of(List.of(bwrap.toString(), "--unshare-user",
				"--dev-bind", "/", "/", "true")));
			requireThat(ClaudeSessionSandbox.probeCommand(Platform.LINUX_X64, programs.path(),
				new Setting(Path.of("settings.json"), custom.toString()), programs.macosExecutable), "named").
				isEqualTo(Optional.of(List.of(custom.toString(), "--unshare-user", "--dev-bind", "/", "/", "true")));
			requireThat(ClaudeSessionSandbox.probeCommand(Platform.LINUX_X64, programs.emptyDirectory.toString(), null,
				programs.macosExecutable), "withoutBwrap").isEmpty();
			requireThat(ClaudeSessionSandbox.probeCommand(Platform.MACOS_ARM64, programs.path(), null,
				programs.macosExecutable), "macos").isEqualTo(Optional.of(List.of(programs.macosExecutable.toString(),
				"-p", "(version 1)(allow default)", "/usr/bin/true")));
			requireThat(ClaudeSessionSandbox.probeCommand(Platform.MACOS_ARM64, programs.path(), null,
				programs.root.resolve("missing")), "withoutSandboxExecutable").isEmpty();
			requireThat(ClaudeSessionSandbox.probeCommand(Platform.WINDOWS_X64, programs.path(), null,
				programs.macosExecutable), "windows").isEmpty();
		}
	}

	/**
	 * Verifies that the temporary folder is the first of {@code TMPDIR}, {@code TMP} and {@code TEMP} that the Claude
	 * process's environment sets, as Claude Code reads it, else {@code /tmp}, so that a process environment without them
	 * has a temporary folder whose length no host decides.
	 */
	@Test
	public void temporaryFolderFollowsEnvironment()
	{
		// Requirement: R-I16
		Path tmpdir = folderOfLength(20);
		Path tmp = Path.of("/second");
		Path temp = Path.of("/third");

		requireThat(ClaudeSessionSandbox.temporaryFolder(Map.of()), "none").isEqualTo(Path.of("/tmp"));
		requireThat(ClaudeSessionSandbox.temporaryFolder(Map.of("PATH", "/bin", "HOME", "/home/user")), "unrelated").
			isEqualTo(Path.of("/tmp"));
		requireThat(ClaudeSessionSandbox.temporaryFolder(Map.of("TMPDIR", tmpdir.toString(), "TMP", tmp.toString(),
			"TEMP", temp.toString())), "all").isEqualTo(tmpdir);
		requireThat(ClaudeSessionSandbox.temporaryFolder(Map.of("TMP", tmp.toString(), "TEMP", temp.toString())),
			"tmp").isEqualTo(tmp);
		requireThat(ClaudeSessionSandbox.temporaryFolder(Map.of("TEMP", temp.toString())), "temp").isEqualTo(temp);
		requireThat(ClaudeSessionSandbox.temporaryFolder(Map.of("TMPDIR", "", "TMP", " ", "TEMP", temp.toString())),
			"blank").isEqualTo(temp);
	}

	/**
	 * Returns a folder whose path is a number of bytes long, whatever the host's own folders are.
	 *
	 * @param length the length in bytes
	 * @return the folder
	 */
	private static Path folderOfLength(int length)
	{
		return Path.of("/" + "t".repeat(length - 1));
	}

	/**
	 * Test-owned programs and folders, with which the decision passes on every platform until a test changes one input.
	 */
	private static final class Programs
	{
		private final Path root;
		private final Path emptyDirectory;
		private final Path bwrapDirectory;
		private final Path socatDirectory;
		private final Path macosExecutable;
		private final Path folder;

		/**
		 * Creates the fixture.
		 *
		 * @param cleanup removes the fixture's directories
		 * @throws IOException if a file cannot be created
		 */
		Programs(TemporaryDirectories cleanup) throws IOException
		{
			root = cleanup.add(Files.createTempDirectory("cat-sandbox-decision-"));
			emptyDirectory = Files.createDirectory(root.resolve("empty"));
			bwrapDirectory = Files.createDirectory(root.resolve("bwrap-bin"));
			socatDirectory = Files.createDirectory(root.resolve("socat-bin"));
			Path macos = Files.createDirectory(root.resolve("macos"));
			// Only the folder's length is decided; a short path keeps it under the limit whatever the host temp path is
			folder = folderOfLength(10);
			executable(bwrapDirectory.resolve("bwrap"));
			executable(socatDirectory.resolve("socat"));
			macosExecutable = executable(macos.resolve("sandbox-exec"));
		}

		/**
		 * Creates an executable file.
		 *
		 * @param file the file
		 * @return the file
		 * @throws IOException if the file cannot be created
		 */
		private static Path executable(Path file) throws IOException
		{
			Files.createFile(file);
			if (PortableFiles.hasModes(file.getParent()))
				Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
			return file;
		}

		/**
		 * Returns a {@code PATH} that holds {@code bwrap} and {@code socat} after a directory that holds neither.
		 *
		 * @return the {@code PATH}
		 */
		String path()
		{
			return String.join(File.pathSeparator, emptyDirectory.toString(), bwrapDirectory.toString(),
				socatDirectory.toString());
		}

		/**
		 * Decides with every cause absent.
		 *
		 * @param platform the platform
		 * @return the refusal, or empty
		 */
		Optional<String> refusal(Platform platform)
		{
			return decide(platform, path(), folder, null, null, macosExecutable, null, PROBE_PASSED);
		}

		/**
		 * Decides with a temporary folder and the settings that name one and a bubblewrap.
		 *
		 * @param platform        the platform
		 * @param temporaryFolder the temporary folder
		 * @param tmpdirSetting   the {@code env.TMPDIR} setting, or {@code null}
		 * @param bwrapPath       the {@code sandbox.bwrapPath} setting, or {@code null}
		 * @return the refusal, or empty
		 */
		Optional<String> withFolder(Platform platform, Path temporaryFolder, Setting tmpdirSetting, Setting bwrapPath)
		{
			return decide(platform, path(), temporaryFolder, tmpdirSetting, bwrapPath, macosExecutable, null,
				PROBE_PASSED);
		}

		/**
		 * Decides with a {@code PATH} and a {@code sandbox.bwrapPath} setting.
		 *
		 * @param platform  the platform
		 * @param path      the {@code PATH}
		 * @param bwrapPath the {@code sandbox.bwrapPath} setting, or {@code null}
		 * @return the refusal, or empty
		 */
		Optional<String> withPath(Platform platform, String path, Setting bwrapPath)
		{
			return decide(platform, path, folder, null, bwrapPath, macosExecutable, null, PROBE_PASSED);
		}

		/**
		 * Decides with a probe's result.
		 *
		 * @param platform the platform
		 * @param probe    the probe's result
		 * @return the refusal, or empty
		 */
		Optional<String> withProbe(Platform platform, ProbeResult probe)
		{
			return decide(platform, path(), folder, null, null, macosExecutable, null, probe);
		}

		/**
		 * Decides with a failure to create a file in the temporary folder.
		 *
		 * @param platform the platform
		 * @param failure  why no file could be created
		 * @return the refusal, or empty
		 */
		Optional<String> withFailure(Platform platform, IOException failure)
		{
			return decide(platform, path(), folder, null, null, macosExecutable, failure, PROBE_PASSED);
		}

		/**
		 * Decides with a macOS sandbox program, a {@code PATH} and a probe's result.
		 *
		 * @param platform          the platform
		 * @param path              the {@code PATH}
		 * @param sandboxExecutable the macOS program that applies a Seatbelt profile
		 * @param probe             the probe's result
		 * @return the refusal, or empty
		 */
		Optional<String> withExecutable(Platform platform, String path, Path sandboxExecutable, ProbeResult probe)
		{
			return decide(platform, path, folder, null, null, sandboxExecutable, null, probe);
		}

		/**
		 * Decides.
		 *
		 * @param platform             the platform
		 * @param path                 the {@code PATH}
		 * @param temporaryFolder      the temporary folder
		 * @param tmpdirSetting        the {@code env.TMPDIR} setting, or {@code null}
		 * @param bwrapPath            the {@code sandbox.bwrapPath} setting, or {@code null}
		 * @param sandboxExecutable    the macOS program that applies a Seatbelt profile
		 * @param temporaryFileFailure why no file could be created in {@code temporaryFolder}, or {@code null}
		 * @param probe                the probe's result
		 * @return the refusal, or empty
		 */
		private static Optional<String> decide(Platform platform, String path, Path temporaryFolder,
			Setting tmpdirSetting, Setting bwrapPath, Path sandboxExecutable, IOException temporaryFileFailure,
			ProbeResult probe)
		{
			return ClaudeSessionSandbox.startRefusal(platform, path, temporaryFolder, tmpdirSetting, bwrapPath,
				sandboxExecutable, temporaryFileFailure, probe);
		}
	}
}

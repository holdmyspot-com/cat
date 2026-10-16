package io.github.cowwoc.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link Platform}.
 */
public final class PlatformTest
{
	/**
	 * Creates a test instance.
	 */
	public PlatformTest()
	{
	}

	/**
	 * Verifies aliases for every supported platform.
	 */
	@Test
	public void detectsSupportedAliases()
	{
		requireThat(Platform.detect("Linux", "amd64"), "linuxAmd64Platform").isEqualTo(Platform.LINUX_X64);
		requireThat(Platform.detect("linux", "x86_64"), "linuxX8664Platform").isEqualTo(Platform.LINUX_X64);
		requireThat(Platform.detect("LINUX", "aarch64"), "linuxAarch64Platform").isEqualTo(Platform.LINUX_ARM64);
		requireThat(Platform.detect("Linux", "arm64"), "linuxArm64Platform").isEqualTo(Platform.LINUX_ARM64);
		requireThat(Platform.detect("Mac OS X", "x86_64"), "macosX8664Platform").isEqualTo(Platform.MACOS_X64);
		requireThat(Platform.detect("Darwin", "amd64"), "darwinAmd64Platform").isEqualTo(Platform.MACOS_X64);
		requireThat(Platform.detect("Mac OS X", "aarch64"), "macosAarch64Platform").isEqualTo(Platform.MACOS_ARM64);
		requireThat(Platform.detect("Darwin", "arm64"), "darwinArm64Platform").isEqualTo(Platform.MACOS_ARM64);
		requireThat(Platform.detect("Windows 11", "amd64"), "windowsAmd64Platform").isEqualTo(Platform.WINDOWS_X64);
		requireThat(Platform.detect("Windows Server 2025", "aarch64"), "windowsAarch64Platform").
			isEqualTo(Platform.WINDOWS_ARM64);
	}

	/**
	 * Verifies that only the Windows platforms report that they are Windows.
	 */
	@Test
	public void identifiesWindows()
	{
		for (Platform platform : Platform.values())
		{
			boolean windows = platform == Platform.WINDOWS_X64 || platform == Platform.WINDOWS_ARM64;
			requireThat(platform.isWindows(), platform.id()).isEqualTo(windows);
		}
	}

	/**
	 * Verifies that Windows names the user's home directory in {@code USERPROFILE} and the other platforms in
	 * {@code HOME}.
	 */
	@Test
	public void namesHomeVariable()
	{
		for (Platform platform : Platform.values())
		{
			String expected;
			if (platform.isWindows())
				expected = "USERPROFILE";
			else
				expected = "HOME";
			requireThat(platform.homeVariable(), platform.id()).isEqualTo(expected);
		}
	}

	/**
	 * Verifies that each platform reads the user's home directory from its own home variable, and rejects an
	 * environment that sets only the other platforms' variable with a message that names the missing one.
	 *
	 * @throws IOException if the home directory cannot be read
	 */
	@Test
	public void readsHomeDirectory() throws IOException
	{
		for (Platform platform : Platform.values())
		{
			String variable = platform.homeVariable();
			String other;
			if (platform.isWindows())
				other = "HOME";
			else
				other = "USERPROFILE";
			Path home = Path.of("user-home");
			requireThat(platform.homeDirectory(Map.of(variable, home.toString(), other, "elsewhere")), platform.id()).
				isEqualTo(home);
			try
			{
				platform.homeDirectory(Map.of(other, "elsewhere"));
				throw new AssertionError("Expected a failure for the missing " + variable + " on " + platform.id());
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), platform.id()).isEqualTo(variable + " is not set");
			}
		}
	}

	/**
	 * Verifies that a Windows environment finds a variable by any case of its name, as Windows does, because Windows
	 * names the program search path {@code Path} while programs look up {@code PATH}.
	 */
	@Test
	public void windowsEnvironmentIgnoresNameCase()
	{
		Map<String, String> environment = Platform.WINDOWS_X64.environment(Map.of("Path", "C:\\Windows"));
		requireThat(environment.get("PATH"), "PATH").isEqualTo("C:\\Windows");
		requireThat(environment.get("Path"), "Path").isEqualTo("C:\\Windows");
	}

	/**
	 * Verifies that a POSIX environment matches a variable only by its exact name, as POSIX systems do.
	 */
	@Test
	public void posixEnvironmentMatchesExactName()
	{
		Map<String, String> environment = Platform.LINUX_X64.environment(Map.of("Path", "/usr/bin"));
		requireThat(environment.get("PATH"), "PATH").isNull();
		requireThat(environment.get("Path"), "Path").isEqualTo("/usr/bin");
	}

	/**
	 * Verifies that a Windows program search tries each extension that {@code PATHEXT} lists in the folders of
	 * {@code Path}, as {@code cmd.exe} does, and skips an extensionless file, such as the POSIX script that npm installs
	 * beside a program's batch file, and a script that Windows cannot start without a program.
	 *
	 * @throws IOException if the test's files cannot be written
	 */
	@Test
	public void windowsFindsProgramByExtension() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path scripts = cleanup.add(Files.createTempDirectory("platform-scripts-"));
			Path programs = cleanup.add(Files.createTempDirectory("platform-programs-"));
			Files.writeString(scripts.resolve("codex.js"), "");
			Files.writeString(programs.resolve("codex"), "");
			Files.writeString(programs.resolve("codex.cmd"), "");
			Map<String, String> environment = Map.of("Path", scripts + ";" + programs, "PATHEXT", ".JS;.EXE;.CMD");

			String program = Platform.WINDOWS_X64.findProgram("codex", environment);
			requireThat(program, "program").isEqualTo(programs.resolve("codex.cmd").toString());
		}
	}

	/**
	 * Verifies that a Windows program search keeps a path that already names a program's extension, and adds the
	 * extension to a path that lacks one.
	 *
	 * @throws IOException if the test's files cannot be written
	 */
	@Test
	public void windowsCompletesProgramPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path programs = cleanup.add(Files.createTempDirectory("platform-programs-")).toAbsolutePath();
			Path batch = Files.writeString(programs.resolve("codex.cmd"), "");
			Map<String, String> environment = Map.of("PATHEXT", ".EXE;.CMD");

			requireThat(Platform.WINDOWS_X64.findProgram(batch.toString(), environment), "withExtension").
				isEqualTo(batch.toString());
			requireThat(Platform.WINDOWS_X64.findProgram(programs.resolve("codex").toString(), environment),
				"withoutExtension").isEqualTo(batch.toString());
		}
	}

	/**
	 * Verifies that a POSIX program search finds an executable file by the program's name in the folders of the child's
	 * {@code PATH}, in their order.
	 *
	 * @throws IOException if the test's files cannot be written
	 */
	@Test
	public void posixFindsProgramOnPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path empty = cleanup.add(Files.createTempDirectory(Path.of("target"), "platform-empty-"));
			Path programs = cleanup.add(Files.createTempDirectory(Path.of("target"), "platform-programs-"));
			Path codex = Files.writeString(programs.resolve("codex"), "");
			requireThat(codex.toFile().setExecutable(true, false), "executable").isTrue();

			String program = Platform.LINUX_X64.findProgram("codex", Map.of("PATH", empty + ":" + programs));
			requireThat(program, "program").isEqualTo(codex.toString());
		}
	}

	/**
	 * Verifies that a program search returns the program's name unchanged when no folder holds a program by that name,
	 * so starting it reports the missing program.
	 *
	 * @throws IOException if the test's files cannot be written
	 */
	@Test
	public void findProgramKeepsUnmatchedName() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path programs = cleanup.add(Files.createTempDirectory(Path.of("target"), "platform-programs-"));
			Files.writeString(programs.resolve("codex.cmd"), "");

			requireThat(Platform.WINDOWS_X64.findProgram("claude", Map.of("Path", programs.toString())), "windows").
				isEqualTo("claude");
			requireThat(Platform.LINUX_X64.findProgram("claude", Map.of("PATH", programs.toString())), "linux").
				isEqualTo("claude");
		}
	}

	/**
	 * Verifies unsupported operating systems are rejected.
	 */
	@Test(expectedExceptions = UnsupportedOperationException.class,
		expectedExceptionsMessageRegExp = "Unsupported platform: os.name=FreeBSD, os.arch=amd64")
	public void rejectsUnsupportedOperatingSystems()
	{
		Platform.detect("FreeBSD", "amd64");
	}

	/**
	 * Verifies unsupported architectures are rejected.
	 */
	@Test(expectedExceptions = UnsupportedOperationException.class,
		expectedExceptionsMessageRegExp = "Unsupported platform: os.name=Linux, os.arch=riscv64")
	public void rejectsUnsupportedArchitectures()
	{
		Platform.detect("Linux", "riscv64");
	}

	/**
	 * Verifies distribution identifiers remain stable.
	 */
	@Test
	public void exposesStableDistributionIds()
	{
		requireThat(Platform.LINUX_X64.id(), "linuxX64Identifier").isEqualTo("linux-x64");
		requireThat(Platform.LINUX_ARM64.id(), "linuxArm64Identifier").isEqualTo("linux-arm64");
		requireThat(Platform.MACOS_X64.id(), "macosX64Identifier").isEqualTo("macos-x64");
		requireThat(Platform.MACOS_ARM64.id(), "macosArm64Identifier").isEqualTo("macos-arm64");
	}
}

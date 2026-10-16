package io.github.cowwoc.cat.common.runner.test;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.runner.HarnessEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link HarnessEnvironment}.
 */
public final class HarnessEnvironmentTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessEnvironmentTest()
	{
	}

	/**
	 * Verifies harness homes and CAT state are never inherited into an isolated run.
	 */
	@Test
	public void removesHarnessAndCatState()
	{
		Map<String, String> result = HarnessEnvironment.scrub(Map.of("PATH", "/bin", "HARNESS_HOME", "/host/harnessa",
			"OTHER_ENGINE_CONFIG", "/host/harnessb", "CAT_INSTALL_DIR", "/host/cat"));

		requireThat(result.get("PATH"), "path").isEqualTo("/bin");
		requireThat(result.containsKey("HARNESS_HOME"), "hasHarnessHome").isFalse();
		requireThat(result.containsKey("OTHER_ENGINE_CONFIG"), "hasOtherHarnessConfig").isFalse();
		requireThat(result.containsKey("CAT_INSTALL_DIR"), "hasCatInstallDirectory").isFalse();
	}

	/**
	 * Verifies that a program started with the scrubbed environment of this process can create a temporary file and
	 * finds the user's home directory in the platform's home variable. On Windows, a program without {@code TEMP} and
	 * {@code TMP} uses the Windows directory for temporary files, which a user cannot write, and a program without
	 * {@code USERPROFILE} finds no home directory.
	 *
	 * @throws IOException          if the program cannot be started
	 * @throws InterruptedException if the thread is interrupted while the program runs
	 */
	@Test
	public void keepsTemporaryDirectoryAndHome() throws IOException, InterruptedException
	{
		Platform platform = Platform.current();
		List<String> command;
		if (platform.isWindows())
		{
			command = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
				"$ErrorActionPreference = 'Stop'; $file = [System.IO.Path]::GetTempFileName(); " +
				"Remove-Item -LiteralPath $file; " +
				"if (-not $env:USERPROFILE) { exit 2 }");
		}
		else
			command = List.of("sh", "-c", "file=$(mktemp) && rm -- \"$file\" && [ -n \"$HOME\" ]");
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().putAll(HarnessEnvironment.scrub(System.getenv()));
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			requireThat(process.waitFor(), "status").withContext(output, "output").isEqualTo(0);
		}
	}

	/**
	 * Verifies that a program finds the home directory that the harness gives it in the variable that the platform's
	 * programs read, {@code USERPROFILE} on Windows and {@code HOME} elsewhere, rather than the user's home directory.
	 *
	 * @throws IOException          if the program cannot be started
	 * @throws InterruptedException if the thread is interrupted while the program runs
	 */
	@Test
	public void givesChildItsHomeDirectory() throws IOException, InterruptedException
	{
		Path home = Path.of("run", "home").toAbsolutePath();
		Map<String, String> environment = new HashMap<>(HarnessEnvironment.scrub(System.getenv()));
		HarnessEnvironment.setHomeDirectory(environment, home);
		List<String> command;
		if (Platform.current().isWindows())
			command = List.of("cmd.exe", "/D", "/C", "echo %USERPROFILE%");
		else
			command = List.of("sh", "-c", "printf '%s\\n' \"$HOME\"");
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			requireThat(process.waitFor(), "status").withContext(output, "output").isEqualTo(0);
			requireThat(output.strip(), "home").isEqualTo(home.toString());
		}
	}

	/**
	 * Verifies only explicitly approved authentication variables are inherited after scrubbing.
	 */
	@Test
	public void copiesApprovedAuthenticationVariables()
	{
		Map<String, String> result = new HashMap<>(HarnessEnvironment.scrub(Map.of("PATH", "/bin")));
		HarnessEnvironment.copyAllowed(Map.of("HARNESS_AUTHENTICATION", "key", "UNRELATED", "value"), result,
			Set.of("HARNESS_AUTHENTICATION"));

		requireThat(result.get("HARNESS_AUTHENTICATION"), "authenticationKey").isEqualTo("key");
		requireThat(result.containsKey("UNRELATED"), "unrelatedVariable").isFalse();
	}

	/**
	 * Verifies an isolated project cannot discover a repository above its owned run directory.
	 */
	@Test
	public void preventsHostGitDiscovery()
	{
		Map<String, String> environment = new HashMap<>(HarnessEnvironment.scrub(Map.of("PATH", "/bin")));
		Path runDirectory = Path.of("/owned/run").toAbsolutePath();

		HarnessEnvironment.preventGitDiscoveryAbove(environment, runDirectory);

		requireThat(environment.get("GIT_CEILING_DIRECTORIES"), "gitCeiling").isEqualTo(runDirectory.toString());
	}
}

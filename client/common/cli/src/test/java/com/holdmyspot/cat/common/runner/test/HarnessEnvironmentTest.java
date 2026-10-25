package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
	 * Verifies that the child environment retains the platform's temporary-directory and home variables.
	 */
	@Test
	public void keepsTemporaryDirectoryAndHome()
	{
		Map<String, String> source = Map.of("PATH", "programs", "HOME", "host-home", "TEMP", "temp", "TMP", "tmp",
			"USERPROFILE", "profile", "SystemDrive", "drive", "LOCALAPPDATA", "local");

		Map<String, String> result = HarnessEnvironment.scrub(source);

		Map<String, String> expected = source;
		if (!Platform.current().isWindows())
			expected = Map.of("PATH", "programs", "HOME", "host-home");
		requireThat(result, "environment").isEqualTo(expected);
	}

	/**
	 * Verifies that the child environment keeps the Node version manager's directory, so that a {@code node} launcher
	 * that loads the manager from {@code NVM_DIR} finds the host's Node installation although the child's home is the
	 * run's home.
	 */
	@Test
	public void keepsNodeVersionManagerDirectory()
	{
		Map<String, String> result = HarnessEnvironment.scrub(Map.of("PATH", "programs", "HOME", "host-home",
			"NVM_DIR", "host-home/.nvm"));

		requireThat(result.get("NVM_DIR"), "nodeVersionManager").isEqualTo("host-home/.nvm");
	}

	/**
	 * Verifies that a child on a host that reaches the network only through a proxy keeps the proxy settings, so that
	 * its harness reaches the model service.
	 */
	@Test
	public void keepsProxySettings()
	{
		Map<String, String> proxies = Map.of("HTTPS_PROXY", "http://proxy:3128", "https_proxy", "http://proxy:3128",
			"HTTP_PROXY", "http://proxy:3128", "ALL_PROXY", "http://proxy:3128", "NO_PROXY", "localhost",
			"no_proxy", "localhost");
		Map<String, String> source = new HashMap<>(proxies);
		source.put("PATH", "programs");

		Map<String, String> result = HarnessEnvironment.scrub(source);

		for (Map.Entry<String, String> proxy : proxies.entrySet())
			requireThat(result.get(proxy.getKey()), proxy.getKey()).isEqualTo(proxy.getValue());
	}

	/**
	 * Verifies that program lookup finds the program named by the child's scrubbed environment.
	 *
	 * @throws IOException if the program file cannot be created
	 */
	@Test
	public void findsProgramByName() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path folder = cleanup.add(Files.createTempDirectory("harness-environment-")).toRealPath();
			String fileName = "cat-tool";
			if (Platform.current().isWindows())
				fileName += ".cmd";
			Path program = Files.createFile(folder.resolve(fileName));
			if (!Platform.current().isWindows())
				requireThat(program.toFile().setExecutable(true, false), "executable").isTrue();
			Map<String, String> source = Platform.current().environment(Map.of("PATH", folder.toString(),
				"PATHEXT", ".CMD"));

			String resolved = Platform.current().findProgram("cat-tool", HarnessEnvironment.scrub(source));

			requireThat(resolved, "program").isEqualTo(program.toString());
		}
	}

	/**
	 * Verifies that the child environment names the run's home rather than the host's home.
	 */
	@Test
	public void givesChildItsHomeDirectory()
	{
		Path home = Path.of("/run/home");
		if (Platform.current().isWindows())
			home = Path.of("C:/run/home");
		Map<String, String> environment = new HashMap<>(HarnessEnvironment.scrub(Map.of("HOME", "host-home",
			"USERPROFILE", "host-profile", "LOCALAPPDATA", "host-data", "PATH", "programs")));

		HarnessEnvironment.setHomeDirectory(environment, home);

		requireThat(environment.get("HOME"), "home").isEqualTo(home.toString());
		requireThat(environment.get("PATH"), "path").isEqualTo("programs");
		if (Platform.current().isWindows())
		{
			requireThat(environment.get("USERPROFILE"), "profile").isEqualTo(home.toString());
			requireThat(environment.get("LOCALAPPDATA"), "localData").isEqualTo(
				home.resolve("AppData/Local").toString());
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

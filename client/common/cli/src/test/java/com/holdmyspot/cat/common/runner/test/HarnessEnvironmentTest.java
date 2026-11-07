package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	 * Verifies that a child started from a command in a sandbox, whose proxy listens on this machine's loopback
	 * address, connects to loopback services through that proxy: the child's {@code NO_PROXY} and {@code no_proxy}
	 * keep only the sandbox's non-loopback entries, in their order, and the proxy variables stay unchanged.
	 */
	@Test
	public void loopbackProxyCarriesLoopbackConnections()
	{
		String sandboxProxy = "http://sandbox-user:sandbox-password@127.0.0.1:3128";
		String sandboxExclusions = "localhost,127.0.0.1,::1,169.254.0.0/16,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";
		Map<String, String> source = new HashMap<>();
		for (String name : List.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy",
			"all_proxy"))
		{
			source.put(name, sandboxProxy);
		}
		source.put("NO_PROXY", sandboxExclusions);
		source.put("no_proxy", sandboxExclusions);

		Map<String, String> result = HarnessEnvironment.scrub(source);

		String remaining = "169.254.0.0/16,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";
		requireThat(result.get("NO_PROXY"), "NO_PROXY").isEqualTo(remaining);
		requireThat(result.get("no_proxy"), "no_proxy").isEqualTo(remaining);
		requireThat(result.get("HTTPS_PROXY"), "HTTPS_PROXY").isEqualTo(sandboxProxy);
		requireThat(result.get("http_proxy"), "http_proxy").isEqualTo(sandboxProxy);
	}

	/**
	 * Verifies that with a loopback proxy, every spelling of a loopback exclusion leaves the child's {@code NO_PROXY},
	 * while an address range or a host name outside the loopback network stays.
	 */
	@Test
	public void loopbackProxyKeepsOtherExclusions()
	{
		Map<String, String> source = Map.of("HTTP_PROXY", "localhost:3128", "NO_PROXY",
			"127.0.0.0/8,example.com, [::1],LOCALHOST,10.0.0.0/8,127.1,::1,127.example.com");

		Map<String, String> result = HarnessEnvironment.scrub(source);

		requireThat(result.get("NO_PROXY"), "NO_PROXY").isEqualTo("example.com,10.0.0.0/8,127.example.com");
	}

	/**
	 * Verifies that a child whose proxy runs on another machine, which cannot reach this machine's loopback services,
	 * keeps its {@code NO_PROXY} and {@code no_proxy} unchanged.
	 */
	@Test
	public void remoteProxyKeepsExclusions()
	{
		String exclusions = "localhost,127.0.0.1,::1,10.0.0.0/8,example.com";
		Map<String, String> source = Map.of("HTTPS_PROXY", "http://proxy.example.com:3128", "NO_PROXY", exclusions,
			"no_proxy", exclusions);

		Map<String, String> result = HarnessEnvironment.scrub(source);

		requireThat(result.get("NO_PROXY"), "NO_PROXY").isEqualTo(exclusions);
		requireThat(result.get("no_proxy"), "no_proxy").isEqualTo(exclusions);
	}

	/**
	 * Verifies that a child whose connections can go to a remote proxy keeps its loopback exclusions even when another
	 * proxy variable names a loopback proxy, since the remote proxy cannot reach this machine's loopback services.
	 */
	@Test
	public void mixedProxiesKeepExclusions()
	{
		String exclusions = "localhost,127.0.0.1,::1";
		Map<String, String> source = Map.of("HTTP_PROXY", "http://127.0.0.1:3128", "HTTPS_PROXY",
			"http://proxy.example.com:3128", "NO_PROXY", exclusions);

		Map<String, String> result = HarnessEnvironment.scrub(source);

		requireThat(result.get("NO_PROXY"), "NO_PROXY").isEqualTo(exclusions);
	}

	/**
	 * Verifies that a child of a parent that names no proxy keeps its {@code NO_PROXY} and {@code no_proxy} unchanged.
	 */
	@Test
	public void noProxyKeepsExclusions()
	{
		String exclusions = "localhost,127.0.0.1,::1,10.0.0.0/8";
		Map<String, String> source = Map.of("NO_PROXY", exclusions, "no_proxy", exclusions);

		Map<String, String> result = HarnessEnvironment.scrub(source);

		requireThat(result.get("NO_PROXY"), "NO_PROXY").isEqualTo(exclusions);
		requireThat(result.get("no_proxy"), "no_proxy").isEqualTo(exclusions);
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

	/**
	 * Verifies that a runner refuses a launch from a session of a CAT run whose runner lets it request no sessions,
	 * naming the option that lets it, and lets a launch from a run that lets its session request sessions, or from no
	 * run, go on.
	 */
	@Test
	public void refusesLaunchInRunWithoutRequests()
	{
		Optional<String> refusal = HarnessEnvironment.inRunRefusal("harness-a", Map.of(HarnessEnvironment.RUN_VARIABLE,
			HarnessEnvironment.RUN_VALUE));

		requireThat(refusal.orElseThrow(), "refusal").startsWith("harness-a-runner refuses to start a session").
			contains("--session-requests");
		requireThat(HarnessEnvironment.inRunRefusal("harness-a", Map.of(HarnessEnvironment.RUN_VARIABLE,
				HarnessEnvironment.RUN_VALUE, SessionRequestAccess.REQUESTS_VARIABLE, "/run/requests")), "requesting").
			isEmpty();
		requireThat(HarnessEnvironment.inRunRefusal("harness-a", Map.of(HarnessEnvironment.RUN_VARIABLE, "0")),
			"otherValue").isEmpty();
		requireThat(HarnessEnvironment.inRunRefusal("harness-a", Map.of("PATH", "/bin")), "outsideRun").isEmpty();
	}
}

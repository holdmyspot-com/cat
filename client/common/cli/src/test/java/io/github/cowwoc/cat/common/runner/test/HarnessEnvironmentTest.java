package io.github.cowwoc.cat.common.runner.test;

import io.github.cowwoc.cat.common.runner.HarnessEnvironment;
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
		Path runDirectory = Path.of("/owned/run");

		HarnessEnvironment.preventGitDiscoveryAbove(environment, runDirectory);

		requireThat(environment.get("GIT_CEILING_DIRECTORIES"), "gitCeiling").isEqualTo(runDirectory.toString());
	}
}

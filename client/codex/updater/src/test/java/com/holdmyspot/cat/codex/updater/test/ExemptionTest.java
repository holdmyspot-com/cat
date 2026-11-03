package com.holdmyspot.cat.codex.updater.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.cli.SandboxExemption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the permission rule that the trusted updater reports for Codex.
 */
public final class ExemptionTest
{
	/**
	 * The registered provider reports a rule for only the installed launcher and routine update arguments.
	 */
	@Test
	public void reportsRoutineUpdatePrefix()
	{
		Path home = Path.of(System.getProperty("cat.updater.test.directory")).resolve("fake-home");
		Path launcher = home.resolve("updater directory/bin/cat-updater");
		Map<String, List<String>> entries = provider().entries(launcher, home);
		String quotedLauncher = JsonMapper.builder().build().writeValueAsString(launcher.toString());
		requireThat(entries, "entries").isEqualTo(Map.of(home.resolve("rules/cat-updater.rules").toString(),
			List.of("""
				prefix_rule(pattern=[%s, "update", "--harness", "codex"], decision="allow")
				""".formatted(quotedLauncher).stripTrailing())));
	}

	/**
	 * Relative launcher paths cannot describe the trusted updater's stable executable.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesRelativeLauncher()
	{
		provider().entries(Path.of("bin/cat-updater"),
			Path.of(System.getProperty("cat.updater.test.directory")).resolve("fake-home"));
	}

	/**
	 * Relative harness homes cannot locate the user's permission file.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesRelativeHome()
	{
		provider().entries(Path.of(System.getProperty("cat.updater.test.directory")).resolve("updater/bin/cat-updater"),
			Path.of("fake-home"));
	}

	/**
	 * Finds the Codex permission service used by the common updater.
	 *
	 * @return the Codex exemption provider
	 */
	private static SandboxExemption provider()
	{
		return ServiceLoader.load(SandboxExemption.class).stream().map(ServiceLoader.Provider::get).
			filter(provider -> provider.harness().equals("codex")).findFirst().
			orElseThrow(() -> new AssertionError("The Codex sandbox exemption service is missing"));
	}
}

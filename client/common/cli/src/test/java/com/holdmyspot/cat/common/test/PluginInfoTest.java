package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.runner.PluginManifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.testng.annotations.Test;

/**
 * Tests {@link PluginInfo}.
 */
public final class PluginInfoTest
{
	/**
	 * Creates a test instance.
	 */
	public PluginInfoTest()
	{
	}

	/**
	 * Verifies the machine-readable representation.
	 */
	@Test
	public void rendersMachineReadableProperties()
	{
		PluginInfo info = new PluginInfo("example-harness", PluginInfo.CAT_VERSION, Platform.LINUX_ARM64);
		requireThat(info.toDisplayString(), "displayString").isEqualTo(
			"harness=example-harness\nversion=" + PluginInfo.CAT_VERSION + "\nplatform=linux-arm64");
	}

	/**
	 * Verifies that CAT's repository is the {@code repository} that both harnesses' plugin manifests state, so that the
	 * manifests stay the source of the value.
	 *
	 * @throws IOException if a manifest cannot be read
	 */
	@Test
	public void catRepositoryMatchesManifests() throws IOException
	{
		Path claude = Path.of("../../claude/plugin/src/main/resources");
		Path codex = Path.of("../../codex/plugin/src/main/resources");

		requireThat(PluginManifest.repository(claude, Path.of(".claude-plugin/plugin.json")), "claude").
			isEqualTo(Optional.of(PluginInfo.CAT_REPOSITORY));
		requireThat(PluginManifest.repository(codex, Path.of(".codex-plugin/plugin.json")), "codex").
			isEqualTo(Optional.of(PluginInfo.CAT_REPOSITORY));
	}

	/**
	 * Verifies that a repository names CAT's when it differs only in the letter case of its scheme or host, or in a
	 * trailing {@code /} or {@code .git}, and not when its path, scheme or form differs.
	 */
	@Test
	public void catRepositoryIgnoresSpelling()
	{
		for (String same : List.of("https://github.com/holdmyspot-com/cat", "HTTPS://GitHub.COM/holdmyspot-com/cat",
			"https://github.com/holdmyspot-com/cat/", "https://github.com/holdmyspot-com/cat.git",
			"https://github.com/holdmyspot-com/cat.git/"))
		{
			requireThat(PluginInfo.isCatRepository(same), "same").withContext(same, "repository").isTrue();
		}
		for (String other : List.of("https://github.com/holdmyspot-com/CAT", "http://github.com/holdmyspot-com/cat",
			"https://github.com/cowwoc/cat", "https://github.com/holdmyspot-com/cat-tools",
			"https://github.com/holdmyspot-com/cat//", "git@github.com:holdmyspot-com/cat.git", "not a url"))
		{
			requireThat(PluginInfo.isCatRepository(other), "other").withContext(other, "repository").isFalse();
		}
		requireThat(PluginInfo.isCatRepository(null), "null").isFalse();
	}

	/**
	 * Verifies blank harness names are rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankHarnessNames()
	{
		new PluginInfo("", PluginInfo.CAT_VERSION, Platform.LINUX_X64);
	}

	/**
	 * Verifies blank version names are rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankVersions()
	{
		new PluginInfo("example-harness", " ", Platform.LINUX_X64);
	}

	/**
	 * Verifies a null platform is rejected.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullPlatform()
	{
		new PluginInfo("example-harness", PluginInfo.CAT_VERSION, null);
	}
}

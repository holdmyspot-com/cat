package com.holdmyspot.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;

import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Tests {@link TrialPlugin}.
 */
public final class TrialPluginTest
{
	/**
	 * Creates the test fixture.
	 */
	public TrialPluginTest()
	{
	}

	/**
	 * Verifies that a plugin named after CAT whose manifest states another repository is not CAT, and that a plugin
	 * without a recorded repository is not CAT either.
	 */
	@Test
	public void catNameWithoutCatRepository()
	{
		requireThat(installed("cat@x", "https://github.com/someone/cat").isCat(), "otherRepository").isFalse();
		requireThat(installed("cat@cat", null).isCat(), "noRepository").isFalse();
		requireThat(built("cat", null).isCat(), "builtWithoutRepository").isFalse();
		requireThat(installed("cat@cat", "https://github.com/cowwoc/cat").isCat(), "formerRepository").isFalse();
	}

	/**
	 * Verifies that a plugin whose manifest states CAT's repository is CAT whatever its name and marketplace, and
	 * whether the project builds it or the user installed it.
	 */
	@Test
	public void catRepositoryUnderOtherName()
	{
		requireThat(installed("tools@market", PluginInfo.CAT_REPOSITORY).isCat(), "installed").isTrue();
		requireThat(built("workflow", PluginInfo.CAT_REPOSITORY).isCat(), "built").isTrue();
		requireThat(installed("cat@dev", "HTTPS://GitHub.com/holdmyspot-com/cat.git").isCat(), "spelling").isTrue();
	}

	/**
	 * Returns a plugin that the project does not build.
	 *
	 * @param name       the plugin's name
	 * @param repository the repository in its manifest, or {@code null} for none
	 * @return the plugin
	 */
	private static TrialPlugin installed(String name, String repository)
	{
		return new TrialPlugin(name, "a".repeat(64), new TrialPlugin.Installed(), List.of(), repository);
	}

	/**
	 * Returns a plugin that the project builds.
	 *
	 * @param name       the plugin's name
	 * @param repository the repository in its manifest, or {@code null} for none
	 * @return the plugin
	 */
	private static TrialPlugin built(String name, String repository)
	{
		return new TrialPlugin(name, "a".repeat(64), new TrialPlugin.Build(List.of("build"), Path.of("out")),
			List.of(), repository);
	}
}

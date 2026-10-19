package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
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

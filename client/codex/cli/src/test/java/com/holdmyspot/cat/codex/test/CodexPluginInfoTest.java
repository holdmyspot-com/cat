package com.holdmyspot.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.CodexPluginInfo;
import com.holdmyspot.cat.common.Platform;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexPluginInfo}.
 */
public final class CodexPluginInfoTest
{
	/**
	 * Creates a test instance.
	 */
	public CodexPluginInfoTest()
	{
	}

	/**
	 * Verifies that Codex's {@code plugin-info} reports the Codex harness, CAT release 1.0 and the platform that it runs
	 * on.
	 */
	@Test
	public void reportsHarnessVersionAndPlatform()
	{
		requireThat(CodexPluginInfo.info().toDisplayString(), "info").isEqualTo(
			"harness=codex\nversion=1.0\nplatform=" + Platform.current().id());
	}
}

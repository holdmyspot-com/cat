package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.ParentSandbox;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a runner launcher recognizes a Codex sandbox that has network off through {@link ParentSandbox}.
 */
public final class ParentSandboxTest
{
	/**
	 * Creates the test.
	 */
	public ParentSandboxTest()
	{
	}

	/**
	 * Verifies that a launcher refuses when Codex marks its sandbox as having no network, and that the reason names
	 * the harness's launcher and the setting that enables network.
	 */
	@Test
	public void refusesWhenNetworkIsOff()
	{
		StringWriter errors = new StringWriter();

		boolean refused = ParentSandbox.refusesNetworkOff("codex", Map.of(ParentSandbox.NETWORK_DISABLED_VARIABLE, "1"),
			new PrintWriter(errors, true));

		requireThat(refused, "refused").isTrue();
		requireThat(errors.toString(), "errors").contains("codex-runner").contains("Codex").
			contains("sandbox_workspace_write.network_access = true");
	}

	/**
	 * Returns environments in which no Codex sandbox has network off.
	 *
	 * @return each environment
	 */
	@DataProvider
	public Object[][] networkAvailable()
	{
		return new Object[][]{{Map.of()}, {Map.of(ParentSandbox.NETWORK_DISABLED_VARIABLE, "")},
			{Map.of(ParentSandbox.NETWORK_DISABLED_VARIABLE, "0")}};
	}

	/**
	 * Verifies that a launcher does not refuse, and says nothing, when Codex does not mark its sandbox as having no
	 * network.
	 *
	 * @param environment the launcher's environment
	 */
	@Test(dataProvider = "networkAvailable")
	public void startsWhenNetworkIsNotOff(Map<String, String> environment)
	{
		StringWriter errors = new StringWriter();

		boolean refused = ParentSandbox.refusesNetworkOff("claude", environment, new PrintWriter(errors, true));

		requireThat(refused, "refused").isFalse();
		requireThat(errors.toString(), "errors").isEmpty();
	}
}

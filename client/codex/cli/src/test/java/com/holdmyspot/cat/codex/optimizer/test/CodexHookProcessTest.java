package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.codex.optimizer.CodexHookProcess;
import com.holdmyspot.cat.common.Platform;

import java.io.IOException;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexHookProcess}.
 */
public final class CodexHookProcessTest
{
	/**
	 * Creates the test.
	 */
	public CodexHookProcessTest()
	{
	}

	/**
	 * Returns the hosts on which CAT starts startup hooks under the Codex sandbox.
	 *
	 * @return the platforms
	 */
	@DataProvider
	public static Object[][] confinedHosts()
	{
		return new Object[][]
		{
			{Platform.LINUX_X64},
			{Platform.MACOS_ARM64}
		};
	}

	/**
	 * Verifies that a refusal on native Windows says what CAT refuses and why, and names WSL2 as the route.
	 */
	@Test
	public void refusesStartupHooksOnNativeWindows()
	{
		IOException failure = expectThrows(IOException.class, () ->
			CodexHookProcess.requireConfinableHost(Platform.WINDOWS_X64));

		requireThat(failure.getMessage(), "message").isEqualTo("CAT refuses to run a startup hook of the Codex " +
			"optimizer on native Windows because it confines the hook through /usr/bin/env under the search path " +
			"/usr/bin:/bin, which Windows lacks, and has no Windows way to confine it. Run the prompt optimizer " +
			"inside WSL2.");
	}

	/**
	 * Verifies that Linux and macOS hosts are not refused.
	 *
	 * @param platform the host platform
	 * @throws IOException if the host is refused
	 */
	@Test(dataProvider = "confinedHosts")
	public void acceptsLinuxAndMacos(Platform platform) throws IOException
	{
		CodexHookProcess.requireConfinableHost(platform);
	}
}

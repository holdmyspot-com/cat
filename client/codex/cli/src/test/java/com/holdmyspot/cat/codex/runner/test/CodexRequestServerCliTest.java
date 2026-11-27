package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexRequestServerCli;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.RequestServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexRequestServerCli}.
 */
public final class CodexRequestServerCliTest
{
	/**
	 * Creates the test.
	 */
	public CodexRequestServerCliTest()
	{
	}

	/**
	 * Returns each native Windows platform.
	 *
	 * @return the platform
	 */
	@DataProvider
	public Object[][] nativeWindows()
	{
		return new Object[][]{{Platform.WINDOWS_X64}, {Platform.WINDOWS_ARM64}};
	}

	/**
	 * Verifies that on native Windows the Codex request server refuses to run, before it looks for its session,
	 * with the status of a refused launch and a reason that names the checks of Codex's Windows sandbox that failed,
	 * each sandbox implementation and what happened under it, and directs the user to WSL2.
	 *
	 * @param platform the native Windows platform
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "nativeWindows")
	public void refusesOnNativeWindows(Platform platform) throws IOException
	{
		// Requirement: R-I32
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "request-server-plugin-"));
			StringWriter errors = new StringWriter();

			int status = RequestServer.serve(new String[0], CodexRequestServerCli.host(jvm, Map.of(), plugin,
				platform, Thread::onSpinWait), new PrintWriter(errors, true));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("writes, reads and network access").
				contains("unelevated").contains("refuses to start a session whose policy denies reads").
				contains("elevated").contains("rejected every command").contains("hard link").
				contains("public hosts").contains("WSL2").contains("wsl --install");
		}
	}

	/**
	 * Verifies that on Linux and macOS, where Codex sandboxes a session, the Codex request server's host gives no
	 * refusal.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsOnLinuxAndMacOs() throws IOException
	{
		// Requirement: R-I32
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "request-server-plugin-"));
			for (Platform platform : Platform.values())
			{
				if (platform.isWindows())
					continue;
				requireThat(CodexRequestServerCli.host(jvm, Map.of(), plugin, platform, Thread::onSpinWait).
						refusal(), "refusal").
					withContext(platform, "platform").isEmpty();
			}
		}
	}
}

package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexRequestServerCli;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
	 * Verifies that the Codex request server's host gives no refusal on any platform, native Windows included, where
	 * every Codex sandbox implementation counts as a sandbox.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsOnEveryPlatform() throws IOException
	{
		// Requirement: R-I44
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope())
		{
			Path plugin = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "request-server-plugin-"));
			for (Platform platform : Platform.values())
			{
				requireThat(CodexRequestServerCli.host(jvm, Map.of(), plugin, platform, Thread::onSpinWait).
						refusal(), "refusal").
					withContext(platform, "platform").isEmpty();
			}
		}
	}
}

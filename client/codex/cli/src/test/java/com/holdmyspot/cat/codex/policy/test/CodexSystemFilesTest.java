package com.holdmyspot.cat.codex.policy.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.policy.CodexHookSandbox;
import com.holdmyspot.cat.codex.policy.CodexSystemFiles;
import com.holdmyspot.cat.common.Platform;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexSystemFiles} and {@link CodexHookSandbox} on the machine that runs the tests.
 */
public final class CodexSystemFilesTest
{
	/**
	 * Creates a test instance.
	 */
	public CodexSystemFilesTest()
	{
	}

	/**
	 * Verifies that the files of this machine are built on every platform, including Windows, which has no managed
	 * preferences and no {@code /tmp}, and that the hook's sandbox source is built there too, so that the check of the
	 * folders on {@code PATH} runs on every platform that Codex runs on.
	 */
	@Test
	public void describesThisMachine()
	{
		CodexSystemFiles files = CodexSystemFiles.ofThisMachine("someone");

		requireThat(files.system().folder(), "folder").isAbsolute();
		requireThat(CodexHookSandbox.ofThisProcess(), "sandbox").isNotNull();
		if (Platform.current().isWindows())
		{
			requireThat(files.managedPreferences(), "managedPreferences").isEmpty();
			requireThat(files.slashTmp(), "slashTmp").isNull();
		}
		else
		{
			requireThat(files.system().folder(), "folder").isEqualTo(Path.of("/etc/codex"));
			requireThat(files.managedPreferences(), "managedPreferences").isNotEmpty();
		}
	}
}

package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.ShellQuoting;
import com.holdmyspot.cat.common.runner.LauncherSpelling;
import java.nio.file.Path;
import java.util.Optional;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests how {@link LauncherSpelling} reads the word by which a shell command names a runner launcher.
 */
public final class LauncherSpellingTest
{
	/**
	 * The launcher's absolute path.
	 */
	private static final Path LAUNCHER = Path.of("/opt/plugin/bin/harness-a-runner").toAbsolutePath();

	/**
	 * Creates the test.
	 */
	public LauncherSpellingTest()
	{
	}

	/**
	 * Verifies that a command that names the launcher by its absolute path, quoted or not and wherever it stands,
	 * names no other spelling.
	 */
	@Test
	public void absolutePathIsNoOtherSpelling()
	{
		String path = ShellQuoting.posix(LAUNCHER.toString());
		requireThat(LauncherSpelling.otherSpelling(path + " --prompt-file task.md", LAUNCHER), "plain").isEmpty();
		requireThat(LauncherSpelling.otherSpelling("'" + LAUNCHER + "' x", LAUNCHER), "singleQuoted").isEmpty();
		requireThat(LauncherSpelling.otherSpelling("cd /work && " + path + " x | tee out", LAUNCHER), "chained").
			isEmpty();
	}

	/**
	 * Verifies that a command that names the launcher by another path or by its bare name returns that word without
	 * its quotes and escapes.
	 */
	@Test
	public void namesOtherSpelling()
	{
		requireThat(LauncherSpelling.otherSpelling("~/plugin/bin/harness-a-runner x", LAUNCHER), "tilde").
			isEqualTo(Optional.of("~/plugin/bin/harness-a-runner"));
		requireThat(LauncherSpelling.otherSpelling("FOO=1 \"./bin/harness-a-runner\" x", LAUNCHER), "relative").
			isEqualTo(Optional.of("./bin/harness-a-runner"));
		requireThat(LauncherSpelling.otherSpelling("timeout 5 harness-a-runner;echo done", LAUNCHER), "bare").
			isEqualTo(Optional.of("harness-a-runner"));
		requireThat(LauncherSpelling.otherSpelling("/opt/plugin/bin/../bin/harness\\-a-runner x", LAUNCHER),
			"escaped").isEqualTo(Optional.of("/opt/plugin/bin/../bin/harness-a-runner"));
	}

	/**
	 * Verifies that a command whose words do not end with the launcher's file name names no spelling.
	 */
	@Test
	public void otherCommandNamesNoSpelling()
	{
		requireThat(LauncherSpelling.otherSpelling("cat notes/harness-a-runner-notes.md", LAUNCHER), "longerName").
			isEmpty();
		requireThat(LauncherSpelling.otherSpelling("git status", LAUNCHER), "unrelated").isEmpty();
	}
}

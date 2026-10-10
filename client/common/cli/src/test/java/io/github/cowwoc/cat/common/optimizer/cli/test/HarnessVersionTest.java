package io.github.cowwoc.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.cli.HarnessVersion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Tests {@link HarnessVersion} with a fake process seam, so that no test starts a process.
 */
public final class HarnessVersionTest
{
	/**
	 * Creates the test.
	 */
	public HarnessVersionTest()
	{
	}

	/**
	 * Verifies that the version is the first non-blank line of the output of {@code <executable> --version}.
	 */
	@Test
	public void readsVersionFromTheOutput()
	{
		// Requirement: R-I4
		List<List<String>> commands = new ArrayList<>();
		String version = HarnessVersion.read("claude", command ->
		{
			commands.add(command);
			return "\n  2.1.3 (Claude Code)\nmore\n";
		});
		requireThat(version, "version").isEqualTo("2.1.3 (Claude Code)");
		requireThat(commands, "commands").isEqualTo(List.of(List.of("claude", "--version")));
	}

	/**
	 * Verifies that the version is {@code unknown} when the output is blank, the process cannot start or it times
	 * out, and that an interrupt is preserved.
	 */
	@Test
	public void fallsBackToUnknown()
	{
		// Requirement: R-I4
		requireThat(HarnessVersion.read("codex", _ -> " \n"), "blank").isEqualTo("unknown");
		requireThat(HarnessVersion.read("codex", _ ->
		{
			throw new IOException("No such file");
		}), "missing").isEqualTo("unknown");
		requireThat(HarnessVersion.read("codex", _ ->
		{
			throw new InterruptedException("stop");
		}), "interrupted").isEqualTo("unknown");
		requireThat(Thread.interrupted(), "interruptKept").isTrue();
	}
}

package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeSandboxSettings;
import com.holdmyspot.cat.claude.runner.ClaudeSandboxSettings.Setting;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Verifies that the settings files that Claude Code loads beside a session's own give the {@code TMPDIR} and the
 * bubblewrap file that decide whether the session's sandbox starts, from the file that Claude Code lets win.
 */
public final class ClaudeSandboxSettingsTest
{
	/**
	 * Creates the test.
	 */
	public ClaudeSandboxSettingsTest()
	{
	}

	/**
	 * Verifies that a {@code TMPDIR} in a file's {@code env} and a {@code sandbox.bwrapPath} are read with the file
	 * that sets each, and that files that set neither give none.
	 *
	 * @throws IOException if a fixture cannot be written
	 */
	@Test
	public void readsTemporaryFolderAndBwrapPath() throws IOException
	{
		// Requirement: R-I16
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sandbox-settings-"));
			Path both = Files.writeString(root.resolve("both.json"), """
				{"env": {"TMPDIR": "/long/folder", "OTHER": "x"}, "sandbox": {"bwrapPath": "/opt/bwrap"}}
				""");
			Path neither = Files.writeString(root.resolve("neither.json"), """
				{"env": {"OTHER": "x"}, "sandbox": {"enabled": true}}
				""");

			ClaudeSandboxSettings read = ClaudeSandboxSettings.read(scope.json(), List.of(both));
			ClaudeSandboxSettings none = ClaudeSandboxSettings.read(scope.json(), List.of(neither));

			requireThat(read.temporaryFolder(), "temporaryFolder").isEqualTo(new Setting(both, "/long/folder"));
			requireThat(read.bwrapPath(), "bwrapPath").isEqualTo(new Setting(both, "/opt/bwrap"));
			requireThat(none.temporaryFolder(), "noTemporaryFolder").isNull();
			requireThat(none.bwrapPath(), "noBwrapPath").isNull();
			requireThat(ClaudeSandboxSettings.read(scope.json(), List.of()).temporaryFolder(), "noFiles").isNull();
		}
	}

	/**
	 * Verifies that a setting that several files set takes the value of the file that Claude Code loads last, whichever
	 * setting each file also sets, so that the refusal names the file that decides.
	 *
	 * @throws IOException if a fixture cannot be written
	 */
	@Test
	public void lastFileWins() throws IOException
	{
		// Requirement: R-I16
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sandbox-settings-"));
			Path first = Files.writeString(root.resolve("first.json"), """
				{"env": {"TMPDIR": "/first"}, "sandbox": {"bwrapPath": "/first/bwrap"}}
				""");
			Path second = Files.writeString(root.resolve("second.json"), """
				{"env": {"TMPDIR": "/second"}}
				""");
			Path third = Files.writeString(root.resolve("third.json"), """
				{"sandbox": {"bwrapPath": "/third/bwrap"}}
				""");

			ClaudeSandboxSettings read = ClaudeSandboxSettings.read(scope.json(), List.of(first, second, third));

			requireThat(read.temporaryFolder(), "temporaryFolder").isEqualTo(new Setting(second, "/second"));
			requireThat(read.bwrapPath(), "bwrapPath").isEqualTo(new Setting(third, "/third/bwrap"));
		}
	}

	/**
	 * Verifies that a file that does not exist, a directory, a file that is not JSON, a JSON value that is not an
	 * object and a setting that is not a non-blank string are skipped, because another check refuses the launch for a
	 * file that cannot be understood and a setting of another type sets nothing.
	 *
	 * @throws IOException if a fixture cannot be written
	 */
	@Test
	public void skipsWhatItCannotRead() throws IOException
	{
		// Requirement: R-I16
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-sandbox-settings-"));
			Path valid = Files.writeString(root.resolve("valid.json"), """
				{"env": {"TMPDIR": "/valid"}, "sandbox": {"bwrapPath": "/valid/bwrap"}}
				""");
			List<Path> skipped = List.of(root.resolve("missing.json"), Files.createDirectory(root.resolve("directory")),
				Files.writeString(root.resolve("broken.json"), "{"), Files.writeString(root.resolve("array.json"), "[]"),
				Files.writeString(root.resolve("types.json"), """
					{"env": {"TMPDIR": 5}, "sandbox": {"bwrapPath": ["x"]}}
					"""), Files.writeString(root.resolve("blank.json"), """
					{"env": {"TMPDIR": " "}, "sandbox": {"bwrapPath": ""}}
					"""), Files.writeString(root.resolve("shapes.json"), """
					{"env": "x", "sandbox": 3}
					"""));
			List<Path> files = new ArrayList<>();
			files.add(valid);
			files.addAll(skipped);

			ClaudeSandboxSettings read = ClaudeSandboxSettings.read(scope.json(), files);

			requireThat(read.temporaryFolder(), "temporaryFolder").isEqualTo(new Setting(valid, "/valid"));
			requireThat(read.bwrapPath(), "bwrapPath").isEqualTo(new Setting(valid, "/valid/bwrap"));
		}
	}
}

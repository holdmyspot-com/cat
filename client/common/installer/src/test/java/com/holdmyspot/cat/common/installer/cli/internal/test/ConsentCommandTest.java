package com.holdmyspot.cat.common.installer.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.cli.internal.Arguments;
import com.holdmyspot.cat.common.installer.cli.internal.ConsentCommand;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests the command with which the user allows the change that an install asked for.
 */
public final class ConsentCommandTest
{
	/**
	 * The command runs the same install with the version folder's installer launcher and gives the permission before
	 * the operation, so that it does not start like the install that permission entries allow without asking, and the
	 * installer accepts it.
	 */
	@Test
	public void repeatsInstallWithPermission()
	{
		Path root = Path.of(System.getProperty("cat.installer.test.directory")).toAbsolutePath();
		Path plugin = root.resolve("home/plugins/cache/cat/cat/1.0+b0123456789ab");
		Path home = root.resolve("home");
		Arguments asked = Arguments.parse(new String[]{"--home", home.toString(), "install", "--harness", "harness-a",
			"v1.2"}, Set.of("harness-a"));

		String command = ConsentCommand.of(plugin, Platform.LINUX_X64, asked);

		String launcher = plugin.resolve("bin/installer").toString();
		requireThat(command, "command").startsWith(launcher + " --home ").doesNotStartWith(launcher + " install");
		String[] words = command.substring(launcher.length() + 1).split(" ");
		Arguments allowed = Arguments.parse(words, Set.of("harness-a"));
		requireThat(allowed.createProfile(), "createProfile").isTrue();
		requireThat(allowed.harness(), "harness").isEqualTo("harness-a");
		requireThat(allowed.home(), "home").isEqualTo(home);
		requireThat(allowed.tag(), "tag").isEqualTo("v1.2");
		requireThat(Arrays.asList(words), "words").doesNotContain("");
	}

	/**
	 * A harness home whose path holds a space stays one word for the shell.
	 */
	@Test
	public void quotesHomeWithSpace()
	{
		Path plugin = Path.of("/data/plugins/cache/cat/cat/1.0+b0123456789ab");
		Arguments asked = Arguments.parse(new String[]{"--home", "/data/my home", "install", "--harness",
			"harness-a"}, Set.of("harness-a"));

		requireThat(ConsentCommand.of(plugin, Platform.LINUX_X64, asked), "command").
			endsWith(" --home '/data/my home' --create-profile install --harness harness-a");
	}

	/**
	 * On Windows, the command runs the launcher as the installer's permission entries name it.
	 */
	@Test
	public void runsWindowsLauncherThroughPowerShell()
	{
		Path plugin = Path.of(System.getProperty("cat.installer.test.directory")).toAbsolutePath().
			resolve("plugin");
		Arguments asked = Arguments.parse(new String[]{"install", "--harness", "harness-a"}, Set.of("harness-a"));

		requireThat(ConsentCommand.of(plugin, Platform.WINDOWS_X64, asked), "command").
			isEqualTo("powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" +
				plugin.resolve("bin/installer.ps1") + "\" --create-profile install --harness harness-a");
	}
}

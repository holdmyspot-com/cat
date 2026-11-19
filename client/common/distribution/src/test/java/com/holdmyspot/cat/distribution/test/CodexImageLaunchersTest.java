package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.distribution.bundle.Launcher;
import com.holdmyspot.cat.distribution.secrets.DistributionMainAccess;
import com.holdmyspot.cat.distribution.secrets.DistributionMainSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests which launchers the Codex plugin's {@code bin} folder holds on each platform, so that the absolute path without
 * an extension that CAT's skills name, such as {@code <plugin>/bin/plugin-info}, resolves to a launcher that Codex's
 * shell can run. {@code codex-plugin-launchers-windows.bats} runs the Windows plugin's launchers by those paths from
 * PowerShell.
 */
public final class CodexImageLaunchersTest
{
	/**
	 * Creates a test instance.
	 */
	public CodexImageLaunchersTest()
	{
	}

	/**
	 * Verifies that the Codex plugin on Linux holds a POSIX launcher for each program and no batch launcher, and that
	 * each launcher starts its own program.
	 *
	 * @throws IOException if the launchers cannot be written or read
	 */
	@Test
	public void linuxPluginHoldsOnlyPosixLaunchers() throws IOException
	{
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path bin = cleanup.add(Files.createTempDirectory("codex-image-")).resolve("bin");
			access.writeCodexLaunchers(bin, Platform.LINUX_X64);

			List<Launcher> launchers = access.codexLaunchers();
			requireThat(launchers, "launchers").isNotEmpty();
			for (Launcher launcher : launchers)
			{
				requireThat(Files.readString(bin.resolve(launcher.name())), launcher.name()).
					contains(" -m " + launcher.module() + "/" + launcher.mainClass() + " \"$@\"\n");
				requireThat(Files.exists(bin.resolve(launcher.name() + ".cmd")), launcher.name() + ".cmdExists").isFalse();
			}
		}
	}

	/**
	 * Verifies that the Codex plugin on Windows holds a batch launcher for each program and no POSIX launcher, because
	 * PowerShell, in which Codex runs commands there, resolves an extensionless path to the POSIX launcher whenever it
	 * exists and cannot run it; and that each batch launcher starts its own program.
	 *
	 * @throws IOException if the launchers cannot be written or read
	 */
	@Test
	public void windowsPluginHoldsOnlyBatchLaunchers() throws IOException
	{
		DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path bin = cleanup.add(Files.createTempDirectory("codex-image-")).resolve("bin");
			access.writeCodexLaunchers(bin, Platform.WINDOWS_X64);

			List<Launcher> launchers = access.codexLaunchers();
			requireThat(launchers, "launchers").isNotEmpty();
			for (Launcher launcher : launchers)
			{
				requireThat(Files.readString(bin.resolve(launcher.name() + ".cmd")), launcher.name() + ".cmd").
					contains(" -m " + launcher.module() + "/" + launcher.mainClass() + " %*\r\n");
				requireThat(Files.exists(bin.resolve(launcher.name())), launcher.name() + "Exists").isFalse();
			}
		}
	}
}

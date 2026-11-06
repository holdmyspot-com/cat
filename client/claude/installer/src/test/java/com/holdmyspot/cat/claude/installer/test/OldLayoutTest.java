package com.holdmyspot.cat.claude.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.claude.installer.Integration;
import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.engine.FolderRetention;
import com.holdmyspot.cat.common.installer.engine.InstallCompletion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests that an install into Claude Code deletes what an earlier layout of CAT's plugin left in the folder of the
 * version folders, which only the installer writes, and keeps the version folders that the harness still needs.
 */
public final class OldLayoutTest
{
	/**
	 * The version that the harness runs after the install.
	 */
	private static final PluginVersion ACTIVE = PluginVersion.parse("1.0+bfedcba987654");
	/**
	 * The version that the active one replaced.
	 */
	private static final PluginVersion REPLACED = PluginVersion.parse("1.0+b0123456789ab");

	/**
	 * Creates a test instance.
	 */
	public OldLayoutTest()
	{
	}

	/**
	 * The files and folders that the flat layout put directly in the plugin's folder, before the plugin moved into
	 * version folders, are deleted, while the active and replaced versions' folders stay.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void deletesFlatLayout() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			ClaudeInstallation installation = new ClaudeInstallation(home);
			Path active = writeVersionFolder(installation.versionFolder(ACTIVE));
			Path replaced = writeVersionFolder(installation.versionFolder(REPLACED));
			// Stands for the plugin that an install of the flat layout wrote into the plugin's folder itself
			Path plugin = installation.versionFolders();
			Path launchers = Files.createDirectories(plugin.resolve("bin"));
			Files.writeString(launchers.resolve("installer"), "#!/bin/sh\n");
			Path runtime = Files.createDirectories(plugin.resolve("client/bin"));
			Files.writeString(runtime.resolve("java"), "runtime");
			Path manifest = Files.createDirectories(plugin.resolve(".claude-plugin")).resolve("plugin.json");
			Files.writeString(manifest, "{}");
			Path license = Files.writeString(plugin.resolve("LICENSE.txt"), "license");

			FolderRetention retention = complete(installation, active);

			requireThat(retention.notices(), "notices").isEmpty();
			for (Path leftover : List.of(launchers, plugin.resolve("client"), manifest.getParent(), license))
				requireThat(Files.exists(leftover, NOFOLLOW_LINKS), leftover.toString()).isFalse();
			requireThat(active.resolve("bin/VERSION"), "active").isRegularFile();
			requireThat(replaced.resolve("bin/VERSION"), "replaced").isRegularFile();
		}
	}

	/**
	 * A symbolic link in the plugin's folder is left alone, and what it points to stays.
	 *
	 * @throws IOException if the fixture cannot be created or inspected
	 */
	@Test
	public void leavesLinks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(testDirectory());
			ClaudeInstallation installation = new ClaudeInstallation(home);
			Path active = writeVersionFolder(installation.versionFolder(ACTIVE));
			writeVersionFolder(installation.versionFolder(REPLACED));
			Path outside = Files.createDirectories(home.resolve("outside"));
			Files.writeString(outside.resolve("keep"), "user file");
			Path link = Files.createSymbolicLink(installation.versionFolders().resolve("linked"), outside);

			FolderRetention retention = complete(installation, active);

			requireThat(retention.notices(), "notices").isEmpty();
			requireThat(Files.isSymbolicLink(link), "link").isTrue();
			requireThat(Files.readString(outside.resolve("keep")), "outside").isEqualTo("user file");
		}
	}

	/**
	 * Completes an install of {@link #ACTIVE}, which replaced {@link #REPLACED}, by an installer that runs from the
	 * active version's folder.
	 *
	 * @param installation the harness installation
	 * @param running      the folder of the running installer
	 * @return the folders that were left
	 * @throws IOException if the harness lock or the installer's state cannot be used
	 */
	private static FolderRetention complete(ClaudeInstallation installation, Path running) throws IOException
	{
		return new InstallCompletion(new Integration(Platform.current()), installation).completeLocked(ACTIVE,
			Optional.of(REPLACED), running, Clock.systemUTC(), _ -> () ->
			{
			});
	}

	/**
	 * Writes a version folder that states its version, as an install of that version leaves it.
	 *
	 * @param folder the folder
	 * @return the folder
	 * @throws IOException if the folder cannot be written
	 */
	private static Path writeVersionFolder(Path folder) throws IOException
	{
		Files.writeString(Files.createDirectories(folder.resolve("bin")).resolve("VERSION"),
			folder.getFileName().toString());
		return folder;
	}

	/**
	 * Creates a directory for one test's harness home.
	 *
	 * @return the directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path testDirectory() throws IOException
	{
		Path base = Path.of(System.getProperty("cat.installer.test.directory"));
		Files.createDirectories(base);
		return Files.createTempDirectory(base, "old-layout-").toRealPath();
	}
}

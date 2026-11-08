package com.holdmyspot.cat.common.installer.engine.test;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Describes a generic harness, {@code harness-a} unless a test names another, as {@link RecordingIntegration}
 * installs CAT into it: the harness reports the version that it was last asked to install, and the installation is
 * consistent only when that version's folder holds the plugin of that version. A marketplace that the harness left
 * without a reported version is an inconsistent installation. The plugin activation contract checks this behavior on
 * this provider and on the Claude Code and Codex activations.
 */
public class RegisteredProvider implements PluginActivation
{
	private final String harness;
	private final List<Path> companions = new ArrayList<>();

	/**
	 * Creates the provider of {@code harness-a}.
	 */
	public RegisteredProvider()
	{
		this("harness-a");
	}

	/**
	 * Creates the provider.
	 *
	 * @param harness the harness
	 */
	public RegisteredProvider(String harness)
	{
		this.harness = harness;
	}

	/**
	 * Returns the version folders whose companion files the installer asked this provider to install, in order.
	 *
	 * @return the version folders
	 */
	public List<Path> companions()
	{
		return List.copyOf(companions);
	}

	/**
	 * Records the version folder; the generic harness has no companion files.
	 */
	@Override
	public void installCompanions(Path home, Path versionFolder)
	{
		companions.add(versionFolder);
	}

	@Override
	public String harness()
	{
		return harness;
	}

	@Override
	public String pluginManifest()
	{
		return "." + harness + "/plugin.json";
	}

	@Override
	public InstalledState installed(Path home) throws IOException
	{
		Path registered = RecordingIntegration.registeredVersion(home);
		if (!Files.isRegularFile(registered, NOFOLLOW_LINKS))
		{
			boolean absent = Files.notExists(RecordingIntegration.layout(harness, home).marketplaceRoot(),
				NOFOLLOW_LINKS);
			return new InstalledState(Optional.empty(), absent);
		}
		String version = Files.readString(registered).strip();
		PluginVersion plugin;
		try
		{
			plugin = PluginVersion.parse(version);
		}
		catch (IllegalArgumentException _)
		{
			// A version without a build has no version folder
			return new InstalledState(Optional.of(version), false);
		}
		Path folder = RecordingIntegration.layout(harness, home).versionFolder(plugin);
		Path stated = folder.resolve(PluginDirectory.VERSION);
		boolean consistent = Files.isRegularFile(stated, NOFOLLOW_LINKS) &&
			Files.readString(stated).strip().equals(version);
		return new InstalledState(Optional.of(version), consistent);
	}

	@Override
	public Path migrationVersionFile(Path home)
	{
		return RecordingIntegration.layout(harness, home).dataDirectory().resolve("migration-version");
	}

	@Override
	public List<Path> removalPaths(Path home)
	{
		RecordingIntegration.Layout layout = RecordingIntegration.layout(harness, home);
		return List.of(layout.marketplaceRoot(), layout.versionFolders().getParent(),
			RecordingIntegration.registeredVersion(home));
	}

	@Override
	public Path dataDirectory(Path home)
	{
		return RecordingIntegration.layout(harness, home).dataDirectory();
	}
}

package com.holdmyspot.cat.common.installer.engine.test;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.ActivationStep;
import com.holdmyspot.cat.common.installer.activation.InstalledState;
import com.holdmyspot.cat.common.installer.activation.PluginActivation;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Describes the generic harness {@code harness-a} as {@link RecordingIntegration} installs CAT into it: the harness
 * reports the version that it was last asked to install, and the installation is consistent only when that version's
 * folder holds the plugin of that version.
 */
public class RegisteredProvider implements PluginActivation
{
	/**
	 * Creates the provider.
	 */
	public RegisteredProvider()
	{
	}

	@Override
	public String harness()
	{
		return "harness-a";
	}

	@Override
	public String pluginManifest()
	{
		return ".harness-a/plugin.json";
	}

	@Override
	public InstalledState installed(Path home) throws IOException
	{
		Path registered = RecordingIntegration.registeredVersion(home);
		if (!Files.isRegularFile(registered, NOFOLLOW_LINKS))
			return new InstalledState(Optional.empty(), true);
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
		Path folder = new RecordingIntegration.Layout(home).versionFolder(plugin);
		Path stated = folder.resolve(PluginDirectory.VERSION);
		boolean consistent = Files.isRegularFile(stated, NOFOLLOW_LINKS) &&
			Files.readString(stated).strip().equals(version);
		return new InstalledState(Optional.of(version), consistent);
	}

	/**
	 * Refuses, because the harness installs each version itself.
	 */
	@Override
	public List<ActivationStep> plan(Path home, String newVersion)
	{
		throw new UnsupportedOperationException("The harness installs each version itself");
	}

	@Override
	public Path migrationVersionFile(Path home)
	{
		return new RecordingIntegration.Layout(home).dataDirectory().resolve("migration-version");
	}

	/**
	 * Refuses, because the harness installs each version itself.
	 */
	@Override
	public List<ActivationStep> installPlan(Path home, String newVersion)
	{
		throw new UnsupportedOperationException("The harness installs each version itself");
	}

	@Override
	public List<Path> removalPaths(Path home)
	{
		RecordingIntegration.Layout layout = new RecordingIntegration.Layout(home);
		return List.of(layout.marketplaceRoot(), layout.versionFolders().getParent(),
			RecordingIntegration.registeredVersion(home));
	}

	@Override
	public Path dataDirectory(Path home)
	{
		return new RecordingIntegration.Layout(home).dataDirectory();
	}
}

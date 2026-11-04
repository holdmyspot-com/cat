package com.holdmyspot.cat.common.installer.engine;

import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;

/**
 * Reads the version that a harness reports for its CAT plugin. A built plugin states its full plugin version, such as
 * {@code 1.0+b0123456789ab}, which names, plans and records the plugin. A plugin installed before builds were stated
 * states only its release, such as {@code 1.0}. Release-level decisions, such as release choice, downgrades and data
 * migrations, use the release of either form.
 */
final class InstalledVersions
{
	/**
	 * Prevents instantiation.
	 */
	private InstalledVersions()
	{
	}

	/**
	 * Returns the release of a plugin's version.
	 *
	 * @param version a full plugin version, such as {@code 1.0+b0123456789ab}, or a release version, such as
	 *                {@code 1.0}
	 * @return the release
	 * @throws NullPointerException if {@code version} is null
	 * @throws TrustException       if {@code version} is neither a full plugin version nor a release version
	 */
	static ReleaseVersion release(String version) throws TrustException
	{
		if (!version.contains(PluginVersion.BUILD_SEPARATOR))
			return ReleaseVersion.parse(version);
		PluginVersion plugin;
		try
		{
			plugin = PluginVersion.parse(version);
		}
		catch (IllegalArgumentException e)
		{
			throw new TrustException(TrustException.Reason.MALFORMED_VERSION, e.getMessage());
		}
		return ReleaseVersion.parse(plugin.release());
	}
}

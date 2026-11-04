package com.holdmyspot.cat.common.runner;

import java.nio.file.Path;

/**
 * Names the installation-written startup cache of a CAT plugin directory.
 *
 * <p>
 * The cache is a Java ahead-of-time cache that lets the plugin's programs start faster. Java accepts it only for the
 * exact module path that it was trained with, which is the plugin's {@code client/modules} folder at the plugin's own
 * location, so it is valid only where it was written. The installation that places a plugin directory writes it there;
 * release archives, copies of a plugin directory and the plugin's packaged identity leave out its folder. A plugin's
 * launchers use the cache only when the file exists.
 */
public final class StartupCache
{
	/**
	 * The folder that holds the cache, relative to the plugin directory.
	 */
	public static final Path DIRECTORY = Path.of("client/aot");
	/**
	 * The cache file, relative to the plugin directory.
	 */
	public static final Path FILE = DIRECTORY.resolve("session-start.aot");

	/**
	 * Prevents construction of utility class instances.
	 */
	private StartupCache()
	{
	}
}

package com.holdmyspot.cat.common.runner;

import java.nio.file.Path;

/**
 * Names the installation-written startup cache of a CAT plugin directory.
 *
 * <p>
 * The cache is a Java ahead-of-time cache that lets the plugin's programs start faster. Java accepts it only for the
 * exact module path that it was trained with, which is the plugin's {@code bin/runtime/modules} folder at the
 * plugin's own location, so it is valid only where it was written. The installation that places a plugin directory
 * writes it there; release archives, copies of a plugin directory and the plugin's packaged identity leave out its
 * folder. A plugin's launchers use the cache only when the file exists.
 * <p>
 * The plugin describes how to train its cache in {@link #TRAINING}, a JSON object of format 1 whose members are
 * {@code arguments}, the arguments that the plugin's {@code bin/java} wrapper runs its session-start program
 * with; {@code plugin_root_variable}, the environment variable that names the plugin directory to that program; and
 * {@code input}, the text that the program reads from its standard input.
 */
public final class StartupCache
{
	/**
	 * The folder that holds the cache, relative to the plugin directory.
	 */
	public static final Path DIRECTORY = PluginDirectory.BIN.resolve("aot");
	/**
	 * The cache file, relative to the plugin directory.
	 */
	public static final Path FILE = DIRECTORY.resolve("session-start.aot");
	/**
	 * The description of how to train the cache, relative to the plugin directory.
	 */
	public static final Path TRAINING = PluginDirectory.BIN.resolve("startup-cache-training.json");

	/**
	 * Prevents construction of utility class instances.
	 */
	private StartupCache()
	{
	}
}

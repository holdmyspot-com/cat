package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.PortablePaths;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Finds the CAT plugin directory that holds the running code.
 *
 * <p>
 * Everything that a plugin runs lives in its {@link #BIN bin} folder: its launchers, its {@link #RUNTIME Java runtime}
 * with its application modules, its installer and the files that describe them. A plugin's launchers run its
 * application modules from {@link #MODULES}, on the Java runtime that the plugin's runtime binding names, so the plugin
 * directory is found from where a class was loaded rather than from the Java runtime.
 */
public final class PluginDirectory
{
	/**
	 * The folder, relative to the plugin directory, that holds everything that the plugin runs.
	 */
	public static final Path BIN = Path.of("bin");
	/**
	 * The folder, relative to the plugin directory, that holds the Java runtime of the plugin's release, together with
	 * the plugin's application modules.
	 */
	public static final Path RUNTIME = BIN.resolve("runtime");
	/**
	 * The folder, relative to the plugin directory, that holds the plugin's application modules.
	 */
	public static final Path MODULES = RUNTIME.resolve("modules");
	/**
	 * The launcher, relative to the plugin directory, that runs the plugin's installer on POSIX platforms and in Git
	 * Bash; the PowerShell launcher beside it adds {@code .ps1}.
	 */
	public static final Path INSTALLER = BIN.resolve("installer");
	/**
	 * The folder, relative to the plugin directory, that holds the installer's default policy.
	 */
	public static final Path INSTALLER_CONFIGURATION = BIN.resolve("conf/installer");
	/**
	 * The file, relative to the plugin directory, that holds the plugin's CAT version.
	 */
	public static final Path VERSION = BIN.resolve("VERSION");

	/**
	 * Returns the plugin directory whose {@link #MODULES} directory holds the module file of a class.
	 *
	 * @param type a class of a CAT application module
	 * @return the plugin directory, an absolute normalized path
	 * @throws NullPointerException  if {@code type} is null
	 * @throws IllegalStateException if the class was not loaded from a module file in a plugin's
	 *                               {@link #MODULES} directory. The message names where it was loaded from.
	 */
	public static Path of(Class<?> type)
	{
		requireThat(type, "type").isNotNull();
		CodeSource source = type.getProtectionDomain().getCodeSource();
		if (source == null || source.getLocation() == null)
		{
			throw new IllegalStateException(type.getName() + " was not loaded from a module file in a CAT plugin's " +
				PortablePaths.text(MODULES) + " directory.");
		}
		URL location = source.getLocation();
		Path module;
		try
		{
			if (!location.getProtocol().equals("file"))
				throw new IllegalStateException(notInPlugin(type, location.toString()));
			module = Path.of(location.toURI()).toAbsolutePath().normalize();
		}
		catch (URISyntaxException | IllegalArgumentException e)
		{
			throw new IllegalStateException(notInPlugin(type, location.toString()), e);
		}
		Path modules = module.getParent();
		if (!Files.isRegularFile(module) || modules == null || !modules.endsWith(MODULES))
			throw new IllegalStateException(notInPlugin(type, module.toString()));
		Path plugin = modules;
		for (int level = 0; level < MODULES.getNameCount(); ++level)
			plugin = plugin.getParent();
		return plugin;
	}

	/**
	 * Returns the message for a class that is not loaded from a plugin's module file.
	 *
	 * @param type     the class
	 * @param location where the class was loaded from
	 * @return the message
	 */
	private static String notInPlugin(Class<?> type, String location)
	{
		return type.getName() + " was loaded from " + location + ", which is not a module file in a CAT plugin's " +
			PortablePaths.text(MODULES) + " directory.";
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private PluginDirectory()
	{
	}
}

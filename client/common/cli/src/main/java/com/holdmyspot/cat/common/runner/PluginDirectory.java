package com.holdmyspot.cat.common.runner;

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
 * A plugin's launchers run its application modules from {@code <plugin>/client/modules}, on the Java runtime that the
 * plugin's runtime binding names, so the plugin directory is found from where a class was loaded rather than from
 * the Java runtime.
 */
public final class PluginDirectory
{
	/**
	 * Returns the plugin directory whose {@code client/modules} directory holds the module file of a class.
	 *
	 * @param type a class of a CAT application module
	 * @return the plugin directory, an absolute normalized path
	 * @throws NullPointerException  if {@code type} is null
	 * @throws IllegalStateException if the class was not loaded from a module file in a plugin's
	 *                               {@code client/modules} directory. The message names where it was loaded from.
	 */
	public static Path of(Class<?> type)
	{
		requireThat(type, "type").isNotNull();
		CodeSource source = type.getProtectionDomain().getCodeSource();
		if (source == null || source.getLocation() == null)
		{
			throw new IllegalStateException(type.getName() + " was not loaded from a module file in a CAT plugin's " +
				"client/modules directory.");
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
		if (!Files.isRegularFile(module) || modules == null || !modules.endsWith("client/modules"))
			throw new IllegalStateException(notInPlugin(type, module.toString()));
		return modules.getParent().getParent();
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
			"client/modules directory.";
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private PluginDirectory()
	{
	}
}

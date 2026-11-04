package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Names a generated launcher and the Java module and main class that it starts.
 *
 * @param name      the launcher's file name, without an extension
 * @param module    the module containing the entry point
 * @param mainClass the entry-point class
 */
public record Launcher(String name, String module, String mainClass)
{
	/**
	 * Validates the launcher configuration.
	 *
	 * @param name      the launcher's file name, without an extension
	 * @param module    the module containing the entry point
	 * @param mainClass the entry-point class
	 * @throws NullPointerException     if {@code name}, {@code module}, or {@code mainClass} are null
	 * @throws IllegalArgumentException if {@code name}, {@code module}, or {@code mainClass} are blank
	 */
	public Launcher
	{
		requireThat(name, "name").isNotBlank();
		requireThat(module, "module").isNotBlank();
		requireThat(mainClass, "mainClass").isNotBlank();
	}
}

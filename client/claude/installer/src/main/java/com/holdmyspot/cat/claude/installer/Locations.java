package com.holdmyspot.cat.claude.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Refuses symbolic links that could redirect the files that CAT installs in a Claude Code configuration directory.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class Locations
{
	/**
	 * Prevents instantiation.
	 */
	private Locations()
	{
	}

	/**
	 * Refuses every symlink from the home directory through a target, including dangling links.
	 *
	 * @param home   the absolute Claude Code configuration directory recorded in policy
	 * @param target the target below the configuration directory
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code home} is relative or {@code target} is not below it
	 * @throws IOException              if a path component is a symlink
	 */
	public static void checkLinks(Path home, Path target) throws IOException
	{
		requireThat(home, "home").isAbsolute();
		home = home.normalize();
		requireThat(target.normalize().startsWith(home), "target.normalize().startsWith(home)").isTrue();
		Path component = home;
		if (Files.isSymbolicLink(component))
			throw new IOException("CAT's path in Claude Code's configuration is a symbolic link: " + component);
		for (Path name : home.relativize(target.normalize()))
		{
			component = component.resolve(name);
			if (Files.isSymbolicLink(component))
				throw new IOException("CAT's path in Claude Code's configuration is a symbolic link: " + component);
		}
	}
}

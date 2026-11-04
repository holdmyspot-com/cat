package com.holdmyspot.cat.claude.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the files that CAT installs in a Claude Code configuration directory, refusing symbolic links that could
 * redirect them.
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
	 * Locates the directory marketplace that holds CAT, without checking links.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return the normalized marketplace root
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	public static Path marketplaceRoot(Path home)
	{
		requireThat(home, "home").isAbsolute();
		return home.normalize().resolve("plugins/cat-marketplace");
	}

	/**
	 * Locates the plugin directory inside the marketplace, which Claude Code loads in place.
	 *
	 * @param home the absolute Claude Code configuration directory recorded in policy
	 * @return the normalized plugin directory
	 * @throws NullPointerException     if {@code home} is null
	 * @throws IllegalArgumentException if {@code home} is relative
	 */
	public static Path pluginDirectory(Path home)
	{
		return marketplaceRoot(home).resolve("plugins/cat");
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

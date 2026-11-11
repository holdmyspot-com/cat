package com.holdmyspot.cat.common;

import java.nio.file.Path;
import java.util.StringJoiner;

/**
 * Relative paths written the same way on every operating system.
 * <p>
 * {@link Path#toString()} separates names with the platform's separator, which is {@code \} on Windows. Paths keep
 * the platform's form in memory and in messages for people. CAT writes a relative path with {@code /} only where
 * another process or platform reads the text: stored records and contracts, the text of a stored digest, and prompts
 * whose paths a model copies into a stored record. {@link Path#of(String, String...)} reads that text back on every
 * platform. A path that starts at a root names a file on one machine only, so it keeps the platform's form.
 */
public final class PortablePaths
{
	/**
	 * Returns a path as CAT writes it.
	 *
	 * @param path a path
	 * @return a relative path's names joined by {@code /}, the empty string for the empty path, or a path that starts
	 *   at a root as the platform writes it
	 * @throws NullPointerException if {@code path} is null
	 */
	public static String text(Path path)
	{
		if (path.getRoot() != null)
			return path.toString();
		StringJoiner joiner = new StringJoiner("/");
		for (Path name : path)
			joiner.add(name.toString());
		return joiner.toString();
	}

	/**
	 * Indicates whether a path starts at a root, which makes it name a file on one machine rather than a file relative
	 * to a project.
	 * <p>
	 * Check this, not {@link Path#isAbsolute()}, to refuse a path that must be relative. On Windows,
	 * {@code Path.of("/etc/hosts")} has a root but no drive, so {@code isAbsolute()} is {@code false} and the path
	 * would pass as relative.
	 *
	 * @param path a path
	 * @return {@code true} if the path has a root
	 * @throws NullPointerException if {@code path} is null
	 */
	public static boolean isRooted(Path path)
	{
		return path.getRoot() != null;
	}

	/**
	 * Prevents construction of this static utility.
	 */
	private PortablePaths()
	{
	}
}

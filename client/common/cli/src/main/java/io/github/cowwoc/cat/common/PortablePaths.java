package io.github.cowwoc.cat.common;

import java.nio.file.Path;
import java.util.StringJoiner;

/**
 * Relative paths written the same way on every operating system.
 * <p>
 * {@link Path#toString()} separates names with the platform's separator, which is {@code \} on Windows. CAT writes a
 * relative path into contracts, campaign records, prompts and messages with {@code /} instead, so that the text names
 * the same file on every platform and matches what models and users write. A path that starts at a root names a file
 * on one machine only, so it keeps the platform's form.
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
	 * Prevents construction of this static utility.
	 */
	private PortablePaths()
	{
	}
}

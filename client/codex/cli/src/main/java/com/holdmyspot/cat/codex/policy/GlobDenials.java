package com.holdmyspot.cat.codex.policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Stream;

/**
 * Turns a Codex {@code deny} glob pattern into the existing paths that it matches, as Codex's Linux sandbox does
 * when a session starts.
 * <p>
 * A path that is created later is not covered. Because CAT reads the policy again for each request, such a path is
 * covered from the next request on.
 */
public final class GlobDenials
{
	/**
	 * Prevents instantiation.
	 */
	private GlobDenials()
	{
	}

	/**
	 * Indicates whether a path pattern is a glob.
	 *
	 * @param pattern the pattern
	 * @return {@code true} if it holds {@code *}, {@code ?}, {@code [} or <code>{</code>
	 */
	public static boolean isGlob(String pattern)
	{
		return pattern.chars().anyMatch(character -> "*?[{".indexOf(character) >= 0);
	}

	/**
	 * Returns the existing paths that an absolute glob pattern matches.
	 *
	 * @param pattern  the pattern
	 * @param maxDepth the number of folder levels below the pattern's fixed part that {@code **} reaches
	 * @return the paths
	 * @throws IOException if a folder cannot be listed
	 */
	public static List<Path> expand(String pattern, int maxDepth) throws IOException
	{
		String[] segments = pattern.split("/", -1);
		StringBuilder fixed = new StringBuilder();
		int depth = 0;
		boolean inGlob = false;
		for (String segment : segments)
		{
			if (!inGlob && isGlob(segment))
				inGlob = true;
			if (inGlob)
			{
				if (segment.contains("**"))
					depth = maxDepth;
				else if (depth < maxDepth)
					++depth;
				continue;
			}
			if (!fixed.isEmpty())
				fixed.append('/');
			fixed.append(segment);
		}
		String prefix = fixed.toString();
		if (prefix.isEmpty())
			prefix = "/";
		Path base = Path.of(prefix);
		if (!Files.isDirectory(base))
			return List.of();
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
		try (Stream<Path> paths = Files.walk(base, depth))
		{
			return paths.filter(matcher::matches).toList();
		}
		catch (UncheckedIOException e)
		{
			throw e.getCause();
		}
	}
}

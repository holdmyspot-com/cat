package com.holdmyspot.cat.codex.policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
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
	 * Returns a pattern in the form that a file system's glob matcher reads as the pattern's folders.
	 * <p>
	 * Where the separator is a backslash, the matcher reads a backslash as the escape of the character after it, so the
	 * folders that a Windows path names would never match; the matcher takes a slash there instead.
	 *
	 * @param pattern   the pattern
	 * @param separator the file system's name separator
	 * @return the pattern that the matcher reads
	 */
	public static String matcherPattern(String pattern, String separator)
	{
		if (separator.equals("\\"))
			return pattern.replace('\\', '/');
		return pattern;
	}

	/**
	 * Returns the existing paths that an absolute glob pattern matches.
	 *
	 * @param pattern  the pattern
	 * @param maxDepth the number of folder levels below the pattern's fixed part that {@code **} reaches
	 * @return the paths
	 * @throws IOException              if a folder cannot be listed
	 * @throws IllegalArgumentException if the part of the pattern before its first wildcard is not a path that the file
	 *                                  system can name, or the pattern is not one that it can match
	 */
	public static List<Path> expand(String pattern, int maxDepth) throws IOException
	{
		String[] segments = pattern.split("/", -1);
		List<String> fixed = new ArrayList<>();
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
			fixed.add(segment);
		}
		// The empty first segment of a Linux or macOS path becomes its leading slash
		String prefix = String.join("/", fixed);
		if (prefix.isEmpty())
			prefix = "/";
		Path base = Path.of(prefix);
		if (!Files.isDirectory(base))
			return List.of();
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" +
			matcherPattern(pattern, FileSystems.getDefault().getSeparator()));
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

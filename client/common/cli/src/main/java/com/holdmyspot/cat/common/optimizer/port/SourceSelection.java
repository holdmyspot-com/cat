package com.holdmyspot.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.regex.PatternSyntaxException;

/**
 * The files of a fork that a staged source holds: each file at or below one of the roots, each file with one of the
 * names in any directory, and each file whose path one of the patterns matches.
 *
 * @param roots    paths relative to the project
 * @param names    file names without a directory, such as {@code AGENTS.md}
 * @param patterns glob patterns relative to the project, with {@code /} separators, in the syntax of
 *                 {@link FileSystems#getDefault()}'s {@code glob:} matcher
 */
public record SourceSelection(Set<Path> roots, Set<String> names, List<String> patterns)
{
	/**
	 * Creates a selection.
	 *
	 * @param roots    paths relative to the project
	 * @param names    file names without a directory, such as {@code AGENTS.md}
	 * @param patterns glob patterns relative to the project, with {@code /} separators, in the syntax of
	 *                 {@link FileSystems#getDefault()}'s {@code glob:} matcher
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if a root is absolute, a name is blank or contains {@code /} or {@code \}, or a
	 *                                  pattern starts with {@code /} or is not a valid glob
	 */
	public SourceSelection
	{
		requireThat(roots, "roots").isNotNull();
		requireThat(names, "names").isNotNull();
		requireThat(patterns, "patterns").isNotNull();
		Set<Path> normalized = new TreeSet<>();
		for (Path root : roots)
		{
			requireThat(root.isAbsolute(), "root.isAbsolute()").withContext(root, "root").isFalse();
			normalized.add(root.normalize());
		}
		for (String name : names)
		{
			requireThat(name, "name").isNotBlank();
			if (name.contains("/") || name.contains("\\"))
				throw new IllegalArgumentException("The name " + name + " contains a directory separator");
		}
		for (String pattern : patterns)
		{
			if (pattern.startsWith("/"))
				throw new IllegalArgumentException("The pattern " + pattern + " is absolute");
			try
			{
				FileSystems.getDefault().getPathMatcher("glob:" + pattern);
			}
			catch (PatternSyntaxException e)
			{
				throw new IllegalArgumentException("The pattern " + pattern + " is not a valid glob: " +
					e.getDescription(), e);
			}
		}
		roots = Set.copyOf(normalized);
		names = Set.copyOf(names);
		patterns = List.copyOf(patterns);
	}

	/**
	 * Returns the selection with more patterns.
	 *
	 * @param added the patterns to add
	 * @return a selection with this selection's roots and names, and its patterns followed by {@code added}
	 * @throws NullPointerException     if {@code added} is null
	 * @throws IllegalArgumentException if an added pattern starts with {@code /} or is not a valid glob
	 */
	public SourceSelection withPatterns(List<String> added)
	{
		requireThat(added, "added").isNotNull();
		if (added.isEmpty())
			return this;
		List<String> combined = new ArrayList<>(patterns);
		combined.addAll(added);
		return new SourceSelection(roots, names, combined);
	}

	/**
	 * Indicates whether the selection holds a file.
	 *
	 * @param file a path relative to the project
	 * @return {@code true} if {@code file} is at or below a root, has one of the names, or matches a pattern
	 * @throws NullPointerException if {@code file} is null
	 */
	public boolean selects(Path file)
	{
		requireThat(file, "file").isNotNull();
		for (Path root : roots)
		{
			if (file.startsWith(root))
				return true;
		}
		Path name = file.getFileName();
		if (name != null && names.contains(name.toString()))
			return true;
		for (String pattern : patterns)
		{
			PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
			if (matcher.matches(file))
				return true;
		}
		return false;
	}

	/**
	 * Returns a text that two selections share exactly when they hold the same files of every fork.
	 *
	 * @return the sorted roots, the sorted names and the patterns, one per line
	 */
	public String describe()
	{
		StringJoiner lines = new StringJoiner("\n");
		for (Path root : new TreeSet<>(roots))
			lines.add("root " + PortablePaths.text(root));
		for (String name : new TreeSet<>(names))
			lines.add("name " + name);
		for (String pattern : new TreeSet<>(patterns))
			lines.add("pattern " + pattern);
		return lines.toString();
	}
}

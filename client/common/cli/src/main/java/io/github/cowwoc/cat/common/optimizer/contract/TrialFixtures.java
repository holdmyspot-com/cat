package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The files that trials need but Git ignores.
 *
 * @param ignoredPaths the project-relative paths of ignored files or directories to stage for every trial
 */
public record TrialFixtures(List<String> ignoredPaths)
{
	/**
	 * Creates the fixtures.
	 *
	 * @param ignoredPaths the project-relative paths of ignored files or directories to stage for every trial
	 * @throws NullPointerException if {@code ignoredPaths} is null
	 * @throws IllegalArgumentException if a path is blank, absolute, not normalized, escapes the project, or is listed
	 *   twice
	 */
	public TrialFixtures
	{
		requireThat(ignoredPaths, "ignoredPaths").isNotNull();
		ignoredPaths = List.copyOf(ignoredPaths);

		Set<String> seen = new HashSet<>();
		for (String path : ignoredPaths)
		{
			requireThat(path, "path").isNotBlank();
			// Each segment must be a plain name, so a path cannot start at the file-system root, climb out of the
			// project, or spell one location two ways.
			for (String segment : path.split("/", -1))
			{
				if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.contains("\\"))
				{
					throw new IllegalArgumentException("Fixture path '" + path + "' must be a normalized path that is " +
						"relative to the project and uses / as the separator");
				}
			}
			if (!seen.add(path))
				throw new IllegalArgumentException("Fixture path '" + path + "' is listed twice");
		}
	}
}

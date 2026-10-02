package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Project files and directories that Git ignores but trials need copied into their project before they run.
 *
 * @param ignoredPaths the project-relative paths of ignored files or directories to copy into every trial project
 */
public record TrialFixtures(List<String> ignoredPaths)
{
	/**
	 * Creates the fixtures.
	 *
	 * @param ignoredPaths the project-relative paths of ignored files or directories to copy into every trial project
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
			if (!ProjectPaths.isNormalized(path))
			{
				throw new IllegalArgumentException("Fixture path '" + path + "' must be a normalized path that is " +
					"relative to the project and uses / as the separator");
			}
			if (!seen.add(path))
				throw new IllegalArgumentException("Fixture path '" + path + "' is listed twice");
		}
	}
}

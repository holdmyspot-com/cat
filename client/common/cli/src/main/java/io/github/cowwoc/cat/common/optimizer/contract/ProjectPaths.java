package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * The project-relative paths that a contract names.
 */
final class ProjectPaths
{
	/**
	 * Indicates whether a path is normalized, relative to the project, and uses {@code /} as the separator. Each
	 * segment must be a plain name, so the path cannot start at the file-system root, climb out of the project, or
	 * spell one location two ways.
	 *
	 * @param path the path
	 * @return {@code true} if the path is a normalized project-relative path
	 */
	static boolean isNormalized(String path)
	{
		for (String segment : path.split("/", -1))
		{
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.contains("\\"))
				return false;
		}
		return true;
	}

	/**
	 * Prevents instantiation.
	 */
	private ProjectPaths()
	{
	}
}

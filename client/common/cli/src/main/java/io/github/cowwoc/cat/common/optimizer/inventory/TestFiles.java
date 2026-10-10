package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * The regression scenario files that a delivery writes beside the candidate, planned against the fork baseline like
 * the candidate's own files.
 *
 * @param files              the content of each file, keyed by its path relative to the base worktree
 * @param createdDirectories the directories, relative to the base worktree, that the delivery creates; each must not
 *                           exist yet
 */
public record TestFiles(Map<Path, FileContent> files, Set<Path> createdDirectories)
{
	/**
	 * No test files.
	 */
	public static final TestFiles NONE = new TestFiles(Map.of(), Set.of());

	/**
	 * Creates test files.
	 *
	 * @param files              the content of each file, keyed by its path relative to the base worktree
	 * @param createdDirectories the directories, relative to the base worktree, that the delivery creates; each must
	 *                           not exist yet
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if a path is absolute, or a created directory holds none of the files
	 */
	public TestFiles
	{
		files = Map.copyOf(files);
		createdDirectories = Set.copyOf(createdDirectories);
		for (Path path : files.keySet())
			requireThat(path.isAbsolute(), "files.isAbsolute(" + path + ")").isFalse();
		for (Path directory : createdDirectories)
		{
			requireThat(directory.isAbsolute(), "createdDirectories.isAbsolute(" + directory + ")").isFalse();
			if (files.keySet().stream().noneMatch(path -> path.startsWith(directory)))
			{
				throw new IllegalArgumentException("The created directory " + directory + " holds none of the files: " +
					files.keySet());
			}
		}
	}
}

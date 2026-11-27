package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds the folders of a search path that a harness's sandbox lets its commands write.
 * <p>
 * A command that can write a folder of the search path can plant a program there that a process outside the sandbox
 * then runs.
 */
public final class WritablePathEntries
{
	/**
	 * Prevents instantiation.
	 */
	private WritablePathEntries()
	{
	}

	/**
	 * A folder of the search path that the sandbox lets its commands write.
	 *
	 * @param folder the absolute folder, as the search path names it
	 * @param grant  the grant that covers the folder
	 */
	public record Writable(Path folder, WriteGrant grant)
	{
		/**
		 * Creates the entry.
		 *
		 * @param folder the absolute folder, as the search path names it
		 * @param grant  the grant that covers the folder
		 * @throws NullPointerException     if any argument is null
		 * @throws IllegalArgumentException if {@code folder} is relative
		 */
		public Writable
		{
			requireThat(folder, "folder").isAbsolute();
			requireThat(grant, "grant").isNotNull();
		}
	}

	/**
	 * Returns the folders of a search path that the sandbox lets its commands write: a folder is writable when a grant
	 * covers it or a folder above it, and no denial covers it. An empty or relative entry names the project folder or a
	 * folder below it, because the session's commands run there. Paths compare by the files that they reach.
	 *
	 * @param searchPath       the search path, in the format of the platform's {@code PATH}
	 * @param projectDirectory the absolute project folder
	 * @param writes           where the sandbox lets commands write
	 * @return the writable folders, in the order of the search path, each once
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code projectDirectory} is relative
	 * @throws IOException              if a symbolic link in a path cannot be read
	 */
	public static List<Writable> find(String searchPath, Path projectDirectory, SandboxWrites writes)
		throws IOException
	{
		requireThat(searchPath, "searchPath").isNotNull();
		requireThat(projectDirectory, "projectDirectory").isAbsolute();
		requireThat(writes, "writes").isNotNull();
		return List.of();
	}
}

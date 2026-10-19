package com.holdmyspot.cat.common.git.replay;

import com.holdmyspot.cat.common.git.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copies a file from a Git tree into a new manifest-local reviewer workspace file.
 */
final class TreeFiles
{
	private final Git git;

	/**
	 * Creates a Git-tree file access boundary.
	 *
	 * @param git Git access for the replay repository
	 */
	TreeFiles(Git git)
	{
		this.git = git;
	}

	/**
	 * Copies one repository-relative file from a Git tree to a new manifest-local workspace file.
	 *
	 * @param manifestFile the manifest that owns the workspace file
	 * @param targetTree the commit or tree containing the source file
	 * @param path the repository-relative path to copy
	 * @param outputFile the new manifest-relative destination file
	 * @throws IOException if the path or output is unsafe, or Git cannot read the selected file
	 */
	void copy(Path manifestFile, String targetTree, String path, Path outputFile) throws IOException
	{
		if (path == null || path.isBlank() || path.startsWith("/") || path.contains(".."))
		{
			throw new IOException("Topic-replay path to copy must be non-blank, repository-relative, and not contain '..': " +
				path);
		}
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		Path output = outputFile.toAbsolutePath().normalize();
		if (!output.startsWith(directory) || Files.exists(output))
		{
			throw new IOException("Topic-replay copied file must be new and below the manifest directory: " + outputFile);
		}
		Files.createDirectories(output.getParent());
		Files.write(output, git.bytes("show", targetTree + ":" + path));
	}
}

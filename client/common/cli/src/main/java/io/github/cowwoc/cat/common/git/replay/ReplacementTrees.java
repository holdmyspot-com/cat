package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates a Git tree that replaces one existing file with reviewer-prepared manifest-local contents.
 */
final class ReplacementTrees
{
	private final Git git;
	private final ManifestStore<?> manifests;

	/**
	 * Creates a replacement-tree construction boundary.
	 *
	 * @param git Git access for the replay repository
	 * @param manifests the owner of manifest-local temporary indexes
	 */
	ReplacementTrees(Git git, ManifestStore<?> manifests)
	{
		this.git = git;
		this.manifests = manifests;
	}

	/**
	 * Writes the tree identifier produced by replacing one path in an existing tree with reviewed contents.
	 *
	 * @param manifestFile the manifest that owns the workspace files
	 * @param baseTree the commit or tree to update
	 * @param path the repository-relative path to replace
	 * @param replacementFile the manifest-relative regular file containing the replacement contents
	 * @param outputFile the new manifest-relative file that receives the resulting tree identifier
	 * @throws IOException if an input is unsafe or Git cannot create the replacement tree
	 */
	void write(Path manifestFile, String baseTree, String path, Path replacementFile, Path outputFile)
		throws IOException
	{
		if (path == null || path.isBlank() || path.startsWith("/") || path.contains(".."))
		{
			throw new IOException("Topic-replay replacement-tree path must be non-blank, repository-relative, and not " +
				"contain '..': " + path);
		}
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		Path replacement = replacementFile.toAbsolutePath().normalize();
		Path output = outputFile.toAbsolutePath().normalize();
		if (!replacement.startsWith(directory) || !output.startsWith(directory) || Files.exists(output))
		{
			throw new IOException("Topic-replay replacement-tree files must be below the manifest directory and the output " +
				"must be new");
		}
		String beforeTree = git.lines("rev-parse", baseTree + "^{tree}").getFirst();
		Path index = manifests.createTemporaryFile(manifestFile, ".index");
		try
		{
			Files.deleteIfExists(index);
			git.lines(index, "read-tree", beforeTree);
			List<String> entry = git.lines(index, "ls-files", "--stage", "--", path);
			if (entry.isEmpty())
				throw new IOException("Topic-replay replacement-tree base does not contain the path: " + path);
			String mode = entry.getFirst().split("\\s+", 2)[0];
			String blob = git.lines("hash-object", "-w", replacement.toString()).getFirst();
			git.lines(index, "update-index", "--cacheinfo", mode + "," + blob + "," + path);
			Files.createDirectories(output.getParent());
			Files.writeString(output, git.lines(index, "write-tree").getFirst() + "\n");
		}
		finally
		{
			Files.deleteIfExists(index);
		}
	}
}

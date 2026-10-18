package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Produces a reviewer-resolvable three-way merge candidate for one source path and earlier tree version.
 */
final class MergeCandidates
{
	private final Git git;
	private final ManifestStore<?> manifests;

	/**
	 * Creates a three-way merge-candidate boundary.
	 *
	 * @param git Git access for the replay repository
	 * @param manifests the owner of manifest-local temporary files
	 */
	MergeCandidates(Git git, ManifestStore<?> manifests)
	{
		this.git = git;
		this.manifests = manifests;
	}

	/**
	 * Writes Git's proposed merge of a source-path change into an earlier path version for reviewer approval.
	 *
	 * @param manifestFile the manifest that owns the output and temporary files
	 * @param manifest the replay facts that declare the source
	 * @param source the source commit that changed the path
	 * @param targetTree the commit or tree containing the earlier path version
	 * @param path the repository-relative changed path to merge
	 * @param outputFile the new manifest-relative file that receives the proposed result
	 * @throws IOException if an input is unsafe or Git cannot read or merge the path versions
	 */
	void write(Path manifestFile, BatchManifest manifest, String source, String targetTree, String path,
		Path outputFile) throws IOException
	{
		if (path == null || path.isBlank() || path.startsWith("/") || path.contains(".."))
		{
			throw new IOException("Topic-replay path to merge must be non-blank, repository-relative, and not contain " +
				"'..': " + path);
		}
		SourceSnapshot snapshot = manifest.sources().stream().filter(candidate -> candidate.hash().equals(source)).
			findFirst().orElseThrow(() -> new IOException("Topic-replay manifest does not contain source commit: " + source));
		if (git.lines("diff-tree", "--root", "--no-commit-id", "--no-renames", "--name-only", "-r", source, "--",
			path).isEmpty())
		{
			throw new IOException("Topic-replay source commit does not change the selected path: " + path);
		}
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		Path output = outputFile.toAbsolutePath().normalize();
		if (!output.startsWith(directory) || Files.exists(output))
		{
			throw new IOException("Topic-replay merge candidate must be new and below the manifest directory: " + outputFile);
		}
		Path earlierFile = manifests.createTemporaryFile(manifestFile, ".earlier");
		Path parentFile = manifests.createTemporaryFile(manifestFile, ".parent");
		Path sourceFile = manifests.createTemporaryFile(manifestFile, ".source");
		try
		{
			Files.write(earlierFile, git.bytes("show", targetTree + ":" + path));
			Files.write(parentFile, git.bytes("show", snapshot.parentTree() + ":" + path));
			Files.write(sourceFile, git.bytes("show", source + ":" + path));
			Files.createDirectories(output.getParent());
			Files.write(output, git.mergeFile(earlierFile, parentFile, sourceFile));
		}
		finally
		{
			Files.deleteIfExists(earlierFile);
			Files.deleteIfExists(parentFile);
			Files.deleteIfExists(sourceFile);
		}
	}
}

package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates and verifies a manifest-local patch that adapts one source path to an earlier tree.
 */
final class TargetPatches
{
	private final Git git;
	private final ManifestStore<?> manifests;
	private final PatchApplication patchApplication;

	/**
	 * Creates a target-adapted patch construction boundary.
	 *
	 * @param git Git access for the replay repository
	 * @param manifests the owner of manifest-local temporary indexes
	 * @param patchApplication the boundary that verifies the written patch's resulting tree
	 */
	TargetPatches(Git git, ManifestStore<?> manifests,
		PatchApplication patchApplication)
	{
		this.git = git;
		this.manifests = manifests;
		this.patchApplication = patchApplication;
	}

	/**
	 * Captures an immutable patch that replaces one path in an earlier tree with reviewer-prepared contents.
	 *
	 * @param manifestFile the manifest that owns the patch and replacement file
	 * @param manifest the replay facts that declare the source
	 * @param source the source commit that must have changed the path
	 * @param patchFile the new manifest-relative patch file
	 * @param targetTree the commit or tree whose path version the patch updates
	 * @param path the repository-relative path to adapt
	 * @param replacementFile the manifest-relative file containing the intended target version
	 * @throws IOException if an input is unsafe, the source did not change the path, or Git cannot create and verify the
	 *         target-relative patch
	 */
	void capture(Path manifestFile, BatchManifest manifest, String source, Path patchFile, String targetTree,
		String path, Path replacementFile) throws IOException
	{
		if (path == null || path.isBlank() || path.startsWith("/") || path.contains(".."))
		{
			throw new IOException("Topic-replay path for an earlier file version must be non-blank, repository-relative, " +
				"and not contain '..': " + path);
		}
		if (manifest.sources().stream().noneMatch(candidate -> candidate.hash().equals(source)))
			throw new IOException("Topic-replay manifest does not contain source commit: " + source);
		if (git.lines("diff-tree", "--root", "--no-commit-id", "--no-renames", "--name-only", "-r", source, "--",
			path).isEmpty())
		{
			throw new IOException("Topic-replay source commit does not change the selected path: " + path);
		}
		Path manifestDirectory = manifestFile.toAbsolutePath().normalize().getParent();
		Path target = patchFile.toAbsolutePath().normalize();
		Path replacement = replacementFile.toAbsolutePath().normalize();
		if (!target.startsWith(manifestDirectory) || Files.exists(target))
		{
			throw new IOException("Topic-replay patch for an earlier file version must be new and below the manifest " +
				"directory: " + patchFile);
		}
		if (!replacement.startsWith(manifestDirectory))
		{
			throw new IOException("Topic-replay replacement file for an earlier file version must be below the manifest " +
				"directory: " + replacementFile);
		}
		String beforeTree = git.lines("rev-parse", targetTree + "^{tree}").getFirst();
		Path index = manifests.createTemporaryFile(manifestFile, ".index");
		try
		{
			Files.deleteIfExists(index);
			git.lines(index, "read-tree", beforeTree);
			List<String> entry = git.lines(index, "ls-files", "--stage", "--", path);
			if (entry.isEmpty())
				throw new IOException("Topic-replay target tree does not contain the adapted path: " + path);
			String mode = entry.getFirst().split("\\s+", 2)[0];
			String blob = git.lines("hash-object", "-w", replacement.toString()).getFirst();
			git.lines(index, "update-index", "--cacheinfo", mode + "," + blob + "," + path);
			String afterTree = git.lines(index, "write-tree").getFirst();
			byte[] patch = git.bytes("diff", "--binary", beforeTree, afterTree, "--", path);
			if (patch.length == 0)
				throw new IOException("Topic-replay replacement for an earlier file version does not change " + path);
			Files.createDirectories(target.getParent());
			Files.write(target, patch);
			String verifiedTree = patchApplication.apply(manifestFile, target, beforeTree);
			if (!verifiedTree.equals(afterTree))
			{
				throw new IOException("Topic-replay patch for an earlier file version did not reproduce its declared path " +
					"version: " + path);
			}
		}
		finally
		{
			Files.deleteIfExists(index);
		}
	}
}

package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Combines a selected source patch with an earlier tree and verifies the resulting manifest-local patch.
 */
final class ThreeWayPatches
{
	private final Git git;
	private final ManifestStore<?> manifests;
	private final PatchApplication patchApplication;

	/**
	 * Creates a three-way patch construction boundary.
	 *
	 * @param git Git access for the replay repository
	 * @param manifests the owner of manifest-local temporary files
	 * @param patchApplication the boundary that verifies the written patch's tree
	 */
	ThreeWayPatches(Git git, ManifestStore<?> manifests,
		PatchApplication patchApplication)
	{
		this.git = git;
		this.manifests = manifests;
		this.patchApplication = patchApplication;
	}

	/**
	 * Combines one selected source patch with an earlier tree and writes the resulting patch relative to that tree.
	 *
	 * @param manifestFile the manifest that owns the new patch
	 * @param source the source commit that supplied the patch
	 * @param patchFile the new manifest-relative patch file
	 * @param targetTree the commit or tree whose version the patch updates
	 * @param sourcePatchBytes the selected source patch bytes
	 * @throws IOException if an input is unsafe or Git cannot combine and verify the patch
	 */
	void capture(Path manifestFile, String source, Path patchFile, String targetTree, byte[] sourcePatchBytes)
		throws IOException
	{
		Path directory = manifestFile.toAbsolutePath().normalize().getParent();
		Path target = patchFile.toAbsolutePath().normalize();
		if (!target.startsWith(directory) || Files.exists(target))
		{
			throw new IOException("Topic-replay patch for an earlier file version must be new and below the manifest " +
				"directory: " + patchFile);
		}
		String beforeTree = git.lines("rev-parse", targetTree + "^{tree}").getFirst();
		Path index = manifests.createTemporaryFile(manifestFile, "git-topic-replay-", ".index");
		Path sourcePatch = manifests.createTemporaryFile(manifestFile, "git-topic-replay-", ".patch");
		try
		{
			Files.deleteIfExists(index);
			Files.write(sourcePatch, sourcePatchBytes);
			git.lines(index, "read-tree", beforeTree);
			try
			{
				git.lines(index, "apply", "--cached", "--3way", sourcePatch.toString());
			}
			catch (IOException e)
			{
				throw new IOException("CAT could not combine source commit " + source +
					" with the declared earlier file version. Prepare the intended version of each changed file and run " +
					"capture-target-adapted-path-patch.", e);
			}
			String afterTree = git.lines(index, "write-tree").getFirst();
			byte[] patch = git.bytes("diff", "--binary", beforeTree, afterTree);
			if (patch.length == 0)
			{
				throw new IOException("Topic-replay source commit does not change the declared earlier file version: " +
					source);
			}
			Files.createDirectories(target.getParent());
			Files.write(target, patch);
			String verifiedTree = patchApplication.apply(manifestFile, target, beforeTree);
			if (!verifiedTree.equals(afterTree))
				throw new IOException("Topic-replay patch for an earlier file version did not reproduce its combined result");
		}
		finally
		{
			Files.deleteIfExists(index);
			Files.deleteIfExists(sourcePatch);
		}
	}
}

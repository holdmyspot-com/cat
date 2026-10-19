package com.holdmyspot.cat.common.git.replay;

import com.holdmyspot.cat.common.git.Git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Selects source hunks or paths and delegates their target-tree adaptation to {@link ThreeWayPatches}.
 */
final class AdaptedSourcePatches
{
	private final Git git;
	private final SourceHunks sourceHunks;
	private final ThreeWayPatches threeWayPatches;

	/**
	 * Creates an adapted source-patch selection boundary.
	 *
	 * @param git Git access for the replay repository
	 * @param sourceHunks the owner of hunk extraction
	 * @param threeWayPatches the owner of target-tree adaptation and verification
	 */
	AdaptedSourcePatches(Git git, SourceHunks sourceHunks, ThreeWayPatches threeWayPatches)
	{
		this.git = git;
		this.sourceHunks = sourceHunks;
		this.threeWayPatches = threeWayPatches;
	}

	/**
	 * Adapts one selected source hunk to an earlier target tree.
	 *
	 * @param manifestFile the manifest that owns the new patch
	 * @param manifest the replay facts that declare the source
	 * @param source the selected source commit
	 * @param patchFile the new manifest-relative patch file
	 * @param targetTree the commit or tree whose version the hunk updates
	 * @param path the selected repository-relative path
	 * @param hunkIndex the one-based hunk number within the path diff
	 * @throws IOException if the source or hunk is invalid, or adaptation fails
	 */
	void captureHunk(Path manifestFile, BatchManifest manifest, String source, Path patchFile, String targetTree,
		String path, int hunkIndex) throws IOException
	{
		validatePath(path, "Topic-replay selected source hunk path");
		byte[] sourcePatch = sourceHunks.extract(manifest, source, path, hunkIndex).getBytes(StandardCharsets.UTF_8);
		threeWayPatches.capture(manifestFile, source, patchFile, targetTree, sourcePatch);
	}

	/**
	 * Adapts selected source paths to an earlier target tree.
	 *
	 * @param manifestFile the manifest that owns the new patch
	 * @param manifest the replay facts that declare the source
	 * @param source the selected source commit
	 * @param patchFile the new manifest-relative patch file
	 * @param targetTree the commit or tree whose version the paths update
	 * @param paths the non-empty selected repository-relative paths
	 * @throws IOException if the source or paths are invalid, or adaptation fails
	 */
	void capturePaths(Path manifestFile, BatchManifest manifest, String source, Path patchFile, String targetTree,
		List<String> paths) throws IOException
	{
		for (String path : paths)
			validatePath(path, "Topic-replay selected source paths");
		SourceSnapshot snapshot = manifest.sources().stream().filter(candidate -> candidate.hash().equals(source)).
			findFirst().orElseThrow(() -> new IOException("Topic-replay manifest does not contain source commit: " + source));
		List<String> arguments = new ArrayList<>(List.of("diff", "--binary", snapshot.parentTree(), source, "--"));
		arguments.addAll(paths);
		threeWayPatches.capture(manifestFile, source, patchFile, targetTree,
			git.bytes(arguments.toArray(String[]::new)));
	}

	/**
	 * Rejects a path that cannot designate one repository-relative source path.
	 *
	 * @param path the selected source path
	 * @param description the operation-specific subject for a validation failure
	 * @throws IOException if {@code path} is blank, absolute, or contains a parent traversal
	 */
	private void validatePath(String path, String description) throws IOException
	{
		if (path == null || path.isBlank() || path.startsWith("/") || path.contains(".."))
			throw new IOException(description + " must be non-blank, repository-relative, and not contain '..': " + path);
	}
}

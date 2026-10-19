package com.holdmyspot.cat.common.git.replay;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates manifest-local patch artifacts and reviewer-visible tree files before output planning.
 */
final class PatchWorkflow
{
	private final ManifestStore<BatchManifest> manifests;
	private final SourcePatches sourcePatches;
	private final SourceHunks sourceHunks;
	private final AdaptedSourcePatches adaptedSourcePatches;
	private final TargetPatches targetPatches;
	private final TreeFiles treeFiles;
	private final ReplacementTrees replacementTrees;
	private final MergeCandidates mergeCandidates;

	/**
	 * Creates the patch-artifact workflow for one replay invocation.
	 *
	 * @param manifests the manifest persistence owner
	 * @param sourcePatches the whole-source and path patch owner
	 * @param sourceHunks the hunk patch owner
	 * @param adaptedSourcePatches the three-way source patch owner
	 * @param targetPatches the target-relative patch owner
	 * @param treeFiles the source-tree file owner
	 * @param replacementTrees the replacement-tree owner
	 * @param mergeCandidates the three-way merge-candidate owner
	 */
	PatchWorkflow(ManifestStore<BatchManifest> manifests, SourcePatches sourcePatches, SourceHunks sourceHunks,
		AdaptedSourcePatches adaptedSourcePatches, TargetPatches targetPatches, TreeFiles treeFiles,
		ReplacementTrees replacementTrees, MergeCandidates mergeCandidates)
	{
		this.manifests = manifests;
		this.sourcePatches = sourcePatches;
		this.sourceHunks = sourceHunks;
		this.adaptedSourcePatches = adaptedSourcePatches;
		this.targetPatches = targetPatches;
		this.treeFiles = treeFiles;
		this.replacementTrees = replacementTrees;
		this.mergeCandidates = mergeCandidates;
	}

	/**
	 * Captures the exact binary diff of one reviewer-selected whole source commit as an immutable patch artifact.
	 *
	 * <p>
	 * This command neither selects the source nor assigns it to an output. It only captures a whole source after the
	 * reviewer has decided that the source does not need a hunk-level split.
	 *
	 * @param manifestFile the version-5 manifest containing the selected source
	 * @param source the selected full source commit hash
	 * @param patchFile the new manifest-relative patch file
	 * @throws IOException if the source is not declared by the manifest, the output path is unsafe, or Git cannot render
	 *         the source diff
	 */
	void captureSourcePatch(Path manifestFile, String source, Path patchFile) throws IOException
	{
		captureSourcePathPatch(manifestFile, source, patchFile, List.of());
	}

	/**
	 * Captures the exact binary diff for reviewer-selected paths from one source commit.
	 *
	 * <p>
	 * The caller supplies the paths after reviewing their placement. This command preserves Git's diff bytes but does
	 * not decide whether a path needs further hunk-level splitting or where its patch belongs.
	 *
	 * @param manifestFile the version-5 manifest containing the selected source
	 * @param source the selected full source commit hash
	 * @param patchFile the new manifest-relative patch file
	 * @param paths the non-empty selected repository-relative paths, or empty to select the whole source
	 * @throws IOException if the source is not declared by the manifest, a path or output path is unsafe, or Git cannot
	 *         render the source diff
	 */
	void captureSourcePathPatch(Path manifestFile, String source, Path patchFile, List<String> paths)
		throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(source, "source").isNotBlank();
		requireThat(patchFile, "patchFile").isNotNull();
		requireThat(paths, "paths").isNotNull();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		sourcePatches.capture(manifestFile, manifest, source, patchFile, paths);
	}

	/**
	 * Captures one reviewer-selected textual diff hunk from one path in a source commit.
	 *
	 * @param manifestFile the version-5 manifest containing the selected source
	 * @param source the selected full source commit hash
	 * @param patchFile the new manifest-relative patch file
	 * @param path the reviewed repository-relative path
	 * @param hunkIndex the one-based hunk number within the path diff
	 * @throws IOException if the source, path, hunk, or output path is invalid
	 */
	void captureSourceHunkPatch(Path manifestFile, String source, Path patchFile, String path,
		int hunkIndex)
		throws IOException
	{
		requireThat(hunkIndex, "hunkIndex").isPositive();
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(source, "source").isNotBlank();
		requireThat(patchFile, "patchFile").isNotNull();
		if (path == null || path.isBlank() || path.startsWith("/") || path.contains(".."))
		{
			throw new IOException("Topic-replay source hunk path must be non-blank, repository-relative, and not contain " +
				"'..': " + path);
		}
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		if (manifest.sources().stream().noneMatch(candidate -> candidate.hash().equals(source)))
			throw new IOException("Topic-replay manifest does not contain source commit: " + source);
		Path target = patchFile.toAbsolutePath().normalize();
		if (!target.startsWith(manifestFile.toAbsolutePath().normalize().getParent()) || Files.exists(target))
		{
			throw new IOException("Topic-replay source hunk patch file must be new and below the manifest directory: " +
				patchFile);
		}
		Files.createDirectories(target.getParent());
		sourceHunks.capture(manifest, source, target, path, hunkIndex);
	}

	/**
	 * Captures one selected source hunk as a patch for an earlier commit when Git can combine the two versions.
	 *
	 * <p>
	 * This is the hunk-level counterpart of {@link #captureThreeWayAdaptedSourcePatch(Path, String, Path, String,
	 * List)}. Use it when other hunks in the same source path belong to different outputs.
	 *
	 * @param manifestFile the version-5 manifest containing the source
	 * @param source the source commit containing the selected hunk
	 * @param patchFile the new manifest-relative patch file
	 * @param targetTree the commit or tree whose version the hunk must update
	 * @param path the repository-relative path containing the hunk
	 * @param hunkIndex the one-based hunk number within the path diff
	 * @throws IOException if an input is unsafe or Git cannot combine and verify the selected hunk
	 */
	void captureThreeWayAdaptedSourceHunkPatch(Path manifestFile, String source, Path patchFile,
		String targetTree, String path, int hunkIndex) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(source, "source").isNotBlank();
		requireThat(patchFile, "patchFile").isNotNull();
		requireThat(targetTree, "targetTree").isNotBlank();
		requireThat(hunkIndex, "hunkIndex").isPositive();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		adaptedSourcePatches.captureHunk(manifestFile, manifest, source, patchFile, targetTree, path, hunkIndex);
	}

	/**
	 * Captures a patch that updates one path changed by a source commit from a declared earlier version of that path.
	 *
	 * <p>
	 * The reviewer supplies the path's intended contents after the selected source change has been adapted to the
	 * declared target tree. This command creates an immutable Git patch from that target tree to those contents and
	 * proves the patch applies back to the same tree. It does not use the result as proof of the source's original tree
	 * transition; record that proof separately with {@link #recordSourcePatchReconstruction(Path, String, Path)}.
	 *
	 * @param manifestFile the version-5 manifest containing the selected source
	 * @param source the source commit containing the selected change
	 * @param patchFile the new manifest-relative patch file
	 * @param targetTree the commit or tree whose version of the path the patch updates
	 * @param path the repository-relative path changed by the source commit to adapt
	 * @param replacementFile the manifest-relative regular file containing the path's intended target version
	 * @throws IOException if an input is unsafe, the source did not change the path, or Git cannot create and verify the
	 *         target-relative patch
	 */
	void captureTargetAdaptedPathPatch(Path manifestFile, String source, Path patchFile,
		String targetTree, String path, Path replacementFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(source, "source").isNotBlank();
		requireThat(patchFile, "patchFile").isNotNull();
		requireThat(targetTree, "targetTree").isNotBlank();
		requireThat(replacementFile, "replacementFile").isRegularFile();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		targetPatches.capture(manifestFile, manifest, source, patchFile, targetTree, path, replacementFile);
	}

	/**
	 * Copies one path from a declared earlier commit into the manifest workspace for a reviewer to update.
	 *
	 * @param manifestFile the version-5 manifest that owns the workspace file
	 * @param targetTree the commit or tree containing the earlier file version
	 * @param path the repository-relative path to copy
	 * @param outputFile the new manifest-relative file that receives the earlier version
	 * @throws IOException if an input is unsafe or Git cannot read the selected file
	 */
	void writeTreePath(Path manifestFile, String targetTree, String path, Path outputFile)
		throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(targetTree, "targetTree").isNotBlank();
		requireThat(outputFile, "outputFile").isNotNull();
		treeFiles.copy(manifestFile, targetTree, path, outputFile);
	}

	/**
	 * Writes the tree identifier produced by replacing one path in an existing tree with reviewed contents.
	 *
	 * @param manifestFile the version-5 manifest that owns the workspace files
	 * @param baseTree the commit or tree to update
	 * @param path the repository-relative path to replace
	 * @param replacementFile the manifest-relative regular file containing the replacement contents
	 * @param outputFile the new manifest-relative file that receives the resulting tree identifier
	 * @throws IOException if an input is unsafe or Git cannot create the replacement tree
	 */
	void writeReplacementTree(Path manifestFile, String baseTree, String path, Path replacementFile,
		Path outputFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(baseTree, "baseTree").isNotBlank();
		requireThat(replacementFile, "replacementFile").isRegularFile();
		requireThat(outputFile, "outputFile").isNotNull();
		replacementTrees.write(manifestFile, baseTree, path, replacementFile, outputFile);
	}

	/**
	 * Writes Git's proposed merge of a source-path change into an earlier version of that path for reviewer approval.
	 *
	 * <p>
	 * The result combines the earlier file with the source's parent-to-source change. Unchanged or independently
	 * changed regions are retained automatically. Git inserts conflict markers only where the reviewer must choose the
	 * intended result; the caller must resolve them before creating a patch from this candidate.
	 *
	 * @param manifestFile the version-5 manifest that declares the source
	 * @param source the source commit that changed the path
	 * @param targetTree the commit or tree containing the earlier file version
	 * @param path the repository-relative changed path to merge
	 * @param outputFile the new manifest-relative file that receives the proposed result
	 * @throws IOException if an input is unsafe or Git cannot read or merge the path versions
	 */
	void writeThreeWayMergeCandidate(Path manifestFile, String source, String targetTree, String path,
		Path outputFile) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(source, "source").isNotBlank();
		requireThat(targetTree, "targetTree").isNotBlank();
		requireThat(outputFile, "outputFile").isNotNull();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		mergeCandidates.write(manifestFile, manifest, source, targetTree, path, outputFile);
	}

	/**
	 * Captures selected paths from a source change as a patch for an earlier commit when Git can combine the versions.
	 *
	 * <p>
	 * Git compares the selected paths in the source commit, its parent, and the declared earlier version. The resulting
	 * patch changes only what the source adds beyond the earlier version. This preserves an earlier cleanup or correction
	 * when it does not overlap the selected source change. If Git cannot combine the versions, prepare the intended
	 * version of each changed file and use {@link #captureTargetAdaptedPathPatch(Path, String, Path, String, String,
	 * Path)} instead.
	 *
	 * @param manifestFile the version-5 manifest containing the source
	 * @param source the source commit containing the selected paths
	 * @param patchFile the new manifest-relative patch file
	 * @param targetTree the commit or tree whose version the source change must update
	 * @param paths the non-empty repository-relative paths to combine
	 * @throws IOException if an input is unsafe or Git cannot combine and verify the source change
	 */
	void captureThreeWayAdaptedSourcePatch(Path manifestFile, String source, Path patchFile,
		String targetTree, List<String> paths) throws IOException
	{
		requireThat(manifestFile, "manifestFile").isRegularFile();
		requireThat(source, "source").isNotBlank();
		requireThat(patchFile, "patchFile").isNotNull();
		requireThat(targetTree, "targetTree").isNotBlank();
		requireThat(paths, "paths").isNotEmpty();
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		adaptedSourcePatches.capturePaths(manifestFile, manifest, source, patchFile, targetTree, paths);
	}
}

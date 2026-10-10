package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies and verifies manifest-local patches through isolated Git indexes.
 */
public final class PatchApplication
{
	private final Git git;
	private final ManifestStore<?> manifests;
	private final PatchArtifacts patches;
	/**
	 * Source reconstructions whose declared patch already reproduced their declared tree in this process. Git trees and
	 * the patch bytes that match a declared SHA-256 cannot change, so a repeated verification can only repeat the patch
	 * hash check.
	 */
	private final Set<SourceReconstruction> verifiedSourceReconstructions = ConcurrentHashMap.newKeySet();

	/**
	 * Creates an isolated patch-application boundary.
	 *
	 * @param git Git access for the replay repository
	 * @param manifests the owner of manifest-local temporary indexes
	 * @param patches the owner of patch declarations and integrity checks
	 */
	public PatchApplication(Git git, ManifestStore<?> manifests,
		PatchArtifacts patches)
	{
		this.git = git;
		this.manifests = manifests;
		this.patches = patches;
	}

	/**
	 * Applies a patch to an isolated index initialized from one tree.
	 *
	 * @param manifestFile the manifest that owns the temporary index
	 * @param patchFile the patch to apply
	 * @param beforeTree the starting tree
	 * @return the staged tree after applying the patch
	 * @throws IOException if Git cannot apply the patch or write the resulting tree
	 */
	String apply(Path manifestFile, Path patchFile, String beforeTree) throws IOException
	{
		Path index = manifests.createTemporaryFile(manifestFile, "git-topic-replay-", ".index");
		try
		{
			Files.deleteIfExists(index);
			git.lines(index, "read-tree", beforeTree);
			git.lines(index, "apply", "--cached", patchFile.toAbsolutePath().normalize().toString());
			return git.lines(index, "write-tree").getFirst();
		}
		finally
		{
			Files.deleteIfExists(index);
		}
	}

	/**
	 * Verifies one independently captured source patch against its original source snapshot.
	 *
	 * @param manifestFile the manifest containing the patch
	 * @param reconstruction the source reconstruction to verify
	 * @throws IOException if the source patch cannot be applied or produces a different source tree
	 */
	public void verifySourceReconstruction(Path manifestFile, SourceReconstruction reconstruction) throws IOException
	{
		if (verifiedSourceReconstructions.contains(reconstruction))
		{
			resolveDeclaredSourcePatch(manifestFile, reconstruction);
			return;
		}
		Path index = manifests.createTemporaryFile(manifestFile, "git-topic-replay-", ".index");
		try
		{
			Files.deleteIfExists(index);
			git.lines(index, "read-tree", reconstruction.beforeTree());
			Path patchFile = resolveDeclaredSourcePatch(manifestFile, reconstruction);
			git.lines(index, "apply", "--cached", patchFile.toAbsolutePath().normalize().toString());
			String actualTree = git.lines(index, "write-tree").getFirst();
			recordVerifiedSourceTree(reconstruction, actualTree);
		}
		finally
		{
			Files.deleteIfExists(index);
		}
	}

	/**
	 * Accepts the tree produced by applying a source patch and remembers the matching reconstruction.
	 *
	 * @param reconstruction the declared source transition
	 * @param actualTree the tree produced by Git's isolated index
	 * @throws IOException if the resulting tree differs from the declared source tree
	 */
	public void recordVerifiedSourceTree(SourceReconstruction reconstruction, String actualTree) throws IOException
	{
		if (!actualTree.equals(reconstruction.afterTree()))
		{
			throw new IOException("Source reconstruction for " + reconstruction.source() + " produced tree " + actualTree +
				" instead of declared tree " + reconstruction.afterTree());
		}
		verifiedSourceReconstructions.add(reconstruction);
	}

	/**
	 * Resolves a source reconstruction's patch file after checking that its bytes match the declared SHA-256.
	 *
	 * @param manifestFile the manifest containing the patch
	 * @param reconstruction the source reconstruction that declares the patch
	 * @return the patch file
	 * @throws IOException if the patch declaration is invalid, the file cannot be read, or its hash differs
	 */
	private Path resolveDeclaredSourcePatch(Path manifestFile, SourceReconstruction reconstruction) throws IOException
	{
		Path patchFile = patches.resolve(manifestFile, reconstruction.sourcePatch());
		if (!reconstruction.sourcePatch().sha256().equals(patches.sha256(patchFile)))
			throw new IOException("Topic-replay source-patch hash does not match its declared SHA-256: " + patchFile);
		return patchFile;
	}

	/**
	 * Verifies one source reconstruction whose declared batches recreate its captured source tree.
	 *
	 * @param manifestFile the manifest containing patch artifacts
	 * @param reconstruction the source reconstruction to verify
	 * @param batches batches indexed by identifier
	 * @throws IOException if a batch is assigned twice or to the wrong source, has an invalid patch, or produces the
	 *                     wrong tree
	 */
	void verifyBatchReconstruction(Path manifestFile, SourceReconstruction reconstruction, Map<String, Batch> batches)
		throws IOException
	{
		Path index = manifests.createTemporaryFile(manifestFile, "git-topic-replay-", ".index");
		try
		{
			Files.deleteIfExists(index);
			git.lines(index, "read-tree", reconstruction.beforeTree());
			Set<String> used = new HashSet<>();
			for (String batchId : reconstruction.batchIds())
			{
				if (!used.add(batchId))
					throw new IOException("Source reconstruction repeats batch: " + batchId);
				Batch batch = batches.get(batchId);
				if (batch == null || !reconstruction.source().equals(batch.source()))
					throw new IOException("Source reconstruction assigns batch " + batchId + " to the wrong source");
				Path patchFile = patches.resolve(manifestFile, batch.patch());
				if (!batch.patch().sha256().equals(patches.sha256(patchFile)))
				{
					throw new IOException("Topic-replay batch hash does not match its declared SHA-256: " + patchFile);
				}
				git.lines(index, "apply", "--cached", patchFile.toAbsolutePath().normalize().toString());
			}
			String actualTree = git.lines(index, "write-tree").getFirst();
			if (!actualTree.equals(reconstruction.afterTree()))
			{
				throw new IOException("Source reconstruction for " + reconstruction.source() + " produced tree " + actualTree +
					" instead of declared tree " + reconstruction.afterTree());
			}
			String sourceTree = git.lines("rev-parse", reconstruction.source() + "^{tree}").getFirst();
			if (!sourceTree.equals(reconstruction.afterTree()))
			{
				throw new IOException(
					"Source reconstruction after tree does not match source commit: " + reconstruction.source());
			}
		}
		finally
		{
			Files.deleteIfExists(index);
		}
	}
}

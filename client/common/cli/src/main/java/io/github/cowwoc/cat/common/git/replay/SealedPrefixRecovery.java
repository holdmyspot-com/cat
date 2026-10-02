package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Revalidates the mechanically sealed prefix of a replay before recovery can reuse it.
 */
final class SealedPrefixRecovery
{
	private final Git git;
	private final History history;
	private final PatchArtifacts patches;
	private final PatchApplication patchApplication;
	private final SealedPrefixValidation sealedPrefixValidation;

	/**
	 * Creates a sealed-prefix recovery verifier.
	 *
	 * @param git the repository command boundary
	 * @param history the repository history reader
	 * @param patches the manifest-local patch artifact boundary
	 * @param patchApplication the patch application boundary
	 * @param sealedPrefixValidation the contiguous-prefix validator
	 */
	SealedPrefixRecovery(Git git, History history, PatchArtifacts patches, PatchApplication patchApplication,
		SealedPrefixValidation sealedPrefixValidation)
	{
		this.git = git;
		this.history = history;
		this.patches = patches;
		this.patchApplication = patchApplication;
		this.sealedPrefixValidation = sealedPrefixValidation;
	}

	/**
	 * Verifies source facts, patch digests, and retained-output witnesses in the sealed prefix.
	 *
	 * @param manifestFile the manifest that owns the sealed prefix
	 * @param manifest the parsed manifest
	 * @return the number of verified sealed outputs
	 * @throws IOException if no contiguous sealed prefix exists or any recorded mechanical fact no longer matches
	 */
	int verify(Path manifestFile, BatchManifest manifest) throws IOException
	{
		int length = sealedPrefixValidation.length(manifest);
		for (SourceSnapshot source : manifest.sources())
		{
			String actualParentTree = history.sourceBeforeTree(source.hash());
			String actualTree = git.lines("rev-parse", source.hash() + "^{tree}").getFirst();
			verifySourceTrees(source, actualParentTree, actualTree);
		}
		String currentTree = history.baseTree(manifest.base());
		for (int index = 0; index < length; ++index)
		{
			Output output = manifest.outputs().get(index);
			if (OutputManifestFacts.discarded(manifest).contains(output.id()))
				continue;
			OutputWitness witness = OutputManifestFacts.witness(manifest, output.id());
			verifyOutputBefore(output, witness, currentTree);
			for (Batch batch : manifest.batches())
			{
				if (!batch.destination().equals(output.id()))
					continue;
				Path patch = verifyBatchPatch(manifestFile, batch, patches);
				currentTree = patchApplication.apply(manifestFile, patch, currentTree);
				verifyBatchAfter(batch, currentTree);
			}
			verifyOutputAfter(output, witness, currentTree);
		}
		return length;
	}

	/**
	 * Checks captured source endpoints against current repository facts.
	 *
	 * @param source the captured source
	 * @param actualParentTree the current parent tree
	 * @param actualTree the current result tree
	 * @throws IOException if either endpoint changed
	 */
	static void verifySourceTrees(SourceSnapshot source, String actualParentTree, String actualTree) throws IOException
	{
		if (!actualParentTree.equals(source.parentTree()) || !actualTree.equals(source.tree()))
		{
			throw new IOException("Topic-replay source " + source.hash() +
				" no longer has its recorded parent or result tree; prepare a new replay instead of reusing this prefix");
		}
	}

	/**
	 * Checks that an output starts at the preceding verified prefix tree.
	 *
	 * @param output the retained output
	 * @param witness the recorded transition
	 * @param currentTree the tree produced by the prefix
	 * @throws IOException if the transition does not continue the prefix
	 */
	static void verifyOutputBefore(Output output, OutputWitness witness, String currentTree) throws IOException
	{
		if (!currentTree.equals(witness.beforeTree()))
		{
			throw new IOException("Topic-replay sealed output " + OutputManifestFacts.describe(output) + " expects tree " +
				witness.beforeTree() + " but the validated prefix produced " + currentTree);
		}
	}

	/**
	 * Resolves a sealed batch and checks its actual bytes against the recorded digest.
	 *
	 * @param manifestFile the manifest owning the patch
	 * @param batch the sealed batch
	 * @param patches the patch artifact owner
	 * @return the verified patch file
	 * @throws IOException if the patch is missing, unsafe, or changed
	 */
	static Path verifyBatchPatch(Path manifestFile, Batch batch, PatchArtifacts patches) throws IOException
	{
		Path patch = patches.resolve(manifestFile, batch.patch());
		if (!patches.sha256(patch).equals(batch.patch().sha256()))
		{
			throw new IOException("Topic-replay sealed batch " + batch.id() +
				" no longer matches its recorded digest: " + patch);
		}
		return patch;
	}

	/**
	 * Checks the tree produced by one sealed batch.
	 *
	 * @param batch the sealed batch
	 * @param actualTree the result of applying its patch
	 * @throws IOException if the result differs from the recorded batch witness
	 */
	static void verifyBatchAfter(Batch batch, String actualTree) throws IOException
	{
		if (!actualTree.equals(batch.destinationWitness().afterTree()))
		{
			throw new IOException("Topic-replay sealed batch " + batch.id() +
				" no longer recreates its recorded tree");
		}
	}

	/**
	 * Checks the tree produced by all batches in one sealed output.
	 *
	 * @param output the retained output
	 * @param witness the recorded output transition
	 * @param actualTree the result of applying its batches
	 * @throws IOException if the result differs from the recorded output witness
	 */
	static void verifyOutputAfter(Output output, OutputWitness witness, String actualTree) throws IOException
	{
		if (!actualTree.equals(witness.afterTree()))
		{
			throw new IOException("Topic-replay sealed output " + OutputManifestFacts.describe(output) +
				" no longer recreates its recorded tree");
		}
	}
}

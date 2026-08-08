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
			if (!actualParentTree.equals(source.parentTree()) || !actualTree.equals(source.tree()))
			{
				throw new IOException("Topic-replay source " + source.hash() +
					" no longer has its recorded parent or result tree; prepare a new replay instead of reusing this prefix");
			}
		}
		String currentTree = history.baseTree(manifest.base());
		for (int index = 0; index < length; ++index)
		{
			Output output = manifest.outputs().get(index);
			if (OutputManifestFacts.discarded(manifest).contains(output.id()))
				continue;
			OutputWitness witness = OutputManifestFacts.witness(manifest, output.id());
			if (!currentTree.equals(witness.beforeTree()))
			{
				throw new IOException("Topic-replay sealed output " + OutputManifestFacts.describe(output) + " expects tree " +
					witness.beforeTree() + " but the validated prefix produced " + currentTree);
			}
			for (Batch batch : manifest.batches())
			{
				if (!batch.destination().equals(output.id()))
					continue;
				Path patch = patches.resolve(manifestFile, batch.patch());
				if (!patches.sha256(patch).equals(batch.patch().sha256()))
				{
					throw new IOException("Topic-replay sealed batch " + batch.id() +
						" no longer matches its recorded digest: " + patch);
				}
				currentTree = patchApplication.apply(manifestFile, patch, currentTree);
				if (!currentTree.equals(batch.destinationWitness().afterTree()))
				{
					throw new IOException("Topic-replay sealed batch " + batch.id() +
						" no longer recreates its recorded tree");
				}
			}
			if (!currentTree.equals(witness.afterTree()))
			{
				throw new IOException("Topic-replay sealed output " + OutputManifestFacts.describe(output) +
					" no longer recreates its recorded tree");
			}
		}
		return length;
	}
}

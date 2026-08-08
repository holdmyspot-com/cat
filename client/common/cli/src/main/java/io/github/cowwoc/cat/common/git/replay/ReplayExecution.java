package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies a sealed replay in an isolated index and advances the current branch after verification.
 */
final class ReplayExecution
{
	private final Git git;
	private final History history;
	private final ManifestStore<?> manifests;
	private final PatchArtifacts patches;

	/**
	 * Creates one replay-execution boundary for a repository.
	 *
	 * @param git Git access for the replay repository
	 * @param history immutable Git history access
	 * @param manifests the owner of manifest-local temporary indexes
	 * @param patches the owner of manifest-local patch artifacts
	 */
	ReplayExecution(Git git, History history, ManifestStore<?> manifests, PatchArtifacts patches)
	{
		this.git = git;
		this.history = history;
		this.manifests = manifests;
		this.patches = patches;
	}

	/**
	 * Applies the sealed batches and atomically advances the checked-out branch to the verified replacement chain.
	 *
	 * @param manifestFile the manifest that owns temporary indexes and patch artifacts
	 * @param manifest the sealed replay state
	 * @return the final replacement commit
	 * @throws IOException if the source tip changed, a batch differs from its witness, or Git cannot update the branch
	 */
	String execute(Path manifestFile, BatchManifest manifest) throws IOException
	{
		String expectedTip = manifest.sources().getLast().hash();
		String currentTip = git.lines("rev-parse", "HEAD").getFirst();
		if (!currentTip.equals(expectedTip))
		{
			throw new IOException("Topic-replay source tip changed from " + expectedTip + " to " + currentTip +
				"; restore the recorded backup or prepare a new manifest before replaying");
		}
		String branchReference = git.lines("symbolic-ref", "--quiet", "HEAD").getFirst();
		String rewrittenTip = applyOutputs(manifestFile, manifest);
		git.lines("update-ref", branchReference, rewrittenTip, expectedTip);
		return rewrittenTip;
	}

	/**
	 * Applies retained outputs to an isolated index and creates their replacement commits.
	 *
	 * @param manifestFile the manifest that owns temporary indexes and patch artifacts
	 * @param manifest the sealed replay state
	 * @return the final replacement commit
	 * @throws IOException if a patch, witness, tree, or replacement commit cannot be verified or created
	 */
	private String applyOutputs(Path manifestFile, BatchManifest manifest) throws IOException
	{
		Path index = manifests.createTemporaryFile(manifestFile, "git-topic-replay-", ".index");
		try
		{
			Files.deleteIfExists(index);
			git.lines(index, "read-tree", history.baseTree(manifest.base()));
			String rewrittenTip = null;
			if (!manifest.base().equals(History.ROOT_BASE))
				rewrittenTip = manifest.base();
			for (Output output : manifest.outputs())
			{
				if (manifest.batches().stream().noneMatch(batch -> batch.destination().equals(output.id())))
					continue;
				rewrittenTip = applyOutput(manifestFile, manifest, index, output, rewrittenTip);
			}
			if (!git.lines(index, "write-tree").getFirst().equals(manifest.tipTree()))
				throw new IOException("Topic replay did not produce the manifest's required final tree");
			return rewrittenTip;
		}
		finally
		{
			Files.deleteIfExists(index);
		}
	}

	/**
	 * Applies one retained output and creates the matching replacement commit.
	 *
	 * @param manifestFile the manifest that owns patch artifacts
	 * @param manifest the sealed replay state
	 * @param index the isolated Git index
	 * @param output the retained output
	 * @param parent the preceding replacement commit, or {@code null} for a root replay
	 * @return the replacement commit created for the output
	 * @throws IOException if a batch or output differs from its recorded witness
	 */
	private String applyOutput(Path manifestFile, BatchManifest manifest, Path index, Output output, String parent)
		throws IOException
	{
		OutputWitness witness = outputWitness(manifest, output.id());
		String beforeTree = git.lines(index, "write-tree").getFirst();
		if (!beforeTree.equals(witness.beforeTree()))
		{
			throw new IOException("Topic-replay output " + output.id() + " requires tree " + witness.beforeTree() +
				" but the preceding output produced " + beforeTree);
		}
		for (Batch batch : manifest.batches())
		{
			if (batch.destination().equals(output.id()))
				applyBatch(manifestFile, index, batch);
		}
		String outputTree = git.lines(index, "write-tree").getFirst();
		if (!outputTree.equals(witness.afterTree()))
		{
			throw new IOException("Topic-replay output " + output.id() + " produced tree " + outputTree +
				" instead of " + witness.afterTree());
		}
		String authorSource = output.source();
		if (authorSource == null)
		{
			authorSource = manifest.batches().stream().filter(batch -> batch.destination().equals(output.id())).findFirst().
				orElseThrow().source();
		}
		return commitOutput(output, authorSource, outputTree, parent);
	}

	/**
	 * Applies one batch and verifies its resulting tree against the manifest witness.
	 *
	 * @param manifestFile the manifest that owns the batch patch
	 * @param index the isolated Git index
	 * @param batch the sealed batch
	 * @throws IOException if the patch digest or tree witness differs
	 */
	private void applyBatch(Path manifestFile, Path index, Batch batch) throws IOException
	{
		Path patchFile = patches.resolve(manifestFile, batch.patch());
		if (!batch.patch().sha256().equals(patches.sha256(patchFile)))
		{
			throw new IOException("Topic-replay batch hash does not match its declared SHA-256: " + patchFile);
		}
		git.lines(index, "apply", "--cached", patchFile.toAbsolutePath().normalize().toString());
		String afterBatch = git.lines(index, "write-tree").getFirst();
		if (!afterBatch.equals(batch.destinationWitness().afterTree()))
		{
			throw new IOException("Topic-replay batch " + batch.id() + " produced tree " + afterBatch +
				" instead of declared tree " + batch.destinationWitness().afterTree());
		}
	}

	/**
	 * Creates one replacement commit while preserving the chosen source author's identity.
	 *
	 * @param output the reviewed output declaration
	 * @param authorSource the source that supplies author identity
	 * @param tree the output's verified tree
	 * @param parent the preceding replacement commit, or {@code null} for a root replacement commit
	 * @return the created commit's full object identifier
	 * @throws IOException if Git cannot read the source author or create the commit
	 */
	private String commitOutput(Output output, String authorSource, String tree, String parent) throws IOException
	{
		Map<String, String> environment = Map.of(
			"GIT_AUTHOR_NAME", git.lines("show", "-s", "--format=%an", authorSource).getFirst(),
			"GIT_AUTHOR_EMAIL", git.lines("show", "-s", "--format=%ae", authorSource).getFirst(),
			"GIT_AUTHOR_DATE", git.lines("show", "-s", "--format=%aI", authorSource).getFirst());
		List<String> arguments = new ArrayList<>(List.of("commit-tree", tree));
		if (parent != null)
			arguments.addAll(List.of("-p", parent));
		arguments.addAll(List.of("-m", output.subject()));
		return git.lines(environment, arguments.toArray(String[]::new)).getFirst();
	}

	/**
	 * Finds a closed retained-output witness.
	 *
	 * @param manifest the replay manifest
	 * @param outputId the retained output identifier
	 * @return the retained output witness
	 * @throws IOException if the output has no retained witness
	 */
	private OutputWitness outputWitness(BatchManifest manifest, String outputId) throws IOException
	{
		return manifest.outputWitnesses().stream().filter(witness -> witness.outputId().equals(outputId)).findFirst().
			orElseThrow(() -> new IOException("Topic-replay manifest does not contain retained output: " + outputId));
	}
}

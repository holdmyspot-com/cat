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
public final class ReplayExecution
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
	public ReplayExecution(Git git, History history, ManifestStore<?> manifests, PatchArtifacts patches)
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
	public String execute(Path manifestFile, BatchManifest manifest) throws IOException
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
			for (Output output : retainedOutputs(manifest))
				rewrittenTip = applyOutput(manifestFile, manifest, index, output, rewrittenTip);
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
	 * Selects the outputs whose recorded batches create replacement commits.
	 *
	 * @param manifest the sealed replay decisions
	 * @return retained outputs in declared chronological order
	 */
	public static List<Output> retainedOutputs(BatchManifest manifest)
	{
		return manifest.outputs().stream().filter(output -> manifest.batches().stream().anyMatch(batch ->
			batch.destination().equals(output.id()))).toList();
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
		for (Batch batch : outputBatches(manifest, output.id()))
			applyBatch(manifestFile, index, batch);
		String outputTree = git.lines(index, "write-tree").getFirst();
		if (!outputTree.equals(witness.afterTree()))
		{
			throw new IOException("Topic-replay output " + output.id() + " produced tree " + outputTree +
				" instead of " + witness.afterTree());
		}
		return commitOutput(output, authorSource(manifest, output), outputTree, parent);
	}

	/**
	 * Selects one retained output's batches in their recorded application order.
	 *
	 * @param manifest the recorded replay batches
	 * @param outputId the destination whose batches are selected
	 * @return batches assigned to that output
	 */
	public static List<Batch> outputBatches(BatchManifest manifest, String outputId)
	{
		return manifest.batches().stream().filter(batch -> batch.destination().equals(outputId)).toList();
	}

	/**
	 * Selects the source whose author identity a retained output preserves.
	 *
	 * @param manifest the recorded source placements
	 * @param output the output being committed
	 * @return its declared source, or the first contributing batch's source for a reviewer-declared topic
	 */
	public static String authorSource(BatchManifest manifest, Output output)
	{
		if (output.source() != null)
			return output.source();
		return outputBatches(manifest, output.id()).stream().findFirst().orElseThrow().source();
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
		// A commit's author header is one line, so its name, email and date cannot contain a line break
		List<String> author = git.lines("show", "-s", "--format=%an%n%ae%n%aI", authorSource);
		Map<String, String> environment = Map.of(
			"GIT_AUTHOR_NAME", author.get(0),
			"GIT_AUTHOR_EMAIL", author.get(1),
			"GIT_AUTHOR_DATE", author.get(2));
		return git.lines(environment, commitArguments(output, tree, parent).toArray(String[]::new)).getFirst();
	}

	/**
	 * Constructs the replacement commit command with its reviewed subject and preceding commit.
	 *
	 * @param output the reviewed output declaration
	 * @param tree the verified output tree
	 * @param parent the preceding commit, or {@code null} for an orphan-root output
	 * @return the ordered Git command arguments
	 */
	public static List<String> commitArguments(Output output, String tree, String parent)
	{
		List<String> arguments = new ArrayList<>(List.of("commit-tree", tree));
		if (parent != null)
			arguments.addAll(List.of("-p", parent));
		arguments.addAll(List.of("-m", output.subject()));
		return arguments;
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

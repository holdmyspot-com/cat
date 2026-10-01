package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Verifies that rewritten Git history realizes the immutable facts of a sealed replay manifest.
 */
final class ReplayVerification
{
	private static final String ROOT_BASE = History.ROOT_BASE;
	private final Git git;
	private final History history;
	private final ReplayPreflight replayPreflight;
	/**
	 * Replacement chains already verified against a manifest in this process. A full commit hash names immutable
	 * history, so a repeated verification of the same chain against the same manifest can only repeat the source-patch
	 * checks.
	 */
	private final Set<VerifiedChain> verifiedChains = ConcurrentHashMap.newKeySet();

	/**
	 * Creates a replay-result verifier.
	 *
	 * @param git the repository command boundary
	 * @param history the repository history reader
	 * @param replayPreflight the owner of source-reconstruction verification
	 */
	ReplayVerification(Git git, History history, ReplayPreflight replayPreflight)
	{
		this.git = git;
		this.history = history;
		this.replayPreflight = replayPreflight;
	}

	/**
	 * Verifies source coverage, ancestry, output order, and output trees for a completed rewritten range.
	 *
	 * @param manifestFile the batch manifest that owns the replay
	 * @param manifest the parsed and validated batch manifest
	 * @param rebaseBase the commit immediately before the rewritten range
	 * @param rewrittenTip the rewritten range's final commit
	 * @throws IOException if the replay differs from the manifest's immutable facts
	 */
	void verify(Path manifestFile, BatchManifest manifest, String rebaseBase, String rewrittenTip) throws IOException
	{
		replayPreflight.verifySourceReconstructions(manifestFile, manifest);
		VerifiedChain chain = new VerifiedChain(manifest, rebaseBase, rewrittenTip);
		if (verifiedChains.contains(chain))
			return;
		verifyChain(manifest, rebaseBase, rewrittenTip);
		if (rewrittenTip.matches("[0-9a-f]{40}"))
			verifiedChains.add(chain);
	}

	/**
	 * Verifies that a rewritten range realizes the manifest's base, output order, and output trees.
	 *
	 * @param manifest the parsed and validated batch manifest
	 * @param rebaseBase the commit immediately before the rewritten range
	 * @param rewrittenTip the rewritten range's final commit
	 * @throws IOException if the replay differs from the manifest's immutable facts
	 */
	private void verifyChain(BatchManifest manifest, String rebaseBase, String rewrittenTip) throws IOException
	{
		if (!manifest.base().equals(rebaseBase))
		{
			throw new IOException("Rebase base does not match manifest: expected " + manifest.base() + " but found " +
				rebaseBase);
		}
		String actualTree = git.lines("rev-parse", rewrittenTip + "^{tree}").getFirst();
		if (!actualTree.equals(manifest.tipTree()))
		{
			throw new IOException("Rewritten tip tree does not match manifest: expected " + manifest.tipTree() +
				" but found " + actualTree);
		}
		if (!rebaseBase.equals(ROOT_BASE))
		{
			try
			{
				git.lines("merge-base", "--is-ancestor", rebaseBase, rewrittenTip);
			}
			catch (IOException e)
			{
				throw new IOException("Rewritten tip does not descend from declared rebase base " + rebaseBase, e);
			}
		}
		List<String> replayed = history.range(rebaseBase, rewrittenTip);
		if (!rebaseBase.equals(ROOT_BASE))
		{
			String[] firstOutput = git.lines("rev-list", "--parents", "-n", "1", replayed.getFirst()).getFirst().split(" ");
			if (firstOutput.length != 2 || !firstOutput[1].equals(rebaseBase))
			{
				throw new IOException("First rewritten output does not have declared rebase base " + rebaseBase +
					" as its direct parent");
			}
		}
		Set<String> destinations = manifest.batches().stream().map(Batch::destination).collect(Collectors.toSet());
		if (replayed.size() != destinations.size())
		{
			throw new IOException("Rewritten history contains " + replayed.size() + " commits but batches declare " +
				destinations.size() + " destination commits");
		}
		List<Output> expectedOutputs = manifest.outputs().stream().filter(output -> destinations.contains(output.id())).
			toList();
		for (int index = 0; index < expectedOutputs.size(); ++index)
		{
			Output output = expectedOutputs.get(index);
			String replayedOutput = replayed.get(index);
			String subject = git.lines("log", "-1", "--format=%s", replayedOutput).getFirst();
			if (!subject.equals(output.subject()))
				throw new IOException("Rewritten output order does not match declared output " + output.id());
			String outputTree = git.lines("rev-parse", replayedOutput + "^{tree}").getFirst();
			String expectedTree = outputWitness(manifest, output.id()).afterTree();
			if (!outputTree.equals(expectedTree))
			{
				throw new IOException("Rewritten output " + output.id() + " produced tree " + outputTree +
					" instead of declared tree " + expectedTree);
			}
		}
	}

	/**
	 * Returns a closed retained-output witness.
	 *
	 * @param manifest the batch manifest
	 * @param outputId the output identifier
	 * @return the witness
	 * @throws IOException if the output was not retained
	 */
	private static OutputWitness outputWitness(BatchManifest manifest, String outputId) throws IOException
	{
		return manifest.outputWitnesses().stream().filter(witness -> witness.outputId().equals(outputId)).findFirst().
			orElseThrow(() -> new IOException("Topic-replay manifest does not contain retained output: " + outputId));
	}

	/**
	 * One replacement chain that satisfied a manifest's immutable facts.
	 *
	 * @param manifest the parsed batch manifest
	 * @param rebaseBase the commit immediately before the rewritten range
	 * @param rewrittenTip the full hash of the rewritten range's final commit
	 */
	private record VerifiedChain(BatchManifest manifest, String rebaseBase, String rewrittenTip)
	{
	}
}

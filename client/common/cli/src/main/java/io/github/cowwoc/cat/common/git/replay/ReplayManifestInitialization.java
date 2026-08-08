package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures immutable Git-range facts and publishes an unreviewed topic-replay manifest.
 */
final class ReplayManifestInitialization
{
	private final Git git;
	private final History history;
	private final ManifestStore<BatchManifest> manifests;

	/**
	 * Creates a replay-manifest initialization boundary.
	 *
	 * @param git Git access for resolving source trees
	 * @param history the owner of range and inventory facts
	 * @param manifests the owner of manifest publication
	 */
	ReplayManifestInitialization(Git git, History history, ManifestStore<BatchManifest> manifests)
	{
		this.git = git;
		this.history = history;
		this.manifests = manifests;
	}

	/**
	 * Creates an initial manifest for one existing linear commit range.
	 *
	 * @param rebaseBase the commit immediately before the range, or {@link History#ROOT_BASE} for an orphan-root range
	 * @param tip the range's final commit
	 * @param manifestFile the new manifest file
	 * @throws IOException if the range is invalid, empty, or cannot be captured
	 */
	void initialize(String rebaseBase, String tip, Path manifestFile) throws IOException
	{
		if (Files.exists(manifestFile))
			throw new IOException("Topic-replay manifest already exists: " + manifestFile);
		String resolvedTip = git.lines("rev-parse", tip).getFirst();
		String base = history.resolveBase(rebaseBase);
		history.verifyPlanningRepositoryState(resolvedTip);
		List<String> sources = history.range(base, resolvedTip);
		if (sources.isEmpty())
			throw new IOException("Topic-replay range does not contain commits: " + rebaseBase + ".." + tip);
		List<InventoryItem> inventory = new ArrayList<>();
		List<SourceSnapshot> sourceSnapshots = new ArrayList<>(sources.size());
		for (String source : sources)
		{
			sourceSnapshots.add(new SourceSnapshot(source, history.sourceBeforeTree(source), git.lines("rev-parse",
				source + "^{tree}").getFirst()));
			inventory.addAll(history.inventory(source).stream().map(entry -> new InventoryItem(entry.id(), entry.source(),
				entry.path(), entry.beforeMode(), entry.beforeBlob(), entry.afterMode(), entry.afterBlob())).toList());
		}
		List<Output> outputs = new ArrayList<>(sources.size());
		for (int index = 0; index < sources.size(); ++index)
			outputs.add(new Output("output-" + (index + 1), sources.get(index), "Unassigned topic " + (index + 1)));
		BatchManifest manifest = new BatchManifest(ManifestStore.BATCH_MANIFEST_VERSION, base,
			git.lines("rev-parse", resolvedTip + "^{tree}").getFirst(), sourceSnapshots, outputs, inventory, List.of(),
			List.of(), List.of(), List.of(), List.of(), List.of(), null);
		manifests.write(manifestFile, manifest);
	}

	/**
	 * Creates an initial manifest whose leading source prefix is already complete and immutable.
	 *
	 * @param rebaseBase the commit immediately before the complete prefix
	 * @param fixedTip the final unchanged commit of the complete prefix
	 * @param tip the final commit of the extended range
	 * @param manifestFile the new replay manifest
	 * @throws IOException if the fixed prefix is not a strict ancestor prefix of the requested range
	 */
	void initializeFixedPrefix(String rebaseBase, String fixedTip, String tip, Path manifestFile) throws IOException
	{
		initialize(rebaseBase, tip, manifestFile);
		BatchManifest manifest = manifests.readUnfinalized(manifestFile);
		String resolvedFixedTip = git.lines("rev-parse", fixedTip).getFirst();
		String resolvedTip = git.lines("rev-parse", tip).getFirst();
		try
		{
			git.lines("merge-base", "--is-ancestor", resolvedFixedTip, resolvedTip);
		}
		catch (IOException e)
		{
			throw new IOException("Topic-replay fixed prefix " + resolvedFixedTip + " is not an ancestor of the requested " +
				"tip " + resolvedTip + ". Select the unchanged completed prefix or prepare the full range.", e);
		}
		List<String> fixedSources = history.range(manifest.base(), resolvedFixedTip);
		if (fixedSources.isEmpty() || fixedSources.size() == manifest.sources().size())
		{
			throw new IOException("Topic-replay fixed prefix must contain at least one completed commit and leave later " +
				"commits to review");
		}
		manifests.write(manifestFile, manifest.withFixedSources(fixedSources));
	}
}

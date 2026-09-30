package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Owns a campaign-pinned delivery fixture without introducing artifacts into the tested worktree. */
final class CandidateDeliveryFixture implements AutoCloseable
{
	private final TemporaryDirectories cleanup;
	private final CandidateDelivery delivery;

	/**
	 * Opens the fixture from complete immutable snapshots and directory roots.
	 *
	 * @param project test-owned worktree
	 * @param snapshots snapshots by their exact content identities
	 * @param directoryRoots complete editable directory roots
	 * @throws IOException if campaign evidence cannot be created
	 */
	CandidateDeliveryFixture(Path project, Map<String, PromptSnapshot> snapshots, List<Path> directoryRoots)
		throws IOException
	{
		cleanup = new TemporaryDirectories();
		for (Map.Entry<String, PromptSnapshot> entry: snapshots.entrySet())
		{
			if (!entry.getKey().equals(entry.getValue().id()))
				throw new IllegalArgumentException("Snapshot registry key must equal snapshot identity");
		}
		Path owner = cleanup.add(Files.createTempDirectory("candidate-delivery-campaign-"));
		CampaignStore store = CampaignStore.initialize(owner.resolve("campaign"), "campaign",
			new ModelProfile("test-model", Optional.empty(), Map.of()));
		PromptSnapshotRepository repository = new PromptSnapshotRepository(store.directory());
		for (PromptSnapshot snapshot: snapshots.values())
			repository.save(snapshot);
		List<EditableRoot> roots = directoryRoots.stream().map(path -> new EditableRoot(path,
			EditableRoot.Kind.DIRECTORY)).toList();
		EvidenceRef rootsRef = new CandidateEvidenceRepository(store.directory(), store.binding()).publish(
			"confirmed-editable-roots", new ConfirmedEditableRoots(2, project.toRealPath(),
				CandidateTestRoots.contract(store.directory()),
				CandidateTestRoots.baseline(project, store.directory(), roots), roots));
		delivery = new CandidateDelivery(project, store.directory(), store.binding(), repository, rootsRef);
	}

	/**
	 * Returns the campaign-bound delivery boundary.
	 *
	 * @return pinned delivery boundary
	 */
	CandidateDelivery delivery()
	{
		return delivery;
	}

	/** Deletes only this fixture's external campaign directory. */
	@Override
	public void close() throws IOException
	{
		cleanup.close();
	}
}

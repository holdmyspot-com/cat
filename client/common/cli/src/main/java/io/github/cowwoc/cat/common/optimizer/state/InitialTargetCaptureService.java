package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.LiveGitInventory;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshot;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshotRepository;
import io.github.cowwoc.cat.common.optimizer.candidate.RetainedPromptSourceResolver;
import io.github.cowwoc.cat.common.optimizer.candidate.SnapshotJson;
import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Captures the registered target before the first contract inspection call.
 */
public final class InitialTargetCaptureService
{
	private static final JsonMapper JSON = SnapshotJson.mapper();

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds capture to one registered campaign.
	 *
	 * @param locator the campaign's durable locator
	 * @param store the pinned campaign evidence store
	 */
	public InitialTargetCaptureService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Captures or retrieves the target's initial snapshot for the current preparation scope.
	 *
	 * @param binding the registered campaign
	 * @return the indexed capture evidence
	 * @throws IOException if campaign authority, source capture, or publication fails
	 */
	public EvidenceRef capture(CampaignBinding binding) throws IOException
	{
		requireRegisteredStore(binding);
		CampaignLookupKey key = locator.keyForBinding(binding);
		PreparationAuthority preparation = new CampaignTransitions(store).readPreparationAuthority(binding);
		if (preparation.kind() != PreparationAuthority.Kind.INITIAL)
			throw new IOException("Initial target capture requires initial contract preparation");
		String indexKey = PreparationIndexKey.derive(preparation);
		Optional<EvidenceRef> existing = store.readPreconfirmationIndex(binding,
			PreconfirmationIndexKind.CONTRACT_SOURCE_CAPTURE, indexKey);
		if (existing.isPresent())
		{
			InitialTargetCapture retained = load(binding, existing.orElseThrow());
			if (!retained.preparation().equals(preparation) ||
				!retained.projectRoot().equals(key.canonicalProjectRoot()) ||
				!retained.targetPath().equals(key.normalizedTargetRelativePath()))
				throw new IOException("Indexed target capture conflicts with current preparation");
			return existing.orElseThrow();
		}

		Path project = locator.verifyInitialFork(binding);
		Path target = key.normalizedTargetRelativePath();
		LiveGitInventory before = captureInventory(project, key);
		List<Path> regularPaths = before.paths().stream().filter(path ->
			path.kind() == LiveGitInventory.Entry.Kind.REGULAR).map(LiveGitInventory.Entry::path).toList();
		PromptSnapshot snapshot = PromptSnapshot.capture(project, regularPaths);
		PromptSource selected = new RetainedPromptSourceResolver(before, snapshot).resolve(target);
		Path sourcePath = selected.resolvedSourcePath();
		PromptSnapshot.Entry entry = snapshot.entries().get(sourcePath);
		if (entry == null || snapshot.entries().size() != regularPaths.size())
			throw new IOException("Selected target does not resolve to a captured regular prompt file: " + target);
		LiveGitInventory after = captureInventory(project, key);
		if (!before.equals(after) || before.paths().stream().noneMatch(path ->
			path.path().equals(sourcePath) && path.kind() == LiveGitInventory.Entry.Kind.REGULAR &&
				path.contentSha256().equals(entry.hash())))
			throw new IOException("Target or Git inventory changed during initial source capture");

		new PromptSnapshotRepository(store.directory()).save(snapshot);
		EvidenceRef inventory = store.appendImmutableJson("initial-git-inventories", JSON.writeValueAsBytes(before));
		InitialTargetCapture captured = new InitialTargetCapture(InitialTargetCapture.SCHEMA_VERSION,
			binding.campaignId(), preparation, key.canonicalProjectRoot(), target, sourcePath,
			selected.linkTarget(), selected.directoryLinks(), entry.hash(), inventory, snapshot.id());
		EvidenceRef reference = store.appendImmutableJson("contract-source-captures",
			JSON.writeValueAsBytes(captured));
		store.appendPreconfirmationIndex(binding, PreconfirmationIndexKind.CONTRACT_SOURCE_CAPTURE, indexKey,
			reference);
		return reference;
	}

	/**
	 * Captures the selected source without including CAT's own source-worktree state.
	 *
	 * @param project source worktree
	 * @param key original campaign key
	 * @return exact Git inventory
	 * @throws IOException if the source cannot be read
	 */
	private LiveGitInventory captureInventory(Path project, CampaignLookupKey key) throws IOException
	{
		if (project.equals(key.canonicalProjectRoot()))
			return LiveGitInventory.capture(project, locator.workflowRoot());
		return LiveGitInventory.capture(project);
	}

	/**
	 * Finds the first preparation's retained source capture after later campaign transitions.
	 *
	 * @param binding the registered campaign
	 * @return the revalidated initial capture reference
	 * @throws IOException if the indexed initial capture is missing or foreign
	 */
	public EvidenceRef initialCapture(CampaignBinding binding) throws IOException
	{
		return loadInitialCapture(binding).reference();
	}

	/**
	 * Finds and verifies the first preparation's retained source capture after later campaign transitions.
	 *
	 * @param binding the registered campaign
	 * @return the indexed reference and its validated capture
	 * @throws IOException if the indexed initial capture is missing or foreign
	 */
	public InitialCaptureEvidence loadInitialCapture(CampaignBinding binding) throws IOException
	{
		if (binding == null)
			throw new IOException("Campaign binding is missing");
		PreparationAuthority initial = new PreparationAuthority(binding.campaignId(),
			PreparationAuthority.Kind.INITIAL, Optional.empty(), Optional.empty(), 1,
			Optional.empty(), 0);
		EvidenceRef reference = store.readPreconfirmationIndex(binding,
			PreconfirmationIndexKind.CONTRACT_SOURCE_CAPTURE, PreparationIndexKey.derive(initial)).orElseThrow(() ->
			new IOException("Initial target capture is missing"));
		InitialTargetCapture capture = load(binding, reference);
		CampaignLookupKey key = locator.keyForBinding(binding);
		if (!capture.preparation().equals(initial) ||
			!capture.projectRoot().equals(key.canonicalProjectRoot()) ||
			!capture.targetPath().equals(key.normalizedTargetRelativePath()))
			throw new IOException("Initial target capture differs from registered campaign");
		return new InitialCaptureEvidence(reference, capture);
	}

	/**
	 * An indexed initial source capture verified against its registered campaign.
	 *
	 * @param reference indexed immutable capture evidence
	 * @param capture capture and source bytes validated for that reference
	 */
	public record InitialCaptureEvidence(EvidenceRef reference, InitialTargetCapture capture)
	{
	}

	/**
	 * Reads and verifies a retained target capture and its source bytes.
	 *
	 * @param binding the capture's registered campaign
	 * @param reference the immutable capture evidence
	 * @return the validated capture
	 * @throws IOException if the capture or its referenced sources are invalid
	 */
	public InitialTargetCapture load(CampaignBinding binding, EvidenceRef reference) throws IOException
	{
		if (binding == null)
			throw new IOException("Campaign binding is missing");
		Objects.requireNonNull(reference, "reference");
		if (!reference.collection().equals("contract-source-captures"))
			throw new IOException("Initial target capture has the wrong evidence collection");
		try
		{
			byte[] bytes = store.readImmutableJson(reference);
			InitialTargetCapture capture = JSON.readValue(bytes, InitialTargetCapture.class);
			if (capture == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(capture)) ||
				!capture.campaignId().equals(binding.campaignId()))
				throw new IOException("Initial target capture is foreign or noncanonical");
			Optional<EvidenceRef> indexed = store.readPreconfirmationIndex(binding,
				PreconfirmationIndexKind.CONTRACT_SOURCE_CAPTURE,
				PreparationIndexKey.derive(capture.preparation()));
			if (!indexed.equals(Optional.of(reference)))
				throw new IOException("Initial target capture lacks its exact campaign index");
			byte[] inventoryBytes = store.readImmutableJson(capture.inventoryRef());
			LiveGitInventory inventory = JSON.readValue(inventoryBytes, LiveGitInventory.class);
			if (inventory == null || !Arrays.equals(inventoryBytes, JSON.writeValueAsBytes(inventory)) ||
				!inventory.projectRoot().equals(locator.optimizationRoot(binding)))
				throw new IOException("Initial target Git inventory is foreign or noncanonical");
			PromptSnapshot snapshot = new PromptSnapshotRepository(store.directory()).load(capture.snapshotId());
			PromptSource selected = new RetainedPromptSourceResolver(inventory, snapshot).resolve(
				capture.targetPath());
			Path sourcePath = selected.resolvedSourcePath();
			PromptSnapshot.Entry target = snapshot.entries().get(sourcePath);
			List<LiveGitInventory.Entry> regularPaths = inventory.paths().stream().filter(path ->
				path.kind() == LiveGitInventory.Entry.Kind.REGULAR).toList();
			if (target == null || !capture.source().equals(selected) ||
				snapshot.entries().size() != regularPaths.size() ||
				regularPaths.stream().anyMatch(path ->
					!snapshot.entries().containsKey(path.path()) ||
						!snapshot.entries().get(path.path()).hash().equals(path.contentSha256())))
				throw new IOException("Initial target snapshot conflicts with its Git inventory");
			return capture;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Initial target capture is malformed", exception);
		}
	}

	/**
	 * Publishes the retained target snapshot manifest for an isolated inspection request.
	 *
	 * @param binding the capture's registered campaign
	 * @param captureRef the indexed initial target capture
	 * @return the immutable snapshot manifest reference
	 * @throws IOException if the capture or snapshot is invalid
	 */
	public EvidenceRef publishSnapshotManifest(CampaignBinding binding, EvidenceRef captureRef) throws IOException
	{
		InitialTargetCapture capture = load(binding, captureRef);
		PromptSnapshot snapshot = new PromptSnapshotRepository(store.directory()).load(capture.snapshotId());
		return store.appendImmutableJson("project-snapshot-manifests",
			JSON.writeValueAsBytes(snapshot.manifest()));
	}

	/**
	 * Materializes the retained project source for native inspection after the live worktree changes.
	 *
	 * @param binding the capture's registered campaign
	 * @param captureRef the indexed initial source capture
	 * @return the validated materialized project root
	 * @throws IOException if the captured project cannot be restored exactly
	 */
	public Path materializeProjectSnapshot(CampaignBinding binding, EvidenceRef captureRef) throws IOException
	{
		InitialTargetCapture capture = load(binding, captureRef);
		PromptSnapshot snapshot = new PromptSnapshotRepository(store.directory()).load(capture.snapshotId());
		Path optimizationRoot = locator.optimizationRoot(binding);
		if (!optimizationRoot.equals(capture.projectRoot()))
		{
			VerifiedCleanFork.Identity fork = VerifiedCleanFork.load(store.directory(), capture.projectRoot());
			Path original = VerifiedCleanFork.materializeOriginal(fork, capture.snapshotId());
			PromptSnapshot observed = PromptSnapshot.capture(original, snapshot.entries().keySet());
			if (!observed.id().equals(snapshot.id()))
				throw new IOException("Original project worktree differs from its retained source snapshot");
			return original;
		}
		return ProjectSnapshotMaterializer.materialize(snapshot, store.directory());
	}

	/**
	 * Requires both collaborators to name the same registered campaign.
	 *
	 * @param binding exact campaign binding
	 * @throws IOException if the locator and store disagree
	 */
	private void requireRegisteredStore(CampaignBinding binding) throws IOException
	{
		CampaignStore registered = locator.requireRegisteredBinding(binding);
		if (!registered.directory().equals(store.directory()))
			throw new IOException("Initial target capture store is not the registered campaign");
		store.lockedSnapshot(binding);
	}
}

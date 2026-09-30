package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.CandidateEvidenceRepository;
import io.github.cowwoc.cat.common.optimizer.candidate.LiveGitInventory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Materializes and verifies an intent's private generation worktree before native authoring.
 */
final class GenerationAttemptWorktreeService
{
	private static final String READINESS = "generation-attempt-readiness";
	private static final String INDEX = "generation-attempt-readiness-index";

	private final CampaignLocator locator;
	private final CampaignStore store;
	private final CampaignBinding binding;
	private final CandidateEvidenceRepository evidence;
	private final GenerationAttemptIntentService intents;

	/**
	 * Binds private worktree preparation to one registered campaign.
	 *
	 * @param locator registered campaign locator
	 * @param store pinned campaign store
	 */
	GenerationAttemptWorktreeService(CampaignLocator locator, CampaignStore store) throws IOException
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
		binding = store.binding();
		evidence = new CandidateEvidenceRepository(store, binding);
		intents = new GenerationAttemptIntentService(locator, store);
	}

	/**
	 * Creates or reopens the exact private worktree reserved by a current intent.
	 *
	 * @param intentRef retained generation intent
	 * @return indexed readiness record
	 * @throws IOException if the intent or private worktree is stale or cannot be verified
	 */
	EvidenceRef prepare(EvidenceRef intentRef) throws IOException
	{
		Objects.requireNonNull(intentRef, "intentRef");
		return CampaignFileLocks.withLock(store.directory().resolve("generation-worktree.lock"), () ->
			prepareLocked(intentRef));
	}

	/**
	 * Checks and publishes one private worktree while its campaign's preparation lock is held.
	 *
	 * @param intentRef retained generation intent
	 * @return indexed readiness record
	 * @throws IOException if the intent or private worktree is stale or cannot be verified
	 */
	private EvidenceRef prepareLocked(EvidenceRef intentRef) throws IOException
	{
		GenerationAttemptIntent intent = intents.requireCurrent(intentRef);
		Campaign campaign = store.load();
		String key = intentRef.evidenceId();
		Optional<LineageEvidenceRef> prior = store.findCreateOnceIndex(INDEX, key, intent.lineage(), campaign);
		if (prior.isPresent())
		{
			EvidenceRef reference = prior.orElseThrow().evidence();
			verifyPristine(read(reference), intentRef, intent);
			return reference;
		}

		VerifiedCleanFork.Identity fork = VerifiedCleanFork.load(store.directory(),
			locator.readRegisteredKey(binding).canonicalProjectRoot());
		if (!fork.optimizationRoot().equals(intent.selectedProject()))
			throw new IOException("Generation attempt names another selected worktree");
		LiveGitInventory selected = evidence.read(intent.selectedInventoryRef(),
			"generation-selected-inventories", LiveGitInventory.class);
		VerifiedCleanFork.ensureAttemptAt(fork, intent.privateProject(), intent.selectedProject(), selected);
		LiveGitInventory privateInventory = LiveGitInventory.capture(intent.privateProject());
		verifyExactReplay(selected, privateInventory);
		EvidenceRef inventoryRef = evidence.publish("generation-private-inventories", privateInventory);
		GenerationAttemptReadiness ready = new GenerationAttemptReadiness(
			GenerationAttemptReadiness.SCHEMA_VERSION, binding.campaignId(), intentRef, inventoryRef);
		EvidenceRef reference = evidence.publish(READINESS, ready);
		try
		{
			store.appendCreateOnceIndexIfCurrent(INDEX, key,
				new LineageEvidenceRef(intent.lineage(), reference), campaign);
		}
		catch (IOException publicationFailure)
		{
			Optional<LineageEvidenceRef> winner = store.findCreateOnceIndex(INDEX, key, intent.lineage());
			if (winner.isEmpty())
				throw publicationFailure;
			reference = winner.orElseThrow().evidence();
		}
		intents.requireCurrent(intentRef);
		verifyPristine(read(reference), intentRef, intent);
		return reference;
	}

	/**
	 * Reads a retained readiness record after reopening the campaign.
	 *
	 * @param reference immutable readiness evidence
	 * @return verified readiness
	 * @throws IOException if the intent, index, or private inventory is invalid
	 */
	GenerationAttemptReadiness read(EvidenceRef reference) throws IOException
	{
		return readWithSources(reference).ready();
	}

	/**
	 * Retains the intent validated while reading readiness for use within the same operation.
	 *
	 * @param reference immutable readiness evidence
	 * @return verified readiness and its source-validated intent
	 * @throws IOException if the intent, index, or private inventory is invalid
	 */
	private ReadinessSources readWithSources(EvidenceRef reference) throws IOException
	{
		GenerationAttemptReadiness ready = evidence.read(reference, READINESS,
			GenerationAttemptReadiness.class);
		GenerationAttemptIntent intent = intents.read(ready.intentRef());
		if (!ready.campaignId().equals(binding.campaignId()) ||
			!store.readCreateOnceIndex(INDEX, ready.intentRef().evidenceId(), intent.lineage()).evidence().
				equals(reference))
			throw new IOException("Generation attempt readiness conflicts with its intent or index");
		LiveGitInventory selected = evidence.read(intent.selectedInventoryRef(),
			"generation-selected-inventories", LiveGitInventory.class);
		LiveGitInventory privateInventory = evidence.read(ready.privateInventoryRef(),
			"generation-private-inventories", LiveGitInventory.class);
		if (!privateInventory.projectRoot().equals(intent.privateProject()))
			throw new IOException("Generation readiness names another private worktree");
		verifyExactReplay(selected, privateInventory);
		return new ReadinessSources(ready, intent);
	}

	/**
	 * Sources validated together by one readiness read.
	 *
	 * @param ready verified readiness
	 * @param intent source-validated intent named by the readiness
	 */
	private record ReadinessSources(GenerationAttemptReadiness ready, GenerationAttemptIntent intent)
	{
	}

	/**
	 * Checks that a retained private worktree still matches its pre-authoring inventory.
	 *
	 * @param reference indexed readiness evidence
	 * @return current readiness for native start
	 * @throws IOException if campaign authority or private state changed
	 */
	GenerationAttemptReadiness requirePristine(EvidenceRef reference) throws IOException
	{
		ReadinessSources sources = readWithSources(reference);
		GenerationAttemptReadiness ready = sources.ready();
		GenerationAttemptIntent intent = intents.requireCurrent(ready.intentRef(), sources.intent());
		verifyPristine(ready, ready.intentRef(), intent);
		return ready;
	}

	/**
	 * Requires the private worktree still to match the record published before authoring.
	 *
	 * @param ready verified readiness record
	 * @param intentRef retained generation intent
	 * @param intent current attempt intent
	 * @throws IOException if the private worktree changed after preparation
	 */
	private void verifyPristine(GenerationAttemptReadiness ready, EvidenceRef intentRef,
		GenerationAttemptIntent intent)
		throws IOException
	{
		if (!ready.intentRef().equals(intentRef))
			throw new IOException("Generation readiness names another attempt");
		VerifiedCleanFork.verifyRegisteredWorktree(
			locator.readRegisteredKey(binding).canonicalProjectRoot(), intent.privateProject());
		LiveGitInventory retained = evidence.read(ready.privateInventoryRef(),
			"generation-private-inventories", LiveGitInventory.class);
		if (!retained.equals(LiveGitInventory.capture(intent.privateProject())))
			throw new IOException("Private generation worktree changed before native authoring");
	}

	/**
	 * Compares the selected and private Git trees, index, bytes, modes, and internal link destinations.
	 *
	 * @param selected exact selected worktree before preparation
	 * @param privateInventory observed private worktree
	 * @throws IOException if content differs or a link still reaches outside the private worktree
	 */
	static void verifyExactReplay(LiveGitInventory selected, LiveGitInventory privateInventory)
		throws IOException
	{
		List<LiveGitInventory.Entry> source = selected.paths();
		List<LiveGitInventory.Entry> copy = privateInventory.paths();
		if (!selected.headCommit().equals(privateInventory.headCommit()) ||
			!selected.indexSha256().equals(privateInventory.indexSha256()) || source.size() != copy.size())
			throw new IOException("Private generation worktree differs from selected Git state");
		for (int index = 0; index < source.size(); ++index)
		{
			LiveGitInventory.Entry original = source.get(index);
			LiveGitInventory.Entry copied = copy.get(index);
			if (!original.path().equals(copied.path()) || original.kind() != copied.kind() ||
				!original.contentSha256().equals(copied.contentSha256()) ||
				!original.permissions().equals(copied.permissions()) ||
				!original.linkTarget().equals(copied.linkTarget()))
				throw new IOException("Private generation path differs from selected state: " + original.path());
			if (original.kind() == LiveGitInventory.Entry.Kind.SYMLINK)
				verifyPrivateLink(selected.projectRoot(), privateInventory.projectRoot(), original, copied);
		}
	}

	/**
	 * Requires a copied link to resolve to the corresponding path inside the private worktree.
	 *
	 * @param selectedRoot selected worktree root
	 * @param privateRoot private worktree root
	 * @param original selected link state
	 * @param copied private link state
	 * @throws IOException if the link is unresolved or reaches the selected or a live external target
	 */
	static void verifyPrivateLink(Path selectedRoot, Path privateRoot,
		LiveGitInventory.Entry original, LiveGitInventory.Entry copied) throws IOException
	{
		if (original.resolvedTarget().isEmpty() || copied.resolvedTarget().isEmpty())
			throw new IOException("Generation link destination cannot be verified: " + original.path());
		Path sourceTarget = Path.of(original.resolvedTarget());
		Path privateTarget = Path.of(copied.resolvedTarget());
		if (!sourceTarget.startsWith(selectedRoot) ||
			!privateTarget.equals(privateRoot.resolve(selectedRoot.relativize(sourceTarget))))
			throw new IOException("Generation link reaches outside the private worktree: " + original.path());
	}
}

package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.CandidateEvidenceRepository;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedEditableRoots;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedSourceAuthorityService;
import io.github.cowwoc.cat.common.optimizer.candidate.LiveGitInventory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Publishes the round-bound intent that must exist before a private generation worktree is created.
 */
final class GenerationAttemptIntentService
{
	private static final String INTENTS = "generation-attempt-intents";
	private static final String INDEX = "generation-attempt-intent-index";

	private final CampaignLocator locator;
	private final CampaignStore store;
	private final CandidateEvidenceRepository evidence;

	/**
	 * Binds attempt preparation to one registered campaign.
	 *
	 * @param locator campaign locator
	 * @param store pinned campaign store
	 * @throws IOException if the campaign binding cannot be verified
	 */
	GenerationAttemptIntentService(CampaignLocator locator, CampaignStore store) throws IOException
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
		evidence = new CandidateEvidenceRepository(store, store.binding());
	}

	/**
	 * Fixes one failing category's current round and selected worktree before private materialization.
	 *
	 * @param roundRef retained development round
	 * @param categoryId failing category named by the round
	 * @param logicalCallId generation call identity
	 * @return immutable intent reference
	 * @throws IOException if the round, candidate, or selected worktree is stale
	 */
	EvidenceRef publish(EvidenceRef roundRef, String categoryId, String logicalCallId) throws IOException
	{
		Objects.requireNonNull(roundRef, "roundRef");
		if (categoryId == null || categoryId.isBlank() || logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Generation category and call identity must be non-blank");
		Campaign campaign = store.load();
		DevelopmentRoundStart round = new DevelopmentRoundService(locator, store).read(roundRef);
		verifyCurrentRound(campaign, round);
		if (!round.failingCategories().contains(categoryId))
			throw new IOException("Generation category does not require an attempt in this round");
		Optional<LineageEvidenceRef> prior = store.findCreateOnceIndex(INDEX, logicalCallId, round.lineage(),
			campaign);
		if (prior.isPresent())
		{
			EvidenceRef reference = prior.orElseThrow().evidence();
			GenerationAttemptIntent retained = requireCurrent(reference);
			if (!retained.roundRef().equals(roundRef) || !retained.categoryId().equals(categoryId))
				throw new IOException("Generation call identity names another round or category");
			return reference;
		}

		EvidenceRef rootsRef = new ConfirmedSourceAuthorityService(locator, store).load(
			round.lineage().contractId());
		Path selected = locator.verifyInitialFork(store.binding()).toRealPath();
		Path privateProject = privateProject(selected, logicalCallId);
		if (Files.exists(privateProject, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Private generation worktree path is already occupied: " + privateProject);
		EvidenceRef inventoryRef = evidence.publish("generation-selected-inventories",
			LiveGitInventory.capture(selected));
		GenerationAttemptIntent intent = new GenerationAttemptIntent(GenerationAttemptIntent.SCHEMA_VERSION,
			campaign.campaignId(), round.lineage(), roundRef, categoryId, logicalCallId,
			round.incumbentCandidateId(), round.measurementEpochRef(), rootsRef, inventoryRef, selected,
			privateProject);
		EvidenceRef reference = evidence.publish(INTENTS, intent);
		try
		{
			store.appendCreateOnceIndexIfCurrent(INDEX, logicalCallId,
				new LineageEvidenceRef(round.lineage(), reference), campaign);
		}
		catch (IOException publicationFailure)
		{
			Optional<LineageEvidenceRef> winner = store.findCreateOnceIndex(INDEX, logicalCallId,
				round.lineage());
			if (winner.isEmpty())
				throw publicationFailure;
			reference = winner.orElseThrow().evidence();
		}
		GenerationAttemptIntent retained = requireCurrent(reference);
		if (!retained.roundRef().equals(roundRef) || !retained.categoryId().equals(categoryId))
			throw new IOException("Generation call identity names another round or category");
		return reference;
	}

	/**
	 * Reopens a retained intent and validates its source and index after restart.
	 *
	 * @param reference immutable intent
	 * @return source-backed intent
	 * @throws IOException if any retained source or index differs
	 */
	GenerationAttemptIntent read(EvidenceRef reference) throws IOException
	{
		GenerationAttemptIntent intent = evidence.read(reference, INTENTS, GenerationAttemptIntent.class);
		DevelopmentRoundStart round = new DevelopmentRoundService(locator, store).read(intent.roundRef());
		if (!intent.campaignId().equals(store.binding().campaignId()) ||
			!intent.lineage().equals(round.lineage()) ||
			!intent.incumbentCandidateId().equals(round.incumbentCandidateId()) ||
			!intent.measurementEpochRef().equals(round.measurementEpochRef()) ||
			!round.failingCategories().contains(intent.categoryId()) ||
			!store.readCreateOnceIndex(INDEX, intent.logicalCallId(), intent.lineage()).evidence().equals(reference))
			throw new IOException("Generation attempt intent conflicts with its round or index");
		ConfirmedEditableRoots roots = evidence.read(intent.confirmedRootsRef(), "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		LiveGitInventory inventory = evidence.read(intent.selectedInventoryRef(),
			"generation-selected-inventories", LiveGitInventory.class);
		if (!roots.confirmedContractRef().equals(new EvidenceRef("contracts", intent.lineage().contractId())) ||
			!roots.projectRoot().equals(locator.readRegisteredKey(store.binding()).canonicalProjectRoot()) ||
			!inventory.projectRoot().equals(intent.selectedProject()) ||
			!intent.selectedProject().equals(locator.optimizationRoot(store.binding()).toRealPath()) ||
			!intent.privateProject().equals(privateProject(intent.selectedProject(), intent.logicalCallId())))
			throw new IOException("Generation attempt intent has foreign roots or selected worktree");
		return intent;
	}

	/**
	 * Requires a retained attempt still to name the active round, candidate, and selected worktree.
	 *
	 * @param reference immutable intent
	 * @return current authorized intent
	 * @throws IOException if current authority or selected state changed
	 */
	GenerationAttemptIntent requireCurrent(EvidenceRef reference) throws IOException
	{
		GenerationAttemptIntent intent = read(reference);
		return requireCurrent(intent);
	}

	/**
	 * Checks current authority using an intent whose source chain was validated in this operation.
	 *
	 * @param reference exact retained intent reference
	 * @param intent source-validated intent from this operation
	 * @return current authorized intent
	 * @throws IOException if the retained intent differs or current authority changed
	 */
	GenerationAttemptIntent requireCurrent(EvidenceRef reference, GenerationAttemptIntent intent)
		throws IOException
	{
		Objects.requireNonNull(intent, "intent");
		requireMatchingIntent(evidence.read(reference, INTENTS, GenerationAttemptIntent.class), intent);
		return requireCurrent(intent);
	}

	/**
	 * Requires the supplied intent to match the exact retained intent before checking current authority.
	 *
	 * @param retained intent decoded from the retained reference
	 * @param intent intent supplied by the caller
	 * @throws IOException if the supplied intent differs
	 */
	static void requireMatchingIntent(GenerationAttemptIntent retained, GenerationAttemptIntent intent)
		throws IOException
	{
		Objects.requireNonNull(retained, "retained");
		Objects.requireNonNull(intent, "intent");
		if (!intent.equals(retained))
			throw new IOException("Generation attempt intent differs from its retained reference");
	}

	/**
	 * Checks the current campaign and selected worktree after the intent's sources have been validated.
	 *
	 * @param intent source-validated retained intent
	 * @return current authorized intent
	 * @throws IOException if current authority or selected state changed
	 */
	private GenerationAttemptIntent requireCurrent(GenerationAttemptIntent intent) throws IOException
	{
		Campaign campaign = store.load();
		if (campaign.status() != CampaignStatus.DEVELOPING || campaign.measurementState() == null ||
			campaign.measurementState().pause().isPresent() ||
			!campaign.campaignId().equals(intent.campaignId()) ||
			!campaign.activeLineageId().equals(intent.lineage().lineageId()) ||
			!campaign.currentCandidateId().equals(intent.incumbentCandidateId()) ||
			!campaign.measurementState().epochRef().equals(intent.measurementEpochRef()))
			throw new IOException("Generation attempt requires the current unpaused development round");
		VerifiedCleanFork.verifyRegisteredWorktree(
			locator.readRegisteredKey(store.binding()).canonicalProjectRoot(), intent.selectedProject());
		if (!LiveGitInventory.capture(intent.selectedProject()).equals(evidence.read(
			intent.selectedInventoryRef(), "generation-selected-inventories", LiveGitInventory.class)))
			throw new IOException("Generation selected worktree changed after intent publication");
		return intent;
	}

	/**
	 * Requires the round's candidate and conditions still to be active before generation.
	 *
	 * @param campaign current campaign state
	 * @param round retained development round
	 * @throws IOException if the round no longer controls generation
	 */
	private static void verifyCurrentRound(Campaign campaign, DevelopmentRoundStart round) throws IOException
	{
		if (campaign.status() != CampaignStatus.DEVELOPING || campaign.measurementState() == null ||
			campaign.measurementState().pause().isPresent() ||
			!campaign.campaignId().equals(round.campaignId()) ||
			!campaign.activeLineageId().equals(round.lineage().lineageId()) ||
			!campaign.currentCandidateId().equals(round.incumbentCandidateId()) ||
			!campaign.measurementState().epochRef().equals(round.measurementEpochRef()))
			throw new IOException("Generation attempt requires the current unpaused development round");
	}

	/**
	 * Chooses a stable sibling so relative source links resolve as they do in the selected worktree.
	 *
	 * @param selected selected optimization worktree
	 * @param logicalCallId stable identity of the generation call
	 * @return reserved path for the call's private worktree
	 */
	private Path privateProject(Path selected, String logicalCallId)
	{
		byte[] key = (store.directory() + ":" + logicalCallId).getBytes(StandardCharsets.UTF_8);
		byte[] digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256").digest(key);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
		return selected.getParent().resolve("." + selected.getFileName() + "-cat-generation-" +
			HexFormat.of().formatHex(digest).substring(0, 32));
	}
}

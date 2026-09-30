package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLocator;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLookupKey;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.InitialTargetCapture;
import io.github.cowwoc.cat.common.optimizer.state.InitialTargetCaptureService;
import io.github.cowwoc.cat.common.optimizer.state.LatestConfirmedClaim;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Publishes the first confirmed claim's source baseline and edit authority. */
public final class ConfirmedSourceAuthorityService
{
	private final CampaignLocator locator;
	private final CampaignStore store;
	private final CampaignBinding binding;
	private final CandidateEvidenceRepository evidence;
	private final JsonMapper json = SnapshotJson.mapper();

	/**
	 * Opens the source-authority publisher for one registered campaign.
	 *
	 * @param locator campaign's durable locator
	 * @param store pinned campaign store
	 * @throws IOException if the store and binding cannot be opened
	 */
	public ConfirmedSourceAuthorityService(CampaignLocator locator, CampaignStore store) throws IOException
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
		binding = store.binding();
		evidence = new CandidateEvidenceRepository(store, binding);
	}

	/**
	 * Publishes authority for the current initial claim or repairs its missing index after a crash.
	 *
	 * @param contractId exact confirmed contract identity
	 * @return revalidated immutable editable-roots reference
	 * @throws IOException if the claim, initial capture, source bytes, or publication differs
	 */
	public EvidenceRef publish(String contractId) throws IOException
	{
		LatestConfirmedClaim claim = claim(contractId);
		Path index = indexPath(claim.contractId());
		if (Files.exists(index, LinkOption.NOFOLLOW_LINKS))
			return load(contractId);
		ValidatedSource validated = validateSource(contractId, claim.key());
		String baselineId = new ConfirmedSourceBaselineRepository(store.directory()).save(validated.baseline());
		ConfirmedEditableRoots roots = new ConfirmedEditableRoots(ConfirmedEditableRoots.SCHEMA_VERSION,
			claim.key().canonicalProjectRoot(), new EvidenceRef("contracts", contractId), baselineId,
			validated.contract().promptSystem().editableRoots());
		EvidenceRef reference = evidence.publish("confirmed-editable-roots", roots);
		PromptSnapshotRepository.writeOnce(index, json.writeValueAsBytes(reference));
		return load(contractId);
	}

	/**
	 * Checks that the reviewed sources still match the captured worktree before confirmation advances the campaign.
	 *
	 * @param contractId prepared immutable contract
	 * @throws IOException if the target, source evidence, or live worktree differs
	 */
	public void verifyBeforeConfirmation(String contractId) throws IOException
	{
		validateSource(contractId, locator.readRegisteredKey(binding));
	}

	/**
	 * Reconstructs the source authority from the retained initial capture and live known sources.
	 *
	 * @param contractId prepared or confirmed contract
	 * @param key registered project and target
	 * @return verified contract and source baseline
	 * @throws IOException if any source or authority differs
	 */
	private ValidatedSource validateSource(String contractId, CampaignLookupKey key) throws IOException
	{
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(contractId);
		if (!contract.promptSystem().targetPath().equals(key.normalizedTargetRelativePath()))
			throw new IOException("Confirmed contract target differs from the registered campaign target");
		InitialTargetCaptureService sourceService = new InitialTargetCaptureService(locator, store);
		InitialTargetCapture capture = sourceService.loadInitialCapture(binding).capture();
		if (!capture.projectRoot().equals(key.canonicalProjectRoot()) ||
			!capture.targetPath().equals(contract.promptSystem().targetPath()))
			throw new IOException("Initial source capture differs from confirmed campaign key");
		PromptSnapshotRepository snapshots = new PromptSnapshotRepository(store.directory());
		PromptSnapshot initial = snapshots.load(capture.snapshotId());
		LiveGitInventory retainedInventory = json.readValue(store.readImmutableJson(capture.inventoryRef()),
			LiveGitInventory.class);
		Path project = locator.verifyInitialFork(binding);
		if (!retainedInventory.projectRoot().equals(project) ||
			!retainedInventory.equals(captureInventory(project, key)))
			throw new IOException("Initial project inventory changed before confirmation");
		List<Path> knownPaths = contract.promptSystem().sources().stream().map(PromptSource::resolvedSourcePath).
			distinct().toList();
		for (PromptSource source: contract.promptSystem().sources())
			verifyRetainedSource(source, retainedInventory, initial);
		ConfirmedSourceBaseline baseline = ConfirmedSourceBaseline.capture(project, knownPaths, initial);
		if (!retainedInventory.equals(captureInventory(project, key)))
			throw new IOException("Initial project inventory changed during confirmation");
		return new ValidatedSource(contract, baseline);
	}

	/**
	 * Checks one reviewed logical path against its no-follow link entry and retained regular-file bytes.
	 *
	 * @param source reviewed source with logical and resolved paths
	 * @param inventory retained H1 worktree inventory
	 * @param initial retained regular-file snapshot
	 * @throws IOException if the link, resolved file, or byte hash differs
	 */
	private void verifyRetainedSource(PromptSource source, LiveGitInventory inventory, PromptSnapshot initial)
		throws IOException
	{
		if (source.resolvedSourcePath().isAbsolute())
			throw new IOException("External resolved source requires separate authority: " + source.path());
		if (!new RetainedPromptSourceResolver(inventory, initial).resolve(source.path()).equals(source))
			throw new IOException("Confirmed source differs from initial capture: " + source.path());
	}

	/**
	 * Captures the active project with the same internal-directory exclusion used at H1.
	 *
	 * @param project verified optimization worktree
	 * @param key registered campaign key
	 * @return current no-follow project inventory
	 * @throws IOException if the worktree inventory cannot be read
	 */
	private LiveGitInventory captureInventory(Path project, CampaignLookupKey key) throws IOException
	{
		if (project.equals(key.canonicalProjectRoot()))
			return LiveGitInventory.capture(project, store.directory().getParent());
		return LiveGitInventory.capture(project);
	}

	/**
	 * Contract and source state validated against the same retained initial capture.
	 *
	 * @param contract immutable reviewed contract
	 * @param baseline matching known-source state
	 */
	private record ValidatedSource(ConfirmedContract contract, ConfirmedSourceBaseline baseline)
	{
	}

	/**
	 * Loads source authority through the current confirmed claim and its create-once index.
	 *
	 * @param contractId exact confirmed contract identity
	 * @return revalidated immutable editable-roots reference
	 * @throws IOException if the index or any bound evidence is absent or inconsistent
	 */
	public EvidenceRef load(String contractId) throws IOException
	{
		LatestConfirmedClaim claim = claim(contractId);
		Path index = indexPath(claim.contractId());
		if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Confirmed source authority index is missing");
		byte[] bytes = Files.readAllBytes(index);
		EvidenceRef reference;
		try
		{
			reference = json.readValue(bytes, EvidenceRef.class);
			if (reference == null || !Arrays.equals(bytes, json.writeValueAsBytes(reference)))
				throw new IOException("Confirmed source authority index is noncanonical");
		}
		catch (RuntimeException e)
		{
			throw new IOException("Confirmed source authority index is malformed", e);
		}
		ConfirmedEditableRoots roots = evidence.read(reference, "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(contractId);
		if (!roots.projectRoot().equals(claim.key().canonicalProjectRoot()) ||
			!roots.confirmedContractRef().equals(new EvidenceRef("contracts", contractId)) ||
			!roots.roots().equals(contract.promptSystem().editableRoots()))
			throw new IOException("Confirmed source authority differs from the current claim");
		ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(store.directory(), roots,
			new PromptSnapshotRepository(store.directory()));
		if (baseline.orderedKnownSourceStates().size() != contract.promptSystem().sources().stream().map(
			PromptSource::resolvedSourcePath).distinct().count())
			throw new IOException("Confirmed source baseline omits or adds known sources");
		for (PromptSource source: contract.promptSystem().sources())
		{
			boolean matched = baseline.orderedKnownSourceStates().stream().anyMatch(state ->
				state.path().equals(source.resolvedSourcePath()) && state.presence() == KnownSourcePresence.REGULAR &&
				state.contentSha256().orElseThrow().equals(source.sha256()));
			if (!matched)
				throw new IOException("Confirmed source baseline differs from contract: " + source.path());
		}
		return reference;
	}

	/**
	 * Requires the current claim to name this campaign and contract.
	 *
	 * @param contractId requested immutable contract
	 * @return current registered claim
	 * @throws IOException if the claim differs
	 */
	private LatestConfirmedClaim claim(String contractId) throws IOException
	{
		LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
		if (!claim.campaignId().equals(binding.campaignId()) || !claim.contractId().equals(contractId))
			throw new IOException("Confirmed source authority requires the current campaign claim");
		return claim;
	}

	/**
	 * Locates the create-once index for one contract in this campaign.
	 *
	 * @param contractId SHA-256 contract identity
	 * @return index file
	 */
	private Path indexPath(String contractId)
	{
		if (!PromptSnapshot.isSha256(contractId))
			throw new IllegalArgumentException("Invalid confirmed contract identity");
		return store.directory().resolve("confirmed-source-authority-index").resolve(contractId + ".json");
	}
}

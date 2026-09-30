package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import io.github.cowwoc.cat.common.optimizer.state.CampaignLocator;
import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.state.CampaignTransitions;
import io.github.cowwoc.cat.common.optimizer.state.InitialTargetCapture;
import io.github.cowwoc.cat.common.optimizer.state.InitialTargetCaptureService;
import io.github.cowwoc.cat.common.optimizer.state.LatestConfirmedClaim;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Admits the first candidate from retained prompt bytes without running an authoring model. */
public final class InitialCandidateService
{
	private final CampaignLocator locator;
	private final CampaignStore store;
	private final CampaignBinding binding;
	private final CandidateEvidenceRepository evidence;
	private final PromptSnapshotRepository snapshots;
	private final JsonMapper json = SnapshotJson.mapper();

	/**
	 * Binds initial candidate admission to one pinned campaign.
	 *
	 * @param locator owning workflow locator
	 * @param store pinned campaign store
	 * @throws IOException if campaign evidence cannot be opened
	 */
	public InitialCandidateService(CampaignLocator locator, CampaignStore store) throws IOException
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
		binding = store.binding();
		evidence = new CandidateEvidenceRepository(store, binding);
		snapshots = new PromptSnapshotRepository(store.directory());
	}

	/**
	 * Publishes and selects the first candidate or repairs its missing index from retained source evidence.
	 *
	 * @return immutable first candidate for the current confirmed lineage
	 * @throws IOException if confirmation or any source dependency is missing or corrupt
	 */
	public InitialCandidate capture() throws IOException
	{
		LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
		locator.verifyInitialFork(binding);
		Path index = indexPath(claim.lineageId());
		if (Files.exists(index, LinkOption.NOFOLLOW_LINKS))
			return adopt(loadIndexed());
		Derived derived = derive(claim);
		snapshots.save(derived.snapshot());
		EvidenceRef reference = evidence.publish("initial-candidates", derived.candidate());
		PromptSnapshotRepository.writeOnce(index, json.writeValueAsBytes(reference));
		return adopt(loadIndexed());
	}

	/**
	 * Commits a captured candidate as the active campaign candidate after source verification.
	 *
	 * @param indexed verified record and its immutable producer reference
	 * @return selected first candidate
	 * @throws IOException if the active campaign claim has changed or conflicts
	 */
	private InitialCandidate adopt(Indexed indexed) throws IOException
	{
		new CampaignTransitions(store).adoptInitialCandidate(binding, indexed.reference());
		return indexed.candidate();
	}

	/**
	 * Reconstructs the indexed first candidate from the current claim and retained original bytes.
	 *
	 * @return verified initial candidate
	 * @throws IOException if the index, candidate, or source evidence disagrees
	 */
	public InitialCandidate load() throws IOException
	{
		return loadIndexed().candidate();
	}

	/**
	 * Verifies that a transition names the exact indexed first candidate and confirmed source selection.
	 *
	 * @param reference proposed immutable first-candidate producer
	 * @throws IOException if the producer differs from the current confirmed source selection
	 */
	public void verifyIndexedReference(EvidenceRef reference) throws IOException
	{
		Objects.requireNonNull(reference, "reference");
		if (!loadIndexed().reference().equals(reference))
			throw new IOException("Candidate does not match confirmed initial source selection");
	}

	/**
	 * Reads the verified first candidate and its indexed producer reference.
	 *
	 * @return candidate and immutable reference
	 * @throws IOException if the index, candidate, or source evidence disagrees
	 */
	private Indexed loadIndexed() throws IOException
	{
		LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
		Path index = indexPath(claim.lineageId());
		if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Initial candidate index is missing");
		byte[] bytes = Files.readAllBytes(index);
		EvidenceRef reference;
		try
		{
			reference = json.readValue(bytes, EvidenceRef.class);
			if (reference == null || !Arrays.equals(bytes, json.writeValueAsBytes(reference)))
				throw new IOException("Initial candidate index is noncanonical");
		}
		catch (RuntimeException e)
		{
			throw new IOException("Initial candidate index is malformed", e);
		}
		InitialCandidate retained = evidence.read(reference, "initial-candidates", InitialCandidate.class);
		Derived expected = derive(claim);
		if (!retained.equals(expected.candidate()) ||
			!snapshots.load(retained.candidateSnapshotId()).id().equals(expected.snapshot().id()))
			throw new IOException("Initial candidate differs from its confirmed source capture");
		return new Indexed(retained, reference);
	}

	/**
	 * Selects the confirmed prompt system from the retained initial project snapshot.
	 *
	 * @param claim current confirmed lineage
	 * @return candidate and exact selected source bytes
	 * @throws IOException if source authority or snapshot evidence differs
	 */
	private Derived derive(LatestConfirmedClaim claim) throws IOException
	{
		if (!claim.campaignId().equals(binding.campaignId()))
			throw new IOException("Initial candidate belongs to another campaign");
		EvidenceRef rootsRef = new ConfirmedSourceAuthorityService(locator, store).load(claim.contractId());
		ConfirmedEditableRoots roots = evidence.read(rootsRef, "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(store.directory(), roots, snapshots);
		InitialTargetCaptureService sources = new InitialTargetCaptureService(locator, store);
		InitialTargetCaptureService.InitialCaptureEvidence indexedCapture = sources.loadInitialCapture(binding);
		EvidenceRef captureRef = indexedCapture.reference();
		InitialTargetCapture capture = indexedCapture.capture();
		if (!capture.snapshotId().equals(baseline.existingSnapshotRef()) ||
			!capture.projectRoot().equals(roots.projectRoot()))
			throw new IOException("Initial candidate source baseline differs from selected project capture");
		PromptSnapshot full = snapshots.load(capture.snapshotId());
		PromptSnapshot selected = full.select(ConfirmedSourceVerifier.candidatePaths(roots, baseline));
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
		PromptSource target = contract.promptSystem().sources().stream().filter(source ->
			source.path().equals(contract.promptSystem().targetPath())).findFirst().orElseThrow();
		PromptSnapshot.Entry resolved = selected.entries().get(capture.resolvedSourcePath());
		if (!capture.source().equals(target) ||
			resolved == null || !resolved.hash().equals(target.sha256()))
			throw new IOException("Initial candidate omits or changes the selected prompt source");
		InitialCandidate candidate = new InitialCandidate(InitialCandidate.SCHEMA_VERSION,
			binding.campaignId(), claim.lineageId(), claim.contractId(), rootsRef, captureRef, selected.id());
		return new Derived(candidate, selected);
	}

	/**
	 * Resolves a create-once lineage index below this campaign.
	 *
	 * @param lineageId SHA-256 confirmed lineage identity
	 * @return index path
	 */
	private Path indexPath(String lineageId)
	{
		if (!PromptSnapshot.isSha256(lineageId))
			throw new IllegalArgumentException("Initial candidate lineage identity is invalid");
		return store.directory().resolve("initial-candidate-index").resolve(lineageId + ".json");
	}

	/**
	 * Candidate record and retained source bytes derived together.
	 *
	 * @param candidate candidate identity and authority
	 * @param snapshot exact prompt-system bytes
	 */
	private record Derived(InitialCandidate candidate, PromptSnapshot snapshot)
	{
	}

	/**
	 * Verified indexed candidate and its transition producer identity.
	 *
	 * @param candidate retained first candidate
	 * @param reference immutable producer reference
	 */
	private record Indexed(InitialCandidate candidate, EvidenceRef reference)
	{
	}
}

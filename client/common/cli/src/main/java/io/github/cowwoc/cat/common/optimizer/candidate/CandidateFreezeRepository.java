package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.state.CampaignBinding;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Publishes and rechecks the immutable dependencies of a frozen candidate. */
public final class CandidateFreezeRepository
{
	private final Path campaignDirectory;
	private final CandidateEvidenceRepository evidence;
	private final PromptSnapshotRepository snapshots;

	/**
	 * Opens a pinned campaign's C3 freeze repositories.
	 *
	 * @param campaignDirectory campaign evidence directory
	 * @param expectedBinding exact C1 binding
	 * @throws IOException if the campaign does not match the binding
	 */
	public CandidateFreezeRepository(Path campaignDirectory, CampaignBinding expectedBinding) throws IOException
	{
		this.campaignDirectory = Objects.requireNonNull(campaignDirectory, "campaignDirectory");
		evidence = new CandidateEvidenceRepository(campaignDirectory, expectedBinding);
		snapshots = new PromptSnapshotRepository(campaignDirectory);
	}

	/**
	 * Publishes a candidate freeze after checking the live candidate and every known source.
	 *
	 * @param project pinned active worktree
	 * @param candidateSnapshotRef persisted candidate snapshot id
	 * @param confirmedEditableRootsRef C4-confirmed roots evidence
	 * @return immutable freeze reference
	 * @throws IOException if the candidate, roots, or source state differs
	 */
	public EvidenceRef freeze(Path project, String candidateSnapshotRef, EvidenceRef confirmedEditableRootsRef)
		throws IOException
	{
		Objects.requireNonNull(project, "project");
		ConfirmedEditableRoots roots = evidence.read(confirmedEditableRootsRef, "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		if (!project.toRealPath().equals(roots.projectRoot()))
			throw new IOException("Confirmed editable roots name a different pinned project root");
		PromptSnapshot candidate = snapshots.load(candidateSnapshotRef);
		ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(campaignDirectory, roots,
			snapshots);
		ConfirmedSourceVerifier.verifyLive(project, baseline, new DeliveryWriteAuthority(roots.roots()));
		DeliveryWriteAuthority authority = new DeliveryWriteAuthority(roots.roots());
		RootInventory inventory = RootInventory.capture(authority, candidate);
		FrozenCandidateVerifier.verify(project, candidate, authority, inventory, baseline);
		EvidenceRef inventoryRef = evidence.publish("root-inventories", inventory);
		CandidateFreezeRecord record = new CandidateFreezeRecord(CandidateFreezeRecord.SCHEMA_VERSION,
			candidateSnapshotRef, confirmedEditableRootsRef, inventoryRef, roots.confirmedSourceBaselineRef());
		return evidence.publish("candidate-freezes", record);
	}

	/**
	 * Rehashes all freeze dependencies and verifies the exact staged project state.
	 *
	 * @param stagedProject isolated project copy
	 * @param freezeRef immutable candidate freeze
	 * @throws IOException if any dependency or staged path differs
	 */
	public void verifyStaged(Path stagedProject, EvidenceRef freezeRef) throws IOException
	{
		CandidateFreezeRecord freeze = evidence.read(freezeRef, "candidate-freezes", CandidateFreezeRecord.class);
		ConfirmedEditableRoots roots = evidence.read(freeze.confirmedEditableRootsRef(), "confirmed-editable-roots",
			ConfirmedEditableRoots.class);
		RootInventory inventory = evidence.read(freeze.rootInventoryRef(), "root-inventories", RootInventory.class);
		PromptSnapshot candidate = snapshots.load(freeze.candidateSnapshotRef());
		if (!freeze.confirmedSourceBaselineRef().equals(roots.confirmedSourceBaselineRef()))
			throw new IOException("Frozen candidate baseline differs from confirmed roots");
		ConfirmedSourceBaseline baseline = ConfirmedSourceVerifier.load(campaignDirectory, roots,
			snapshots);
		FrozenCandidateVerifier.verify(stagedProject, candidate, new DeliveryWriteAuthority(roots.roots()),
			inventory, baseline);
	}
}

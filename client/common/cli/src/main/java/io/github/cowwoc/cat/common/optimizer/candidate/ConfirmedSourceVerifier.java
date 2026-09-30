package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.state.CampaignStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rechecks the immutable roots-bound source baseline at each candidate boundary.
 */
final class ConfirmedSourceVerifier
{
	/**
	 * Prevents construction.
	 */
	private ConfirmedSourceVerifier()
	{
	}

	/**
	 * Rehashes the roots-bound contract and loads the exact source baseline and original snapshot.
	 *
	 * @param campaignDirectory pinned campaign
	 * @param roots rehashed confirmed roots
	 * @param snapshots pinned snapshot repository
	 * @return validated baseline
	 * @throws IOException if a bound record is missing or differs
	 */
	static ConfirmedSourceBaseline load(Path campaignDirectory, ConfirmedEditableRoots roots,
		PromptSnapshotRepository snapshots) throws IOException
	{
		CampaignStore.open(campaignDirectory).readImmutableJson(roots.confirmedContractRef());
		ConfirmedSourceBaseline baseline = new ConfirmedSourceBaselineRepository(campaignDirectory).
			load(roots.confirmedSourceBaselineRef());
		PromptSnapshot existing = snapshots.load(baseline.existingSnapshotRef());
		for (KnownSourceState state: baseline.orderedKnownSourceStates())
		{
			PromptSnapshot.Entry entry = existing.entries().get(state.path());
			if (state.presence() == KnownSourcePresence.ABSENT && entry != null ||
				state.presence() == KnownSourcePresence.REGULAR &&
					(entry == null || !state.contentSha256().orElseThrow().equals(entry.hash())))
				throw new IOException("Confirmed source baseline differs from existing snapshot: " + state.path());
		}
		return baseline;
	}

	/**
	 * Rejects a live noneditable source that differs from the confirmed regular or absent state.
	 *
	 * @param project selected worktree
	 * @param baseline roots-bound confirmed sources
	 * @param authority confirmed edit authority
	 * @throws IOException if a source drifts
	 */
	static void verifyLive(Path project, ConfirmedSourceBaseline baseline, DeliveryWriteAuthority authority)
		throws IOException
	{
		for (KnownSourceState state: baseline.orderedKnownSourceStates())
		{
			if (authority.permits(state.path()))
				continue;
			PromptSnapshot.Entry observed = PromptSnapshot.capture(project, List.of(state.path())).entries().
				get(state.path());
			if (state.presence() == KnownSourcePresence.ABSENT && observed != null ||
				state.presence() == KnownSourcePresence.REGULAR &&
					(observed == null || !state.contentSha256().orElseThrow().equals(observed.hash())))
				throw new CandidateDeliveryException(CandidateDeliveryException.Reason.CONCURRENT_MODIFICATION,
					"Confirmed noneditable source changed: " + state.path(), Optional.empty(),
					List.of(state.path()));
		}
	}

	/**
	 * Selects every editable root and each known source outside those roots for a complete candidate snapshot.
	 *
	 * @param roots confirmed edit authority
	 * @param baseline confirmed known sources
	 * @return project-relative snapshot selection paths
	 */
	static List<Path> candidatePaths(ConfirmedEditableRoots roots, ConfirmedSourceBaseline baseline)
	{
		DeliveryWriteAuthority authority = new DeliveryWriteAuthority(roots.roots());
		List<Path> paths = new ArrayList<>(roots.roots().stream().map(EditableRoot::path).toList());
		for (KnownSourceState state: baseline.orderedKnownSourceStates())
			if (!authority.permits(state.path()))
				paths.add(state.path());
		return List.copyOf(paths);
	}
}

package io.github.cowwoc.cat.common.optimizer.candidate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Checks exact staged editable subtrees and known read-only source states before a trial. */
public final class FrozenCandidateVerifier
{
	/** Prevents construction. */
	private FrozenCandidateVerifier()
	{
	}

	/**
	 * Rehashes a staged copy against the frozen candidate, root inventory, and confirmed known sources.
	 *
	 * @param project staged project root
	 * @param candidate frozen candidate snapshot
	 * @param authority confirmed editable roots
	 * @param inventory frozen complete root paths
	 * @param baseline immutable confirmed known-source states
	 * @throws IOException if any staged byte or structural state differs
	 */
	public static void verify(Path project, PromptSnapshot candidate, DeliveryWriteAuthority authority,
		RootInventory inventory, ConfirmedSourceBaseline baseline) throws IOException
	{
		Objects.requireNonNull(project, "project");
		Objects.requireNonNull(candidate, "candidate");
		Objects.requireNonNull(authority, "authority");
		Objects.requireNonNull(inventory, "inventory");
		Objects.requireNonNull(baseline, "baseline");
		if (!inventory.equals(RootInventory.capture(authority, candidate)))
			throw new IOException("Frozen root inventory differs from candidate manifest");
		Set<Path> knownReadOnlyRegular = baseline.orderedKnownSourceStates().stream().filter(state ->
			state.presence() == KnownSourcePresence.REGULAR && !authority.permits(state.path())).
			map(KnownSourceState::path).collect(Collectors.toUnmodifiableSet());
		for (Path path: candidate.entries().keySet())
		{
			if (!authority.permits(path) && !knownReadOnlyRegular.contains(path))
				throw new IOException("Frozen manifest contains unconfirmed noneditable source: " + path);
		}
		for (RootInventory.Root root: inventory.roots())
		{
			PromptSnapshot live = PromptSnapshot.capture(project, List.of(root.editableRoot().path()));
			if (!live.entries().keySet().equals(Set.copyOf(root.manifestEntries())))
				throw new IOException("Staged frozen root paths differ: " + root.editableRoot().path());
			for (Map.Entry<Path, PromptSnapshot.Entry> entry: live.entries().entrySet())
			{
				PromptSnapshot.Entry expected = candidate.entries().get(entry.getKey());
				if (expected == null || !expected.hash().equals(entry.getValue().hash()) ||
					expected.kind() != entry.getValue().kind())
					throw new IOException("Staged frozen root bytes differ: " + entry.getKey());
			}
		}
		for (KnownSourceState state: baseline.orderedKnownSourceStates())
		{
			if (authority.permits(state.path()))
				continue;
			PromptSnapshot.Entry expected = candidate.entries().get(state.path());
			if (state.presence() == KnownSourcePresence.ABSENT && expected != null ||
				state.presence() == KnownSourcePresence.REGULAR &&
					(expected == null || !state.contentSha256().orElseThrow().equals(expected.hash())))
				throw new IOException("Frozen read-only source differs from baseline: " + state.path());
			PromptSnapshot live = PromptSnapshot.capture(project, List.of(state.path()));
			PromptSnapshot.Entry observed = live.entries().get(state.path());
			if (state.presence() == KnownSourcePresence.ABSENT && observed != null ||
				state.presence() == KnownSourcePresence.REGULAR &&
					(observed == null || !state.contentSha256().orElseThrow().equals(observed.hash())))
				throw new IOException("Staged read-only source differs from baseline: " + state.path());
		}
	}
}

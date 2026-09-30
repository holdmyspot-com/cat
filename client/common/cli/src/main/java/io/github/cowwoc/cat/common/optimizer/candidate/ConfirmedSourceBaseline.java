package io.github.cowwoc.cat.common.optimizer.candidate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable known-source state distinct from the initial selected-target snapshot.
 *
 * @param schemaVersion supported persistent schema
 * @param orderedKnownSourceStates complete ordered known-source path states
 * @param existingSnapshotRef identity of the snapshot containing every regular source
 */
public record ConfirmedSourceBaseline(int schemaVersion, List<KnownSourceState> orderedKnownSourceStates,
	String existingSnapshotRef)
{
	/** The only supported baseline schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates the persisted baseline shape. */
	public ConfirmedSourceBaseline
	{
		if (schemaVersion != SCHEMA_VERSION)
			throw new IllegalArgumentException("Unsupported confirmed source baseline schema: " + schemaVersion);
		orderedKnownSourceStates = List.copyOf(Objects.requireNonNull(orderedKnownSourceStates,
			"orderedKnownSourceStates"));
		if (orderedKnownSourceStates.isEmpty())
			throw new IllegalArgumentException("Confirmed source baseline must contain a known source");
		if (!PromptSnapshot.isSha256(existingSnapshotRef))
			throw new IllegalArgumentException("Existing snapshot ref must be a SHA-256 identity");
		Set<Path> paths = new HashSet<>();
		Path previous = null;
		for (KnownSourceState state: orderedKnownSourceStates)
		{
			Objects.requireNonNull(state, "knownSourceState");
			if (!paths.add(state.path()) || previous != null && previous.toString().compareTo(state.path().toString()) >= 0)
				throw new IllegalArgumentException("Known source states must be unique and path-ordered");
			previous = state.path();
		}
	}

	/**
	 * Captures a complete known-source union and verifies that the supplied snapshot still names the same live bytes.
	 *
	 * @param project selected live project
	 * @param knownPaths complete known-source file paths
	 * @param existingSnapshot snapshot that contains all regular known sources
	 * @return frozen known-source baseline
	 * @throws IOException if a live source differs from the snapshot
	 */
	public static ConfirmedSourceBaseline capture(Path project, Collection<Path> knownPaths,
		PromptSnapshot existingSnapshot) throws IOException
	{
		Objects.requireNonNull(knownPaths, "knownPaths");
		Objects.requireNonNull(existingSnapshot, "existingSnapshot");
		List<KnownSourceState> states = new ArrayList<>();
		Set<Path> unique = new HashSet<>();
		for (Path path: knownPaths)
		{
			Objects.requireNonNull(path, "knownPath");
			if (path.isAbsolute() || path.toString().isEmpty() || !path.equals(path.normalize()) ||
				!unique.add(path))
				throw new IllegalArgumentException("Known source path is invalid or duplicate: " + path);
			PromptSnapshot live = PromptSnapshot.capture(project, List.of(path));
			PromptSnapshot.Entry current = live.entries().get(path);
			PromptSnapshot.Entry recorded = existingSnapshot.entries().get(path);
			if (current == null != (recorded == null) || current != null && !current.hash().equals(recorded.hash()))
				throw new IOException("Known source does not match existing snapshot: " + path);
			if (current == null)
				states.add(new KnownSourceState(path, KnownSourcePresence.ABSENT, Optional.empty()));
			else
				states.add(new KnownSourceState(path, KnownSourcePresence.REGULAR, Optional.of(current.hash())));
		}
		states.sort(Comparator.comparing(state -> state.path().toString()));
		return new ConfirmedSourceBaseline(SCHEMA_VERSION, states, existingSnapshot.id());
	}
}

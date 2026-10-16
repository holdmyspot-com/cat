package io.github.cowwoc.cat.common.git.replay;

import java.util.List;
import java.util.Set;

/**
 * Immutable replay facts that a reviewer-authored candidate universe must cover.
 *
 * @param inventoryIds every captured changed artifact
 * @param sourceHashes the captured sources whose identities discovery evidence may not use as a boundary
 * @param exposedEntryPointIds every selectable entry point the universe must place
 * @param rootRange indicates whether the replay starts at the repository root
 */
public record CandidateUniverseRequirements(Set<String> inventoryIds, List<String> sourceHashes,
	Set<String> exposedEntryPointIds, boolean rootRange)
{
	/**
	 * Defensively retains the immutable facts used by candidate-universe validation.
	 */
	public CandidateUniverseRequirements
	{
		inventoryIds = Set.copyOf(inventoryIds);
		sourceHashes = List.copyOf(sourceHashes);
		exposedEntryPointIds = Set.copyOf(exposedEntryPointIds);
	}
}

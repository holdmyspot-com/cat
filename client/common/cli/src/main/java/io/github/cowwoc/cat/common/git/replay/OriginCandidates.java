package io.github.cowwoc.cat.common.git.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Selects earlier source commits whose same-path changes require origin review.
 */
public final class OriginCandidates
{
	/**
	 * Selects candidates whose changed path existed in an earlier reviewed source.
	 *
	 * @param sources captured source commits in replay order
	 * @param inventory immutable source-path facts
	 * @param sourcesToReview sources whose changed paths require origin review
	 * @return origin candidates in source and inventory order
	 */
	public List<Candidate> select(List<String> sources, List<History.InventoryEntry> inventory,
		Set<String> sourcesToReview)
	{
		Map<String, Integer> sourceIndexes = new HashMap<>();
		for (int index = 0; index < sources.size(); ++index)
			sourceIndexes.put(sources.get(index), index);

		List<Candidate> result = new ArrayList<>();
		for (History.InventoryEntry item : inventory)
		{
			if (!sourcesToReview.contains(item.source()) || item.beforeMode().equals("000000"))
				continue;
			int sourceIndex = sourceIndexes.get(item.source());
			List<String> predecessors = inventory.stream().filter(candidate -> candidate.path().equals(item.path()) &&
				sourceIndexes.get(candidate.source()) < sourceIndex).map(History.InventoryEntry::source).
				distinct().toList();
			if (!predecessors.isEmpty())
				result.add(new Candidate(item.id(), item.path(), predecessors));
		}
		return result;
	}

	/**
	 * One changed path with its earlier same-path source commits.
	 *
	 * @param inventoryId the immutable changed-path identifier
	 * @param path the changed repository-relative path
	 * @param predecessors earlier source commits that changed the same path
	 */
	public record Candidate(String inventoryId, String path, List<String> predecessors)
	{
	}
}

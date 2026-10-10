package io.github.cowwoc.cat.common.git.replay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Establishes whether immutable source transitions permit prior review evidence reuse.
 */
public final class ReviewEvidenceTransfer
{
	/**
	 * Prevents instantiation of this static transfer-policy owner.
	 */
	private ReviewEvidenceTransfer()
	{
	}

	/**
	 * Maps a reviewed prefix to a replacement prefix with identical tree transitions.
	 *
	 * @param previousSources sources that supplied the completed review
	 * @param replacementSources sources in the replacement manifest
	 * @param excludedSources prior sources that must be reopened
	 * @return old-to-new source-hash map
	 * @throws IOException if a source position does not describe the same transition
	 */
	public static Map<String, String> sourceTransitionMap(List<SourceSnapshot> previousSources,
		List<SourceSnapshot> replacementSources, Set<String> excludedSources) throws IOException
	{
		Map<String, String> result = new HashMap<>();
		for (int index = 0; index < previousSources.size(); ++index)
		{
			SourceSnapshot previous = previousSources.get(index);
			SourceSnapshot replacement = replacementSources.get(index);
			if (!previous.parentTree().equals(replacement.parentTree()) || !previous.tree().equals(replacement.tree()))
			{
				for (int suffix = index; suffix < previousSources.size(); ++suffix)
				{
					if (!excludedSources.contains(previousSources.get(suffix).hash()))
						throw new IOException("Topic-replay source transition " + (index + 1) + " differs; exclude that source " +
							"and every later prior source, then review the replacement suffix");
				}
				break;
			}
			result.put(previous.hash(), replacement.hash());
		}
		return result;
	}

	/**
	 * Returns whether a source-scoped identifier belongs to an excluded prior source.
	 *
	 * @param identifier the source-scoped identifier
	 * @param excludedSources prior source hashes excluded from reuse
	 * @return {@code true} if the identifier belongs to an excluded source
	 */
	static boolean isExcludedSourceScopedId(String identifier, Set<String> excludedSources)
	{
		if (identifier == null)
			return false;
		int separator = identifier.indexOf(':');
		return separator > 0 && excludedSources.contains(identifier.substring(0, separator));
	}

	/**
	 * Maps an identifier whose first colon-separated component is a source commit hash.
	 *
	 * @param sources the verified old-to-new source map
	 * @param identifier the source-scoped identifier
	 * @param evidenceKind reader-facing name of the evidence being imported
	 * @return the equivalent replacement identifier
	 * @throws IOException if the identifier is malformed or names a source outside the verified prefix
	 */
	public static String mapSourceScopedId(Map<String, String> sources, String identifier, String evidenceKind)
		throws IOException
	{
		if (identifier == null)
			throw new IOException("Cannot reuse " + evidenceKind + " without its source-scoped identifier");
		int separator = identifier.indexOf(':');
		if (separator < 1)
		{
			throw new IOException("Cannot reuse " + evidenceKind + " with a malformed source-scoped identifier: " +
				identifier);
		}
		return requiredMappedSource(sources, identifier.substring(0, separator), evidenceKind) + identifier.substring(
			separator);
	}

	/**
	 * Maps a source review's artifact placements into a replacement manifest.
	 *
	 * @param sources the verified old-to-new source map
	 * @param placements the placements to map
	 * @return mapped placements, or an empty list when a whole-source review has no placements
	 * @throws IOException if a placement cannot be mapped into the verified prefix
	 */
	static List<UnitPlacement> mapUnitPlacements(Map<String, String> sources, List<UnitPlacement> placements)
		throws IOException
	{
		if (placements == null)
			return List.of();
		List<UnitPlacement> result = new ArrayList<>(placements.size());
		for (UnitPlacement placement : placements)
		{
			result.add(new UnitPlacement(mapSourceScopedId(sources, placement.inventoryId(), "source-review placement"),
				placement.affectedUnit(), placement.owningResponsibility(), placement.destination(), placement.evidence()));
		}
		return result;
	}

	/**
	 * Returns a replacement source after checking that it belongs to the verified transition prefix.
	 *
	 * @param sources the verified old-to-new source map
	 * @param source the prior source hash
	 * @param evidenceKind reader-facing name of the evidence being imported
	 * @return the matching replacement source hash
	 * @throws IOException if the source is not in the verified prefix
	 */
	static String requiredMappedSource(Map<String, String> sources, String source, String evidenceKind)
		throws IOException
	{
		String mapped = sources.get(source);
		if (mapped == null)
		{
			throw new IOException("Cannot reuse " + evidenceKind + " because source " + source +
				" is not in the verified replacement prefix");
		}
		return mapped;
	}
}

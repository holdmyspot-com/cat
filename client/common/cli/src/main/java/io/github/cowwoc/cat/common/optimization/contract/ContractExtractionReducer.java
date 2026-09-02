package io.github.cowwoc.cat.common.optimization.contract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministically unions independent requirement extractions without deleting either agent's unique obligations.
 */
public final class ContractExtractionReducer
{
	private static final int MAXIMUM_RESOLUTION_PASSES = 2;

	/**
	 * Prevents construction of this static extraction reducer.
	 */
	private ContractExtractionReducer()
	{
	}

	/**
	 * Unions primary and challenger requirements, collapsing only equal normalized propositions with equal source traces.
	 *
	 * @param primary the primary extractor's complete requirement set
	 * @param challenger the independent challenger's complete requirement set
	 * @return the retained requirements and named semantic disagreements
	 */
	public static Merge merge(List<CertificationContract.Requirement> primary,
		List<CertificationContract.Requirement> challenger)
	{
		List<CertificationContract.Requirement> proposals = new ArrayList<>(copyRequirements(primary, "primary"));
		proposals.addAll(copyRequirements(challenger, "challenger"));
		proposals.sort(Comparator.comparing(CertificationContract.Requirement::identifier));

		List<CertificationContract.Requirement> retained = new ArrayList<>();
		List<Disagreement> disagreements = new ArrayList<>();
		for (CertificationContract.Requirement proposal : proposals)
		{
			int exactIndex = findExact(retained, proposal);
			if (exactIndex >= 0)
			{
				retained.set(exactIndex, collapse(retained.get(exactIndex), proposal));
				continue;
			}

			int conflictIndex = findConflict(retained, proposal);
			if (conflictIndex >= 0)
			{
				CertificationContract.Requirement conflict = retained.remove(conflictIndex);
				disagreements.add(disagreement(conflict, proposal));
				continue;
			}
			retained.add(proposal);
		}
		retained.sort(Comparator.comparing(CertificationContract.Requirement::identifier));
		disagreements.sort(Comparator.comparing(Disagreement::identifier));
		return new Merge(retained, disagreements);
	}

	/**
	 * Applies one bounded resolver pass without permitting unresolved records to disappear.
	 *
	 * @param merge the deterministic union result
	 * @param decisions resolver decisions for any named disagreements
	 * @param resolutionPass the one-based resolver pass
	 * @return ready, pending, or blocked extraction state
	 */
	public static Resolution resolve(Merge merge, List<ResolverDecision> decisions, int resolutionPass)
	{
		if (merge == null)
			throw new NullPointerException("merge must not be null");
		if (resolutionPass < 1 || resolutionPass > MAXIMUM_RESOLUTION_PASSES)
			throw new IllegalArgumentException("resolutionPass must be one or two: " + resolutionPass);
		if (decisions == null)
			throw new NullPointerException("decisions must not be null");

		Map<String, ResolverDecision> decisionsById = new HashMap<>();
		for (ResolverDecision decision : decisions)
		{
			if (decision == null)
				throw new NullPointerException("decisions must not contain null elements");
			if (decisionsById.putIfAbsent(decision.disagreementIdentifier(), decision) != null)
				throw new IllegalArgumentException("Duplicate resolver decision: " + decision.disagreementIdentifier());
		}

		List<CertificationContract.Requirement> requirements = new ArrayList<>(merge.requirements());
		List<Disagreement> unresolved = new ArrayList<>();
		for (Disagreement disagreement : merge.disagreements())
		{
			ResolverDecision decision = decisionsById.get(disagreement.identifier());
			if (decision == null || decision.action() == ResolverAction.UNRESOLVED)
			{
				unresolved.add(disagreement.withPassCount(resolutionPass));
				continue;
			}
			CertificationContract.Requirement retained = decision.retainedRequirement();
			if (retained == null)
				throw new IllegalArgumentException("A resolved disagreement requires one retained requirement");
			validateResolution(disagreement, decision.action(), retained);
			requirements.add(retained);
		}
		requirements.sort(Comparator.comparing(CertificationContract.Requirement::identifier));
		if (unresolved.isEmpty())
			return new Resolution(ResolutionStatus.READY, requirements, List.of());
		if (resolutionPass == MAXIMUM_RESOLUTION_PASSES)
			return new Resolution(ResolutionStatus.BLOCKED, requirements, unresolved);
		return new Resolution(ResolutionStatus.PENDING, requirements, unresolved);
	}

	/**
	 * Finds an exact collapse candidate.
	 *
	 * @param retained the retained requirements
	 * @param proposal the new proposal
	 * @return the matching index or {@code -1}
	 */
	private static int findExact(List<CertificationContract.Requirement> retained,
		CertificationContract.Requirement proposal)
	{
		for (int index = 0; index < retained.size(); ++index)
		{
			CertificationContract.Requirement candidate = retained.get(index);
			if (normalize(candidate.atomicProposition()).equals(normalize(proposal.atomicProposition())) &&
				candidate.sourceSpanIdentifiers().equals(proposal.sourceSpanIdentifiers()))
			{
				return index;
			}
		}
		return -1;
	}

	/**
	 * Finds a competing interpretation of one source span or identifier.
	 *
	 * @param retained the retained requirements
	 * @param proposal the new proposal
	 * @return the conflicting index or {@code -1}
	 */
	private static int findConflict(List<CertificationContract.Requirement> retained,
		CertificationContract.Requirement proposal)
	{
		for (int index = 0; index < retained.size(); ++index)
		{
			CertificationContract.Requirement candidate = retained.get(index);
			Set<String> sharedSpans = new HashSet<>(candidate.sourceSpanIdentifiers());
			sharedSpans.retainAll(proposal.sourceSpanIdentifiers());
			if (candidate.identifier().equals(proposal.identifier()) || !sharedSpans.isEmpty())
				return index;
		}
		return -1;
	}

	/**
	 * Collapses exact semantic duplicates while retaining all applicability and dependency references.
	 *
	 * @param first the first requirement
	 * @param second the duplicate requirement
	 * @return the collapsed requirement
	 */
	private static CertificationContract.Requirement collapse(CertificationContract.Requirement first,
		CertificationContract.Requirement second)
	{
		CertificationContract.Requirement selected = first;
		if (second.identifier().compareTo(first.identifier()) < 0)
			selected = second;
		return new CertificationContract.Requirement(selected.identifier(), selected.atomicProposition(),
			first.sourceSpanIdentifiers(), union(first.applicabilityRuleIdentifiers(),
				second.applicabilityRuleIdentifiers()), union(first.dependencyIdentifiers(), second.dependencyIdentifiers()));
	}

	/**
	 * Creates a stable disagreement record.
	 *
	 * @param first the first interpretation
	 * @param second the competing interpretation
	 * @return the disagreement
	 */
	private static Disagreement disagreement(CertificationContract.Requirement first,
		CertificationContract.Requirement second)
	{
		List<CertificationContract.Requirement> competing = new ArrayList<>(List.of(first, second));
		competing.sort(Comparator.comparing(CertificationContract.Requirement::identifier));
		Set<String> sourceSpans = new TreeSet<>(first.sourceSpanIdentifiers());
		sourceSpans.addAll(second.sourceSpanIdentifiers());
		String identifier = "disagreement:" + competing.get(0).identifier() + ":" + competing.get(1).identifier();
		return new Disagreement(identifier, competing, List.copyOf(sourceSpans), ConflictCategory.SEMANTIC_INTERPRETATION,
			0);
	}

	/**
	 * Validates that a resolver choice preserves required source traces.
	 *
	 * @param disagreement the conflict
	 * @param action the resolver action
	 * @param retained the retained requirement
	 */
	private static void validateResolution(Disagreement disagreement, ResolverAction action,
		CertificationContract.Requirement retained)
	{
		if (action == ResolverAction.CHOOSE_WITH_TRACE)
		{
			boolean matches = disagreement.competingRequirements().stream().
				anyMatch(candidate -> candidate.equals(retained));
			if (!matches)
				throw new IllegalArgumentException("choose with trace must retain one competing requirement");
			return;
		}
		if (!retained.sourceSpanIdentifiers().containsAll(disagreement.sourceSpanUnion()))
			throw new IllegalArgumentException("A merged resolution must preserve every source span");
	}

	/**
	 * Returns a sorted set union.
	 *
	 * @param first the first list
	 * @param second the second list
	 * @return the sorted union
	 */
	private static List<String> union(List<String> first, List<String> second)
	{
		Set<String> result = new TreeSet<>(first);
		result.addAll(second);
		return List.copyOf(result);
	}

	/**
	 * Normalizes proposition whitespace and case for exact duplicate detection.
	 *
	 * @param proposition the proposition
	 * @return the normalized proposition
	 */
	private static String normalize(String proposition)
	{
		return proposition.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	/**
	 * Copies a nonnull requirement list.
	 *
	 * @param values the requirements
	 * @param name the input name
	 * @return the immutable copy
	 */
	private static List<CertificationContract.Requirement> copyRequirements(
		List<CertificationContract.Requirement> values, String name)
	{
		if (values == null)
			throw new NullPointerException(name + " must not be null");
		for (CertificationContract.Requirement value : values)
		{
			if (value == null)
				throw new NullPointerException(name + " must not contain null elements");
		}
		return List.copyOf(values);
	}

	/**
	 * Names a disagreement category.
	 */
	public enum ConflictCategory
	{
		SEMANTIC_INTERPRETATION
	}

	/**
	 * Names a resolver decision.
	 */
	public enum ResolverAction
	{
		CHOOSE_WITH_TRACE,
		MERGE_PRESERVING_SOURCE,
		UNRESOLVED
	}

	/**
	 * Names the bounded extraction-resolution state.
	 */
	public enum ResolutionStatus
	{
		READY,
		PENDING,
		BLOCKED
	}

	/**
	 * Holds the deterministic union result.
	 *
	 * @param requirements all non-conflicting retained requirements
	 * @param disagreements every named semantic conflict
	 */
	public record Merge(List<CertificationContract.Requirement> requirements, List<Disagreement> disagreements)
	{
		/**
		 * Snapshots one merge result.
		 *
		 * @param requirements all non-conflicting retained requirements
		 * @param disagreements every named semantic conflict
		 */
		public Merge
		{
			requirements = List.copyOf(requirements);
			disagreements = List.copyOf(disagreements);
		}
	}

	/**
	 * Holds one named disagreement.
	 *
	 * @param identifier the stable disagreement identifier
	 * @param competingRequirements the competing records
	 * @param sourceSpanUnion every source span from both records
	 * @param category the conflict category
	 * @param resolutionPassCount completed resolver passes
	 */
	public record Disagreement(String identifier, List<CertificationContract.Requirement> competingRequirements,
		List<String> sourceSpanUnion, ConflictCategory category, int resolutionPassCount)
	{
		/**
		 * Validates and snapshots one disagreement.
		 *
		 * @param identifier the stable disagreement identifier
		 * @param competingRequirements the competing records
		 * @param sourceSpanUnion every source span from both records
		 * @param category the conflict category
		 * @param resolutionPassCount completed resolver passes
		 */
		public Disagreement
		{
			if (identifier == null || identifier.isBlank())
				throw new IllegalArgumentException("identifier must not be blank");
			competingRequirements = List.copyOf(competingRequirements);
			if (competingRequirements.size() != 2)
				throw new IllegalArgumentException("A disagreement requires exactly two competing requirements");
			sourceSpanUnion = List.copyOf(sourceSpanUnion);
			if (category == null)
				throw new NullPointerException("category must not be null");
			if (resolutionPassCount < 0 || resolutionPassCount > MAXIMUM_RESOLUTION_PASSES)
				throw new IllegalArgumentException("resolutionPassCount must be between zero and two");
		}

		/**
		 * Returns the same disagreement after another resolver pass.
		 *
		 * @param passCount the completed pass count
		 * @return the updated disagreement
		 */
		private Disagreement withPassCount(int passCount)
		{
			return new Disagreement(identifier, competingRequirements, sourceSpanUnion, category, passCount);
		}
	}

	/**
	 * Holds one semantic resolver decision.
	 *
	 * @param disagreementIdentifier the named disagreement
	 * @param action the resolver result
	 * @param retainedRequirement the chosen or merged requirement, or {@code null} when unresolved
	 * @param rationale the source-traced rationale
	 */
	public record ResolverDecision(String disagreementIdentifier, ResolverAction action,
		CertificationContract.Requirement retainedRequirement, String rationale)
	{
		/**
		 * Validates one resolver decision.
		 *
		 * @param disagreementIdentifier the named disagreement
		 * @param action the resolver result
		 * @param retainedRequirement the chosen or merged requirement, or {@code null} when unresolved
		 * @param rationale the source-traced rationale
		 */
		public ResolverDecision
		{
			if (disagreementIdentifier == null || disagreementIdentifier.isBlank())
				throw new IllegalArgumentException("disagreementIdentifier must not be blank");
			if (action == null)
				throw new NullPointerException("action must not be null");
			if (rationale == null || rationale.isBlank())
				throw new IllegalArgumentException("rationale must not be blank");
		}
	}

	/**
	 * Holds the result of one bounded resolver pass.
	 *
	 * @param status the extraction state
	 * @param requirements every retained requirement
	 * @param unresolvedDisagreements conflicts still unresolved at this pass
	 */
	public record Resolution(ResolutionStatus status, List<CertificationContract.Requirement> requirements,
		List<Disagreement> unresolvedDisagreements)
	{
		/**
		 * Validates and snapshots one resolution result.
		 *
		 * @param status the extraction state
		 * @param requirements every retained requirement
		 * @param unresolvedDisagreements conflicts still unresolved at this pass
		 */
		public Resolution
		{
			if (status == null)
				throw new NullPointerException("status must not be null");
			requirements = List.copyOf(requirements);
			unresolvedDisagreements = List.copyOf(unresolvedDisagreements);
		}
	}
}

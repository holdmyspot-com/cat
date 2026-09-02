package io.github.cowwoc.cat.common.optimization.contract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Monotonically combines two independent whole-contract proposals before deterministic contract freezing.
 */
public final class ContractDraftReducer
{
	/**
	 * Prevents construction of this static reducer.
	 */
	private ContractDraftReducer()
	{
	}

	/**
	 * Combines two independent proposals and applies one bounded semantic-resolution pass.
	 *
	 * @param primary the primary extractor's complete proposal
	 * @param challenger the independent challenger's complete proposal
	 * @param resolverDecisions semantic resolver decisions
	 * @param resolutionPass the one-based bounded resolution pass
	 * @return the combined extraction state and retained evidence
	 */
	public static Result merge(CertificationContract.Draft primary, CertificationContract.Draft challenger,
		List<ContractExtractionReducer.ResolverDecision> resolverDecisions, int resolutionPass)
	{
		Objects.requireNonNull(primary, "primary must not be null");
		Objects.requireNonNull(challenger, "challenger must not be null");
		Objects.requireNonNull(resolverDecisions, "resolverDecisions must not be null");
		List<Conflict> conflicts = new ArrayList<>();
		if (!primary.schemaVersion().equals(challenger.schemaVersion()))
		{
			conflicts.add(new Conflict(RecordKind.SCHEMA, primary.schemaVersion(),
				"Challenger declared " + challenger.schemaVersion()));
		}

		List<CertificationContract.Artifact> artifacts = mergeRecords(primary.artifacts(), challenger.artifacts(),
			CertificationContract.Artifact::identifier, RecordKind.ARTIFACT, conflicts);
		List<CertificationContract.SourceSpan> spans = mergeRecords(primary.sourceSpans(), challenger.sourceSpans(),
			CertificationContract.SourceSpan::identifier, RecordKind.SOURCE_SPAN, conflicts);
		findDuplicates(primary.requirements(), CertificationContract.Requirement::identifier, RecordKind.REQUIREMENT,
			"primary", conflicts);
		findDuplicates(challenger.requirements(), CertificationContract.Requirement::identifier, RecordKind.REQUIREMENT,
			"challenger", conflicts);
		ContractExtractionReducer.Merge requirementMerge = ContractExtractionReducer.merge(primary.requirements(),
			challenger.requirements());
		ContractExtractionReducer.Resolution resolution = ContractExtractionReducer.resolve(requirementMerge,
			resolverDecisions, resolutionPass);
		List<CertificationContract.ApplicabilityRule> applicabilityRules = mergeRecords(primary.applicabilityRules(),
			challenger.applicabilityRules(), CertificationContract.ApplicabilityRule::identifier,
			RecordKind.APPLICABILITY_RULE, conflicts);
		List<CertificationContract.Distribution> distributions = mergeRecords(primary.distributions(),
			challenger.distributions(), CertificationContract.Distribution::identifier, RecordKind.DISTRIBUTION, conflicts);
		List<CertificationContract.LeafFamily> families = mergeRecords(primary.families(), challenger.families(),
			CertificationContract.LeafFamily::identifier, RecordKind.LEAF_FAMILY, conflicts);
		List<CertificationContract.Assertion> assertions = mergeRecords(primary.assertions(), challenger.assertions(),
			CertificationContract.Assertion::identifier, RecordKind.ASSERTION, conflicts);
		List<CertificationContract.PromptTest> promptTests = mergeRecords(primary.promptTests(), challenger.promptTests(),
			CertificationContract.PromptTest::identifier, RecordKind.PROMPT_TEST, conflicts);
		List<String> protocols = mergeStrings(primary.evaluatorProtocolIds(), challenger.evaluatorProtocolIds());

		CertificationContract.Draft merged = new CertificationContract.Draft(primary.schemaVersion(), artifacts, spans,
			resolution.requirements(), applicabilityRules, distributions, families, assertions, protocols, promptTests);
		conflicts.sort(Comparator.comparing(Conflict::kind).thenComparing(Conflict::identifier));
		if (!conflicts.isEmpty() || resolution.status() == ContractExtractionReducer.ResolutionStatus.BLOCKED)
		{
			return new Result(Status.BLOCKED, merged, Optional.empty(), conflicts,
				resolution.unresolvedDisagreements(), List.of());
		}
		if (resolution.status() == ContractExtractionReducer.ResolutionStatus.PENDING)
			return new Result(Status.PENDING, merged, Optional.empty(), List.of(),
				resolution.unresolvedDisagreements(), List.of());

		CertificationContract.Result frozen = CertificationContract.freeze(merged);
		if (frozen.status() == CertificationContract.Status.BLOCKED)
		{
			return new Result(Status.BLOCKED, merged, Optional.empty(), List.of(), List.of(), frozen.issues());
		}
		return new Result(Status.READY, merged, frozen.contract(), List.of(), List.of(), List.of());
	}

	/**
	 * Combines uniquely identified records while retaining every unique proposal.
	 *
	 * @param primary the primary records
	 * @param challenger the challenger records
	 * @param identifier the identifier accessor
	 * @param kind the record kind
	 * @param conflicts the conflict accumulator
	 * @param <T> the record type
	 * @return the sorted combined records
	 */
	private static <T> List<T> mergeRecords(List<T> primary, List<T> challenger, Function<T, String> identifier,
		RecordKind kind, List<Conflict> conflicts)
	{
		findDuplicates(primary, identifier, kind, "primary", conflicts);
		findDuplicates(challenger, identifier, kind, "challenger", conflicts);
		Map<String, T> primaryById = firstByIdentifier(primary, identifier);
		List<T> result = new ArrayList<>(primary);
		Set<String> challengerSeen = new HashSet<>();
		for (T value : challenger)
		{
			String id = identifier.apply(value);
			if (!challengerSeen.add(id))
				continue;
			T existing = primaryById.get(id);
			if (existing == null)
				result.add(value);
			else if (!existing.equals(value))
				conflicts.add(new Conflict(kind, id, "Primary and challenger records differ"));
		}
		result.sort(Comparator.comparing(identifier));
		return List.copyOf(result);
	}

	/**
	 * Records duplicate identifiers within one extractor's proposal.
	 *
	 * @param values the records
	 * @param identifier the identifier accessor
	 * @param kind the record kind
	 * @param owner the extractor name
	 * @param conflicts the conflict accumulator
	 * @param <T> the record type
	 */
	private static <T> void findDuplicates(List<T> values, Function<T, String> identifier, RecordKind kind,
		String owner, List<Conflict> conflicts)
	{
		Set<String> seen = new HashSet<>();
		for (T value : values)
		{
			String id = identifier.apply(value);
			if (!seen.add(id))
				conflicts.add(new Conflict(kind, id, "Duplicate identifier in " + owner + " proposal"));
		}
	}

	/**
	 * Indexes the first record under each identifier.
	 *
	 * @param values the records
	 * @param identifier the identifier accessor
	 * @param <T> the record type
	 * @return the first-record index
	 */
	private static <T> Map<String, T> firstByIdentifier(List<T> values, Function<T, String> identifier)
	{
		Map<String, T> result = new HashMap<>();
		for (T value : values)
			result.putIfAbsent(identifier.apply(value), value);
		return result;
	}

	/**
	 * Returns the sorted union of open protocol identifiers.
	 *
	 * @param primary the primary identifiers
	 * @param challenger the challenger identifiers
	 * @return the sorted union
	 */
	private static List<String> mergeStrings(List<String> primary, List<String> challenger)
	{
		Set<String> result = new HashSet<>(primary);
		result.addAll(challenger);
		return result.stream().sorted().toList();
	}

	/**
	 * Names the whole-contract reduction result.
	 */
	public enum Status
	{
		READY,
		PENDING,
		BLOCKED
	}

	/**
	 * Names each independently identified contract record kind.
	 */
	public enum RecordKind
	{
		SCHEMA,
		ARTIFACT,
		SOURCE_SPAN,
		REQUIREMENT,
		APPLICABILITY_RULE,
		DISTRIBUTION,
		LEAF_FAMILY,
		ASSERTION,
		PROMPT_TEST
	}

	/**
	 * Holds one deterministic identity collision.
	 *
	 * @param kind the record kind
	 * @param identifier the conflicting identifier
	 * @param detail the conflict detail
	 */
	public record Conflict(RecordKind kind, String identifier, String detail)
	{
		/**
		 * Validates one conflict.
		 *
		 * @param kind the record kind
		 * @param identifier the conflicting identifier
		 * @param detail the conflict detail
		 */
		public Conflict
		{
			Objects.requireNonNull(kind, "kind must not be null");
			if (identifier == null || identifier.isBlank())
				throw new IllegalArgumentException("identifier must not be blank");
			if (detail == null || detail.isBlank())
				throw new IllegalArgumentException("detail must not be blank");
		}
	}

	/**
	 * Holds one complete whole-contract reduction result.
	 *
	 * @param status the reduction state
	 * @param mergedDraft the monotonic draft retained so far
	 * @param contract the frozen contract when ready
	 * @param conflicts deterministic record conflicts
	 * @param disagreements unresolved semantic disagreements
	 * @param contractIssues deterministic freeze issues
	 */
	public record Result(Status status, CertificationContract.Draft mergedDraft,
		Optional<CertificationContract> contract, List<Conflict> conflicts,
		List<ContractExtractionReducer.Disagreement> disagreements, List<CertificationContract.Issue> contractIssues)
	{
		/**
		 * Validates and snapshots one reduction result.
		 *
		 * @param status the reduction state
		 * @param mergedDraft the monotonic draft retained so far
		 * @param contract the frozen contract when ready
		 * @param conflicts deterministic record conflicts
		 * @param disagreements unresolved semantic disagreements
		 * @param contractIssues deterministic freeze issues
		 */
		public Result
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(mergedDraft, "mergedDraft must not be null");
			Objects.requireNonNull(contract, "contract must not be null");
			conflicts = List.copyOf(conflicts);
			disagreements = List.copyOf(disagreements);
			contractIssues = List.copyOf(contractIssues);
			if ((status == Status.READY) != contract.isPresent())
				throw new IllegalArgumentException("Only a ready reduction may contain a frozen contract");
		}
	}
}

package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;

import java.util.List;

/**
 * The validated contract and findings from the session that reads the prompt system and proposes its requirements
 * and tests (the deriver).
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param contract the proposed contract, with the judge model, effort, prompt and result schema that CAT supplies
 * @param derivedSources the prompt files that load although no harness event reports them
 * @param scopeConditions the conditions that make certification unavailable
 * @param conflicts the groups of requirements that no reply can satisfy together
 * @param observedSources non-blank text listing the prompt sources reported loading by the deriver session's
 *   harness events, one per line, or {@code none}
 * @param derivation the deriver's non-blank output as JSON, which a revision sends back to the deriver with the user's
 *   feedback
 */
public record DerivedProposal(Contract contract, List<DerivedSource> derivedSources,
	List<ScopeCondition> scopeConditions, List<Conflict> conflicts, String observedSources, String derivation)
{
	/**
	 * Creates a derived proposal.
	 *
	 * @param contract the proposed contract, with the judge model, effort, prompt and result schema that CAT supplies
	 * @param derivedSources the prompt files that load although no harness event reports them
	 * @param scopeConditions the conditions that make certification unavailable
	 * @param conflicts the groups of requirements that no reply can satisfy together
	 * @param observedSources non-blank text listing the prompt sources reported loading by the deriver session's
	 *   harness events
	 * @param derivation the deriver's non-blank output as JSON
	 * @throws NullPointerException if any of the arguments are null, or a list contains null
	 * @throws IllegalArgumentException if {@code observedSources} or {@code derivation} are blank
	 */
	public DerivedProposal
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(observedSources, "observedSources").isNotBlank();
		requireThat(derivation, "derivation").isNotBlank();
		derivedSources = List.copyOf(derivedSources);
		scopeConditions = List.copyOf(scopeConditions);
		conflicts = List.copyOf(conflicts);
	}
}

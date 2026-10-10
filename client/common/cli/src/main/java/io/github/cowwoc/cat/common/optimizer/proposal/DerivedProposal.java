package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The validated contract and findings from the session that reads the prompt system and proposes its requirements
 * and tests (the contract author).
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param contract the proposed contract, with the judge model, effort, prompt and result schema that CAT supplies
 * @param derivedSources the prompt files that load although no harness event reports them
 * @param scopeConditions the conditions that make certification unavailable
 * @param conflicts the groups of requirements that no reply can satisfy together
 * @param observedSources non-blank text listing the prompt sources reported loading by the contract author session's
 *   harness events, one per line, or {@code none}
 * @param derivation the contract author's non-blank output as JSON, which a revision sends back to the contract author
 *   with the user's feedback
 * @param citedScenarios the content of each recorded regression scenario that a prompt test cites, by citation
 */
public record DerivedProposal(Contract contract, List<DerivedSource> derivedSources,
	List<ScopeCondition> scopeConditions, List<Conflict> conflicts, String observedSources, String derivation,
	Map<String, String> citedScenarios)
{
	/**
	 * Creates a derived proposal.
	 *
	 * @param contract the proposed contract, with the judge model, effort, prompt and result schema that CAT supplies
	 * @param derivedSources the prompt files that load although no harness event reports them
	 * @param scopeConditions the conditions that make certification unavailable
	 * @param conflicts the groups of requirements that no reply can satisfy together
	 * @param observedSources non-blank text listing the prompt sources reported loading by the contract author
	 *   session's harness events
	 * @param derivation the contract author's non-blank output as JSON
	 * @param citedScenarios the content of each recorded regression scenario that a prompt test cites, by citation
	 * @throws NullPointerException if any of the arguments are null, or a list or map contains null
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
		requireThat(citedScenarios, "citedScenarios").isNotNull();
		citedScenarios = Collections.unmodifiableMap(new LinkedHashMap<>(citedScenarios));
	}
}

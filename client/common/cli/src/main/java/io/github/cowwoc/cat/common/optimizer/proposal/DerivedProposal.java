package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;

import java.util.List;

/**
 * What one deriver session proposed, after validation.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param contract the proposed contract, with CAT's judge fields
 * @param derivedSources the prompt files that load although no native event reports them
 * @param scopeConditions the conditions that make certification unavailable
 * @param observedSources the prompt sources that the deriver session's native events showed loading, one per line,
 *   or {@code none}
 * @param derivation the deriver's output as JSON, which a revision sends back to the deriver with the user's
 *   feedback
 */
public record DerivedProposal(Contract contract, List<DerivedSource> derivedSources,
	List<ScopeCondition> scopeConditions, String observedSources, String derivation)
{
	/**
	 * Creates a derived proposal.
	 *
	 * @param contract the proposed contract, with CAT's judge fields
	 * @param derivedSources the prompt files that load although no native event reports them
	 * @param scopeConditions the conditions that make certification unavailable
	 * @param observedSources the prompt sources that the deriver session's native events showed loading
	 * @param derivation the deriver's output as JSON
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
	}
}

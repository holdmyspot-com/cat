package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;

import java.nio.file.Path;
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
 * @param draft the draft directory that holds the prompt tests as files
 * @param changes how each prompt test relates to the recorded prompt tests, by prompt test identifier; empty until
 *   the caller compares the draft with the project
 */
public record DerivedProposal(Contract contract, List<DerivedSource> derivedSources,
	List<ScopeCondition> scopeConditions, List<Conflict> conflicts, String observedSources, String derivation,
	Path draft, Map<String, DraftChange> changes)
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
	 * @param draft the draft directory that holds the prompt tests as files
	 * @param changes how each prompt test relates to the recorded prompt tests, by prompt test identifier
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
		requireThat(draft, "draft").isNotNull();
		requireThat(changes, "changes").isNotNull();
		changes = Collections.unmodifiableMap(new LinkedHashMap<>(changes));
	}

	/**
	 * Returns this proposal with other derived sources.
	 *
	 * @param sources the prompt files that load although no harness event reports them
	 * @return the proposal
	 * @throws NullPointerException if {@code sources} is null or contains null
	 */
	public DerivedProposal withDerivedSources(List<DerivedSource> sources)
	{
		return new DerivedProposal(contract, sources, scopeConditions, conflicts, observedSources, derivation, draft,
			changes);
	}

	/**
	 * Returns this proposal with its draft directory at another path and with the relation of each prompt test to the
	 * recorded prompt tests.
	 *
	 * @param draft the draft directory
	 * @param changes how each prompt test relates to the recorded prompt tests, by prompt test identifier
	 * @return the proposal
	 * @throws NullPointerException if any of the arguments are null
	 */
	public DerivedProposal withDraft(Path draft, Map<String, DraftChange> changes)
	{
		return new DerivedProposal(contract, derivedSources, scopeConditions, conflicts, observedSources, derivation,
			draft, changes);
	}
}

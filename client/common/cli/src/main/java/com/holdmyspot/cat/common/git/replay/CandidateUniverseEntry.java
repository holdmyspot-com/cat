package com.holdmyspot.cat.common.git.replay;

import java.util.List;

/**
 * One independently discovered behavior before its output placement is selected.
 *
 * @param id the reviewer-local stable identifier
 * @param observableOutcome the behavior a user or reader can observe
 * @param discoveryEvidence the raw-range evidence that discovered the behavior
 * @param responsibility the responsibility that owns the behavior
 * @param acceptanceEvidence evidence that proves the behavior is usable
 * @param splitCounterfactual the result of testing the behavior as a separate output
 * @param units reviewer-named semantic units that may contribute to the behavior
 * @param exposedEntryPoints every tool-derived selectable entry point this candidate owns
 */
public record CandidateUniverseEntry(String id, String observableOutcome, String discoveryEvidence,
	String responsibility, String acceptanceEvidence, String splitCounterfactual, List<CandidateUniverseUnit> units,
	List<String> exposedEntryPoints)
{
}

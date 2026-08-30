package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Selects the next evaluator action from the durable record for a candidate prompt revision without choosing that
 * revision.
 * <p>
 * The candidate-state record identifies the latest regression check, any promotion, and earlier failures that the next
 * action must still cover.
 */
final class CandidatePolicy
{
	/**
	 * Prevents construction of this static utility.
	 */
	private CandidatePolicy()
	{
	}

	/**
	 * Selects the only evaluator action permitted by the retained candidate-state record.
	 *
	 * @param state the retained candidate-state record, or {@code null} when no candidate has a record
	 * @param uncoveredObligations earlier failures the latest check did not cover
	 * @param latestCheckPassed whether the latest required regression check passed
	 * @param statePath the candidate-state record path included in an error message
	 * @return the selected transition
	 * @throws IOException if the state requires a semantic repair before advancing
	 */
	static Transition select(ObjectNode state, List<String> uncoveredObligations, boolean latestCheckPassed,
		Path statePath) throws IOException
	{
		if (state == null || !state.has("latest_check") || !uncoveredObligations.isEmpty() ||
			state.path("latest_check_status").asString().equals("failed"))
		{
			return Transition.REGRESSION_CHECK;
		}
		if (state.has("current_promotion"))
			return Transition.CERTIFY_PROMOTED;
		if (!latestCheckPassed)
		{
			throw new IOException("Candidate's latest regression check has failed; revise the prompt, suite, or " +
				"infrastructure before advancing: " + statePath);
		}
		return Transition.PROMOTE;
	}

	/**
	 * Identifies check records that cannot establish candidate prompt-failure evidence.
	 *
	 * @param checks the validated candidate-check records
	 * @return the paths of incomplete checks
	 */
	static Set<String> incompleteCheckRuns(ArrayNode checks)
	{
		Set<String> result = new HashSet<>();
		for (JsonNode node : checks)
		{
			if (node.path("status").asString().equals("incomplete"))
				result.add(node.path("run").asString());
		}
		return result;
	}

	/**
	 * Reports whether a signature can be discarded with its incomplete check.
	 *
	 * @param signature the validated signature
	 * @param incompleteChecks the recorded incomplete checks
	 * @return whether the signature has no earlier completed evidence
	 */
	static boolean discardableIncompleteSignature(ObjectNode signature, Set<String> incompleteChecks)
	{
		String lastObservedCheck = signature.path("last_observed_check").asString();
		return incompleteChecks.contains(lastObservedCheck) &&
			signature.path("first_observed_check").asString().equals(lastObservedCheck) &&
			signature.path("observations").asInt() == 1;
	}

	/**
	 * Removes signatures sourced only from incomplete checks.
	 *
	 * @param signatures the validated signatures
	 * @param incompleteChecks the recorded incomplete checks
	 * @return the removed signature keys
	 */
	static Set<String> discardIncompleteSignatures(ArrayNode signatures, Set<String> incompleteChecks)
	{
		Set<String> result = new HashSet<>();
		for (int index = signatures.size() - 1; index >= 0; --index)
		{
			ObjectNode signature = (ObjectNode) signatures.get(index);
			if (!incompleteChecks.contains(signature.path("last_observed_check").asString()))
				continue;
			result.add(signature.path("key").asString());
			signatures.remove(index);
		}
		return result;
	}

	/**
	 * Removes obligations opened only by discarded incomplete signatures.
	 *
	 * @param obligations the validated lineage obligations
	 * @param discardedKeys the removed signature keys
	 * @param incompleteChecks the recorded incomplete checks
	 */
	static void discardIncompleteObligations(ArrayNode obligations, Set<String> discardedKeys,
		Set<String> incompleteChecks)
	{
		for (int index = obligations.size() - 1; index >= 0; --index)
		{
			ObjectNode obligation = (ObjectNode) obligations.get(index);
			if (discardedKeys.contains(obligation.path("key").asString()) &&
				incompleteChecks.contains(obligation.path("last_observed_check").asString()))
			{
				obligations.remove(index);
			}
		}
	}

	/**
	 * Removes incomplete check summaries and restores the latest retained check.
	 *
	 * @param state the validated candidate state
	 * @param incompleteChecks the recorded incomplete checks
	 */
	static void discardIncompleteCheckRecords(ObjectNode state, Set<String> incompleteChecks)
	{
		ArrayNode checks = (ArrayNode) state.path("checks");
		for (int index = checks.size() - 1; index >= 0; --index)
			if (incompleteChecks.contains(checks.get(index).path("run").asString()))
				checks.remove(index);
		if (checks.isEmpty())
		{
			state.remove("latest_check");
			state.remove("latest_check_status");
			return;
		}
		ObjectNode latest = (ObjectNode) checks.get(checks.size() - 1);
		state.put("latest_check", latest.path("run").asString());
		state.put("latest_check_status", latest.path("status").asString());
	}

	enum Transition
	{
		REGRESSION_CHECK("regression-check", "run"),
		PROMOTE("promote", "check_run"),
		CERTIFY_PROMOTED("certify-promoted", "run");

		private final String command;
		private final String recordField;

		/**
		 * Creates one transition definition.
		 *
		 * @param command the public transition name
		 * @param recordField the receipt field containing its retained record
		 */
		Transition(String command, String recordField)
		{
			this.command = command;
			this.recordField = recordField;
		}

		/**
		 * Returns the public transition name.
		 *
		 * @return the command name
		 */
		String command()
		{
			return command;
		}

		/**
		 * Returns the receipt field naming the retained transition record.
		 *
		 * @return the record field name
		 */
		String recordField()
		{
			return recordField;
		}
	}
}

package io.github.cowwoc.cat.common.evaluation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Projects completed prompt-evaluation records into stable reports.
 */
public final class Report
{
	/**
	 * Provides the record and decision operations needed to project a report.
	 */
	public interface Access
	{
		/**
		 * Reads one evaluation record.
		 *
		 * @param runPath the persistent result record
		 * @return the parsed record
		 * @throws IOException if the record cannot be read
		 */
		ObjectNode readRun(Path runPath) throws IOException;

		/**
		 * Computes the evaluation decision.
		 *
		 * @param runPath the persistent result record
		 * @return the current decision
		 * @throws IOException if the decision cannot be computed
		 */
		ObjectNode decide(Path runPath) throws IOException;

		/**
		 * Returns the prompt identity recorded for a candidate.
		 *
		 * @param run the evaluation record
		 * @param runPath the persistent result record
		 * @return the candidate identity
		 * @throws IOException if the identity is invalid
		 */
		String candidateIdentity(ObjectNode run, Path runPath) throws IOException;

		/**
		 * Reads the durable attempt ledger.
		 *
		 * @param run the evaluation record
		 * @param runPath the persistent result record
		 * @return attempt records in ledger order
		 * @throws IOException if the ledger is invalid
		 */
		ArrayNode attempts(ObjectNode run, Path runPath) throws IOException;
	}

	/**
	 * Prevents instantiation.
	 */
	private Report()
	{
	}

	/**
	 * Returns one stable user-facing summary of a completed evaluation.
	 *
	 * @param json the lifecycle-owned mapper that constructs the report
	 * @param runPath the persistent result record
	 * @param access record and decision operations
	 * @return the decision and fields needed to interpret or compare its compliance rate
	 * @throws IOException if the record is not terminal or cannot verify its retained evidence
	 */
	public static ObjectNode report(JsonMapper json, Path runPath, Access access) throws IOException
	{
		ObjectNode run = access.readRun(runPath);
		if (hasCompleted(run, "smoke", "smoke_result"))
			return completedResult(run, "smoke_result", "smoke result", runPath);
		if (hasCompleted(run, "regression_check", "regression_check"))
			return completedResult(run, "regression_check", "regression check", runPath);
		if (hasCompleted(run, "routing_matrix", "routing_matrix"))
			return completedResult(run, "routing_matrix", "routing matrix", runPath);
		if (hasCompleted(run, "reviewer_controls", "reviewer_controls"))
			return completedResult(run, "reviewer_controls", "reviewer controls", runPath);
		if (hasCompleted(run, "profile_capability_diagnostic", "profile_capability_diagnostic"))
			return capabilityDiagnosticResult(run, runPath);

		String mode = run.path("mode").asString();
		if (List.of("smoke", "routing_matrix", "reviewer_controls", "profile_capability_diagnostic").contains(mode))
			throw new IOException("Prompt " + mode.replace('_', ' ') + " is still running; use status instead: " +
				runPath);
		if (!text(run, "purpose", runPath).equals("certification"))
		{
			ObjectNode result = json.createObjectNode();
			result.put("purpose", "diagnostic");
			String classification = mode;
			if (classification.isBlank())
				classification = "direct_route_diagnostic";
			result.put("classification", classification);
			result.put("run", runPath.toString());
			return result;
		}
		ObjectNode decision = access.decide(runPath);
		if (decision.path("overall_decision").asString().equals("RUNNING"))
			throw new IOException("Prompt evaluation is still running; use status instead: " + runPath);
		ObjectNode result = json.createObjectNode();
		result.put("decision", decision.path("overall_decision").asString());
		result.put("compliance_rate", decision.path("sequential_lower_confidence_bound").asDouble());
		result.put("confidence_level", decision.path("confidence_level").asDouble());
		result.put("completed_trials", decision.path("complete_suite_runs").asInt());
		result.put("passed_trials", decision.path("complete_suite_passes").asInt());
		result.put("observed_pass_fraction", decision.path("observed_compliance").asDouble());
		result.put("suite_fingerprint", text(run, "suite_sha256", runPath));
		result.put("assembled_prompt_route_fingerprint", text(run, "assembled_prompt_route_sha256", runPath));
		result.put("execution_context_fingerprint", text(run, "execution_context_sha256", runPath));
		result.set("execution_identity", object(run.path("execution_identity"), "execution identity").deepCopy());
		result.put("evaluation_epoch", text(run, "evaluation_epoch_sha256", runPath));
		result.put("harness", text(run, "harness", runPath));
		result.put("profile", text(run, "profile", runPath));
		if (mode.equals("benchmark"))
		{
			result.put("candidate_bundle_sha256", text(run, "candidate_bundle_sha256", runPath));
			result.put("candidate_identity_sha256", access.candidateIdentity(run, runPath));
			result.put("benchmark_elapsed_millis", longValue(run, "benchmark_elapsed_millis", runPath));
		}
		return result;
	}

	/**
	 * Adds retained timing to a completed report when requested.
	 *
	 * @param json the lifecycle-owned mapper that constructs the report
	 * @param runPath the persistent result record
	 * @param includePerformance whether to include measured worker durations
	 * @param access record and decision operations
	 * @return the requested completed evaluation summary
	 * @throws IOException if the record is invalid
	 */
	public static ObjectNode report(JsonMapper json, Path runPath, boolean includePerformance, Access access)
		throws IOException
	{
		ObjectNode result = report(json, runPath, access);
		if (includePerformance)
			result.set("performance", performanceReport(json, runPath, access));
		return result;
	}

	/**
	 * Copies one completed operation result into a report.
	 *
	 * @param run the evaluation record
	 * @param field the completed-result field
	 * @param name the field's diagnostic name
	 * @param runPath the persistent result record
	 * @return the copied completed result
	 * @throws IOException if the result is invalid
	 */
	private static ObjectNode completedResult(ObjectNode run, String field, String name, Path runPath) throws IOException
	{
		ObjectNode result = object(run.path(field), name).deepCopy();
		result.put("run", runPath.toString());
		return result;
	}

	/**
	 * Projects a completed capability diagnostic.
	 *
	 * @param run the evaluation record
	 * @param runPath the persistent result record
	 * @return the completed diagnostic projection
	 * @throws IOException if the diagnostic is invalid
	 */
	private static ObjectNode capabilityDiagnosticResult(ObjectNode run, Path runPath) throws IOException
	{
		ObjectNode diagnostic = object(run.path("profile_capability_diagnostic"), "profile capability diagnostic");
		ObjectNode result = object(diagnostic.path("result"), "profile capability diagnostic result").deepCopy();
		result.put("target_executor_profile", text(diagnostic, "target_executor_profile", runPath));
		result.put("diagnostic_executor_profile", text(diagnostic, "diagnostic_executor_profile", runPath));
		result.put("validator_profile", text(diagnostic, "validator_profile", runPath));
		result.put("run", runPath.toString());
		return result;
	}

	/**
	 * Returns whether a run retained the result for one terminal mode.
	 *
	 * @param run the evaluation record
	 * @param mode the terminal mode
	 * @param resultField the retained result field
	 * @return whether the terminal result exists
	 */
	private static boolean hasCompleted(ObjectNode run, String mode, String resultField)
	{
		return run.path("mode").asString().equals(mode) && run.path(resultField).isObject();
	}

	/**
	 * Summarizes retained worker timing.
	 *
	 * @param json the lifecycle-owned mapper that constructs the timing projection
	 * @param runPath the persistent result record
	 * @param access record access operations
	 * @return the worker timing projection
	 * @throws IOException if retained timing is invalid
	 */
	private static ObjectNode performanceReport(JsonMapper json, Path runPath, Access access) throws IOException
	{
		ObjectNode run = access.readRun(runPath);
		ObjectNode result = json.createObjectNode();
		ArrayNode workers = result.putArray("workers");
		long criticalPathMillis = -1;
		ObjectNode criticalPath = null;
		for (JsonNode node : access.attempts(run, runPath))
		{
			ObjectNode attempt = object(node, "attempt");
			if (!attempt.path("performance").isObject())
				continue;
			ObjectNode performance = object(attempt.path("performance"), "worker performance");
			ObjectNode worker = workers.addObject();
			worker.put("case_id", text(attempt, "case_id", runPath));
			worker.put("trial", attempt.path("trial").asInt());
			long totalMillis = 0;
			for (String stage : List.of("queue", "startup", "execution", "validation", "merge"))
			{
				String field = stage + "_millis";
				if (!performance.path(field).isMissingNode())
				{
					long duration = performance.path(field).asLong(-1);
					if (duration < 0)
						throw new IOException("Worker performance has an invalid " + field + ": " + runPath);
					worker.put(field, duration);
					totalMillis += duration;
				}
			}
			worker.put("total_millis", totalMillis);
			if (totalMillis > criticalPathMillis)
			{
				criticalPathMillis = totalMillis;
				criticalPath = worker.deepCopy();
			}
		}
		result.put("timed_workers", workers.size());
		if (criticalPath == null)
		{
			result.put("critical_path", "unavailable");
			return result;
		}
		result.set("critical_path", criticalPath);
		return result;
	}

	/**
	 * Requires an object node.
	 *
	 * @param value the node to inspect
	 * @param name the diagnostic name
	 * @return the object node
	 * @throws IOException if the node is not an object
	 */
	private static ObjectNode object(JsonNode value, String name) throws IOException
	{
		if (!value.isObject())
			throw new IOException("Expected object for " + name);
		return (ObjectNode) value;
	}

	/**
	 * Requires a nonblank text field.
	 *
	 * @param value the containing object
	 * @param field the required field
	 * @param source the source path
	 * @return the text value
	 * @throws IOException if the field is invalid
	 */
	private static String text(ObjectNode value, String field, Path source) throws IOException
	{
		if (!value.hasNonNull(field) || !value.get(field).isString() || value.get(field).asString().isBlank())
			throw new IOException("Missing text " + field + ": " + source);
		return value.get(field).asString();
	}

	/**
	 * Requires a non-negative long field.
	 *
	 * @param value the containing object
	 * @param field the required field
	 * @param source the source path
	 * @return the long value
	 * @throws IOException if the field is invalid
	 */
	private static long longValue(ObjectNode value, String field, Path source) throws IOException
	{
		if (!value.has(field) || !value.get(field).canConvertToLong() || value.get(field).asLong() < 0)
			throw new IOException("Missing non-negative timestamp " + field + ": " + source);
		return value.get(field).asLong();
	}
}

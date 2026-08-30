package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.cat.common.PromptEvaluation.object;
import static io.github.cowwoc.cat.common.PromptEvaluation.text;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Defines compatibility between two immutable certification comparison contracts.
 */
public final class Comparison
{
	private static final List<String> CONTRACT_FIELDS = List.of("suite_sha256", "validator_contract_sha256", "harness",
		"executor_owner", "executor_model", "executor_effort", "executor_profile", "validator_owner",
		"validator_model", "validator_effort", "validator_profile", "executor_runner_sha256",
		"validator_runner_sha256", "validator_batch_runner_sha256", "execution_inputs_sha256",
		"runner_runtime_sha256", "sandbox_policy_sha256", "network_policy_sha256", "cache_policy_sha256");

	/**
	 * Prevents construction of this static compatibility owner.
	 */
	private Comparison()
	{
	}

	/**
	 * Reads and validates coordinator-owned certification evidence needed by a comparison.
	 */
	public interface Access
	{
		/**
		 * Reads one persisted evaluation record.
		 *
		 * @param path the record path
		 * @return the retained record
		 * @throws IOException if the record cannot be read
		 */
		ObjectNode read(Path path) throws IOException;

		/**
		 * Validates immutable run inputs before comparing a retained record.
		 *
		 * @param run the retained record
		 * @param path the record path
		 * @throws IOException if immutable inputs are invalid
		 */
		void validateInputs(ObjectNode run, Path path) throws IOException;

		/**
		 * Validates retained evidence before comparing a record.
		 *
		 * @param run the retained record
		 * @param path the record path
		 * @throws IOException if retained evidence is invalid
		 */
		void validateEvidence(ObjectNode run, Path path) throws IOException;

		/**
		 * Returns the coordinator-owned terminal decision for one record.
		 *
		 * @param path the record path
		 * @return the terminal decision
		 * @throws IOException if the decision cannot be determined
		 */
		ObjectNode decide(Path path) throws IOException;

		/**
		 * Returns the immutable candidate identity for one record.
		 *
		 * @param run the retained record
		 * @param path the record path
		 * @return the candidate identity
		 * @throws IOException if the identity is invalid
		 */
		String candidateIdentity(ObjectNode run, Path path) throws IOException;

		/**
		 * Reads one required persisted long value.
		 *
		 * @param run the retained record
		 * @param field the value name
		 * @param path the record path
		 * @return the persisted value
		 * @throws IOException if the value is invalid
		 */
		long longValue(ObjectNode run, String field, Path path) throws IOException;
	}

	/**
	 * Names the two records and compatibility mode for one comparison request.
	 *
	 * @param baselinePath the earlier certification record
	 * @param candidatePath the later certification record
	 * @param diagnostic whether incompatible fields are returned instead of rejected
	 */
	public record Request(Path baselinePath, Path candidatePath, boolean diagnostic)
	{
	}

	/**
	 * Compares two certification records through the coordinator-owned evidence access boundary.
	 *
	 * @param request the record paths and compatibility mode
	 * @param access retained-record, validation, and decision access
	 * @param json the caller-owned JSON mapper
	 * @return the comparable measurements, or incompatible fields in diagnostic mode
	 * @throws IOException if either record is invalid or strict comparison rejects a difference
	 */
	public static ObjectNode compare(Request request, Access access, JsonMapper json) throws IOException
	{
		ObjectNode baseline = access.read(request.baselinePath());
		ObjectNode candidate = access.read(request.candidatePath());
		verifyCertification(baseline, request.baselinePath());
		verifyCertification(candidate, request.candidatePath());
		access.validateInputs(baseline, request.baselinePath());
		access.validateInputs(candidate, request.candidatePath());
		access.validateEvidence(baseline, request.baselinePath());
		access.validateEvidence(candidate, request.candidatePath());

		List<String> differences = differingFields(baseline, candidate, request.baselinePath(), request.candidatePath());
		if (!differences.isEmpty() && !request.diagnostic())
		{
			throw new IOException("Runs are not comparable because comparison contract field " + differences.getFirst() +
				" differs");
		}
		ObjectNode comparison = json.createObjectNode();
		comparison.put("comparable", differences.isEmpty());
		comparison.put("comparison_contract_sha256", text(baseline, "evaluation_epoch_sha256", request.baselinePath()));
		ArrayNode differenceValues = comparison.putArray("differing_comparison_contract_fields");
		for (String difference : differences)
			differenceValues.add(difference);
		if (differences.isEmpty())
		{
			comparison.set("baseline", measurement(baseline, request.baselinePath(), access, json));
			comparison.set("candidate", measurement(candidate, request.candidatePath(), access, json));
		}
		return comparison;
	}

	/**
	 * Returns every fixed comparison-contract field that differs between two certification records.
	 *
	 * @param baseline the earlier certification record
	 * @param candidate the later certification record
	 * @param baselinePath the earlier record path
	 * @param candidatePath the later record path
	 * @return differing field names, or {@code evaluation_epoch} when either record is unbound
	 * @throws IOException if a declared comparison contract is malformed
	 */
	public static List<String> differingFields(ObjectNode baseline, ObjectNode candidate, Path baselinePath,
		Path candidatePath) throws IOException
	{
		if (!baseline.has("evaluation_epoch") || !candidate.has("evaluation_epoch"))
			return List.of("evaluation_epoch");
		ObjectNode baselineEpoch = object(baseline.path("evaluation_epoch"), "baseline evaluation epoch");
		ObjectNode candidateEpoch = object(candidate.path("evaluation_epoch"), "candidate evaluation epoch");
		List<String> differences = new ArrayList<>();
		for (String field : CONTRACT_FIELDS)
		{
			if (!text(baselineEpoch, field, baselinePath).equals(text(candidateEpoch, field, candidatePath)))
				differences.add(field);
		}
		return differences;
	}

	/**
	 * Rejects a retained record outside the certification lifecycle.
	 *
	 * @param run the retained record
	 * @param path the record path
	 * @throws IOException if the record is not a certification result
	 */
	private static void verifyCertification(ObjectNode run, Path path) throws IOException
	{
		if (!text(run, "purpose", path).equals("certification"))
			throw new IOException("Only certification records can be compared");
	}

	/**
	 * Produces one comparison-safe terminal measurement.
	 *
	 * @param run the retained record
	 * @param path the record path
	 * @param access retained decision and identity access
	 * @param json the caller-owned JSON mapper
	 * @return the terminal measurement
	 * @throws IOException if the retained record is still running or invalid
	 */
	private static ObjectNode measurement(ObjectNode run, Path path, Access access, JsonMapper json)
		throws IOException
	{
		ObjectNode decision = access.decide(path);
		if (decision.path("overall_decision").asString().equals("RUNNING"))
			throw new IOException("A running evaluation cannot be compared: " + path);
		ObjectNode result = json.createObjectNode();
		result.put("decision", decision.path("overall_decision").asString());
		result.put("compliance_rate", decision.path("sequential_lower_confidence_bound").asDouble());
		result.put("confidence_level", decision.path("confidence_level").asDouble());
		result.put("completed_trials", decision.path("complete_suite_runs").asInt());
		result.put("passed_trials", decision.path("complete_suite_passes").asInt());
		result.put("candidate_identity_sha256", access.candidateIdentity(run, path));
		if (run.has("candidate_bundle_sha256"))
			result.put("candidate_bundle_sha256", text(run, "candidate_bundle_sha256", path));
		if (run.path("mode").asString().equals("benchmark"))
			result.put("benchmark_elapsed_millis", access.longValue(run, "benchmark_elapsed_millis", path));
		return result;
	}
}

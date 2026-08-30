package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.ToolScope;

import java.io.IOException;
import java.nio.file.Path;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests immutable certification comparison-contract compatibility.
 */
public final class ComparisonTest
{
	/**
	 * Creates the test fixture.
	 */
	public ComparisonTest()
	{
	}

	/**
	 * Verifies matching bound comparison contracts have no reported differences.
	 *
	 * @throws IOException if the comparison contract is invalid
	 */
	@Test
	public void acceptsMatchingContracts() throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			ObjectNode baseline = boundRecord(scope.json());

			requireThat(Comparison.differingFields(baseline, baseline.deepCopy(), Path.of("baseline.json"),
				Path.of("candidate.json")), "differences").isEmpty();
		}
	}

	/**
	 * Verifies an unbound record cannot enter a certification comparison.
	 *
	 * @throws IOException if the comparison contract is invalid
	 */
	@Test
	public void excludesUnboundRecord() throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			requireThat(Comparison.differingFields(scope.json().createObjectNode(), boundRecord(scope.json()),
				Path.of("baseline.json"), Path.of("candidate.json")), "differences").
				containsExactly(new String[]{"evaluation_epoch"});
		}
	}

	/**
	 * Verifies diagnostic comparison retains every differing fixed condition in contract order.
	 *
	 * @throws IOException if the comparison contract is invalid
	 */
	@Test
	public void reportsEveryDifferingContractField() throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			ObjectNode baseline = boundRecord(scope.json());
			ObjectNode candidate = baseline.deepCopy();
			ObjectNode candidateEpoch = (ObjectNode) candidate.path("evaluation_epoch");
			candidateEpoch.put("harness", "other-harness");
			candidateEpoch.put("executor_model", "other-model");

			requireThat(Comparison.differingFields(baseline, candidate, Path.of("baseline.json"),
				Path.of("candidate.json")), "differences").containsExactly(new String[]{"harness", "executor_model"});
		}
	}

	/**
	 * Creates one complete immutable comparison contract.
	 *
	 * @param json the scope-owned mapper that constructs the fixture record
	 * @return a record with a bound evaluation epoch
	 */
	private static ObjectNode boundRecord(JsonMapper json)
	{
		ObjectNode epoch = json.createObjectNode();
		for (String field : new String[]{"suite_sha256", "validator_contract_sha256", "harness", "executor_owner",
			"executor_model", "executor_effort", "executor_profile", "validator_owner", "validator_model",
			"validator_effort", "validator_profile", "executor_runner_sha256", "validator_runner_sha256",
			"validator_batch_runner_sha256", "execution_inputs_sha256", "runner_runtime_sha256",
			"sandbox_policy_sha256", "network_policy_sha256", "cache_policy_sha256"})
		{
			epoch.put(field, field + "-value");
		}
		return json.createObjectNode().set("evaluation_epoch", epoch);
	}
}

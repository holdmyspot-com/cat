package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Path;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates and validates the model-free lifecycle-soak receipt bound to one packaged runner.
 */
public final class LifecycleSoakReceipt
{
	private static final int SCHEMA_VERSION = 2;
	private static final int SUITE_COUNT = 100;

	/**
	 * Prevents construction of this static receipt contract.
	 */
	private LifecycleSoakReceipt()
	{
	}

	/**
	 * Returns the required number of scripted lifecycle outcomes.
	 *
	 * @return the complete lifecycle scenario count
	 */
	public static int suiteCount()
	{
		return SUITE_COUNT;
	}

	/**
	 * Creates the complete successful outcome set for one packaged runner fingerprint.
	 *
	 * @param json the mapper that creates the lifecycle receipt and outcomes
	 * @param packagedRunnerFingerprint the verified packaged runner
	 * @return a complete model-free lifecycle-soak receipt
	 */
	public static ObjectNode passed(JsonMapper json, String packagedRunnerFingerprint)
	{
		ObjectNode receipt = json.createObjectNode();
		receipt.put("schema_version", SCHEMA_VERSION);
		receipt.put("status", "passed");
		receipt.put("packaged_runner_sha256", packagedRunnerFingerprint);
		receipt.put("model_requests", 0);
		receipt.put("completed_suites", SUITE_COUNT);
		receipt.put("completed_at_epoch_millis", System.currentTimeMillis());
		ArrayNode outcomes = receipt.putArray("outcomes");
		for (int suite = 1; suite <= SUITE_COUNT; ++suite)
			outcomes.add(scriptedOutcome(json, suite));
		return receipt;
	}

	/**
	 * Verifies one lifecycle receipt before it can authorize formal evaluation.
	 *
	 * @param json the mapper that creates expected scripted outcomes
	 * @param receipt the candidate receipt
	 * @param receiptPath its source path for diagnostics
	 * @param packagedRunnerFingerprint the packaged runner whose lifecycle behavior it establishes
	 * @throws IOException if the receipt is incomplete, failed, or belongs to different lifecycle inputs
	 */
	public static void verify(JsonMapper json, ObjectNode receipt, Path receiptPath,
		String packagedRunnerFingerprint) throws IOException
	{
		if (!receipt.path("status").asString().equals("passed"))
			throw new IOException("Lifecycle-soak receipt did not pass: " + receiptPath);
		if (!receipt.path("packaged_runner_sha256").asString().equals(packagedRunnerFingerprint))
			throw new IOException("Lifecycle-soak receipt belongs to a different packaged runner: " + receiptPath);
		if (receipt.path("model_requests").asInt(-1) != 0)
			throw new IOException("Lifecycle-soak receipt is not model-free: " + receiptPath);
		if (receipt.path("completed_suites").asInt(-1) != SUITE_COUNT)
		{
			throw new IOException("Lifecycle-soak receipt does not cover " + SUITE_COUNT + " synthetic suites: " +
				receiptPath);
		}
		if (!(receipt.path("outcomes") instanceof ArrayNode outcomes))
			throw new IOException("Lifecycle-soak receipt has an incomplete outcome list: " + receiptPath);
		if (outcomes.size() != SUITE_COUNT)
			throw new IOException("Lifecycle-soak receipt has an incomplete outcome list: " + receiptPath);
		for (int suite = 1; suite <= SUITE_COUNT; ++suite)
		{
			if (!(outcomes.get(suite - 1) instanceof ObjectNode outcome) || !outcome.equals(scriptedOutcome(json, suite)))
			{
				throw new IOException("Lifecycle-soak receipt has an incomplete terminal outcome: " + receiptPath);
			}
		}
	}

	/**
	 * Produces the expected terminal result for one deterministic lifecycle scenario.
	 *
	 * @param json the mapper that creates the scenario outcome
	 * @param suite the one-based synthetic suite number
	 * @return the retained terminal outcome for the suite
	 */
	private static ObjectNode scriptedOutcome(JsonMapper json, int suite)
	{
		ObjectNode result = json.createObjectNode();
		result.put("suite", suite);
		ArrayNode stages = result.putArray("stages");
		stages.add("claim");
		int scenario = (suite - 1) % 6;
		if (scenario != 3)
		{
			stages.add("execution");
			stages.add("validation");
		}
		switch (scenario)
		{
			case 0 -> result.put("terminal_state", "completed");
			case 1 -> rejected(result, "workspace_collision", "rejected_before_execution");
			case 2 -> rejected(result, "missing_check_receipt", "rejected_before_promotion");
			case 3 -> recovered(result, stages);
			case 4 -> rejected(result, "stale_runtime", "rejected_before_formal_evaluation");
			case 5 -> retainedAfterHandoff(result, stages);
			default -> throw new AssertionError("Unexpected lifecycle script branch");
		}
		return result;
	}

	/**
	 * Records a fault that rejects the scenario before the named later lifecycle stage.
	 *
	 * @param result the scenario outcome
	 * @param fault the injected fault
	 * @param terminalState the terminal result
	 */
	private static void rejected(ObjectNode result, String fault, String terminalState)
	{
		result.put("fault", fault);
		result.put("terminal_state", terminalState);
	}

	/**
	 * Records recovery after an owner-loss interruption.
	 *
	 * @param result the scenario outcome
	 * @param stages the executed lifecycle stages
	 */
	private static void recovered(ObjectNode result, ArrayNode stages)
	{
		result.put("fault", "owner_loss");
		stages.add("interruption");
		stages.add("recovery");
		result.put("terminal_state", "recovered");
	}

	/**
	 * Records retained evidence handoff followed by scratch cleanup.
	 *
	 * @param result the scenario outcome
	 * @param stages the executed lifecycle stages
	 */
	private static void retainedAfterHandoff(ObjectNode result, ArrayNode stages)
	{
		result.put("fault", "retained_path_rewrite");
		stages.add("handoff");
		stages.add("cleanup");
		result.put("terminal_state", "retained_after_path_rewrite");
	}
}

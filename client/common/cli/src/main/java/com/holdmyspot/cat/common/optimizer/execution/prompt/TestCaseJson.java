package com.holdmyspot.cat.common.optimizer.execution.prompt;

import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValueJson;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a test case as the JSON that the role prompts show: its test, its values and expected outcome,
 * its turns and its expected behaviour.
 * <p>
 * The draw's generator and stream position are left out, because they describe how CAT sampled the test case rather
 * than what the trial asked.
 */
final class TestCaseJson
{
	/**
	 * Prevents instantiation.
	 */
	private TestCaseJson()
	{
	}

	/**
	 * Encodes the trial's test, test case, turns and expected behaviour as JSON.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param testCase the trial data to show the prompt author or judge
	 * @return the JSON object
	 */
	static ObjectNode encode(JsonMapper json, DrawnTestCase testCase)
	{
		ObjectNode node = json.createObjectNode();
		node.put("test", testCase.testId());
		write(testCase.testCase(), node);
		ArrayNode turns = node.putArray("turns");
		testCase.turns().forEach(turns::add);
		node.put("expected_behavior", testCase.expectedBehaviour());
		return node;
	}

	/**
	 * Writes a test case's values and expected outcome.
	 *
	 * @param testCase the test case
	 * @param node the object that receives the {@code values} and {@code expected_outcome} properties
	 */
	static void write(TestCase testCase, ObjectNode node)
	{
		TestValueJson.write(testCase.values(), node.putArray("values"));
		node.put(TestCase.EXPECTED_OUTCOME, testCase.expectedOutcome());
	}
}

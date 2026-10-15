package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.Split;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a confirmed contract for sessions that must not learn its held-out part.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
final class DevelopmentContract
{
	/**
	 * Prevents instantiation.
	 */
	private DevelopmentContract()
	{
	}

	/**
	 * Writes the confirmed contract without its held-out part: omits the salt and keeps of each test's test cases only
	 * the development ones.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param schema writes the contract in the proposal schema
	 * @param contract the confirmed contract
	 * @return the contract's JSON
	 */
	static ObjectNode toJson(JsonMapper json, ProposalSchema schema, Contract contract)
	{
		ObjectNode root = (ObjectNode) json.readTree(schema.toJson(contract));
		root.remove("salt");
		Split split = contract.split();
		JsonNode testNodes = root.get("tests");
		for (int i = 0; i < contract.tests().size(); ++i)
			omitHeldOutTestCases(contract.tests().get(i), (ObjectNode) testNodes.get(i), split);
		return root;
	}

	/**
	 * Removes from a contract's JSON the content of each fixture that CAT copied from a recorded scenario, leaving its
	 * path and the file that it was copied from, for a session that works in a copy of the project and so can read that
	 * file when it needs the content.
	 *
	 * @param contract the contract's JSON in the proposal schema
	 */
	static void omitRecordedContent(ObjectNode contract)
	{
		for (JsonNode test : contract.get("tests"))
		{
			JsonNode recorded = test.get(ProposalSchema.RECORDED_FIXTURES);
			if (recorded == null)
				continue;
			for (JsonNode fixture : recorded)
				((ObjectNode) fixture).remove("content");
		}
	}

	/**
	 * Removes from a test's JSON the test cases of the held-out part. Committed test cases all belong to the
	 * development part.
	 *
	 * @param test the prompt test
	 * @param node the test's JSON in the proposal schema
	 * @param split the contract's split
	 */
	private static void omitHeldOutTestCases(PromptTest test, ObjectNode node, Split split)
	{
		ArrayNode testCases = (ArrayNode) node.get("test_cases");
		for (int i = test.testCases().size() - 1; i >= 0; --i)
		{
			if (split.partOf(test, test.testCases().get(i)) != Part.DEVELOPMENT)
				testCases.remove(i);
		}
	}
}

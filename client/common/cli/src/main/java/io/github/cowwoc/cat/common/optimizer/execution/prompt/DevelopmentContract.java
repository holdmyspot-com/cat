package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.Split;
import io.github.cowwoc.cat.common.optimizer.contract.Split.KeyCombination;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.VariationRule;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	 * Writes the confirmed contract without its held-out part: omits the salt, keeps of each key dimension only the
	 * values that a development key-value combination contains, and lists each test's development key-value
	 * combinations in {@code development_key_combinations}.
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
		JsonNode categoryNodes = root.get("categories");
		for (int i = 0; i < contract.categories().size(); ++i)
		{
			Category category = contract.categories().get(i);
			JsonNode testNodes = categoryNodes.get(i).get("tests");
			for (int j = 0; j < category.tests().size(); ++j)
				omitHeldOutValues(category.tests().get(j), (ObjectNode) testNodes.get(j), split);
		}
		return root;
	}

	/**
	 * Removes from a test's JSON the key values that only held-out key-value combinations contain, and lists its
	 * development key-value combinations.
	 *
	 * @param test the test definition
	 * @param node the test's JSON in the proposal schema
	 * @param split the contract's split
	 */
	private static void omitHeldOutValues(TestDefinition test, ObjectNode node, Split split)
	{
		List<VariationRule> keyRules = test.keyRules();
		Map<String, Set<String>> developmentLabels = new HashMap<>();
		for (VariationRule rule : keyRules)
			developmentLabels.put(rule.dimension(), new HashSet<>());
		ArrayNode combinations = node.putArray("development_key_combinations");
		for (KeyCombination combination : split.combinations(test, Part.DEVELOPMENT))
		{
			ObjectNode combinationNode = combinations.addObject();
			for (int i = 0; i < keyRules.size(); ++i)
			{
				String dimension = keyRules.get(i).dimension();
				String label = combination.values().get(i).label();
				combinationNode.put(dimension, label);
				developmentLabels.get(dimension).add(label);
			}
		}
		for (JsonNode variation : node.get("variations"))
		{
			Set<String> kept = developmentLabels.get(variation.get("dimension").asString());
			if (kept == null)
				continue;
			ArrayNode values = (ArrayNode) variation.get("values");
			for (int i = values.size() - 1; i >= 0; --i)
			{
				if (!kept.contains(values.get(i).get("label").asString()))
					values.remove(i);
			}
		}
	}
}

package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.VariationChoice;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a trial instance as the JSON that judge and author prompts show: its category, its test, the variation
 * values it drew, its turns and its expected behaviour.
 * <p>
 * The draw's generator and stream position are left out, because they describe how CAT sampled the instance rather
 * than what the trial asked.
 */
final class InstanceJson
{
	/**
	 * Prevents instantiation.
	 */
	private InstanceJson()
	{
	}

	/**
	 * Writes an instance.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param instance the instance
	 * @return the JSON object
	 */
	static ObjectNode of(JsonMapper json, TrialInstance instance)
	{
		ObjectNode node = json.createObjectNode();
		node.put("category", instance.categoryId());
		node.put("test", instance.testId());
		ArrayNode choices = node.putArray("choices");
		for (VariationChoice choice : instance.choices())
		{
			ObjectNode choiceNode = choices.addObject();
			choiceNode.put("dimension", choice.dimension());
			choiceNode.put("label", choice.label());
		}
		ArrayNode turns = node.putArray("turns");
		instance.turns().forEach(turns::add);
		node.put("expected_behavior", instance.expectedBehaviour());
		return node;
	}
}

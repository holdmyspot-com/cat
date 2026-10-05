package io.github.cowwoc.cat.common.optimizer.cli;

import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationParameter;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.execution.DeliveryReport;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryConflict;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange;
import io.github.cowwoc.cat.common.optimizer.report.EnumNames;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;

/**
 * Renders a {@link DeliveryReport} into the command output schema.
 */
final class DeliveryJson
{
	/**
	 * Prevents instantiation.
	 */
	private DeliveryJson()
	{
	}

	/**
	 * Renders a delivery.
	 *
	 * @param report the delivery
	 * @param mapper the mapper that creates the nodes
	 * @return the JSON object
	 */
	static ObjectNode of(DeliveryReport report, JsonMapper mapper)
	{
		ObjectNode root = mapper.createObjectNode();
		switch (report.result())
		{
			case DeliveryResult.Delivered delivered ->
			{
				root.put("result", "delivered");
				root.put("label", delivered.label().label());
				applicability(root.putObject("applicability"), delivered.applicability());
				paths(root.putArray("changedPromptSources"), delivered.changedPromptSources());
				paths(root.putArray("baseOnlyChanges"), delivered.baseOnlyChanges());
				ArrayNode external = root.putArray("externalChanges");
				for (DeliveryChange change : delivered.externalChanges())
				{
					ObjectNode node = external.addObject();
					node.put("target", change.target().toString());
					node.put("sequence", change.sequence());
					node.put("kind", kind(change));
				}
				root.put("diff", delivered.diff());
			}
			case DeliveryResult.Conflicted conflicted ->
			{
				root.put("result", "conflicted");
				ArrayNode array = root.putArray("conflicts");
				for (DeliveryConflict conflict : conflicted.conflicts())
				{
					ObjectNode node = array.addObject();
					node.put("kind", EnumNames.lowerCase(conflict.kind()).replace('_', '-'));
					node.put("target", conflict.target().toString());
					node.put("path", conflict.path().toString());
				}
			}
			case DeliveryResult.UnownedChange unowned ->
			{
				root.put("result", "unowned-change");
				ArrayNode array = root.putArray("changes");
				for (PathChange change : unowned.changes())
				{
					ObjectNode node = array.addObject();
					node.put("path", change.path().toString());
					node.put("kind", switch (change)
					{
						case PathChange.Added _ -> "added";
						case PathChange.Removed _ -> "removed";
						case PathChange.Modified _ -> "modified";
					});
				}
			}
			case DeliveryResult.ConcurrentEdit edit ->
			{
				root.put("result", "concurrent-edit");
				root.put("target", edit.change().target().toString());
				root.put("applied", edit.applied());
			}
			case DeliveryResult.VerificationFailed failed ->
			{
				root.put("result", "verification-failed");
				paths(root.putArray("mismatched"), failed.mismatched());
			}
		}
		return root;
	}

	/**
	 * Renders an applicability.
	 *
	 * @param node the node to fill
	 * @param applicability the applicability
	 */
	private static void applicability(ObjectNode node, Applicability applicability)
	{
		node.put("status", EnumNames.lowerCase(applicability.status()));
		node.put("promptContentChanged", applicability.promptContentChanged());
		ArrayNode changed = node.putArray("changed");
		for (MeasurementCondition condition : applicability.changed())
			changed.add(condition.name());
		for (CertificationParameter parameter : applicability.parameters())
			changed.add(parameter.name());
	}

	/**
	 * Renders paths.
	 *
	 * @param array the array to fill
	 * @param paths the paths
	 */
	private static void paths(ArrayNode array, Iterable<Path> paths)
	{
		for (Path path : paths)
			array.add(path.toString());
	}

	/**
	 * Returns the kind of a change.
	 *
	 * @param change the change
	 * @return {@code "create"}, {@code "edit"} or {@code "delete"}
	 */
	private static String kind(DeliveryChange change)
	{
		return switch (change)
		{
			case DeliveryChange.Create _ -> "create";
			case DeliveryChange.Edit _ -> "edit";
			case DeliveryChange.Delete _ -> "delete";
		};
	}
}

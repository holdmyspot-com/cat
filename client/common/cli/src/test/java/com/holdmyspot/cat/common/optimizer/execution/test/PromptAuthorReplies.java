package com.holdmyspot.cat.common.optimizer.execution.test;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.campaign.FailureKind;
import com.holdmyspot.cat.common.optimizer.execution.NeededEdit;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorBrief;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorConflict;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileDelta;
import com.holdmyspot.cat.common.optimizer.inventory.Hunk;
import com.holdmyspot.cat.common.optimizer.inventory.Hunks;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Writes the final response of a prompt author session in the report format that the production prompt author reads.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class PromptAuthorReplies
{
	/**
	 * A final response that is not a report.
	 */
	public static final String MALFORMED = "I could not write the report.";

	/**
	 * Prevents instantiation.
	 */
	private PromptAuthorReplies()
	{
	}

	/**
	 * Writes the report of a prompt author that edited one file. The report classifies every supplied failure that no
	 * conflict names as missing guidance addressed by every hunk of the edit, and every failure that a conflict names as
	 * a conflict.
	 *
	 * @param brief the brief that the prompt author received
	 * @param file the edited file
	 * @param before the file's content before the edit
	 * @param after the file's content after the edit
	 * @param neededEdits the edits that the prompt author reports needing but not making, or empty to write a response
	 *   that is not a report
	 * @param conflicts the conflicts that the prompt author reports
	 * @return the final response
	 */
	public static String report(PromptAuthorBrief brief, Path file, byte[] before, byte[] after,
		Optional<List<NeededEdit>> neededEdits, List<PromptAuthorConflict> conflicts)
	{
		if (neededEdits.isEmpty())
			return MALFORMED;
		Set<String> conflicting = new HashSet<>();
		for (PromptAuthorConflict conflict : conflicts)
			conflicting.addAll(conflict.failures());
		List<String> failureIds = brief.failureIds().stream().sorted().toList();
		List<String> guidance = failureIds.stream().filter(identifier -> !conflicting.contains(identifier)).toList();
		String dissimilar = brief.contract().tests().getFirst().id();
		JsonMapper json = JsonMapper.builder().build();
		ObjectNode report = json.createObjectNode();
		ArrayNode edits = report.putArray("needed_edits");
		for (NeededEdit edit : neededEdits.get())
		{
			ObjectNode node = edits.addObject().put("requirement", edit.requirement()).
				put("path", PortablePaths.text(edit.path())).put("operation", EnumNames.lowerCase(edit.operation()));
			if (edit.target() != null)
				node.put("target", PortablePaths.text(edit.target()));
		}
		ArrayNode kinds = report.putArray("kinds");
		ArrayNode hunks = report.putArray("hunks");
		List<String> hunkIds = new ArrayList<>();
		FileDelta delta = new FileDelta(file, new FileContent(before), new FileContent(after),
			PosixFilePermissions.fromString("rw-r--r--"));
		for (Hunk hunk : Hunks.between(delta))
		{
			String id = "hunk-" + (hunkIds.size() + 1);
			hunkIds.add(id);
			ObjectNode node = hunks.addObject().put("id", id).put("path", PortablePaths.text(file)).
				put("before_start", hunk.beforeStart()).put("before_end", hunk.beforeEnd()).
				put("after_start", hunk.afterStart()).put("after_end", hunk.afterEnd());
			ArrayNode cited = node.putArray("failures");
			failureIds.forEach(cited::add);
			node.put("analogous", "Another use requiring the same instruction").put("dissimilar", dissimilar);
		}
		if (!guidance.isEmpty())
			addKind(kinds, "missing instruction", guidance, FailureKind.GUIDANCE, hunkIds);
		if (!conflicting.isEmpty())
			addKind(kinds, "conflicting requirements", conflicting.stream().sorted().toList(),
				FailureKind.CONFLICT, List.of());
		ArrayNode conflictNodes = report.putArray("conflicts");
		for (PromptAuthorConflict conflict : conflicts)
		{
			ObjectNode node = conflictNodes.addObject();
			conflict.failures().forEach(node.putArray("failures")::add);
			conflict.requirementIds().forEach(node.putArray("requirement_ids")::add);
			ArrayNode quotations = node.putArray("quotations");
			for (PromptAuthorConflict.Quotation quotation : conflict.quotations())
			{
				quotations.addObject().put("path", PortablePaths.text(quotation.path())).put("text", quotation.text());
			}
			conflict.resolutions().forEach(node.putArray("resolutions")::add);
			node.put("recommended", conflict.recommended()).put("reason", conflict.reason());
		}
		return json.writeValueAsString(report);
	}

	/**
	 * Adds a kind to a report.
	 *
	 * @param kinds the report's kinds
	 * @param name the kind's name
	 * @param failures the identifiers of the failures that belong to the kind
	 * @param classification the kind's classification
	 * @param hunks the identifiers of the hunks that address the kind
	 */
	private static void addKind(ArrayNode kinds, String name, List<String> failures, FailureKind classification,
		List<String> hunks)
	{
		ObjectNode kind = kinds.addObject().put("name", name);
		failures.forEach(kind.putArray("failures")::add);
		kind.put("classification", EnumNames.lowerCase(classification));
		hunks.forEach(kind.putArray("hunks")::add);
	}
}

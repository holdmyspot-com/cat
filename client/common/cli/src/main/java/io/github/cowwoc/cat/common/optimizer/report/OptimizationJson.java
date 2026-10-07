package io.github.cowwoc.cat.common.optimizer.report;

import io.github.cowwoc.cat.common.optimizer.campaign.AuthorDiagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorKind;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptSize;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the descriptive optimization measurements and diagnoses in status JSON.
 */
final class OptimizationJson
{
	/**
	 * Prevents instantiation.
	 */
	private OptimizationJson()
	{
	}

	/**
	 * Writes snapshot comparisons and retained diagnoses.
	 *
	 * @param root the status object
	 * @param report the optimization report
	 */
	static void write(ObjectNode root, OptimizationReport report)
	{
		ObjectNode sizes = root.putObject("prompt_size");
		ObjectNode working = sizes.putObject("working");
		working.put("snapshot", report.workingSnapshot().hex());
		size(working, report.working());
		ObjectNode incumbent = sizes.putObject("incumbent");
		incumbent.put("snapshot", report.incumbentSnapshot().hex());
		size(incumbent, report.incumbent());
		ArrayNode inputs = root.putArray("trial_input_tokens");
		for (OptimizationReport.InputComparison comparison : report.inputs())
		{
			ObjectNode cell = inputs.addObject();
			cell.put("cell", comparison.cell());
			cell.put("working_median", comparison.workingMedian());
			cell.put("incumbent_median", comparison.incumbentMedian());
			cell.put("working_outcomes", comparison.workingOutcomes());
			cell.put("incumbent_outcomes", comparison.incumbentOutcomes());
			cell.put("working_sessions", comparison.workingSessions());
			cell.put("incumbent_sessions", comparison.incumbentSessions());
			cell.put("unavailable", comparison.unavailable());
		}
		ArrayNode authors = root.putArray("author_diagnoses");
		for (AuthorDiagnosis diagnosis : report.authors())
		{
			ObjectNode author = authors.addObject();
			author.put("attempt", diagnosis.number());
			author.put("cell", diagnosis.cell());
			author.put("snapshot", diagnosis.snapshot().hex());
			author.put("transcript", diagnosis.transcript().toString());
			author.put("effects", diagnosis.effects().toString());
			ArrayNode kinds = author.putArray("kinds");
			for (AuthorKind kind : diagnosis.kinds())
			{
				ObjectNode element = kinds.addObject();
				element.put("name", kind.name());
				element.put("classification", EnumNames.lowerCase(kind.classification()));
				ArrayNode instances = element.putArray("instances");
				kind.instances().forEach(instances::add);
				ArrayNode hunks = element.putArray("hunks");
				kind.hunks().forEach(hunks::add);
			}
			ArrayNode rejections = author.putArray("rejections");
			for (String rejection : diagnosis.rejections())
				rejections.add(rejection);
		}
		ArrayNode removed = root.putArray("removed_hunks");
		for (String hunk : report.removed())
			removed.add(hunk);

		ArrayNode attempts = root.putArray("author_attempts");
		for (AttemptProgress progress : report.attempts())
		{
			ObjectNode attempt = attempts.addObject();
			attempt.put("round", progress.round());
			attempt.put("number", progress.number());
			attempt.put("working_snapshot", progress.working().hex());
			attempt.put("cell", progress.cell());
			if (progress.snapshot() == null)
				attempt.putNull("snapshot");
			else
				attempt.put("snapshot", progress.snapshot().hex());
			attempt.put("passing_runs", progress.passingRuns());
			attempt.put("total_runs", progress.totalRuns());
			attempt.put("accepted", progress.accepted());
			attempt.put("winner", progress.winner());
		}
	}

	/**
	 * Writes measurements with explicit null values for unavailable counts.
	 *
	 * @param node the snapshot object
	 * @param size the measurements
	 */
	private static void size(ObjectNode node, PromptSize size)
	{
		node.put("bytes", size.bytes());
		node.put("words", size.words());
		node.put("unavailable", size.unavailable());
	}
}

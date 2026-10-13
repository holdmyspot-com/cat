package io.github.cowwoc.cat.common.optimizer.report;

import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorDiagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorKind;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptSize;
import io.github.cowwoc.cat.common.optimizer.campaign.SoundTest;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevision;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevisionRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevisions;

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
		ArrayNode promptAuthors = root.putArray("prompt_author_diagnoses");
		for (PromptAuthorDiagnosis diagnosis : report.promptAuthors())
		{
			ObjectNode promptAuthor = promptAuthors.addObject();
			promptAuthor.put("attempt", diagnosis.number());
			promptAuthor.put("cell", diagnosis.cell());
			promptAuthor.put("snapshot", diagnosis.snapshot().hex());
			promptAuthor.put("transcript", diagnosis.transcript().toString());
			promptAuthor.put("effects", diagnosis.effects().toString());
			ArrayNode kinds = promptAuthor.putArray("kinds");
			for (PromptAuthorKind kind : diagnosis.kinds())
			{
				ObjectNode element = kinds.addObject();
				element.put("name", kind.name());
				element.put("classification", EnumNames.lowerCase(kind.classification()));
				ArrayNode failures = element.putArray("failures");
				kind.failures().forEach(failures::add);
				ArrayNode hunks = element.putArray("hunks");
				kind.hunks().forEach(hunks::add);
				element.put("fault", kind.fault());
			}
			ArrayNode rejections = promptAuthor.putArray("rejections");
			for (String rejection : diagnosis.rejections())
				rejections.add(rejection);
		}
		ArrayNode removed = root.putArray("removed_hunks");
		for (String hunk : report.removed())
			removed.add(hunk);

		ArrayNode attempts = root.putArray("prompt_author_attempts");
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
		writeTestRevisions(root.putObject("test_revisions"), report.testRevisions());
	}

	/**
	 * Writes the requested test revisions, each completed revision with its diff, and each test reported sound.
	 *
	 * @param node the empty object that receives them
	 * @param revisions the test revisions
	 */
	private static void writeTestRevisions(ObjectNode node, TestRevisions revisions)
	{
		ArrayNode requests = node.putArray("requests");
		for (TestRevisionRequest request : revisions.requests())
		{
			ObjectNode element = requests.addObject();
			element.put("test", request.test());
			element.put("trigger", EnumNames.lowerCase(request.trigger()));
			ArrayNode kinds = element.putArray("kinds");
			for (PromptAuthorKind kind : request.kinds())
				kinds.add(kind.name());
			ArrayNode failures = element.putArray("failures");
			request.failures().keySet().forEach(failures::add);
		}
		ArrayNode completed = node.putArray("revisions");
		for (TestRevision revision : revisions.revisions())
		{
			ObjectNode element = completed.addObject();
			element.put("lineage", revision.lineage());
			element.put("test", revision.test());
			element.put("diff", revision.diff());
			element.put("reasons", revision.reasons());
		}
		ArrayNode sound = node.putArray("sound");
		for (SoundTest report : revisions.sound())
		{
			ObjectNode element = sound.addObject();
			element.put("lineage", report.lineage());
			element.put("test", report.test());
			element.put("reasons", report.reasons());
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

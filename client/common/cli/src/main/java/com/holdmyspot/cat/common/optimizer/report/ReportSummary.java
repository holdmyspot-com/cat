package com.holdmyspot.cat.common.optimizer.report;

import com.holdmyspot.cat.common.optimizer.campaign.AssumptionCheck;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorDiagnosis;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorKind;
import com.holdmyspot.cat.common.optimizer.campaign.PromptSize;
import com.holdmyspot.cat.common.optimizer.campaign.SoundTest;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevision;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevisionRequest;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevisions;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.Revert;
import com.holdmyspot.cat.common.optimizer.inventory.AuthorityWarning;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.statistics.Interval;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a {@link StatusReport} as text for the user.
 */
final class ReportSummary
{
	/**
	 * Prevents instantiation.
	 */
	private ReportSummary()
	{
	}

	/**
	 * Lists the test cases added to the development part of the tests.
	 *
	 * @param lines receives the text's lines
	 * @param testCases the test cases, in the order they were added
	 */
	private static void addedTestCases(List<String> lines, List<AddedTestCaseReport> testCases)
	{
		if (testCases.isEmpty())
			return;
		lines.add("Development test cases added after certification attempts with formal failures (development " +
			"draws only; the contract, its claims and formal draws are unchanged):");
		for (AddedTestCaseReport testCase : testCases)
		{
			List<String> values = new ArrayList<>();
			for (Map.Entry<String, String> value : testCase.values().entrySet())
				values.add(value.getKey() + " = \"" + escape(value.getValue()) + "\"");
			lines.add("  attempt " + testCase.attempt() + ", test " + testCase.testId() + ": " +
				String.join(", ", values) + "; expected outcome \"" + escape(testCase.expectedOutcome()) + "\"");
		}
	}

	/**
	 * Shows line breaks in a value on one line.
	 *
	 * @param text the value
	 * @return the value with each line break written as {@code \\n}
	 */
	private static String escape(String text)
	{
		return text.replace("\n", "\\n");
	}

	/**
	 * Renders a report.
	 *
	 * @param report the report
	 * @param zone the user's time zone, in which the text shows times
	 * @return the text
	 */
	static String of(StatusReport report, ZoneId zone)
	{
		List<String> lines = new ArrayList<>();
		lines.add("Campaign " + report.campaignId() + ": " + report.status().label() + " (phase " +
			EnumNames.lowerCase(report.phase()) + ", candidate " + report.candidate() + ")");
		if (report.outcome() != null)
		{
			StringBuilder outcome = new StringBuilder("Outcome: ").append(report.outcome());
			if (report.outcomeDetail() != null)
				outcome.append(" (").append(report.outcomeDetail()).append(')');
			lines.add(outcome.toString());
		}
		if (report.delivery() != null)
			lines.add("Delivery: " + report.delivery());
		if (report.lifecycle() == Lifecycle.CANCELLED)
			lines.add("The user stopped the campaign.");
		if (report.pending() != null)
			lines.add("Waiting for: " + report.pending());
		for (Revert revert : report.reverts())
		{
			lines.add("Reverted candidate " + revert.withdrawn().number() + " (" +
				revert.withdrawn().snapshot().hex() + ") in cell " + revert.cell() + ": development 95% Wilson " +
				"upper bound " + decimal(revert.upperBound()) + " < predecessor lower bound " +
				decimal(revert.predecessorLowerBound()));
		}
		addedTestCases(lines, report.addedTestCases());
		if (!report.choices().isEmpty())
		{
			List<String> labels = new ArrayList<>();
			for (ChoiceItem choice : report.choices())
			{
				StringBuilder label = new StringBuilder(choice.label());
				if (choice.attempt() > 0)
				{
					label.append(" (attempt ").append(choice.attempt());
					if (choice.historical())
						label.append(", historical");
					label.append(')');
				}
				labels.add(label.toString());
			}
			lines.add("Choices: " + String.join("; ", labels));
		}
		for (AuthorityWarning warning : report.authorityWarnings())
			lines.add(authorityWarning(warning));
		for (CellReport cell : report.cells())
			cell(lines, cell);
		if (!report.allocation().isEmpty())
		{
			List<String> counts = new ArrayList<>();
			for (CellReport cell : report.cells())
				counts.add(cell.cell() + " " + report.allocation().get(cell.cell()));
			lines.add("Allocation (adopted outcomes, not benchmark weights): " + String.join(", ", counts));
		}
		if (report.benchmark() != null)
		{
			List<String> weights = new ArrayList<>();
			for (Benchmark.CellWeights weight : report.benchmark().cells())
			{
				weights.add(weight.cell() + ": " + weight.testCases() + " held-out test cases of weight " +
					decimal(weight.testCaseWeight()));
			}
			lines.add("Benchmark weights (testing assumptions, not usage frequencies): " +
				String.join("; ", weights));
		}
		if (report.trialProfiles() != null)
			lines.addAll(report.trialProfiles().describe());
		cellTier(lines, report.cellTier());
		remaining(lines, report.remaining());
		tokens(lines, report.tokens());
		optimization(lines, report.optimization());
		StatusReport.Concurrency concurrency = report.concurrency();
		String throttle = "no hold";
		if (concurrency.holdUntil() != null)
			throttle = "hold until " + localTime(concurrency.holdUntil(), zone);
		// A hold that another campaign of the harness set stops this campaign's trials too
		if (concurrency.sharedHoldUntil() != null)
			throttle += ", shared harness hold until " + localTime(concurrency.sharedHoldUntil(), zone);
		lines.add("Concurrency: window " + concurrency.window() + " of ceiling " + concurrency.ceiling() +
			", slow start " + concurrency.slowStartSize() + ", throttle " + throttle + " (window cap " +
			concurrency.windowCap() + ", backoffs " + concurrency.backoffs() + ")");
		if (!report.assumptions().passRatesByConcurrency().isEmpty())
		{
			lines.add("Pass rate by concurrency level (descriptive only):");
			for (AssumptionCheck.LevelRate rate : report.assumptions().passRatesByConcurrency())
			{
				lines.add("  level " + rate.concurrency() + ": " + rate.passes() + "/" + rate.trials() + " " +
					interval(rate.interval()));
			}
		}
		if (report.assumptions().dependenceDetected())
			lines.add("Dependence between trials was detected: " + report.assumptions().consequence().label());
		for (AttemptSummary attempt : report.attempts())
			attempt(lines, attempt);
		for (KeepOffer offer : report.keepOffers())
			offer(lines, offer);
		if (report.claim() != null)
			lines.add("Claim: " + report.claim());
		lines.add("Limitations:");
		for (String limitation : report.limitations())
			lines.add("  - " + limitation);
		return String.join("\n", lines);
	}

	/**
	 * Describes a certification attempt's outcome, its applicability and the conditions it leaves unchecked.
	 *
	 * @param lines receives the text's lines
	 * @param attempt the attempt
	 */
	static void attempt(List<String> lines, AttemptSummary attempt)
	{
		StringBuilder text = new StringBuilder(200).append("Attempt ").append(attempt.index()).append(": ");
		if (attempt.outcome() == null)
			text.append("running");
		else
			text.append(attempt.outcome());
		if (attempt.historical())
			text.append(" (historical; changed: ").append(String.join(", ", attempt.changed())).append(')');
		lines.add(text.toString());
		if (!attempt.unchecked().isEmpty())
		{
			List<String> names = new ArrayList<>();
			for (MeasurementCondition condition : attempt.unchecked())
				names.add(condition.displayName());
			lines.add("  Not checked: " + String.join(", ", names) + ".");
		}
	}

	/**
	 * Formats an instant for the user to read: ISO-8601 in the user's time zone with its UTC offset, to the second.
	 *
	 * @param instant the instant
	 * @param zone the user's time zone
	 * @return the text, for example {@code 2026-10-09T17:13:08-04:00}
	 */
	private static String localTime(Instant instant, ZoneId zone)
	{
		return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.truncatedTo(ChronoUnit.SECONDS).atZone(zone));
	}

	/**
	 * Describes an authority warning and the commands that answer it.
	 *
	 * @param warning the warning
	 * @return the line
	 */
	private static String authorityWarning(AuthorityWarning warning)
	{
		List<String> choices = new ArrayList<>();
		for (AuthorityWarning.Choice choice : warning.choices())
			choices.add(ReportJson.command(choice).replace('_', ' '));
		return "Authority warning: requirement " + warning.requirement() + " needs an edit at " + warning.path() +
			", which resolves to " + warning.target() + " (limit: " +
			EnumNames.lowerCase(warning.limit()).replace('_', ' ') + "); choices: " +
			String.join(", ", choices);
	}

	/**
	 * Adds the certification scope and each test's weakest cell and development cells.
	 *
	 * @param lines the lines
	 * @param tier the cells, or {@code null} before a contract is confirmed
	 */
	private static void cellTier(List<String> lines, CellTierReport tier)
	{
		if (tier == null)
		{
			lines.add("Weakest cells: not yet fixed; confirmation fixes them from the capability ranking");
			return;
		}
		lines.add("Certification scope: " + EnumNames.lowerCase(tier.scope()).replace('_', ' ') + " (" +
			tier.scopeCells() + " cells)");
		for (CellTierReport.TestCells cells : tier.tests())
		{
			lines.add("Test " + cells.test() + ": weakest cell " + cells.weakestCell() +
				"; development cells " + String.join(", ", cells.developmentCells()));
		}
	}

	/**
	 * Adds a cell's four outputs.
	 *
	 * @param lines the lines
	 * @param report the cell's report
	 */
	private static void cell(List<String> lines, CellReport report)
	{
		lines.add("Cell " + report.cell() + ":");
		CellReport.PassRate passRate = report.passRate();
		if (passRate.rate() == null)
			lines.add("  pass rate (" + passRate.label() + "): unavailable, " + passRate.unavailable());
		else
		{
			lines.add("  pass rate (" + passRate.label() + "): " + percent(passRate.rate()) + " (" +
				report.passes() + "/" + report.trials() + ")");
		}
		CellReport.Improvement improvement = report.improvement();
		if (improvement.unavailable() != null)
			lines.add("  improvement over the incumbent: unavailable, " + improvement.unavailable());
		else
		{
			StringBuilder text = new StringBuilder(400).append("  improvement over the incumbent: ");
			if (improvement.points() == null)
				text.append("points unavailable");
			else
				text.append(String.format(Locale.ROOT, "%+.1f points", improvement.points()));
			if (improvement.incumbent() != null)
			{
				text.append(", incumbent baseline ").append(improvement.incumbent().baseline()).append(' ').
					append(improvement.incumbent().passes()).append('/').append(improvement.incumbent().trials()).
					append(" (").append(improvement.deficiency()).append(')');
			}
			text.append(", difference ").append(interval(improvement.difference()));
			if (improvement.state() != null)
			{
				text.append(", ").append(improvement.state()).append(" (UI ").
					append(decimal(improvement.incumbentUpper())).append(", LI ").
					append(decimal(improvement.incumbentLower())).append(')');
			}
			lines.add(text.toString());
		}
		CellReport.Threshold comparison = report.comparison();
		StringBuilder comparisonText = new StringBuilder(300).append("  comparison with the minimum pass rate ").
			append(comparison.minimumPassRate().movePointRight(2).stripTrailingZeros().toPlainString()).append("%: ").
			append(comparison.state());
		if (comparison.commitGate() != null)
		{
			comparisonText.append(" (commit gate ").append(comparison.commitGate());
			if (!comparison.reasons().isEmpty())
				comparisonText.append(": ").append(String.join("; ", comparison.reasons()));
			comparisonText.append(')');
		}
		if (comparison.lowerBound() != null)
		{
			comparisonText.append(", lower bound ").append(decimal(comparison.lowerBound())).
				append(", upper bound ").append(decimal(comparison.upperBound())).append(" at alpha ").
				append(decimal(comparison.alpha()));
		}
		lines.add(comparisonText.toString());
		paired(lines, comparison.controls(), "control");
		paired(lines, comparison.incumbents(), "incumbent");
		if (comparison.cellState() != null)
			lines.add("  cell: " + comparison.cellState() + " at alpha " + decimal(comparison.alpha()));
		for (CellReport.ErrorBar bar : report.errorBars())
		{
			String kind = "two-sided";
			if (bar.oneSided())
				kind = "one-sided";
			String note = "";
			if (bar.exploratory())
				note = ", descriptive only";
			lines.add("  error bar: " + bar.method() + " " + interval(bar.interval()) + " (" + kind + note + ")");
		}
	}

	/**
	 * Appends a line for each assertion's comparison with one kind of reference trial.
	 *
	 * @param lines the lines to append to
	 * @param reports the comparisons
	 * @param reference the name of the reference trials: {@code control} or {@code incumbent}
	 */
	private static void paired(List<String> lines, List<CellReport.PairedReport> reports, String reference)
	{
		for (CellReport.PairedReport report : reports)
		{
			StringBuilder text = new StringBuilder(120).append("  ").append(reference).append(" comparison ").
				append(report.assertionId()).append(": ").append(report.state());
			if (report.pairs() == 0)
			{
				// An implied comparison's state already says that it runs no pairs
				if (!report.state().equals(CellReport.PairedReport.IMPLIED))
					text.append(", no pairs");
			}
			else
			{
				text.append(", candidate ").append(percent(report.candidateRate())).append(" (").
					append(report.candidatePasses()).append('/').append(report.pairs()).append("), ").append(reference).
					append(' ').append(percent(report.referenceRate())).append(" (").append(report.referencePasses()).
					append('/').append(report.pairs()).append(')');
			}
			lines.add(text.toString());
		}
	}

	/**
	 * Adds the minimum remaining trials.
	 *
	 * @param lines the lines
	 * @param remaining the count
	 */
	private static void remaining(List<String> lines, RemainingWork remaining)
	{
		if (remaining.total() == null)
		{
			lines.add("Minimum remaining trials: unavailable, " + remaining.unavailable());
			return;
		}
		lines.add("Minimum remaining trials if every remaining trial passes: " + remaining.total());
	}

	/**
	 * Adds the token statistics.
	 *
	 * @param lines the lines
	 * @param tokens the statistics
	 */
	private static void tokens(List<String> lines, TokenStatistics tokens)
	{
		if (tokens.unavailable() != null)
		{
			lines.add("Token usage per session: unavailable, " + tokens.unavailable());
			return;
		}
		lines.add("Token usage per session (median / 90th percentile):");
		for (SessionRole role : SessionRole.values())
		{
			TokenStatistics.RoleUsage usage = tokens.roles().get(role);
			if (usage == null)
				continue;
			lines.add("  " + EnumNames.lowerCase(role) + " (" + usage.sessions() + " sessions): input " +
				pair(usage.input()) + ", cached input " + pair(usage.cachedInput()) + ", output " +
				pair(usage.output()) + ", reasoning output " + pair(usage.reasoningOutput()));
		}
	}

	/**
	 * Adds descriptive sizes, input comparisons, and retained diagnoses.
	 *
	 * @param lines the summary lines
	 * @param report the optimization report
	 */
	private static void optimization(List<String> lines, OptimizationReport report)
	{
		lines.add("Target prompt file size, working: " + promptSize(report.working()));
		lines.add("Target prompt file size, incumbent: " + promptSize(report.incumbent()));
		for (OptimizationReport.InputComparison input : report.inputs())
		{
			if (input.unavailable() != null)
				lines.add("Trial input tokens in cell " + input.cell() + ": unavailable, " + input.unavailable());
			else
			{
				lines.add("Median trial input tokens in cell " + input.cell() + ": working " + input.workingMedian() +
					" (" + input.workingSessions() + " sessions), incumbent " + input.incumbentMedian() + " (" +
					input.incumbentSessions() + " sessions)");
			}
		}
		for (PromptAuthorDiagnosis promptAuthor : report.promptAuthors())
		{
			List<String> kinds = new ArrayList<>();
			for (PromptAuthorKind kind : promptAuthor.kinds())
			{
				StringBuilder kindText = new StringBuilder(kind.name()).append(" (").
					append(EnumNames.lowerCase(kind.classification()).replace('_', ' '));
				if (!kind.fault().isEmpty())
					kindText.append(": ").append(kind.fault());
				kinds.add(kindText.append(')').toString());
			}
			lines.add("Prompt author diagnosis of attempt " + promptAuthor.number() + " in cell " +
				promptAuthor.cell() + ": " + String.join(", ", kinds) + "; evidence " + promptAuthor.transcript());
			for (String rejection : promptAuthor.rejections())
				lines.add("  Rejected: " + rejection);
		}
		if (!report.removed().isEmpty())
			lines.add("Minimality removed hunks: " + String.join(", ", report.removed()));
		for (AttemptProgress attempt : report.attempts())
		{
			String accepted = "pending";
			if (attempt.accepted() != null)
				accepted = attempt.accepted().toString();
			lines.add("Round " + attempt.round() + ", Attempt " + attempt.number() + " in cell " + attempt.cell() +
				": passing promotion runs " + attempt.passingRuns() + "/" + attempt.totalRuns() +
				"; accepted: " + accepted + "; winner: " + attempt.winner());
		}
		testRevisions(lines, report.testRevisions());
	}

	/**
	 * Adds the requested test revisions, each completed revision with its diff and reasons, and each test reported
	 * sound.
	 *
	 * @param lines the summary lines
	 * @param revisions the test revisions
	 */
	private static void testRevisions(List<String> lines, TestRevisions revisions)
	{
		for (TestRevisionRequest request : revisions.requests())
		{
			lines.add("Test revision requested for test " + request.test() + " by " +
				EnumNames.lowerCase(request.trigger()).replace('_', ' '));
		}
		for (TestRevision revision : revisions.revisions())
		{
			lines.add("Test revision of test " + revision.test() + " in lineage " + revision.lineage() + ": " +
				revision.reasons());
			for (String line : revision.diff().split("\n"))
				lines.add("  " + line);
		}
		for (SoundTest sound : revisions.sound())
		{
			lines.add("Test " + sound.test() + " reported sound in lineage " + sound.lineage() + ": " +
				sound.reasons());
		}
	}

	/**
	 * Describes the available sizes of the target prompt file and missing measurements.
	 *
	 * @param size the measurements
	 * @return the description
	 */
	private static String promptSize(PromptSize size)
	{
		if (size.bytes() == null)
			return "unavailable, " + size.unavailable();
		if (size.words() == null)
			return size.bytes() + " bytes; words unavailable, " + size.unavailable();
		return size.bytes() + " bytes, " + size.words() + " words";
	}

	/**
	 * Adds a keep-improvement offer.
	 *
	 * @param lines the lines
	 * @param offer the offer
	 */
	private static void offer(List<String> lines, KeepOffer offer)
	{
		StringBuilder text = new StringBuilder(200).append("Keep improvement, attempt ").append(offer.attempt());
		if (offer.historical())
			text.append(" (historical; differs in ").append(String.join(", ", offer.differing())).append(')');
		lines.add(text.append(": weakest cell lower bound ").append(decimal(offer.weakestLowerBound())).
			toString());
		for (KeepOffer.CellEvidence cell : offer.cells())
		{
			StringBuilder line = new StringBuilder(300).append("  ").append(cell.cell()).append(": ").
				append(cell.passes()).append('/').append(cell.trials()).append(", ").
				append(cell.state()).append(" (UI ").append(decimal(cell.incumbentUpper())).
				append(", LI ").append(decimal(cell.incumbentLower())).append("), difference ").
				append(interval(cell.difference()));
			if (cell.note() != null)
				line.append(". ").append(cell.note());
			lines.add(line.toString());
		}
	}

	/**
	 * Formats the median and 90th percentile.
	 *
	 * @param percentiles the percentiles
	 * @return the text
	 */
	private static String pair(TokenStatistics.Percentiles percentiles)
	{
		return percentiles.median() + " / " + percentiles.p90();
	}

	/**
	 * Formats a fraction as a percentage.
	 *
	 * @param fraction the fraction
	 * @return the text
	 */
	private static String percent(double fraction)
	{
		return String.format(Locale.ROOT, "%.1f%%", 100.0 * fraction);
	}

	/**
	 * Formats a number with three decimals.
	 *
	 * @param value the number
	 * @return the text
	 */
	private static String decimal(double value)
	{
		return String.format(Locale.ROOT, "%.3f", value);
	}

	/**
	 * Formats an interval.
	 *
	 * @param interval the interval
	 * @return the text
	 */
	private static String interval(Interval interval)
	{
		return "[" + decimal(interval.lower()) + ", " + decimal(interval.upper()) + "]";
	}
}

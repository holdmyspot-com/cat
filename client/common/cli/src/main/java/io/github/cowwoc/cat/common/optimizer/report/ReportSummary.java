package io.github.cowwoc.cat.common.optimizer.report;

import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorDiagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptSize;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.statistics.Interval;

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
	 * Renders a report.
	 *
	 * @param report the report
	 * @return the text
	 */
	static String of(StatusReport report)
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
		if (report.pending() != null)
			lines.add("Waiting for: " + report.pending());
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
				weights.add(weight.cell() + ": " + weight.tests() + " tests of weight " +
					decimal(weight.testWeight()));
			}
			lines.add("Benchmark weights (testing assumptions, not usage frequencies): " +
				String.join("; ", weights));
		}
		if (report.trialProfiles() != null)
			lines.addAll(report.trialProfiles().describe());
		if (report.cellTier() != null)
			cellTier(lines, report.cellTier());
		remaining(lines, report.remaining());
		tokens(lines, report.tokens());
		optimization(lines, report.optimization());
		StatusReport.Concurrency concurrency = report.concurrency();
		String throttle = "no hold";
		if (concurrency.holdUntil() != null)
			throttle = "hold until " + concurrency.holdUntil();
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
		{
			StringBuilder text = new StringBuilder(200).append("Attempt ").append(attempt.index()).append(": ");
			if (attempt.outcome() == null)
				text.append("running");
			else
				text.append(attempt.outcome());
			if (attempt.historical())
				text.append(" (historical; changed: ").append(String.join(", ", attempt.changed())).append(')');
			lines.add(text.toString());
		}
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
	 * Describes an authority warning and the commands that answer it.
	 *
	 * @param warning the warning
	 * @return the line
	 */
	private static String authorityWarning(AuthorityWarning warning)
	{
		List<String> choices = new ArrayList<>();
		for (AuthorityWarning.Choice choice : warning.choices())
		{
			String label = ReportJson.command(choice).replace('-', ' ');
			if (choice == AuthorityWarning.Choice.AUTHORIZE_TARGET)
				choices.add(label + " (--target " + warning.target() + ")");
			else
				choices.add(label);
		}
		return "Authority warning: requirement " + warning.requirement() + " needs an edit at " + warning.path() +
			", which resolves to " + warning.target() + " (limit: " +
			EnumNames.lowerCase(warning.limit()).replace('_', ' ') + "); choices: " +
			String.join(", ", choices);
	}

	/**
	 * Adds the development cell tier.
	 *
	 * @param lines the lines
	 * @param tier the cell tier
	 */
	private static void cellTier(List<String> lines, CellTierReport tier)
	{
		for (Map.Entry<String, List<String>> entry : tier.activeCells().entrySet())
		{
			lines.add("Active development cells of category " + entry.getKey() + ": " +
				String.join(", ", entry.getValue()));
		}
		String answer;
		if (tier.widened())
			answer = "yes";
		else
			answer = "no";
		lines.add("Development widened to every cell: " + answer);
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
		StringBuilder comparisonText = new StringBuilder(300).append("  comparison with 95%: ").append(comparison.state());
		if (comparison.readiness() != null)
		{
			comparisonText.append(" (").append(comparison.readiness());
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
		lines.add("Prompt size, working: " + promptSize(report.working()));
		lines.add("Prompt size, incumbent: " + promptSize(report.incumbent()));
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
		for (AuthorDiagnosis author : report.authors())
		{
			List<String> classifications = author.classifications().values().stream().map(FailureKind::label).
				distinct().sorted().toList();
			lines.add("Author diagnosis in cell " + author.cell() + ": " + String.join(", ", classifications) +
				"; evidence " + author.transcript());
			for (String rejection : author.rejections())
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
	}

	/**
	 * Describes available prompt sizes and missing measurements.
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

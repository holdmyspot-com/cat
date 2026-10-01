package io.github.cowwoc.cat.common.optimizer.report;

import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.statistics.Interval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
			report.phase().name().toLowerCase(Locale.ROOT) + ", candidate " + report.candidate() + ")");
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
		for (CategoryReport category : report.categories())
			category(lines, category);
		if (!report.allocation().isEmpty())
		{
			List<String> counts = new ArrayList<>();
			for (CategoryReport category : report.categories())
				counts.add(category.category() + " " + report.allocation().get(category.category()));
			lines.add("Allocation (adopted outcomes, not benchmark weights): " + String.join(", ", counts));
		}
		if (report.benchmark() != null)
		{
			List<String> weights = new ArrayList<>();
			for (Benchmark.CategoryWeights weight : report.benchmark().categories())
			{
				weights.add(weight.category() + ": " + weight.tests() + " tests of weight " +
					decimal(weight.testWeight()));
			}
			lines.add("Benchmark weights (testing assumptions, not usage frequencies): " +
				String.join("; ", weights));
		}
		remaining(lines, report.remaining());
		tokens(lines, report.tokens());
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
	 * Adds a category's four outputs.
	 *
	 * @param lines the lines
	 * @param category the category
	 */
	private static void category(List<String> lines, CategoryReport category)
	{
		lines.add("Category " + category.category() + ":");
		CategoryReport.PassRate passRate = category.passRate();
		if (passRate.rate() == null)
			lines.add("  pass rate (" + passRate.label() + "): unavailable, " + passRate.unavailable());
		else
		{
			lines.add("  pass rate (" + passRate.label() + "): " + percent(passRate.rate()) + " (" +
				category.passes() + "/" + category.trials() + ")");
		}
		CategoryReport.Improvement improvement = category.improvement();
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
		CategoryReport.Threshold comparison = category.comparison();
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
		for (CategoryReport.ErrorBar bar : category.errorBars())
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
			lines.add("  " + role.name().toLowerCase(Locale.ROOT) + " (" + usage.sessions() + " sessions): input " +
				pair(usage.input()) + ", cached input " + pair(usage.cachedInput()) + ", output " +
				pair(usage.output()) + ", reasoning output " + pair(usage.reasoningOutput()));
		}
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
		lines.add(text.append(": weakest category lower bound ").append(decimal(offer.weakestLowerBound())).
			toString());
		for (KeepOffer.Category category : offer.categories())
		{
			StringBuilder line = new StringBuilder(300).append("  ").append(category.category()).append(": ").
				append(category.passes()).append('/').append(category.trials()).append(", ").
				append(category.state()).append(" (UI ").append(decimal(category.incumbentUpper())).
				append(", LI ").append(decimal(category.incumbentLower())).append("), difference ").
				append(interval(category.difference()));
			if (category.note() != null)
				line.append(". ").append(category.note());
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

package io.github.cowwoc.cat.common.optimizer.report;

import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileBinding;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfiles;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.statistics.Interval;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts a {@link StatusReport} to JSON.
 */
final class ReportJson
{
	/**
	 * Prevents instantiation.
	 */
	private ReportJson()
	{
	}

	/**
	 * Converts a report.
	 *
	 * @param report the report
	 * @param mapper the mapper that creates the nodes
	 * @return the JSON object
	 */
	static ObjectNode of(StatusReport report, JsonMapper mapper)
	{
		ObjectNode root = mapper.createObjectNode();
		root.put("status", report.status().label());
		root.put("campaign", report.campaignId());
		root.put("lifecycle", report.lifecycle().name().toLowerCase(Locale.ROOT));
		root.put("phase", report.phase().name().toLowerCase(Locale.ROOT));
		root.put("attempt", report.attempt());
		ObjectNode candidate = root.putObject("candidate");
		candidate.put("number", report.candidate());
		candidate.put("snapshot", report.snapshot());
		putText(root, "outcome", report.outcome());
		putText(root, "outcomeDetail", report.outcomeDetail());
		ObjectNode delivery = root.putObject("delivery");
		putText(delivery, "label", report.delivery());
		delivery.put("attempt", report.deliveryAttempt());
		putText(root, "pending", report.pending());
		choices(root.putArray("choices"), report);
		authorityWarnings(root.putArray("authorityWarnings"), report);
		ArrayNode cells = root.putArray("cells");
		for (CategoryReport cell : report.cells())
			cell(cells.addObject(), cell);
		ObjectNode allocation = root.putObject("allocation");
		for (Map.Entry<String, Integer> entry : report.allocation().entrySet())
			allocation.put(entry.getKey(), entry.getValue());
		benchmark(root, report);
		trialProfiles(root, report.trialProfiles());
		cellTier(root, report.cellTier());
		remaining(root.putObject("remaining"), report.remaining());
		tokens(root.putObject("tokens"), report.tokens());
		StatusReport.Concurrency concurrency = report.concurrency();
		ObjectNode concurrencyNode = root.putObject("concurrency");
		concurrencyNode.put("window", concurrency.window());
		concurrencyNode.put("ceiling", concurrency.ceiling());
		concurrencyNode.put("slowStartSize", concurrency.slowStartSize());
		putText(concurrencyNode, "holdUntil", concurrency.holdUntil());
		concurrencyNode.put("windowCap", concurrency.windowCap());
		concurrencyNode.put("backoffs", concurrency.backoffs());
		assumptions(root.putObject("assumptions"), report.assumptions());
		ArrayNode attempts = root.putArray("attempts");
		for (AttemptSummary attempt : report.attempts())
		{
			ObjectNode node = attempts.addObject();
			node.put("index", attempt.index());
			putText(node, "outcome", attempt.outcome());
			node.put("historical", attempt.historical());
			strings(node.putArray("changed"), attempt.changed());
		}
		keepOffers(root.putArray("keepOffers"), report);
		putText(root, "claim", report.claim());
		strings(root.putArray("limitations"), report.limitations());
		ObjectNode conditions = root.putObject("conditions");
		for (Map.Entry<String, String> entry : report.conditions().entrySet())
			conditions.put(entry.getKey(), entry.getValue());
		return root;
	}

	/**
	 * Adds the choices.
	 *
	 * @param array the array
	 * @param report the report
	 */
	private static void choices(ArrayNode array, StatusReport report)
	{
		for (ChoiceItem choice : report.choices())
		{
			ObjectNode node = array.addObject();
			node.put("id", choice.id());
			node.put("label", choice.label());
			if (choice.attempt() > 0)
			{
				node.put("attempt", choice.attempt());
				node.put("historical", choice.historical());
			}
			if (choice.reviewPointAbove() > 0)
				node.put("reviewPointAbove", choice.reviewPointAbove());
		}
	}

	/**
	 * Adds the warnings of a pending authority decision.
	 *
	 * @param array the array
	 * @param report the report
	 */
	private static void authorityWarnings(ArrayNode array, StatusReport report)
	{
		for (AuthorityWarning warning : report.authorityWarnings())
		{
			ObjectNode node = array.addObject();
			node.put("requirement", warning.requirement());
			node.put("path", warning.path().toString());
			node.put("target", warning.target().toString());
			node.put("limit", warning.limit().name().toLowerCase(Locale.ROOT).replace('_', '-'));
			ArrayNode choices = node.putArray("choices");
			for (AuthorityWarning.Choice choice : warning.choices())
				choices.add(command(choice));
		}
	}

	/**
	 * Returns the command that gives a choice of an authority warning.
	 *
	 * @param choice the choice
	 * @return the {@code choose} identifier, or {@code revise} for a contract revision
	 */
	static String command(AuthorityWarning.Choice choice)
	{
		return switch (choice)
		{
			case AUTHORIZE_TARGET -> "authorize-target";
			case USE_AUTHORIZED_SOURCE -> "use-authorized-source";
			case STOP_CAMPAIGN -> "stop";
			case REVISE_CAMPAIGN -> "revise";
		};
	}

	/**
	 * Adds a cell's four outputs.
	 *
	 * @param node the node
	 * @param category the cell's report
	 */
	private static void cell(ObjectNode node, CategoryReport category)
	{
		node.put("cell", category.cell());
		node.put("passes", category.passes());
		node.put("trials", category.trials());
		ObjectNode passRate = node.putObject("passRate");
		passRate.put("label", category.passRate().label());
		putNumber(passRate, "rate", category.passRate().rate());
		putText(passRate, "unavailable", category.passRate().unavailable());

		CategoryReport.Improvement improvement = category.improvement();
		ObjectNode improvementNode = node.putObject("improvement");
		putText(improvementNode, "unavailable", improvement.unavailable());
		putNumber(improvementNode, "points", improvement.points());
		if (improvement.incumbent() != null)
		{
			ObjectNode incumbent = improvementNode.putObject("incumbent");
			incumbent.put("baseline", improvement.incumbent().baseline());
			incumbent.put("passes", improvement.incumbent().passes());
			incumbent.put("trials", improvement.incumbent().trials());
		}
		putText(improvementNode, "deficiency", improvement.deficiency());
		if (improvement.difference() != null)
		{
			interval(improvementNode.putObject("difference"), improvement.difference());
			putText(improvementNode, "differenceMethod", improvement.differenceMethod());
		}
		putText(improvementNode, "state", improvement.state());
		putNumber(improvementNode, "incumbentUpper", improvement.incumbentUpper());
		putNumber(improvementNode, "incumbentLower", improvement.incumbentLower());

		CategoryReport.Threshold comparison = category.comparison();
		ObjectNode comparisonNode = node.putObject("comparison");
		comparisonNode.put("state", comparison.state());
		putText(comparisonNode, "readiness", comparison.readiness());
		strings(comparisonNode.putArray("reasons"), comparison.reasons());
		putNumber(comparisonNode, "lowerBound", comparison.lowerBound());
		putNumber(comparisonNode, "upperBound", comparison.upperBound());
		putNumber(comparisonNode, "alpha", comparison.alpha());

		ArrayNode bars = node.putArray("errorBars");
		for (CategoryReport.ErrorBar bar : category.errorBars())
		{
			ObjectNode barNode = bars.addObject();
			barNode.put("method", bar.method());
			barNode.put("oneSided", bar.oneSided());
			barNode.put("exploratory", bar.exploratory());
			putNumber(barNode, "errorProbability", bar.errorProbability());
			barNode.put("sampleSize", bar.sampleSize());
			interval(barNode.putObject("interval"), bar.interval());
		}
	}

	/**
	 * Adds the benchmark weights.
	 *
	 * @param root the root
	 * @param report the report
	 */
	private static void benchmark(ObjectNode root, StatusReport report)
	{
		if (report.benchmark() == null)
		{
			root.putNull("benchmark");
			return;
		}
		ArrayNode array = root.putObject("benchmark").putArray("cells");
		for (Benchmark.CategoryWeights weights : report.benchmark().cells())
		{
			ObjectNode node = array.addObject();
			node.put("cell", weights.cell());
			node.put("tests", weights.tests());
			node.put("testWeight", weights.testWeight());
		}
	}

	/**
	 * Adds the confirmed trial profiles and cells.
	 *
	 * @param root the root
	 * @param profiles the trial profiles, or {@code null} before a contract is confirmed
	 */
	private static void trialProfiles(ObjectNode root, TrialProfiles profiles)
	{
		if (profiles == null)
		{
			root.putNull("trialProfiles");
			return;
		}
		ObjectNode node = root.putObject("trialProfiles");
		node.put("inventorySource", profiles.inventory().source());
		ArrayNode inventory = node.putArray("inventory");
		for (TrialProfile profile : profiles.inventory().profiles())
			profile(inventory.addObject(), profile);
		ArrayNode bindings = node.putArray("bindings");
		for (ProfileBinding binding : profiles.bindings())
		{
			ObjectNode bindingNode = bindings.addObject();
			bindingNode.put("file", binding.file());
			profile(bindingNode, binding.profile());
			bindingNode.put("outsideInventory", profiles.inventory().isOutside(binding.profile()));
		}
		ArrayNode restriction = node.putArray("restriction");
		for (TrialProfile pattern : profiles.restriction())
			profile(restriction.addObject(), pattern);
		ArrayNode cells = node.putArray("cells");
		for (Cell cell : profiles.cells())
		{
			ObjectNode cellNode = cells.addObject();
			cellNode.put("id", cell.id());
			cellNode.put("category", cell.categoryId());
			profile(cellNode, cell.profile());
		}
	}

	/**
	 * Adds the development cell tier.
	 *
	 * @param root the root
	 * @param tier the cell tier, or {@code null} before a contract is confirmed
	 */
	private static void cellTier(ObjectNode root, CellTierReport tier)
	{
		if (tier == null)
		{
			root.putNull("cellTier");
			return;
		}
		ObjectNode node = root.putObject("cellTier");
		node.put("widened", tier.widened());
		ArrayNode active = node.putArray("activeCells");
		for (Map.Entry<String, List<String>> entry : tier.activeCells().entrySet())
		{
			ObjectNode category = active.addObject();
			category.put("category", entry.getKey());
			ArrayNode cells = category.putArray("cells");
			for (String cell : entry.getValue())
				cells.add(cell);
		}
	}

	/**
	 * Adds a profile's model and effort, an empty value meaning the harness's default.
	 *
	 * @param node the node
	 * @param profile the profile
	 */
	private static void profile(ObjectNode node, TrialProfile profile)
	{
		node.put("model", profile.model());
		node.put("effort", profile.effort());
	}

	/**
	 * Adds the minimum remaining trials.
	 *
	 * @param node the node
	 * @param remaining the count
	 */
	private static void remaining(ObjectNode node, RemainingWork remaining)
	{
		node.put("phase", remaining.phase().name().toLowerCase(Locale.ROOT));
		if (remaining.total() == null)
			node.putNull("total");
		else
			node.put("total", remaining.total().intValue());
		putText(node, "unavailable", remaining.unavailable());
		ArrayNode entries = node.putArray("entries");
		for (RemainingWork.Entry entry : remaining.entries())
		{
			ObjectNode entryNode = entries.addObject();
			entryNode.put("cell", entry.cell());
			entryNode.put("kind", entry.kind().name().toLowerCase(Locale.ROOT));
			if (entry.trials() == null)
				entryNode.putNull("trials");
			else
				entryNode.put("trials", entry.trials().intValue());
			putText(entryNode, "unavailable", entry.unavailable());
		}
	}

	/**
	 * Adds the token statistics.
	 *
	 * @param node the node
	 * @param tokens the statistics
	 */
	private static void tokens(ObjectNode node, TokenStatistics tokens)
	{
		putText(node, "unavailable", tokens.unavailable());
		ObjectNode roles = node.putObject("roles");
		for (SessionRole role : SessionRole.values())
		{
			TokenStatistics.RoleUsage usage = tokens.roles().get(role);
			if (usage == null)
				continue;
			ObjectNode roleNode = roles.putObject(role.name().toLowerCase(Locale.ROOT));
			roleNode.put("sessions", usage.sessions());
			percentiles(roleNode.putObject("input"), usage.input());
			percentiles(roleNode.putObject("cachedInput"), usage.cachedInput());
			percentiles(roleNode.putObject("output"), usage.output());
			percentiles(roleNode.putObject("reasoningOutput"), usage.reasoningOutput());
		}
	}

	/**
	 * Adds percentiles.
	 *
	 * @param node the node
	 * @param percentiles the percentiles
	 */
	private static void percentiles(ObjectNode node, TokenStatistics.Percentiles percentiles)
	{
		node.put("median", percentiles.median());
		node.put("p90", percentiles.p90());
	}

	/**
	 * Adds the assumption review.
	 *
	 * @param node the node
	 * @param assumptions the review
	 */
	private static void assumptions(ObjectNode node, AssumptionReport assumptions)
	{
		ArrayNode rates = node.putArray("passRatesByConcurrency");
		for (AssumptionCheck.LevelRate rate : assumptions.passRatesByConcurrency())
		{
			ObjectNode rateNode = rates.addObject();
			rateNode.put("concurrency", rate.concurrency());
			rateNode.put("passes", rate.passes());
			rateNode.put("trials", rate.trials());
			interval(rateNode.putObject("interval"), rate.interval());
		}
		node.put("descriptive", true);
		node.put("dependenceDetected", assumptions.dependenceDetected());
		if (assumptions.consequence() == null)
			node.putNull("consequence");
		else
			node.put("consequence", assumptions.consequence().label());
	}

	/**
	 * Adds the keep-improvement offers.
	 *
	 * @param array the array
	 * @param report the report
	 */
	private static void keepOffers(ArrayNode array, StatusReport report)
	{
		for (KeepOffer offer : report.keepOffers())
		{
			ObjectNode node = array.addObject();
			node.put("attempt", offer.attempt());
			node.put("historical", offer.historical());
			strings(node.putArray("differing"), offer.differing());
			node.put("weakestLowerBound", offer.weakestLowerBound());
			ArrayNode cells = node.putArray("cells");
			for (KeepOffer.Category category : offer.cells())
			{
				ObjectNode categoryNode = cells.addObject();
				categoryNode.put("cell", category.cell());
				categoryNode.put("passes", category.passes());
				categoryNode.put("trials", category.trials());
				categoryNode.put("state", category.state());
				categoryNode.put("incumbentUpper", category.incumbentUpper());
				categoryNode.put("incumbentLower", category.incumbentLower());
				interval(categoryNode.putObject("difference"), category.difference());
				putText(categoryNode, "note", category.note());
			}
		}
	}

	/**
	 * Adds an interval.
	 *
	 * @param node the node
	 * @param interval the interval
	 */
	private static void interval(ObjectNode node, Interval interval)
	{
		node.put("lower", interval.lower());
		node.put("upper", interval.upper());
	}

	/**
	 * Adds strings.
	 *
	 * @param array the array
	 * @param values the strings
	 */
	private static void strings(ArrayNode array, Iterable<String> values)
	{
		for (String value : values)
			array.add(value);
	}

	/**
	 * Adds a text, or null.
	 *
	 * @param node the node
	 * @param name the field name
	 * @param value the text, or {@code null}
	 */
	private static void putText(ObjectNode node, String name, String value)
	{
		if (value == null)
			node.putNull(name);
		else
			node.put(name, value);
	}

	/**
	 * Adds a number, or null.
	 *
	 * @param node the node
	 * @param name the field name
	 * @param value the number, or {@code null}
	 */
	private static void putNumber(ObjectNode node, String name, Double value)
	{
		if (value == null)
			node.putNull(name);
		else
			node.put(name, value.doubleValue());
	}
}

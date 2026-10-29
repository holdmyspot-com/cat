package com.holdmyspot.cat.common.optimizer.report;

import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.campaign.AssumptionCheck;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.Revert;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.ProfileBinding;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfiles;
import com.holdmyspot.cat.common.optimizer.inventory.AuthorityWarning;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.statistics.Interval;

import java.util.List;
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
		root.put("lifecycle", EnumNames.lowerCase(report.lifecycle()));
		root.put("phase", EnumNames.lowerCase(report.phase()));
		root.put("attempt", report.attempt());
		ObjectNode candidate = root.putObject("candidate");
		candidate.put("number", report.candidate());
		candidate.put("snapshot", report.snapshot());
		putText(root, "outcome", report.outcome());
		putText(root, "outcome_detail", report.outcomeDetail());
		ObjectNode delivery = root.putObject("delivery");
		putText(delivery, "label", report.delivery());
		delivery.put("attempt", report.deliveryAttempt());
		putText(root, "pending", report.pending());
		choices(root.putArray("choices"), report);
		authorityWarnings(root.putArray("authority_warnings"), report);
		ArrayNode cells = root.putArray("cells");
		for (CellReport cell : report.cells())
			cell(cells.addObject(), cell);
		ObjectNode allocation = root.putObject("allocation");
		for (Map.Entry<String, Integer> entry : report.allocation().entrySet())
			allocation.put(entry.getKey(), entry.getValue());
		benchmark(root, report);
		trialProfiles(root, report.trialProfiles());
		cellTier(root, report.cellTier());
		remaining(root.putObject("remaining"), report.remaining());
		tokens(root.putObject("tokens"), report.tokens());
		OptimizationJson.write(root, report.optimization());
		reverts(root.putArray("reverts"), report.reverts());
		addedTestCases(root.putArray("added_test_cases"), report.addedTestCases());
		StatusReport.Concurrency concurrency = report.concurrency();
		ObjectNode concurrencyNode = root.putObject("concurrency");
		concurrencyNode.put("window", concurrency.window());
		concurrencyNode.put("ceiling", concurrency.ceiling());
		concurrencyNode.put("slow_start_size", concurrency.slowStartSize());
		if (concurrency.holdUntil() == null)
			concurrencyNode.putNull("hold_until");
		else
			concurrencyNode.put("hold_until", concurrency.holdUntil().toString());
		if (concurrency.sharedHoldUntil() == null)
			concurrencyNode.putNull("shared_hold_until");
		else
			concurrencyNode.put("shared_hold_until", concurrency.sharedHoldUntil().toString());
		concurrencyNode.put("window_cap", concurrency.windowCap());
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
			ArrayNode unchecked = node.putArray("unchecked");
			for (MeasurementCondition condition : attempt.unchecked())
				unchecked.add(EnumNames.lowerCase(condition));
			strings(node.putArray("undecided"), attempt.undecided());
		}
		keepOffers(root.putArray("keep_offers"), report);
		putText(root, "claim", report.claim());
		strings(root.putArray("limitations"), report.limitations());
		ObjectNode conditions = root.putObject("conditions");
		for (Map.Entry<String, String> entry : report.conditions().entrySet())
			conditions.put(entry.getKey(), entry.getValue());
		return root;
	}

	/**
	 * Adds the withdrawn candidates and the estimates that triggered each revert.
	 *
	 * @param array the report's revert array
	 * @param reverts the recorded withdrawals, in withdrawal order
	 */
	private static void reverts(ArrayNode array, List<Revert> reverts)
	{
		for (Revert revert : reverts)
		{
			ObjectNode node = array.addObject();
			ObjectNode withdrawn = node.putObject("withdrawn");
			withdrawn.put("number", revert.withdrawn().number());
			withdrawn.put("snapshot", revert.withdrawn().snapshot().hex());
			node.put("cell", revert.cell());
			node.put("upper_bound", revert.upperBound());
			node.put("predecessor_lower_bound", revert.predecessorLowerBound());
		}
	}

	/**
	 * Adds the test cases added to the development part of the tests.
	 *
	 * @param array the report's array of added test cases
	 * @param testCases the test cases, in the order they were added
	 */
	private static void addedTestCases(ArrayNode array, List<AddedTestCaseReport> testCases)
	{
		for (AddedTestCaseReport testCase : testCases)
		{
			ObjectNode node = array.addObject();
			node.put("attempt", testCase.attempt());
			node.put("test", testCase.testId());
			ArrayNode values = node.putArray("values");
			for (Map.Entry<String, String> value : testCase.values().entrySet())
				values.addObject().put("variable", value.getKey()).put("text", value.getValue());
			node.put("expected_outcome", testCase.expectedOutcome());
		}
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
			if (choice.choice() == Choice.RESOLUTION)
			{
				node.put("recommended", choice.recommended());
				if (choice.recommended())
					node.put("reason", choice.reason());
			}
			if (choice.attempt() > 0)
			{
				node.put("attempt", choice.attempt());
				node.put("historical", choice.historical());
			}
			if (choice.reviewPointAbove() > 0)
				node.put("review_point_above", choice.reviewPointAbove());
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
			node.put("limit", EnumNames.lowerCase(warning.limit()));
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
			case USE_AUTHORIZED_SOURCE -> "use_authorized_source";
			case STOP_CAMPAIGN -> "stop";
			case REVISE_CAMPAIGN -> "revise";
		};
	}

	/**
	 * Adds a cell's four outputs.
	 *
	 * @param node the node
	 * @param report the cell's report
	 */
	private static void cell(ObjectNode node, CellReport report)
	{
		node.put("cell", report.cell());
		node.put("passes", report.passes());
		node.put("trials", report.trials());
		ObjectNode passRate = node.putObject("pass_rate");
		passRate.put("label", report.passRate().label());
		putNumber(passRate, "rate", report.passRate().rate());
		putText(passRate, "unavailable", report.passRate().unavailable());

		CellReport.Improvement improvement = report.improvement();
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
			putText(improvementNode, "difference_method", improvement.differenceMethod());
		}
		putText(improvementNode, "state", improvement.state());
		putNumber(improvementNode, "incumbent_upper", improvement.incumbentUpper());
		putNumber(improvementNode, "incumbent_lower", improvement.incumbentLower());

		CellReport.Threshold comparison = report.comparison();
		ObjectNode comparisonNode = node.putObject("comparison");
		comparisonNode.put("state", comparison.state());
		comparisonNode.put("minimum_pass_rate", comparison.minimumPassRate());
		putText(comparisonNode, "commit_gate", comparison.commitGate());
		strings(comparisonNode.putArray("reasons"), comparison.reasons());
		putNumber(comparisonNode, "lower_bound", comparison.lowerBound());
		putNumber(comparisonNode, "upper_bound", comparison.upperBound());
		putNumber(comparisonNode, "alpha", comparison.alpha());
		paired(comparisonNode.putArray("controls"), comparison.controls(), "control");
		paired(comparisonNode.putArray("incumbents"), comparison.incumbents(), "incumbent");
		putText(comparisonNode, "cell_state", comparison.cellState());

		ArrayNode bars = node.putArray("error_bars");
		for (CellReport.ErrorBar bar : report.errorBars())
		{
			ObjectNode barNode = bars.addObject();
			barNode.put("method", bar.method());
			barNode.put("one_sided", bar.oneSided());
			barNode.put("exploratory", bar.exploratory());
			putNumber(barNode, "error_probability", bar.errorProbability());
			barNode.put("sample_size", bar.sampleSize());
			interval(barNode.putObject("interval"), bar.interval());
		}
	}


	/**
	 * Writes each assertion's comparison with one kind of reference trial.
	 *
	 * @param nodes the array to write to
	 * @param reports the comparisons
	 * @param reference the JSON name of the reference trials: {@code control} or {@code incumbent}
	 */
	private static void paired(ArrayNode nodes, List<CellReport.PairedReport> reports, String reference)
	{
		for (CellReport.PairedReport report : reports)
		{
			ObjectNode node = nodes.addObject();
			node.put("assertion_id", report.assertionId());
			node.put("state", report.state());
			node.put("pairs", report.pairs());
			node.put("candidate_passes", report.candidatePasses());
			node.put(reference + "_passes", report.referencePasses());
			putNumber(node, "candidate_rate", report.candidateRate());
			putNumber(node, reference + "_rate", report.referenceRate());
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
		for (Benchmark.CellWeights weights : report.benchmark().cells())
		{
			ObjectNode node = array.addObject();
			node.put("cell", weights.cell());
			node.put("test_cases", weights.testCases());
			node.put("test_case_weight", weights.testCaseWeight());
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
			root.putNull("trial_profiles");
			return;
		}
		ObjectNode node = root.putObject("trial_profiles");
		node.put("inventory_source", profiles.inventory().source());
		ArrayNode inventory = node.putArray("inventory");
		for (TrialProfile profile : profiles.inventory().profiles())
			profile(inventory.addObject(), profile);
		ArrayNode bindings = node.putArray("bindings");
		for (ProfileBinding binding : profiles.bindings())
		{
			ObjectNode bindingNode = bindings.addObject();
			bindingNode.put("file", binding.file());
			profile(bindingNode, binding.profile());
			bindingNode.put("outside_inventory", profiles.inventory().isOutside(binding.profile()));
		}
		ArrayNode restriction = node.putArray("restriction");
		for (TrialProfile pattern : profiles.restriction())
			profile(restriction.addObject(), pattern);
		ArrayNode cells = node.putArray("cells");
		for (Cell cell : profiles.cells())
		{
			ObjectNode cellNode = cells.addObject();
			cellNode.put("id", cell.id());
			cellNode.put("test", cell.testId());
			profile(cellNode, cell.profile());
		}
	}

	/**
	 * Adds the certification scope and each test's weakest cell and development cells.
	 *
	 * @param root the root
	 * @param tier the cells, or {@code null} before a contract is confirmed
	 */
	private static void cellTier(ObjectNode root, CellTierReport tier)
	{
		if (tier == null)
		{
			root.putNull("cell_tier");
			return;
		}
		ObjectNode node = root.putObject("cell_tier");
		node.put("scope", EnumNames.lowerCase(tier.scope()));
		node.put("scope_cells", tier.scopeCells());
		ArrayNode tests = node.putArray("tests");
		for (CellTierReport.TestCells cells : tier.tests())
		{
			ObjectNode test = tests.addObject();
			test.put("test", cells.test());
			test.put("weakest_cell", cells.weakestCell());
			ArrayNode development = test.putArray("development_cells");
			for (String cell : cells.developmentCells())
				development.add(cell);
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
		node.put("phase", EnumNames.lowerCase(remaining.phase()));
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
			entryNode.put("kind", EnumNames.lowerCase(entry.kind()));
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
			ObjectNode roleNode = roles.putObject(EnumNames.lowerCase(role));
			roleNode.put("sessions", usage.sessions());
			percentiles(roleNode.putObject("input"), usage.input());
			percentiles(roleNode.putObject("cached_input"), usage.cachedInput());
			percentiles(roleNode.putObject("output"), usage.output());
			percentiles(roleNode.putObject("reasoning_output"), usage.reasoningOutput());
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
		ArrayNode rates = node.putArray("pass_rates_by_concurrency");
		for (AssumptionCheck.LevelRate rate : assumptions.passRatesByConcurrency())
		{
			ObjectNode rateNode = rates.addObject();
			rateNode.put("concurrency", rate.concurrency());
			rateNode.put("passes", rate.passes());
			rateNode.put("trials", rate.trials());
			interval(rateNode.putObject("interval"), rate.interval());
		}
		node.put("descriptive", true);
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
			node.put("weakest_lower_bound", offer.weakestLowerBound());
			ArrayNode cells = node.putArray("cells");
			for (KeepOffer.CellEvidence cell : offer.cells())
			{
				ObjectNode cellNode = cells.addObject();
				cellNode.put("cell", cell.cell());
				cellNode.put("passes", cell.passes());
				cellNode.put("trials", cell.trials());
				cellNode.put("state", cell.state());
				cellNode.put("incumbent_upper", cell.incumbentUpper());
				cellNode.put("incumbent_lower", cell.incumbentLower());
				interval(cellNode.putObject("difference"), cell.difference());
				putText(cellNode, "note", cell.note());
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

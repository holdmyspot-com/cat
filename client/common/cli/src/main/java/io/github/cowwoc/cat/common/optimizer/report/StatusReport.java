package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.ConcurrencyPolicy;
import io.github.cowwoc.cat.common.optimizer.campaign.ImprovementOffer;
import io.github.cowwoc.cat.common.optimizer.campaign.KeepImprovement;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.Throttle;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfiles;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.statistics.Comparison;
import io.github.cowwoc.cat.common.optimizer.statistics.ComparisonBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ConfidenceBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.Interval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The status of a campaign: the JSON that the driver emits and the human summary that the user reads.
 * <p>
 * The report shows the four outputs of {@code design.md} section 2.9 for every cell, the trial profiles, the
 * development cell tier, the outcome names, the claim and its limitations, the minimum remaining trials, token
 * statistics, the descriptive check by concurrency level and the choices that the user may give now, with the
 * evidence of a keep-improvement choice. It never shows a pooled pass fraction, a token total, a time estimate or a
 * completion probability.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param status what the host does next, or how the campaign ended
 * @param campaignId the campaign's identifier
 * @param lifecycle the campaign's lifecycle state
 * @param phase the phase that the category reports describe
 * @param attempt the certification index that the report describes, or 0 if none
 * @param candidate the number of the candidate that the report describes
 * @param snapshot the hexadecimal identity of that candidate's snapshot
 * @param outcome the label of the campaign's outcome, or {@code null} if it has none
 * @param outcomeDetail the precise infrastructure failure when the outcome is {@code infrastructure failure},
 *   otherwise {@code null}
 * @param delivery the label of the result that the campaign decided to deliver, or {@code null} if none
 * @param deliveryAttempt the certification index of the delivered snapshot, or 0 if none or uncertified
 * @param pending a description of the decision that the campaign waits for, or {@code null} if none
 * @param choices the answers that the user may give now
 * @param authorityWarnings the warnings of a pending authority decision, empty if none is asked now
 * @param cells the four outputs of each cell
 * @param allocation the adopted outcomes of each cell in the reported phase; allocation is not a benchmark
 *   weight
 * @param benchmark the benchmark weights, or {@code null} before a contract is confirmed
 * @param trialProfiles the confirmed trial profiles and cells, or {@code null} before a contract is confirmed
 * @param cellTier each category's active development cells and whether development has widened, or {@code null}
 *   before a contract is confirmed
 * @param remaining the minimum remaining trials
 * @param tokens the token statistics
 * @param concurrency the concurrency state
 * @param assumptions the descriptive check by concurrency level
 * @param attempts every certification attempt
 * @param keepOffers each {@code keep improvement} choice with its evidence
 * @param claim the statement that the measurements support, or {@code null} before a contract is confirmed
 * @param limitations what the claim does not cover
 * @param conditions the measurement conditions that the claim holds under
 */
public record StatusReport(ReportStatus status, String campaignId, Lifecycle lifecycle, Phase phase, int attempt,
	int candidate, String snapshot, String outcome, String outcomeDetail,
	String delivery, int deliveryAttempt, String pending,
	List<ChoiceItem> choices, List<AuthorityWarning> authorityWarnings, List<CategoryReport> cells,
	Map<String, Integer> allocation,
	Benchmark benchmark, TrialProfiles trialProfiles, CellTierReport cellTier, RemainingWork remaining,
	TokenStatistics tokens,
	Concurrency concurrency,
	AssumptionReport assumptions, List<AttemptSummary> attempts, List<KeepOffer> keepOffers, String claim,
	List<String> limitations, Map<String, String> conditions)
{
	/**
	 * The concurrency state that limits how many trials run at once.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param window the active candidate's concurrency window
	 * @param ceiling the campaign's ceiling
	 * @param slowStartSize the slow-start limit
	 * @param holdUntil the instant before which no trial is admitted, in ISO-8601, or {@code null} if none
	 * @param windowCap the window that the last rate-limit response set, or 0 if none
	 * @param backoffs the consecutive rate-limit backoffs
	 */
	public record Concurrency(int window, int ceiling, int slowStartSize, String holdUntil, int windowCap,
		int backoffs)
	{
		/**
		 * Creates the state.
		 *
		 * @param window the active candidate's concurrency window
		 * @param ceiling the campaign's ceiling
		 * @param slowStartSize the slow-start limit
		 * @param holdUntil the instant before which no trial is admitted, or {@code null} if none
		 * @param windowCap the window that the last rate-limit response set, or 0 if none
		 * @param backoffs the consecutive rate-limit backoffs
		 * @throws IllegalArgumentException if {@code window}, {@code ceiling} or {@code slowStartSize} are not
		 *   positive, or {@code windowCap} or {@code backoffs} are negative
		 */
		public Concurrency
		{
			requireThat(window, "window").isPositive();
			requireThat(ceiling, "ceiling").isPositive();
			requireThat(slowStartSize, "slowStartSize").isPositive();
			requireThat(windowCap, "windowCap").isNotNegative();
			requireThat(backoffs, "backoffs").isNotNegative();
		}
	}

	/**
	 * Creates a report.
	 *
	 * @param status what the host does next, or how the campaign ended
	 * @param campaignId the campaign's identifier
	 * @param lifecycle the campaign's lifecycle state
	 * @param phase the phase that the category reports describe
	 * @param attempt the certification index that the report describes, or 0 if none
	 * @param candidate the number of the candidate that the report describes
	 * @param snapshot the hexadecimal identity of that candidate's snapshot
	 * @param outcome the label of the campaign's outcome, or {@code null} if it has none
	 * @param outcomeDetail the precise infrastructure failure when the outcome is {@code infrastructure failure},
	 *   otherwise {@code null}
	 * @param delivery the label of the result that the campaign decided to deliver, or {@code null} if none
	 * @param deliveryAttempt the certification index of the delivered snapshot, or 0 if none or uncertified
	 * @param pending a description of the decision that the campaign waits for, or {@code null} if none
	 * @param choices the answers that the user may give now
	 * @param authorityWarnings the warnings of a pending authority decision, empty if none is asked now
	 * @param cells the four outputs of each cell
	 * @param allocation the adopted outcomes of each cell in the reported phase
	 * @param benchmark the benchmark weights, or {@code null} before a contract is confirmed
	 * @param trialProfiles the confirmed trial profiles and cells, or {@code null} before a contract is confirmed
	 * @param cellTier each category's active development cells and whether development has widened, or
	 *   {@code null} before a contract is confirmed
	 * @param remaining the minimum remaining trials
	 * @param tokens the token statistics
	 * @param concurrency the concurrency state
	 * @param assumptions the descriptive check by concurrency level
	 * @param attempts every certification attempt
	 * @param keepOffers each {@code keep improvement} choice with its evidence
	 * @param claim the statement that the measurements support, or {@code null} before a contract is confirmed
	 * @param limitations what the claim does not cover
	 * @param conditions the measurement conditions that the claim holds under
	 * @throws NullPointerException if any argument other than {@code outcome}, {@code outcomeDetail},
	 *   {@code delivery}, {@code pending},
	 *   {@code benchmark}, {@code trialProfiles}, {@code cellTier} or {@code claim} is null
	 * @throws IllegalArgumentException if {@code attempt}, {@code candidate} or {@code deliveryAttempt} are negative
	 */
	public StatusReport
	{
		requireThat(status, "status").isNotNull();
		requireThat(campaignId, "campaignId").isNotBlank();
		requireThat(lifecycle, "lifecycle").isNotNull();
		requireThat(phase, "phase").isNotNull();
		requireThat(attempt, "attempt").isNotNegative();
		requireThat(candidate, "candidate").isNotNegative();
		requireThat(snapshot, "snapshot").isNotBlank();
		requireThat(deliveryAttempt, "deliveryAttempt").isNotNegative();
		requireThat(choices, "choices").isNotNull();
		requireThat(authorityWarnings, "authorityWarnings").isNotNull();
		requireThat(cells, "cells").isNotNull();
		requireThat(allocation, "allocation").isNotNull();
		requireThat(remaining, "remaining").isNotNull();
		requireThat(tokens, "tokens").isNotNull();
		requireThat(concurrency, "concurrency").isNotNull();
		requireThat(assumptions, "assumptions").isNotNull();
		requireThat(attempts, "attempts").isNotNull();
		requireThat(keepOffers, "keepOffers").isNotNull();
		requireThat(limitations, "limitations").isNotNull();
		requireThat(conditions, "conditions").isNotNull();
		choices = List.copyOf(choices);
		authorityWarnings = List.copyOf(authorityWarnings);
		cells = List.copyOf(cells);
		allocation = Map.copyOf(allocation);
		attempts = List.copyOf(attempts);
		keepOffers = List.copyOf(keepOffers);
		limitations = List.copyOf(limitations);
		conditions = Map.copyOf(conditions);
	}

	/**
	 * The text of an outcome that is not a compliance outcome: work stopped and can resume.
	 */
	static final String INTERRUPTED = "interrupted";
	/**
	 * The text of an outcome that is not a compliance outcome: a precise failure stopped work.
	 */
	static final String INFRASTRUCTURE_FAILURE = "infrastructure failure";
	/**
	 * The limitations that every result carries.
	 */
	private static final List<String> FIXED_LIMITATIONS = List.of(
		"A pass means the frozen judge accepted the response. It is not actual compliance, and the judge is not " +
			"calibrated against human review.",
		"The benchmark covers the contract's tests. It does not cover usage frequencies or scenarios that the " +
			"contract omits.",
		"Provider prompt caching is assumed to behave as it did during the measurements.",
		"Conditions that CAT cannot observe may differ, and trials cannot be replayed deterministically.");
	/**
	 * The limitation that applies when staging replaced symbolic links.
	 */
	private static final String SYMBOLIC_LINK_LIMITATION = "Staging replaced symbolic links with copies of their " +
		"targets, so trials did not see the links.";

	/**
	 * Returns every outcome label that a campaign can report: the labels of {@code design.md} section 1.
	 * <p>
	 * The {@code not certified} label of an uncertified delivery is not an outcome of the table, so it is absent.
	 *
	 * @return the labels, in the order of the design
	 */
	public static List<String> outcomeLabels()
	{
		List<String> labels = new ArrayList<>();
		for (Outcome outcome : Outcome.values())
		{
			if (outcome != Outcome.NOT_CERTIFIED)
				labels.add(outcome.label());
		}
		labels.add(INTERRUPTED);
		labels.add(INFRASTRUCTURE_FAILURE);
		return labels;
	}

	/**
	 * Builds the report of a campaign.
	 *
	 * @param campaign the campaign
	 * @param evidence what the campaign does not persist
	 * @return the report
	 * @throws NullPointerException if any argument is null
	 */
	public static StatusReport of(Campaign campaign, ReportEvidence evidence)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(evidence, "evidence").isNotNull();
		CertificationAttempt attempt = Phase.reportedAttempt(campaign).orElse(null);
		Phase phase = Phase.of(campaign);
		Candidate candidate = campaign.development().working();
		int attemptIndex = 0;
		if (attempt != null)
		{
			candidate = attempt.candidate();
			attemptIndex = attempt.index();
		}
		AssumptionReport assumptions = AssumptionReport.of(evidence);
		String outcome = null;
		String outcomeDetail = null;
		if (campaign.outcome() != null)
			outcome = campaign.outcome().label();
		else if (assumptions.dependenceDetected())
			outcome = Outcome.INVALID_STATISTICAL_ASSUMPTIONS.label();
		else if (evidence.infrastructureFailure() != null)
		{
			outcome = INFRASTRUCTURE_FAILURE;
			outcomeDetail = evidence.infrastructureFailure();
		}
		else if (evidence.interrupted())
			outcome = INTERRUPTED;
		String delivery = null;
		int deliveryAttempt = 0;
		if (campaign.delivery() != null)
		{
			delivery = campaign.delivery().label().label();
			deliveryAttempt = campaign.delivery().attempt();
		}

		List<CategoryReport> cells = List.of();
		Map<String, Integer> allocation = new LinkedHashMap<>();
		Benchmark benchmark = null;
		TrialProfiles trialProfiles = null;
		String claim = null;
		List<AttemptSummary> attempts = new ArrayList<>();
		List<KeepOffer> keepOffers = new ArrayList<>();
		List<String> limitations = new ArrayList<>(FIXED_LIMITATIONS);
		if (campaign.isConfirmed())
		{
			cells = CategoryReports.of(campaign, attempt);
			for (CategoryReport cell : cells)
				allocation.put(cell.cell(), cell.trials());
			benchmark = benchmark(campaign.contract());
			trialProfiles = campaign.contract().profiles();
			claim = claim(campaign, candidate);
			limitations.addAll(campaign.contract().limitations());
			attempts = attempts(campaign);
			keepOffers = keepOffers(campaign);
		}
		if (evidence.symbolicLinks())
			limitations.add(SYMBOLIC_LINK_LIMITATION);
		Map<String, String> conditions = conditions(campaign.identity());

		return new StatusReport(status(campaign), campaign.header().id(), campaign.lifecycle(),
			phase, attemptIndex,
			candidate.number(), candidate.snapshot().hex(), outcome, outcomeDetail, delivery, deliveryAttempt,
			describe(campaign.pending()), choices(campaign), authorityWarnings(campaign), cells, allocation,
			benchmark, trialProfiles, CellTierReport.of(campaign), RemainingWork.of(campaign),
			TokenStatistics.of(evidence.tokenUsage()),
			concurrency(campaign), assumptions, attempts, keepOffers, claim, limitations, conditions);
	}

	/**
	 * Returns what the host agent does next, or how a campaign that ended finished.
	 * <p>
	 * An ended campaign without a recorded outcome reports its delivery label, or {@code interrupted} if it
	 * delivered nothing.
	 *
	 * @param campaign the campaign
	 * @return the status
	 */
	private static ReportStatus status(Campaign campaign)
	{
		return switch (campaign.lifecycle())
		{
			case FINISHED, CLOSED ->
			{
				if (campaign.outcome() != null)
					yield new ReportStatus.Ended(campaign.outcome());
				if (campaign.delivery() != null)
					yield new ReportStatus.Ended(campaign.delivery().label());
				yield new ReportStatus.Interrupted();
			}
			case CANCELLED -> new ReportStatus.Active(Status.AWAITING_USER);
			case ACTIVE ->
			{
				if (!campaign.isConfirmed() || campaign.pending() != null)
					yield new ReportStatus.Active(Status.AWAITING_USER);
				yield new ReportStatus.Active(Status.RUNNING);
			}
		};
	}

	/**
	 * Describes the decision that a campaign waits for.
	 *
	 * @param pending the decision, or {@code null} if none
	 * @return the description, or {@code null} if there is no decision
	 */
	private static String describe(PendingDecision pending)
	{
		if (pending == null)
			return null;
		return switch (pending)
		{
			case PendingDecision.MeasurementChanged changed ->
			{
				List<String> names = new ArrayList<>();
				for (MeasurementCondition condition : changed.changed())
					names.add(condition.name());
				yield "the measurement conditions changed: " + String.join(", ", names);
			}
			case PendingDecision.UnownedChange change -> "a change that CAT did not make: " + change.description();
			case PendingDecision.TrialFailures _ -> "a trial ran without an outcome and needs a retry";
			case PendingDecision.CalibrationFailure failure -> "calibration failed: " + failure.detail();
			case PendingDecision.CheckpointReached checkpoint ->
				"review checkpoint reached in category " + checkpoint.category() + " after " + checkpoint.outcomes() +
					" outcomes";
			case PendingDecision.ImprovementProven proven ->
				"attempt " + proven.attempt() + " proved an improvement without certifying";
			case PendingDecision.RegressionTradeOff tradeOff ->
				"attempt " + tradeOff.attempt() + " certified but is proven worse in a category";
			case PendingDecision.PlateauReached _ -> "a full development round produced no stronger candidate";
			case PendingDecision.CertificationUnavailable unavailable -> unavailable.outcome().label();
			case PendingDecision.AuthorityWarnings warnings ->
			{
				List<String> edits = new ArrayList<>();
				for (AuthorityWarning warning : warnings.warnings())
					edits.add("requirement " + warning.requirement() + " at " + warning.path());
				yield "an author needs edits outside the edit authority: " + String.join("; ", edits);
			}
		};
	}

	/**
	 * Returns the warnings of the authority decision that a campaign asks for now.
	 *
	 * @param campaign the campaign
	 * @return the warnings, empty if the campaign asks for no authority decision now
	 */
	private static List<AuthorityWarning> authorityWarnings(Campaign campaign)
	{
		if (campaign.lifecycle() == Lifecycle.ACTIVE &&
			campaign.pending() instanceof PendingDecision.AuthorityWarnings warnings)
		{
			return warnings.warnings();
		}
		return List.of();
	}

	/**
	 * Returns the answers that the user may give now.
	 *
	 * @param campaign the campaign
	 * @return the choices
	 */
	private static List<ChoiceItem> choices(Campaign campaign)
	{
		List<ChoiceItem> items = new ArrayList<>();
		for (Choice choice : campaign.choices())
		{
			switch (choice)
			{
				case KEEP_IMPROVEMENT ->
				{
					for (ImprovementOffer offer : KeepImprovement.offers(campaign))
						items.add(new ChoiceItem(choice, offer.attempt(), offer.historical(), 0));
				}
				case MORE_EVIDENCE ->
				{
					int above = 0;
					if (campaign.pending() instanceof PendingDecision.CheckpointReached checkpoint)
						above = checkpoint.outcomes();
					items.add(new ChoiceItem(choice, 0, false, above));
				}
				default -> items.add(new ChoiceItem(choice, 0, false, 0));
			}
		}
		return items;
	}

	/**
	 * Returns the benchmark weights of a contract: each cell weighs its category's tests.
	 *
	 * @param contract the contract
	 * @return the benchmark
	 */
	private static Benchmark benchmark(Contract contract)
	{
		List<Benchmark.CategoryWeights> weights = new ArrayList<>();
		for (Cell cell : contract.cells())
		{
			Category category = contract.categoryOf(cell);
			weights.add(new Benchmark.CategoryWeights(cell.id(), category.tests().size(),
				category.testProbability()));
		}
		return new Benchmark(weights);
	}

	/**
	 * Returns the measurement conditions by name. A condition of one cell is named
	 * {@code CONDITION[cell]}.
	 *
	 * @param identity the measurement identity
	 * @return the value of each recorded condition
	 */
	private static Map<String, String> conditions(MeasurementIdentity identity)
	{
		Map<String, String> conditions = new LinkedHashMap<>();
		for (Map.Entry<MeasurementCondition, String> entry : identity.values().entrySet())
			conditions.put(entry.getKey().name(), entry.getValue());
		for (Map.Entry<String, Map<MeasurementCondition, String>> cell : identity.cells().entrySet())
		{
			for (Map.Entry<MeasurementCondition, String> entry : cell.getValue().entrySet())
				conditions.put(entry.getKey().name() + "[" + cell.getKey() + "]", entry.getValue());
		}
		return conditions;
	}

	/**
	 * Returns the statement that the measurements support.
	 *
	 * @param campaign the campaign, which has a confirmed contract
	 * @param candidate the candidate that the report describes
	 * @return the claim
	 */
	private static String claim(Campaign campaign, Candidate candidate)
	{
		JudgeProfile judge = campaign.contract().judge();
		List<String> values = new ArrayList<>();
		for (Map.Entry<String, String> entry : new TreeMap<>(conditions(campaign.identity())).entrySet())
			values.add(entry.getKey() + "=" + entry.getValue());
		return "The pass rates describe candidate " + candidate.number() + " under the frozen judge (role profile " +
			judge.profile().label() + "; decision rule: " + judge.decisionRule() + ") on the balanced benchmark of " +
			campaign.contract().cells().size() + " cells, whose weights are testing assumptions, " +
			"not usage frequencies, under the measured conditions " + String.join(", ", values) + ".";
	}

	/**
	 * Returns the concurrency state.
	 *
	 * @param campaign the campaign
	 * @return the state
	 */
	private static Concurrency concurrency(Campaign campaign)
	{
		Throttle throttle = campaign.throttle();
		String holdUntil = null;
		if (!throttle.holdUntil().equals(Instant.EPOCH))
			holdUntil = throttle.holdUntil().toString();
		return new Concurrency(ConcurrencyPolicy.window(campaign), campaign.ceiling(), campaign.slowStart().size(),
			holdUntil, throttle.windowCap(), throttle.backoffs());
	}

	/**
	 * Returns every certification attempt with its applicability.
	 *
	 * @param campaign the campaign, which has a confirmed contract
	 * @return the summaries
	 */
	private static List<AttemptSummary> attempts(Campaign campaign)
	{
		List<AttemptSummary> summaries = new ArrayList<>();
		for (CertificationAttempt attempt : campaign.attempts())
		{
			Applicability applicability = Applicability.of(attempt.candidate().snapshot(), attempt.identity(),
				campaign.development().working().snapshot(), campaign.identity());
			List<String> changed = new ArrayList<>();
			if (applicability.promptContentChanged())
				changed.add("PROMPT_CONTENT");
			for (MeasurementCondition condition : applicability.changed())
				changed.add(condition.name());
			String outcome = null;
			if (attempt.outcome() != null)
				outcome = attempt.outcome().label();
			summaries.add(new AttemptSummary(attempt.index(), outcome,
				applicability.status() == Applicability.Status.HISTORICAL, changed));
		}
		return summaries;
	}

	/**
	 * Returns each keep-improvement choice with the evidence that qualifies it.
	 *
	 * @param campaign the campaign, which has a confirmed contract
	 * @return the offers
	 */
	private static List<KeepOffer> keepOffers(Campaign campaign)
	{
		List<KeepOffer> offers = new ArrayList<>();
		for (ImprovementOffer offer : KeepImprovement.offers(campaign))
		{
			CertificationAttempt attempt = campaign.attempt(offer.attempt());
			DecisionBoundaries boundaries = attempt.procedure().boundaries();
			double weakest = 1.0;
			List<KeepOffer.Category> cells = new ArrayList<>();
			for (String category : attempt.procedure().categories())
			{
				CategoryCounts counts = attempt.count(category);
				weakest = Math.min(weakest, ExploratoryBounds.wilson(counts.passes(), counts.outcomes()).lower());
				ComparisonBoundaries comparison = CategoryReports.comparison(attempt, category);
				Comparison frozen = comparison.comparison();
				Interval difference = frozen.difference(
					ConfidenceBounds.lower(counts.passes(), counts.failures(), boundaries.level()),
					ConfidenceBounds.upper(counts.passes(), counts.failures(), boundaries.level()));
				String state = CategoryReports.UNDECIDED;
				if (comparison.isProvenBetter(counts.passes(), counts.failures()))
					state = CategoryReports.PROVEN_BETTER;
				else if (comparison.isProvenWorse(counts.passes(), counts.failures()))
					state = CategoryReports.PROVEN_WORSE;
				String note = null;
				if (!state.equals(CategoryReports.PROVEN_BETTER) && difference.lower() < 0)
				{
					note = "Not proven better: the candidate might be slightly worse, because the lower end of the " +
						"difference interval is below 0.";
				}
				cells.add(new KeepOffer.Category(category, counts.passes(), counts.outcomes(), state,
					frozen.incumbentUpper().doubleValue(), frozen.incumbentLower().doubleValue(), difference, note));
			}
			List<String> differing = new ArrayList<>();
			for (MeasurementCondition condition : offer.differing())
				differing.add(condition.name());
			offers.add(new KeepOffer(offer.attempt(), offer.historical(), differing, weakest, cells));
		}
		return offers;
	}

	/**
	 * Returns the JSON status.
	 *
	 * @param mapper the mapper that creates the nodes
	 * @return the JSON object
	 * @throws NullPointerException if {@code mapper} is null
	 */
	public ObjectNode toJson(JsonMapper mapper)
	{
		requireThat(mapper, "mapper").isNotNull();
		return ReportJson.of(this, mapper);
	}

	/**
	 * Returns the human summary.
	 *
	 * @return the summary text
	 */
	public String summary()
	{
		return ReportSummary.of(this);
	}
}

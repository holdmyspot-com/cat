package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Pairing.UnpairedTrial;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.statistics.ConfidenceBounds;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.ExploratoryBounds;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Plans which trials may start next from the persisted campaign state. An admission either reruns an assigned
 * trial or assigns a new one. A cell tests one prompt test with one model and reasoning effort.
 */
public final class Scheduler
{
	/**
	 * Excludes a cell from the next development assignment when it leads the least-sampled eligible cell by this
	 * many representative assignments.
	 */
	public static final int COVERAGE_LEAD = 50;

	/**
	 * Prevents instantiation.
	 */
	private Scheduler()
	{
	}

	/**
	 * Plans admissions without changing the campaign: reruns of assigned trials, then diagnostic and promotion runs,
	 * then the active candidate's formal, reproduction or development trials within its concurrency window. While an
	 * ended attempt's formal failure groups are reproducing, their reproduction trials run instead of development
	 * trials. Outside certification, the incumbent's baseline trials use any remaining capacity.
	 * <p>
	 * Each new assignment carries its {@link Allocation}: the allocation rule that chose it, the campaign's
	 * {@link Campaign#evidenceRevision() evidence revision}, and {@code now} as its assignment time.
	 *
	 * @param campaign the campaign
	 * @param now the current time, which becomes the assignment time of each new assignment
	 * @param freeCapacity the maximum number of nested sessions that may start now; a nonpositive value admits none
	 * @return the admissions, in priority order; empty if the campaign is not active, waits for a decision, a test
	 *   revision or a confirmation, or is throttled
	 * @throws NullPointerException if {@code campaign} or {@code now} are null
	 */
	public static List<Admission> plan(Campaign campaign, Instant now, int freeCapacity)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(now, "now").isNotNull();
		List<Admission> plan = new ArrayList<>();
		if (freeCapacity <= 0 || campaign.lifecycle() != Lifecycle.ACTIVE || !campaign.isConfirmed() ||
			campaign.pending() != null || campaign.revisionRequested() || campaign.awaitsTestRevision() ||
			!campaign.throttle().allowsAdmission(now))
		{
			return plan;
		}
		for (LogicalTrial trial : campaign.trials())
		{
			if (trial.state() == TrialState.ASSIGNED)
				plan.add(new Admission.Rerun(trial.key()));
		}
		Allocations allocations = new Allocations(campaign.evidenceRevision(), now);
		planTargeted(campaign, plan, allocations);
		Optional<CertificationAttempt> running = campaign.runningAttempt();
		if (running.isPresent())
			planFormal(campaign, running.get(), plan, freeCapacity, allocations);
		else
		{
			Optional<CertificationAttempt> reproducing = campaign.reproducing();
			if (reproducing.isPresent())
				planReproduction(campaign, reproducing.get(), plan, freeCapacity, allocations);
			else if (!campaign.development().isPaused())
				planDevelopment(campaign, plan, freeCapacity, allocations);
			planBaseline(campaign, plan, freeCapacity, allocations);
		}
		if (plan.size() > freeCapacity)
			return plan.subList(0, freeCapacity);
		return plan;
	}

	/**
	 * Plans the diagnostic rerun that is due and the unassigned promotion runs.
	 *
	 * @param campaign the campaign
	 * @param plan the admissions planned so far
	 * @param allocations the allocation decisions of this plan
	 */
	private static void planTargeted(Campaign campaign, List<Admission> plan, Allocations allocations)
	{
		Development development = campaign.development();
		Sha256 working = development.working().snapshot();
		Diagnosis diagnosis = development.diagnosis();
		if (diagnosis != null && !diagnosis.pending().isEmpty() && diagnosis.rerun() == null)
		{
			DrawnTestCase testCase = diagnosis.pending().getFirst();
			plan.add(new Admission.Assign(Purpose.DIAGNOSTIC, development.series(), working, testCase.cellId(),
				testCase, allocations.of(AllocationRule.TARGETED)));
		}
		Round round = development.round();
		if (round != null && round.weak() && development.promptAuthor() == null)
		{
			List<PromptAuthorAttempt> pending = round.validationAttempts().stream().filter(attempt ->
				Promotion.decide(attempt, campaign) == Promotion.Decision.PENDING).toList();
			for (PromptAuthorAttempt attempt : round.validationAttempts())
			{
				if (pending.contains(attempt))
					planPromotion(attempt, working, plan, allocations, _ -> true);
				else
					planPromotion(attempt, working, plan, allocations, run -> isSharedReference(run, pending));
			}
			return;
		}
		PromptAuthorAttempt promptAuthor = development.promptAuthor();
		if (promptAuthor != null && Promotion.decide(promptAuthor, campaign) == Promotion.Decision.PENDING)
			planPromotion(promptAuthor, working, plan, allocations, _ -> true);
	}

	/**
	 * Indicates whether a decided contender's run is a working reference that an undecided contender still needs.
	 *
	 * @param run the decided contender's run
	 * @param pending the undecided contenders
	 * @return {@code true} if the run measures the working candidate on a test case of another contender's regression
	 *   run
	 */
	private static boolean isSharedReference(PromotionRun run, List<PromptAuthorAttempt> pending)
	{
		if (run.role() != PromotionRun.Role.WORKING_REFERENCE)
			return false;
		for (PromptAuthorAttempt attempt : pending)
		{
			for (PromotionRun other : attempt.runs())
			{
				if (other.role() == PromotionRun.Role.REGRESSION &&
					Promotion.isSameTestCase(other.testCase(), run.testCase()))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Plans an attempt's unassigned runs that a filter selects. A rejected attempt plans no run of its own, and plans
	 * only the working candidate's reference runs that an undecided contender shares.
	 *
	 * @param promptAuthor the attempt
	 * @param working the working snapshot
	 * @param plan the planned admissions
	 * @param allocations the allocation decisions
	 * @param due selects the runs to plan
	 */
	private static void planPromotion(PromptAuthorAttempt promptAuthor, Sha256 working, List<Admission> plan,
		Allocations allocations, Predicate<PromotionRun> due)
	{
		for (PromotionRun run : promptAuthor.runs())
		{
			if (run.trial() != null || run.verdict() != null || !due.test(run))
				continue;
			plan.add(new Admission.Assign(Purpose.PROMOTION, promptAuthor.number(), Promotion.snapshot(promptAuthor,
				run, working), run.testCase().cellId(), run.testCase(), allocations.of(AllocationRule.TARGETED)));
		}
	}

	/**
	 * Counts unadopted trials per cell for one purpose and series.
	 *
	 * @param campaign the campaign
	 * @param purpose the trial purpose to count
	 * @param series the development series or baseline, certification or prompt-author-attempt number to count
	 * @return the number of trials per cell
	 */
	private static Map<String, Integer> countInFlight(Campaign campaign, Purpose purpose, int series)
	{
		Map<String, Integer> inFlight = new HashMap<>();
		for (LogicalTrial trial : campaign.trials())
		{
			TrialKey key = trial.key();
			if (key.purpose() == purpose && key.series() == series)
				inFlight.merge(key.cell(), 1, Integer::sum);
		}
		return inFlight;
	}

	/**
	 * Returns the sum of a map's values.
	 *
	 * @param counts the map
	 * @return the sum
	 */
	private static int total(Map<String, Integer> counts)
	{
		int total = 0;
		for (int count : counts.values())
			total += count;
		return total;
	}

	/**
	 * Plans the running attempt's formal trials in its {@link CellTier open cells}. Each test offers the open cell
	 * with the fewest adopted outcomes, ties to fewer unadopted trials and then contract order. Among the offered
	 * cells, an untried cell first, then the cell whose confidence lower bound at the attempt's significance level
	 * is lowest, ties to fewer adopted outcomes and then contract order. A cell whose minimum-pass-rate part is
	 * decided, or one whose unadopted trials reach its remaining passes to certification, offers nothing.
	 * <p>
	 * The formal trials of the minimum-pass-rate part come first. Then each undecided open cell whose paired
	 * comparisons started receives, in turn and in contract order, the control and incumbent trials of its waiting
	 * candidate formal trials, and then the further candidate formal trials that its slowest comparison needs. The
	 * formal, control and incumbent trials in flight share the attempt's window.
	 *
	 * @param campaign the campaign
	 * @param attempt the running attempt
	 * @param plan the admissions planned so far
	 * @param freeCapacity the number of sessions that may start now
	 * @param allocations the allocation decisions of this plan
	 */
	private static void planFormal(Campaign campaign, CertificationAttempt attempt, List<Admission> plan,
		int freeCapacity, Allocations allocations)
	{
		Map<String, Integer> inFlight = countInFlight(campaign, Purpose.FORMAL, attempt.index());
		Map<Reference, Map<String, Integer>> referencesInFlight = new EnumMap<>(Reference.class);
		int slots = ConcurrencyPolicy.window(campaign) - total(inFlight);
		for (Reference reference : Reference.values())
		{
			Map<String, Integer> references = countInFlight(campaign, reference.purpose(), attempt.index());
			referencesInFlight.put(reference, references);
			slots -= total(references);
		}
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		List<Cell> openCells = CellTier.openCells(campaign, attempt);
		while (slots > 0 && plan.size() < freeCapacity)
		{
			Map<String, String> offered = new LinkedHashMap<>();
			for (Cell cell : openCells)
			{
				String id = cell.id();
				CellCounts counts = attempt.count(id);
				int assigned = inFlight.getOrDefault(id, 0);
				if (attempt.pairing(Reference.CONTROL, id).isComparing() ||
					DecisionTable.minimumDecision(boundaries, counts) != DecisionTable.CellDecision.UNDECIDED ||
					ConcurrencyPolicy.cellLimit(boundaries, counts) - assigned <= 0)
				{
					continue;
				}
				offered.merge(cell.testId(), id, (current, candidate) ->
				{
					if (hasFewerFormalOutcomes(attempt, inFlight, candidate, current))
						return candidate;
					return current;
				});
			}
			String best = null;
			for (String cell : offered.values())
			{
				if (best == null || hasFormalPriority(attempt, inFlight, cell, best))
					best = cell;
			}
			if (best == null)
				break;
			plan.add(new Admission.Assign(Purpose.FORMAL, attempt.index(), attempt.candidate().snapshot(), best,
				null, allocations.of(AllocationRule.FORMAL)));
			inFlight.merge(best, 1, Integer::sum);
			--slots;
		}
		planPaired(campaign, attempt, openCells, plan, Math.min(slots, freeCapacity - plan.size()), inFlight,
			referencesInFlight, allocations);
	}

	/**
	 * Plans the control and incumbent trials of the undecided open cells whose paired comparisons started, then the
	 * further candidate formal trials that their comparisons need, one trial per cell in turn and in contract order.
	 * <p>
	 * A cell's trials of each reference kind pair, in assignment order, with its candidate formal trials that wait for
	 * one of that kind, and its in-flight trials of each kind never exceed the pairs that its slowest undecided
	 * comparison with that kind still needs if every pair favours the candidate. When both kinds can run a trial, the
	 * kind with fewer trials in flight runs first, ties to the control. Further candidate formal trials run only when
	 * the waiting and in-flight candidate formal trials cannot complete the pairs of some kind, up to the cell's cap.
	 *
	 * @param campaign the campaign
	 * @param attempt the running attempt
	 * @param openCells the open cells, in contract order
	 * @param plan the admissions planned so far
	 * @param slots the number of trials that may be planned
	 * @param inFlight the unadopted candidate formal trials per cell, updated with the planned ones
	 * @param referencesInFlight the unadopted control and incumbent trials per cell, updated with the planned ones
	 * @param allocations the allocation decisions of this plan
	 */
	private static void planPaired(Campaign campaign, CertificationAttempt attempt, List<Cell> openCells,
		List<Admission> plan, int slots, Map<String, Integer> inFlight,
		Map<Reference, Map<String, Integer>> referencesInFlight, Allocations allocations)
	{
		List<String> comparing = new ArrayList<>();
		for (Cell cell : openCells)
		{
			String id = cell.id();
			if (attempt.pairing(Reference.CONTROL, id).isComparing() &&
				DecisionTable.cellDecision(attempt, id) == DecisionTable.CellDecision.UNDECIDED)
			{
				comparing.add(id);
			}
		}
		Map<String, Map<Reference, Integer>> needed = new HashMap<>();
		for (String id : comparing)
		{
			Map<Reference, Integer> cellNeeds = new EnumMap<>(Reference.class);
			for (Reference reference : Reference.values())
				cellNeeds.put(reference, pairsNeeded(attempt, id, reference));
			needed.put(id, cellNeeds);
		}
		boolean planned = true;
		while (planned && slots > 0)
		{
			planned = false;
			for (String id : comparing)
			{
				if (slots <= 0)
					break;
				Admission admission = nextPairedAdmission(campaign, attempt, inFlight, id, needed.get(id),
					referencesInFlight, allocations);
				if (admission == null)
					continue;
				plan.add(admission);
				--slots;
				planned = true;
			}
		}
	}

	/**
	 * Returns the pairs that a cell's slowest undecided comparison with one kind of reference trial still needs if
	 * every further pair favours the candidate.
	 *
	 * @param attempt the running attempt
	 * @param cell the identifier of a cell whose paired comparisons are undecided
	 * @param reference the kind of reference trial
	 * @return the pairs, at most the pairs that the cell's cap still allows and at least 1 if some comparison with
	 *   that kind is undecided; 0 if every comparison with that kind passed or the cell has none
	 */
	private static int pairsNeeded(CertificationAttempt attempt, String cell, Reference reference)
	{
		Pairing pairing = attempt.pairing(reference, cell);
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		int needed = DecisionTable.pairsToPass(pairing, boundaries);
		return Math.min(needed, Math.max(1, DecisionTable.remainingPairs(pairing, boundaries)));
	}

	/**
	 * Returns a cell's next control or incumbent trial, or else its next further candidate formal trial, and counts it
	 * in flight.
	 *
	 * @param campaign the campaign
	 * @param attempt the running attempt
	 * @param inFlight the unadopted candidate formal trials per cell
	 * @param cell the identifier of a cell whose paired comparisons are undecided
	 * @param needed the pairs that the cell's slowest undecided comparison with each kind of reference trial still
	 *   needs
	 * @param referencesInFlight the unadopted control and incumbent trials per cell
	 * @param allocations the allocation decisions of this plan
	 * @return the admission, or {@code null} if the cell needs no further trial now
	 */
	private static Admission nextPairedAdmission(Campaign campaign, CertificationAttempt attempt,
		Map<String, Integer> inFlight, String cell, Map<Reference, Integer> needed,
		Map<Reference, Map<String, Integer>> referencesInFlight, Allocations allocations)
	{
		Reference next = null;
		int nextInFlight = 0;
		int furtherFormal = 0;
		for (Reference reference : Reference.values())
		{
			List<UnpairedTrial> unpaired = attempt.pairing(reference, cell).unpaired();
			int kindNeeded = needed.get(reference);
			int references = referencesInFlight.get(reference).getOrDefault(cell, 0);
			if (references < Math.min(unpaired.size(), kindNeeded) && (next == null || references < nextInFlight))
			{
				next = reference;
				nextInFlight = references;
			}
			furtherFormal = Math.max(furtherFormal, kindNeeded - unpaired.size());
		}
		if (next != null)
		{
			referencesInFlight.get(next).merge(cell, 1, Integer::sum);
			Sha256 snapshot = switch (next)
			{
				case CONTROL -> attempt.control();
				case INCUMBENT -> campaign.incumbent().snapshot();
			};
			return new Admission.Assign(next.purpose(), attempt.index(), snapshot, cell,
				attempt.pairing(next, cell).unpaired().get(nextInFlight).testCase(),
				allocations.of(AllocationRule.PAIRED));
		}
		int formal = inFlight.getOrDefault(cell, 0);
		int capRemaining = attempt.procedure().boundaries().cap() - attempt.count(cell).outcomes() - formal;
		if (Math.min(furtherFormal - formal, capRemaining) <= 0)
			return null;
		inFlight.merge(cell, 1, Integer::sum);
		return new Admission.Assign(Purpose.FORMAL, attempt.index(), attempt.candidate().snapshot(), cell, null,
			allocations.of(AllocationRule.PAIRED));
	}

	/**
	 * Indicates whether a cell has fewer formal outcomes than another of its test: fewer adopted outcomes, then
	 * fewer unadopted trials. A tie preserves the earlier cell in contract order.
	 *
	 * @param attempt the running attempt
	 * @param inFlight the unadopted trials per cell
	 * @param cell the identifier of a cell that comes after {@code other} in contract order
	 * @param other the identifier of the test's first cell so far
	 * @return {@code true} if {@code cell} comes first
	 */
	private static boolean hasFewerFormalOutcomes(CertificationAttempt attempt, Map<String, Integer> inFlight,
		String cell, String other)
	{
		int byOutcomes = Integer.compare(attempt.count(cell).outcomes(), attempt.count(other).outcomes());
		if (byOutcomes != 0)
			return byOutcomes < 0;
		return inFlight.getOrDefault(cell, 0) < inFlight.getOrDefault(other, 0);
	}

	/**
	 * Indicates whether a cell has priority over another for a formal trial: an untried cell first, then the lower
	 * confidence bound, then fewer adopted outcomes. A tie preserves the earlier cell in contract order.
	 *
	 * @param attempt the running attempt
	 * @param inFlight the unadopted trials per cell
	 * @param cell the identifier of a cell that comes after {@code other} in contract order
	 * @param other the identifier of the highest-priority cell so far
	 * @return {@code true} if {@code cell} comes first
	 */
	private static boolean hasFormalPriority(CertificationAttempt attempt, Map<String, Integer> inFlight,
		String cell, String other)
	{
		CellCounts counts = attempt.count(cell);
		CellCounts otherCounts = attempt.count(other);
		boolean untried = counts.outcomes() == 0 && inFlight.getOrDefault(cell, 0) == 0;
		boolean otherUntried = otherCounts.outcomes() == 0 && inFlight.getOrDefault(other, 0) == 0;
		if (untried != otherUntried)
			return untried;
		SignificanceLevel level = attempt.procedure().boundaries().level();
		int byBound = Double.compare(ConfidenceBounds.lower(counts.passes(), counts.failures(), level).value(),
			ConfidenceBounds.lower(otherCounts.passes(), otherCounts.failures(), level).value());
		if (byBound != 0)
			return byBound < 0;
		return counts.outcomes() < otherCounts.outcomes();
	}

	/**
	 * Plans the reproduction trials of an ended attempt's failure groups, each in its current round's cell, one group
	 * at a time in turn, until each round's adopted and unadopted trials reach {@value FailureGroup#TRIALS}. A group
	 * whose round awaits values offers nothing.
	 *
	 * @param campaign the campaign
	 * @param attempt the reproducing attempt
	 * @param plan the admissions planned so far
	 * @param freeCapacity the number of sessions that may start now
	 * @param allocations the allocation decisions of this plan
	 */
	private static void planReproduction(Campaign campaign, CertificationAttempt attempt, List<Admission> plan,
		int freeCapacity, Allocations allocations)
	{
		List<FailureGroup> groups = attempt.groups();
		int[] remaining = new int[groups.size()];
		int inFlight = 0;
		for (int i = 0; i < groups.size(); ++i)
		{
			FailureGroup group = groups.get(i);
			if (group.status() == FailureGroup.Status.REPRODUCING && !group.round().awaitsValues())
				remaining[i] = FailureGroup.TRIALS - group.round().trials();
		}
		for (LogicalTrial trial : campaign.trials())
		{
			TrialKey key = trial.key();
			if (key.purpose() != Purpose.REPRODUCTION)
				continue;
			++inFlight;
			int index = key.series() - 1;
			if (index >= 0 && index < groups.size() && groups.get(index).round().cellId().equals(key.cell()))
				--remaining[index];
		}
		int slots = ConcurrencyPolicy.window(campaign) - inFlight;
		Sha256 working = campaign.development().working().snapshot();
		boolean planned = true;
		while (planned && slots > 0 && plan.size() < freeCapacity)
		{
			planned = false;
			for (int i = 0; i < groups.size() && slots > 0 && plan.size() < freeCapacity; ++i)
			{
				if (remaining[i] <= 0)
					continue;
				plan.add(new Admission.Assign(Purpose.REPRODUCTION, i + 1, working, groups.get(i).round().cellId(),
					null, allocations.of(AllocationRule.TARGETED)));
				--remaining[i];
				--slots;
				planned = true;
			}
		}
	}

	/**
	 * Plans the working candidate's representative development trials in the {@link CellTier development cells}.
	 * <p>
	 * Development cells that meet the commit gate or are at their review point are ineligible. Exclude an eligible cell
	 * when it leads the least-sampled eligible cell by at least {@value #COVERAGE_LEAD} assignments. Among the remaining
	 * cells, the lowest 95% Wilson lower bound comes first, ties to fewer outcomes, then higher test risk, then contract
	 * order. Record {@link AllocationRule#LOWEST_EXPLORATORY_BOUND} when the chosen cell also wins that ordering across
	 * all eligible cells before the lead exclusion; otherwise record {@link AllocationRule#COVERAGE_FLOOR}.
	 *
	 * @param campaign the campaign
	 * @param plan the admissions planned so far
	 * @param freeCapacity the number of sessions that may start now
	 * @param allocations the allocation decisions of this plan
	 */
	private static void planDevelopment(Campaign campaign, List<Admission> plan, int freeCapacity,
		Allocations allocations)
	{
		Development development = campaign.development();
		Contract contract = campaign.contract();
		Map<String, Integer> inFlight = countInFlight(campaign, Purpose.DEVELOPMENT, development.series());
		int slots = ConcurrencyPolicy.window(campaign) - total(inFlight);
		List<Cell> developmentCells = CellTier.developmentCells(campaign);
		while (slots > 0 && plan.size() < freeCapacity)
		{
			List<Cell> eligible = new ArrayList<>();
			for (Cell cell : developmentCells)
			{
				CellCounts counts = development.count(cell.id());
				if (!CommitGate.isMetInCell(counts, contract.minimumPassRate()) &&
					counts.outcomes() < development.reviewPoint(cell.id()))
				{
					eligible.add(cell);
				}
			}
			if (eligible.isEmpty())
				return;
			int leastAssigned = Integer.MAX_VALUE;
			for (Cell cell : eligible)
				leastAssigned = Math.min(leastAssigned, assigned(development, inFlight, cell.id()));
			Cell best = null;
			Cell weakest = null;
			for (Cell cell : eligible)
			{
				if (weakest == null || isWeaker(contract, development, cell, weakest))
					weakest = cell;
				if (assigned(development, inFlight, cell.id()) >= leastAssigned + COVERAGE_LEAD)
					continue;
				if (best == null || isWeaker(contract, development, cell, best))
					best = cell;
			}
			AllocationRule rule;
			if (best.equals(weakest))
				rule = AllocationRule.LOWEST_EXPLORATORY_BOUND;
			else
				rule = AllocationRule.COVERAGE_FLOOR;
			plan.add(new Admission.Assign(Purpose.DEVELOPMENT, development.series(),
				development.working().snapshot(), best.id(), null, allocations.of(rule)));
			inFlight.merge(best.id(), 1, Integer::sum);
			--slots;
		}
	}

	/**
	 * Returns a cell's representative development assignments.
	 *
	 * @param development the development state
	 * @param inFlight the unadopted trials per cell
	 * @param cell the cell's identifier
	 * @return the adopted outcomes plus the unadopted trials
	 */
	private static int assigned(Development development, Map<String, Integer> inFlight, String cell)
	{
		return development.count(cell).outcomes() + inFlight.getOrDefault(cell, 0);
	}

	/**
	 * Indicates whether a cell has a lower 95% Wilson bound than another, breaking ties by fewer adopted outcomes
	 * and then higher test risk. A tie preserves the earlier cell in contract order.
	 *
	 * @param contract the campaign's contract
	 * @param development the development state
	 * @param cell a cell that comes after {@code other} in cell order
	 * @param other the best cell so far
	 * @return {@code true} if {@code cell} comes first
	 */
	private static boolean isWeaker(Contract contract, Development development, Cell cell, Cell other)
	{
		CellCounts counts = development.count(cell.id());
		CellCounts otherCounts = development.count(other.id());
		int byBound = Double.compare(ExploratoryBounds.wilson(counts.passes(), counts.outcomes()).lower(),
			ExploratoryBounds.wilson(otherCounts.passes(), otherCounts.outcomes()).lower());
		if (byBound != 0)
			return byBound < 0;
		if (counts.outcomes() != otherCounts.outcomes())
			return counts.outcomes() < otherCounts.outcomes();
		return contract.testOf(cell).riskRank().compareTo(contract.testOf(other).riskRank()) < 0;
	}

	/**
	 * Plans the incumbent's baseline trials in the certification scope's cells on the remaining capacity, one cell at a
	 * time in turn, until each cell's unadopted and adopted trials reach its target.
	 *
	 * @param campaign the campaign
	 * @param plan the admissions planned so far
	 * @param freeCapacity the number of sessions that may start now
	 * @param allocations the allocation decisions of this plan
	 */
	private static void planBaseline(Campaign campaign, List<Admission> plan, int freeCapacity,
		Allocations allocations)
	{
		if (campaign.development().working().isIncumbent())
			return;
		IncumbentBaseline baseline = campaign.baseline();
		List<String> cells = CellTier.scopeCellIds(campaign.contract());
		Map<String, Integer> inFlight = countInFlight(campaign, Purpose.BASELINE, baseline.index());
		Map<String, Integer> needed = new HashMap<>();
		for (String cell : cells)
		{
			CellCounts counts = baseline.count(cell);
			int target = Baseline.target(counts, baseline.index(), cells.size(),
				campaign.contract().minimumPassRate());
			needed.put(cell, target - counts.outcomes() - inFlight.getOrDefault(cell, 0));
		}
		boolean added = true;
		while (added)
		{
			added = false;
			for (String cell : cells)
			{
				int need = needed.get(cell);
				if (need <= 0 || plan.size() >= freeCapacity)
					continue;
				plan.add(new Admission.Assign(Purpose.BASELINE, baseline.index(), campaign.incumbent().snapshot(),
					cell, null, allocations.of(AllocationRule.BASELINE)));
				needed.put(cell, need - 1);
				added = true;
			}
		}
	}

	/**
	 * The evidence revision and time that every allocation decision of one plan shares.
	 *
	 * @param evidenceRevision the campaign's evidence revision
	 * @param now the assignment time
	 */
	private record Allocations(long evidenceRevision, Instant now)
	{
		/**
		 * Returns the allocation decision of a rule.
		 *
		 * @param rule the allocation rule that chose a trial
		 * @return the allocation decision
		 */
		Allocation of(AllocationRule rule)
		{
			return new Allocation(rule, evidenceRevision, now);
		}
	}
}

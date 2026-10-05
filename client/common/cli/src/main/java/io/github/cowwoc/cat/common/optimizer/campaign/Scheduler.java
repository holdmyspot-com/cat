package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.statistics.ConfidenceBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plans which trials may start next from the persisted campaign state. An admission either reruns an assigned
 * trial or assigns a new one. A cell tests one category with one model and reasoning effort.
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
	 * @return the admissions, in priority order; empty if the campaign is not active, waits for a decision or a
	 *   confirmation, or is throttled
	 * @throws NullPointerException if {@code campaign} or {@code now} are null
	 */
	public static List<Admission> plan(Campaign campaign, Instant now, int freeCapacity)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(now, "now").isNotNull();
		List<Admission> plan = new ArrayList<>();
		if (freeCapacity <= 0 || campaign.lifecycle() != Lifecycle.ACTIVE || !campaign.isConfirmed() ||
			campaign.pending() != null || campaign.revisionRequested() || !campaign.throttle().allowsAdmission(now))
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
			TrialInstance instance = diagnosis.pending().getFirst();
			plan.add(new Admission.Assign(Purpose.DIAGNOSTIC, development.series(), working, instance.cellId(),
				instance, allocations.of(AllocationRule.TARGETED)));
		}
		Round round = development.round();
		if (round != null && round.weak() && development.author() == null)
		{
			for (AuthorAttempt attempt : round.validationAttempts())
				planPromotion(attempt, working, plan, allocations);
			return;
		}
		AuthorAttempt author = development.author();
		if (author != null && Promotion.decide(author, campaign.lineage().regressionSet(),
			development.instanceOutcomes()) == Promotion.Decision.PENDING)
			planPromotion(author, working, plan, allocations);
	}

	/**
	 * Plans every unassigned run of an attempt, including runs after an early weak-contender rejection.
	 *
	 * @param author the attempt
	 * @param working the working snapshot
	 * @param plan the planned admissions
	 * @param allocations the allocation decisions
	 */
	private static void planPromotion(AuthorAttempt author, Sha256 working, List<Admission> plan,
		Allocations allocations)
	{
		for (PromotionRun run : author.runs())
		{
			if (run.trial() != null || run.verdict() != null)
				continue;
			plan.add(new Admission.Assign(Purpose.PROMOTION, author.number(), Promotion.snapshot(author, run, working),
				run.instance().cellId(), run.instance(), allocations.of(AllocationRule.TARGETED)));
		}
	}

	/**
	 * Counts unadopted trials per cell for one purpose and series.
	 *
	 * @param campaign the campaign
	 * @param purpose the trial purpose to count
	 * @param series the development series or baseline, certification or author-attempt number to count
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
	 * Plans the running attempt's formal trials in its {@link CellTier open cells}. Each category offers the open cell
	 * with the fewest adopted outcomes, ties to fewer unadopted trials and then contract order. Among the offered
	 * cells, an untried cell first, then the cell whose confidence lower bound at the attempt's significance level
	 * is lowest, ties to fewer adopted outcomes and then contract order. A decided cell, or one whose unadopted trials
	 * reach its remaining passes to certification, offers nothing.
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
		int slots = ConcurrencyPolicy.window(campaign) - total(inFlight);
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
				if (DecisionTable.cellDecision(boundaries, counts) != DecisionTable.CellDecision.UNDECIDED ||
					ConcurrencyPolicy.cellLimit(boundaries, counts) - assigned <= 0)
				{
					continue;
				}
				offered.merge(cell.categoryId(), id, (current, candidate) ->
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
				return;
			plan.add(new Admission.Assign(Purpose.FORMAL, attempt.index(), attempt.candidate().snapshot(), best,
				null, allocations.of(AllocationRule.FORMAL)));
			inFlight.merge(best, 1, Integer::sum);
			--slots;
		}
	}

	/**
	 * Indicates whether a cell has fewer formal outcomes than another of its category: fewer adopted outcomes, then
	 * fewer unadopted trials. A tie preserves the earlier cell in contract order.
	 *
	 * @param attempt the running attempt
	 * @param inFlight the unadopted trials per cell
	 * @param cell the identifier of a cell that comes after {@code other} in contract order
	 * @param other the identifier of the category's first cell so far
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
	 * Development cells that are ready or at their review point are ineligible. Exclude an eligible cell when it leads
	 * the least-sampled eligible cell by at least {@value #COVERAGE_LEAD} assignments. Among the remaining cells, the
	 * lowest 95% Wilson lower bound comes first, ties to fewer outcomes, then higher category risk, then contract order.
	 * Record {@link AllocationRule#LOWEST_EXPLORATORY_BOUND} when the chosen cell also wins that ordering across all
	 * eligible cells before the lead exclusion; otherwise record {@link AllocationRule#COVERAGE_FLOOR}.
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
				if (!ReadinessGate.isReady(counts) && counts.outcomes() < development.reviewPoint(cell.id()))
					eligible.add(cell);
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
	 * and then higher category risk. A tie preserves the earlier cell in contract order.
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
		return contract.categoryOf(cell).riskRank().compareTo(contract.categoryOf(other).riskRank()) < 0;
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
			int target = Baseline.target(counts, baseline.index(), cells.size());
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
					cell, null, allocations.of(AllocationRule.BASELINE_ARM)));
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

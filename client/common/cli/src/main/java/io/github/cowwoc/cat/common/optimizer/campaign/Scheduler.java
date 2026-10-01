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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plans the next admissions from the persisted state, following the allocation order of {@code design.md} section
 * 3.3.
 */
public final class Scheduler
{
	/**
	 * The most representative development assignments by which a category may lead the least-sampled eligible one.
	 */
	public static final int COVERAGE_LEAD = 50;

	/**
	 * Prevents instantiation.
	 */
	private Scheduler()
	{
	}

	/**
	 * Plans admissions: reruns of assigned trials, then targeted work, then the active candidate's development or
	 * formal trials within its concurrency window, then the incumbent's baseline arm on the remaining capacity.
	 * <p>
	 * Each new assignment carries its {@link Allocation}: the allocation rule that chose it, the campaign's
	 * {@link Campaign#evidenceRevision() evidence revision}, and {@code now} as its assignment time.
	 *
	 * @param campaign the campaign
	 * @param now the current time, which becomes the assignment time of each new assignment
	 * @param freeCapacity the number of nested sessions that may start now
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
			if (!campaign.development().isPaused())
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
		AuthorAttempt author = development.author();
		if (author == null)
			return;
		for (PromotionRun run : author.runs())
		{
			if (run.trial() != null)
				continue;
			plan.add(new Admission.Assign(Purpose.PROMOTION, author.number(), Promotion.snapshot(author, run, working),
				run.instance().cellId(), run.instance(), allocations.of(AllocationRule.TARGETED)));
		}
	}

	/**
	 * Returns the number of unadopted trials per category of a group.
	 *
	 * @param campaign the campaign
	 * @param purpose the group's purpose
	 * @param series the group's series
	 * @return the number of trials per category
	 */
	private static Map<String, Integer> inFlight(Campaign campaign, Purpose purpose, int series)
	{
		Map<String, Integer> inFlight = new HashMap<>();
		for (LogicalTrial trial : campaign.trials())
		{
			TrialKey key = trial.key();
			if (key.purpose() == purpose && key.series() == series)
				inFlight.merge(key.category(), 1, Integer::sum);
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
	 * Plans the running attempt's formal trials: one per category first, then the undecided category whose lower
	 * bound at the attempt's level is lowest, ties to fewer outcomes and then category order.
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
		Map<String, Integer> inFlight = inFlight(campaign, Purpose.FORMAL, attempt.index());
		int slots = ConcurrencyPolicy.window(campaign) - total(inFlight);
		DecisionBoundaries boundaries = attempt.procedure().boundaries();
		while (slots > 0 && plan.size() < freeCapacity)
		{
			String best = null;
			for (String category : attempt.procedure().categories())
			{
				CategoryCounts counts = attempt.count(category);
				int assigned = inFlight.getOrDefault(category, 0);
				if (DecisionTable.categoryDecision(boundaries, counts) != DecisionTable.CategoryDecision.UNDECIDED ||
					ConcurrencyPolicy.categoryLimit(boundaries, counts) - assigned <= 0)
				{
					continue;
				}
				if (best == null || isFurtherFromCertifying(attempt, inFlight, category, best))
					best = category;
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
	 * Indicates whether a category should receive the next formal trial before another.
	 *
	 * @param attempt the running attempt
	 * @param inFlight the unadopted trials per category
	 * @param category a category that comes after {@code other} in category order
	 * @param other the best category so far
	 * @return {@code true} if {@code category} comes first
	 */
	private static boolean isFurtherFromCertifying(CertificationAttempt attempt, Map<String, Integer> inFlight,
		String category, String other)
	{
		CategoryCounts counts = attempt.count(category);
		CategoryCounts otherCounts = attempt.count(other);
		boolean untried = counts.outcomes() == 0 && inFlight.getOrDefault(category, 0) == 0;
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
	 * Plans the working candidate's representative development trials: among the cells that are neither ready nor at
	 * their review point, and that lead the least-sampled such cell by fewer than {@value #COVERAGE_LEAD}
	 * assignments, the lowest 95% Wilson lower bound first, ties to fewer outcomes, then the higher risk of the
	 * cell's category, then cell order. The allocation rule is {@link AllocationRule#COVERAGE_FLOOR} when the floor
	 * excluded the cell that the bound alone would choose, otherwise {@link AllocationRule#LOWEST_EXPLORATORY_BOUND}.
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
		Map<String, Integer> inFlight = inFlight(campaign, Purpose.DEVELOPMENT, development.series());
		int slots = ConcurrencyPolicy.window(campaign) - total(inFlight);
		List<Cell> eligible = new ArrayList<>();
		for (Cell cell : contract.cells())
		{
			CategoryCounts counts = development.count(cell.id());
			if (!ReadinessGate.isReady(counts) && counts.outcomes() < development.reviewPoint(cell.id()))
				eligible.add(cell);
		}
		while (slots > 0 && plan.size() < freeCapacity && !eligible.isEmpty())
		{
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
	 * Indicates whether a cell should receive the next development trial before another.
	 *
	 * @param contract the campaign's contract
	 * @param development the development state
	 * @param cell a cell that comes after {@code other} in cell order
	 * @param other the best cell so far
	 * @return {@code true} if {@code cell} comes first
	 */
	private static boolean isWeaker(Contract contract, Development development, Cell cell, Cell other)
	{
		CategoryCounts counts = development.count(cell.id());
		CategoryCounts otherCounts = development.count(other.id());
		int byBound = Double.compare(ExploratoryBounds.wilson(counts.passes(), counts.outcomes()).lower(),
			ExploratoryBounds.wilson(otherCounts.passes(), otherCounts.outcomes()).lower());
		if (byBound != 0)
			return byBound < 0;
		if (counts.outcomes() != otherCounts.outcomes())
			return counts.outcomes() < otherCounts.outcomes();
		return contract.categoryOf(cell).riskRank().compareTo(contract.categoryOf(other).riskRank()) < 0;
	}

	/**
	 * Plans the incumbent's baseline trials on the remaining capacity, one cell at a time in turn, until each cell's
	 * unadopted and adopted trials reach its target.
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
		List<String> cells = campaign.contract().cellIds();
		Map<String, Integer> inFlight = inFlight(campaign, Purpose.BASELINE, baseline.index());
		Map<String, Integer> needed = new HashMap<>();
		for (String cell : cells)
		{
			CategoryCounts counts = baseline.count(cell);
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

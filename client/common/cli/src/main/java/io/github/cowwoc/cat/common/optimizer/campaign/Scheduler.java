package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Category;
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
	 *
	 * @param campaign the campaign
	 * @param now the current time
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
		planTargeted(campaign, plan);
		Optional<CertificationAttempt> running = campaign.runningAttempt();
		if (running.isPresent())
			planFormal(campaign, running.get(), plan, freeCapacity);
		else
		{
			if (!campaign.development().isPaused())
				planDevelopment(campaign, plan, freeCapacity);
			planBaseline(campaign, plan, freeCapacity);
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
	 */
	private static void planTargeted(Campaign campaign, List<Admission> plan)
	{
		Development development = campaign.development();
		Sha256 working = development.working().snapshot();
		Diagnosis diagnosis = development.diagnosis();
		if (diagnosis != null && !diagnosis.pending().isEmpty() && diagnosis.rerun() == null)
		{
			TrialInstance instance = diagnosis.pending().getFirst();
			plan.add(new Admission.Assign(Purpose.DIAGNOSTIC, development.series(), working, instance.categoryId(),
				instance));
		}
		AuthorAttempt author = development.author();
		if (author == null)
			return;
		for (PromotionRun run : author.runs())
		{
			if (run.trial() != null)
				continue;
			plan.add(new Admission.Assign(Purpose.PROMOTION, author.number(), Promotion.snapshot(author, run, working),
				run.instance().categoryId(), run.instance()));
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
	 */
	private static void planFormal(Campaign campaign, CertificationAttempt attempt, List<Admission> plan,
		int freeCapacity)
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
				null));
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
	 * Plans the working candidate's representative development trials: among the categories that are neither ready
	 * nor at their review point, and that lead the least-sampled such category by fewer than
	 * {@value #COVERAGE_LEAD} assignments, the lowest 95% Wilson lower bound first, ties to fewer outcomes, then
	 * higher risk, then category order.
	 *
	 * @param campaign the campaign
	 * @param plan the admissions planned so far
	 * @param freeCapacity the number of sessions that may start now
	 */
	private static void planDevelopment(Campaign campaign, List<Admission> plan, int freeCapacity)
	{
		Development development = campaign.development();
		Map<String, Integer> inFlight = inFlight(campaign, Purpose.DEVELOPMENT, development.series());
		int slots = ConcurrencyPolicy.window(campaign) - total(inFlight);
		List<Category> eligible = new ArrayList<>();
		for (Category category : campaign.contract().categories())
		{
			CategoryCounts counts = development.count(category.id());
			if (!ReadinessGate.isReady(counts) && counts.outcomes() < development.reviewPoint(category.id()))
				eligible.add(category);
		}
		while (slots > 0 && plan.size() < freeCapacity && !eligible.isEmpty())
		{
			int leastAssigned = Integer.MAX_VALUE;
			for (Category category : eligible)
				leastAssigned = Math.min(leastAssigned, assigned(development, inFlight, category.id()));
			Category best = null;
			for (Category category : eligible)
			{
				if (assigned(development, inFlight, category.id()) >= leastAssigned + COVERAGE_LEAD)
					continue;
				if (best == null || isWeaker(development, category, best))
					best = category;
			}
			plan.add(new Admission.Assign(Purpose.DEVELOPMENT, development.series(),
				development.working().snapshot(), best.id(), null));
			inFlight.merge(best.id(), 1, Integer::sum);
			--slots;
		}
	}

	/**
	 * Returns a category's representative development assignments.
	 *
	 * @param development the development state
	 * @param inFlight the unadopted trials per category
	 * @param category the category
	 * @return the adopted outcomes plus the unadopted trials
	 */
	private static int assigned(Development development, Map<String, Integer> inFlight, String category)
	{
		return development.count(category).outcomes() + inFlight.getOrDefault(category, 0);
	}

	/**
	 * Indicates whether a category should receive the next development trial before another.
	 *
	 * @param development the development state
	 * @param category a category that comes after {@code other} in category order
	 * @param other the best category so far
	 * @return {@code true} if {@code category} comes first
	 */
	private static boolean isWeaker(Development development, Category category, Category other)
	{
		CategoryCounts counts = development.count(category.id());
		CategoryCounts otherCounts = development.count(other.id());
		int byBound = Double.compare(ExploratoryBounds.wilson(counts.passes(), counts.outcomes()).lower(),
			ExploratoryBounds.wilson(otherCounts.passes(), otherCounts.outcomes()).lower());
		if (byBound != 0)
			return byBound < 0;
		if (counts.outcomes() != otherCounts.outcomes())
			return counts.outcomes() < otherCounts.outcomes();
		return category.riskRank().compareTo(other.riskRank()) < 0;
	}

	/**
	 * Plans the incumbent's baseline trials on the remaining capacity, one category at a time in turn, until each
	 * category's unadopted and adopted trials reach its target.
	 *
	 * @param campaign the campaign
	 * @param plan the admissions planned so far
	 * @param freeCapacity the number of sessions that may start now
	 */
	private static void planBaseline(Campaign campaign, List<Admission> plan, int freeCapacity)
	{
		if (campaign.development().working().isIncumbent())
			return;
		IncumbentBaseline baseline = campaign.baseline();
		List<Category> categories = campaign.contract().categories();
		Map<String, Integer> inFlight = inFlight(campaign, Purpose.BASELINE, baseline.index());
		Map<String, Integer> needed = new HashMap<>();
		for (Category category : categories)
		{
			CategoryCounts counts = baseline.count(category.id());
			int target = Baseline.target(counts, baseline.index(), categories.size());
			needed.put(category.id(), target - counts.outcomes() - inFlight.getOrDefault(category.id(), 0));
		}
		boolean added = true;
		while (added)
		{
			added = false;
			for (Category category : categories)
			{
				int need = needed.get(category.id());
				if (need <= 0 || plan.size() >= freeCapacity)
					continue;
				plan.add(new Admission.Assign(Purpose.BASELINE, baseline.index(), campaign.incumbent().snapshot(),
					category.id(), null));
				needed.put(category.id(), need - 1);
				added = true;
			}
		}
	}
}

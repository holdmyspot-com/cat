package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.statistics.ExploratoryBounds;
import com.holdmyspot.cat.common.optimizer.statistics.Interval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Describes trial pass rates by concurrency level and checks reported access to state forbidden to trials.
 * <p>
 * These results support review of trial independence. The caller decides whether detected dependence makes
 * certification unavailable with {@link Outcome#INVALID_STATISTICAL_ASSUMPTIONS}.
 */
public final class AssumptionCheck
{
	/**
	 * One outcome counted by the campaign, with the concurrency level its trial started at.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param concurrency the number of the candidate's trials in flight when the trial started, including itself
	 * @param verdict the outcome
	 */
	public record Observation(int concurrency, Verdict verdict)
	{
		/**
		 * Creates an observation.
		 *
		 * @param concurrency the number of the candidate's trials in flight when the trial started, including itself
		 * @param verdict the outcome
		 * @throws NullPointerException if {@code verdict} is null
		 * @throws IllegalArgumentException if {@code concurrency} is not positive
		 */
		public Observation
		{
			requireThat(concurrency, "concurrency").isPositive();
			requireThat(verdict, "verdict").isNotNull();
		}
	}

	/**
	 * The descriptive pass rate at one concurrency level.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param concurrency the concurrency level
	 * @param passes the passes at that level
	 * @param trials the outcomes at that level
	 * @param interval the exploratory 95% Wilson interval on the pass rate
	 */
	public record LevelRate(int concurrency, int passes, int trials, Interval interval)
	{
		/**
		 * Creates a rate.
		 *
		 * @param concurrency the concurrency level
		 * @param passes the passes at that level
		 * @param trials the outcomes at that level
		 * @param interval the exploratory 95% Wilson interval on the pass rate
		 * @throws NullPointerException if {@code interval} is null
		 * @throws IllegalArgumentException if {@code concurrency} or {@code trials} are not positive, or
		 *   {@code passes} is outside [0, trials]
		 */
		public LevelRate
		{
			requireThat(concurrency, "concurrency").isPositive();
			requireThat(trials, "trials").isPositive();
			requireThat(passes, "passes").isBetween(0, true, trials, true);
			requireThat(interval, "interval").isNotNull();
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private AssumptionCheck()
	{
	}

	/**
	 * Describes pass rates by concurrency level. The rates are descriptive and support no claim.
	 *
	 * @param observations the adopted outcomes
	 * @return one rate per observed level, in increasing level order
	 * @throws NullPointerException if {@code observations} is null or contains null
	 */
	public static List<LevelRate> passRatesByConcurrency(List<Observation> observations)
	{
		requireThat(observations, "observations").isNotNull();
		SortedMap<Integer, int[]> levels = new TreeMap<>();
		for (Observation observation : observations)
		{
			int[] counts = levels.computeIfAbsent(observation.concurrency(), _ -> new int[2]);
			if (observation.verdict() == Verdict.PASS)
				++counts[0];
			++counts[1];
		}
		List<LevelRate> rates = new ArrayList<>();
		for (Map.Entry<Integer, int[]> entry : levels.entrySet())
		{
			int passes = entry.getValue()[0];
			int trials = entry.getValue()[1];
			rates.add(new LevelRate(entry.getKey(), passes, trials, ExploratoryBounds.wilson(passes, trials)));
		}
		return rates;
	}

	/**
	 * Indicates whether a reported accessed path lies under a root forbidden to trials, after removing
	 * {@code .} and {@code ..} path components. This checks paths without resolving symbolic links.
	 *
	 * @param accessed the absolute paths that the trial accessed
	 * @param protectedRoots the absolute roots of state that no trial may access
	 * @return {@code true} if an accessed path equals or lies under a protected root
	 * @throws NullPointerException if either argument is null or contains null
	 * @throws IllegalArgumentException if a path is not absolute
	 */
	public static boolean isDependent(Collection<Path> accessed, Collection<Path> protectedRoots)
	{
		requireThat(accessed, "accessed").isNotNull();
		requireThat(protectedRoots, "protectedRoots").isNotNull();
		List<Path> roots = new ArrayList<>();
		for (Path root : protectedRoots)
		{
			requireThat(root.isAbsolute(), "root.isAbsolute()").withContext(root, "root").isTrue();
			roots.add(root.normalize());
		}
		boolean dependent = false;
		for (Path path : accessed)
		{
			requireThat(path.isAbsolute(), "path.isAbsolute()").withContext(path, "path").isTrue();
			Path normalized = path.normalize();
			for (Path root : roots)
			{
				if (normalized.startsWith(root))
					dependent = true;
			}
		}
		return dependent;
	}
}

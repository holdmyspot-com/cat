package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The paired comparisons of one cell of a certification attempt with one kind of reference trial: the control
 * trials, which run the incumbent with the target prompt file emptied and its dependents removed, or the incumbent
 * trials, which run the incumbent unchanged. The {@code j}-th reference trial of a cell pairs with the cell's
 * {@code j}-th candidate formal trial in assignment order and runs that trial's test case. Each assertion of the
 * cell's prompt test then compares how often the candidate and the reference trials pass it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param unpaired    the adopted candidate formal trials that no adopted reference trial pairs with yet, in
 *                    assignment order
 * @param comparisons the adopted pairs of each assertion of the cell's prompt test, by assertion identifier, in the
 *                    prompt test's assertion order; empty until the cell's minimum-pass-rate part certifies, and then
 *                    holding every assertion
 */
public record Pairing(List<UnpairedTrial> unpaired, Map<String, PairCounts> comparisons)
{
	/**
	 * No candidate formal trials and no comparisons.
	 */
	public static final Pairing EMPTY = new Pairing(List.of(), Map.of());

	/**
	 * Creates a pairing.
	 *
	 * @param unpaired    the adopted candidate formal trials that no adopted reference trial pairs with yet, in
	 *                    assignment order
	 * @param comparisons the adopted pairs of each assertion of the cell's prompt test, by assertion identifier, in the
	 *                    prompt test's assertion order; empty until the cell's minimum-pass-rate part certifies
	 * @throws NullPointerException     if any of the arguments are null or contain null
	 * @throws IllegalArgumentException if two assertions have different numbers of pairs
	 */
	public Pairing
	{
		requireThat(unpaired, "unpaired").isNotNull();
		requireThat(comparisons, "comparisons").isNotNull();
		unpaired = List.copyOf(unpaired);
		int pairs = -1;
		for (Map.Entry<String, PairCounts> entry : comparisons.entrySet())
		{
			requireThat(entry.getKey(), "assertionId").isNotNull();
			requireThat(entry.getValue(), "comparisons[" + entry.getKey() + "]").isNotNull();
			int assertionPairs = entry.getValue().pairs();
			if (pairs < 0)
				pairs = assertionPairs;
			requireThat(assertionPairs, "comparisons[" + entry.getKey() + "].pairs()").isEqualTo(pairs, "pairs");
		}
		comparisons = Collections.unmodifiableMap(new LinkedHashMap<>(comparisons));
	}

	/**
	 * Indicates whether the cell's minimum-pass-rate part certified, so that reference trials run in the cell. The
	 * certification stays in effect when later candidate formal trials fail.
	 *
	 * @return {@code true} if the pairing compares the cell's assertions
	 */
	public boolean isComparing()
	{
		return !comparisons.isEmpty();
	}

	/**
	 * Returns the number of adopted pairs.
	 *
	 * @return the pairs that each assertion compares, or 0 if the cell is not comparing
	 */
	public int pairs()
	{
		for (PairCounts counts : comparisons.values())
			return counts.pairs();
		return 0;
	}

	/**
	 * Returns this pairing with one more adopted candidate formal trial waiting for its reference trial.
	 *
	 * @param trial the candidate formal trial
	 * @return the updated pairing
	 * @throws NullPointerException if {@code trial} is null
	 */
	public Pairing withCandidate(UnpairedTrial trial)
	{
		requireThat(trial, "trial").isNotNull();
		List<UnpairedTrial> updated = new ArrayList<>(unpaired);
		updated.add(trial);
		return new Pairing(updated, comparisons);
	}

	/**
	 * Returns this pairing comparing every assertion of the cell's prompt test, with no pairs yet.
	 *
	 * @param assertionIds the identifiers of the prompt test's assertions, in its assertion order
	 * @return the updated pairing
	 * @throws NullPointerException     if {@code assertionIds} is null
	 * @throws IllegalArgumentException if {@code assertionIds} is empty
	 * @throws IllegalStateException    if the pairing already compares the assertions
	 */
	public Pairing startComparing(Collection<String> assertionIds)
	{
		requireThat(assertionIds, "assertionIds").isNotEmpty();
		if (isComparing())
			throw new IllegalStateException("The cell already compares its assertions: " + comparisons.keySet());
		Map<String, PairCounts> started = new LinkedHashMap<>();
		for (String assertionId : assertionIds)
			started.put(assertionId, PairCounts.EMPTY);
		return new Pairing(unpaired, started);
	}

	/**
	 * Returns this pairing after adopting the reference trial that pairs with the first unpaired candidate formal trial.
	 *
	 * @param testCase        the test case that the reference trial ran
	 * @param referencePassed the identifiers of the assertions that the reference trial passed
	 * @return the updated pairing
	 * @throws NullPointerException  if any of the arguments are null
	 * @throws IllegalStateException if the cell is not comparing, no candidate formal trial waits for a reference
	 *                               trial, or the first one ran another test case
	 */
	public Pairing withReference(DrawnTestCase testCase, Set<String> referencePassed)
	{
		requireThat(testCase, "testCase").isNotNull();
		requireThat(referencePassed, "referencePassed").isNotNull();
		if (!isComparing())
			throw new IllegalStateException("The cell does not compare its assertions yet");
		if (unpaired.isEmpty())
			throw new IllegalStateException("No candidate formal trial waits for a reference trial");
		UnpairedTrial candidate = unpaired.getFirst();
		if (!Promotion.isSameTestCase(candidate.testCase(), testCase))
		{
			throw new IllegalStateException("The reference trial ran " + testCase + ", but the candidate formal trial " +
				"that it pairs with ran " + candidate.testCase());
		}
		Map<String, PairCounts> updated = new LinkedHashMap<>();
		for (Map.Entry<String, PairCounts> entry : comparisons.entrySet())
		{
			String assertionId = entry.getKey();
			updated.put(assertionId, entry.getValue().add(candidate.passed().contains(assertionId),
				referencePassed.contains(assertionId)));
		}
		return new Pairing(unpaired.subList(1, unpaired.size()), updated);
	}

	/**
	 * An adopted candidate formal trial that waits for the reference trial it pairs with.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param testCase the test case that the trial ran, which its reference trial runs
	 * @param passed   the identifiers of the assertions that the trial passed
	 */
	public record UnpairedTrial(DrawnTestCase testCase, Set<String> passed)
	{
		/**
		 * Creates an unpaired trial.
		 *
		 * @param testCase the test case that the trial ran, which its reference trial runs
		 * @param passed   the identifiers of the assertions that the trial passed
		 * @throws NullPointerException if any of the arguments are null or {@code passed} contains null
		 */
		public UnpairedTrial
		{
			requireThat(testCase, "testCase").isNotNull();
			requireThat(passed, "passed").isNotNull();
			passed = Set.copyOf(passed);
		}
	}
}

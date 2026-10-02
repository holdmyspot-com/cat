package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Map;

/**
 * The outcomes and pending diagnosis or author work used to improve the current prompt snapshot.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param working the working candidate
 * @param series the sequence of development counts, identified by a number that changes whenever counts restart
 * @param counts the working candidate's sampled outcomes included in evidence per cell in this series
 * @param failures the failures that an author attempt receives, in failure order
 * @param diagnosis the reruns in progress, or {@code null} if none are
 * @param round the development round in progress, or {@code null} if none is
 * @param author the author attempt whose promotion runs are in progress, or {@code null} if none is
 * @param reviewPoints the outcome count that triggers another review for each cell the user extended
 * @param widened {@code true} once every active cell met the readiness gate's count conditions in this series, which
 *   makes every cell eligible for representative trials ({@link CellTier})
 * @param instanceOutcomes the working candidate's adopted outcomes grouped by instance content
 * @param predecessor the retained state before the latest promotion, or {@code null} if no revert is available
 * @param reverts the withdrawn candidates and their retained evidence, in withdrawal order
 */
public record Development(Candidate working, int series, Map<String, CellCounts> counts,
	List<Failure> failures, Diagnosis diagnosis, Round round, AuthorAttempt author,
	Map<String, Integer> reviewPoints, boolean widened, List<InstanceOutcomes> instanceOutcomes,
	Predecessor predecessor, List<Revert> reverts)
{
	/**
	 * The adopted-outcome count that triggers a cell's review unless the user extends it.
	 */
	public static final int DEFAULT_REVIEW_POINT = 200;

	/**
	 * Creates a development state.
	 *
	 * @param working the working candidate
	 * @param series the sequence of development counts, identified by a number that changes whenever counts restart
	 * @param counts the working candidate's sampled outcomes included in evidence per cell in this series
	 * @param failures the failures that an author attempt receives, in failure order
	 * @param diagnosis the reruns in progress, or {@code null} if none are
	 * @param round the development round in progress, or {@code null} if none is
	 * @param author the author attempt whose promotion runs are in progress, or {@code null} if none is
	 * @param reviewPoints the outcome count that triggers another review for each cell the user extended
	 * @param widened {@code true} once every active cell met the readiness gate's count conditions in this series
	 * @param instanceOutcomes the working candidate's adopted outcomes grouped by instance content
	 * @param predecessor the retained state before the latest promotion, or {@code null} if no revert is available
	 * @param reverts the withdrawn candidates and their retained evidence, in withdrawal order
	 * @throws NullPointerException if {@code working}, {@code counts}, {@code failures} or {@code reviewPoints} are
	 *   null
	 * @throws NullPointerException if {@code instanceOutcomes} or {@code reverts} are null or contain null
	 * @throws NullPointerException if {@code author} is non-null while {@code round} is null
	 * @throws IllegalArgumentException if {@code series} is negative
	 */
	public Development
	{
		requireThat(working, "working").isNotNull();
		requireThat(series, "series").isNotNegative();
		requireThat(counts, "counts").isNotNull();
		requireThat(failures, "failures").isNotNull();
		requireThat(reviewPoints, "reviewPoints").isNotNull();
		if (author != null)
			requireThat(round, "round").isNotNull();
		counts = Map.copyOf(counts);
		failures = List.copyOf(failures);
		reviewPoints = Map.copyOf(reviewPoints);
		instanceOutcomes = List.copyOf(instanceOutcomes);
		reverts = List.copyOf(reverts);
	}

	/**
	 * Creates development state with the recorded failures and diagnostic reruns as its instance evidence.
	 *
	 * @param working the working candidate
	 * @param series the development count series
	 * @param counts the representative counts per cell
	 * @param failures the retained failing instances and diagnostic reruns
	 * @param diagnosis the diagnosis in progress, or {@code null}
	 * @param round the round in progress, or {@code null}
	 * @param author the author under validation, or {@code null}
	 * @param reviewPoints the outcome counts that trigger cell reviews
	 * @param widened whether every cell is eligible for representative trials
	 * @throws NullPointerException if a required argument or collection element is null
	 * @throws IllegalArgumentException if {@code series} is negative
	 */
	public Development(Candidate working, int series, Map<String, CellCounts> counts, List<Failure> failures,
		Diagnosis diagnosis, Round round, AuthorAttempt author, Map<String, Integer> reviewPoints, boolean widened)
	{
		this(working, series, counts, failures, diagnosis, round, author, reviewPoints, widened,
			InstanceOutcomes.fromFailures(failures), null, List.of());
	}

	/**
	 * Returns a fresh development series for a working candidate.
	 *
	 * @param working the working candidate
	 * @param series the series number
	 * @return the development state, with no outcomes, that has not widened
	 * @throws NullPointerException if {@code working} is null
	 * @throws IllegalArgumentException if {@code series} is negative
	 */
	public static Development start(Candidate working, int series)
	{
		return new Development(working, series, Map.of(), List.of(), null, null, null, Map.of(), false);
	}

	/**
	 * Returns a cell's outcomes included in development evidence.
	 *
	 * @param cell the cell's identifier
	 * @return the counts, empty if the cell has no outcomes
	 * @throws NullPointerException if {@code cell} is null
	 */
	public CellCounts count(String cell)
	{
		requireThat(cell, "category").isNotNull();
		return counts.getOrDefault(cell, CellCounts.EMPTY);
	}

	/**
	 * Returns the outcome count that triggers a cell's next review.
	 *
	 * @param cell the cell's identifier
	 * @return the adopted-outcome count at which the cell's review is due
	 * @throws NullPointerException if {@code cell} is null
	 */
	public int reviewPoint(String cell)
	{
		requireThat(cell, "category").isNotNull();
		return reviewPoints.getOrDefault(cell, DEFAULT_REVIEW_POINT);
	}

	/**
	 * Indicates whether new representative development trials must wait.
	 *
	 * @return {@code true} if a diagnosis, round or author attempt is in progress
	 */
	public boolean isPaused()
	{
		return diagnosis != null || round != null || author != null;
	}
}

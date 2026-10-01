package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Map;

/**
 * The development state of the working candidate.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param working the working candidate
 * @param series the development series, which changes whenever the counts restart
 * @param counts the working candidate's representative outcomes per category in this series
 * @param failures the failures that an author attempt receives, in failure order
 * @param diagnosis the reruns in progress, or {@code null} if none are
 * @param round the development round in progress, or {@code null} if none is
 * @param author the author attempt whose promotion runs are in progress, or {@code null} if none is
 * @param reviewPoints the review point of each category that the user extended
 */
public record Development(Candidate working, int series, Map<String, CategoryCounts> counts,
	List<Failure> failures, Diagnosis diagnosis, Round round, AuthorAttempt author,
	Map<String, Integer> reviewPoints)
{
	/**
	 * The review point of a category that the user has not extended.
	 */
	public static final int DEFAULT_REVIEW_POINT = 200;

	/**
	 * Creates a development state.
	 *
	 * @param working the working candidate
	 * @param series the development series, which changes whenever the counts restart
	 * @param counts the working candidate's representative outcomes per category in this series
	 * @param failures the failures that an author attempt receives, in failure order
	 * @param diagnosis the reruns in progress, or {@code null} if none are
	 * @param round the development round in progress, or {@code null} if none is
	 * @param author the author attempt whose promotion runs are in progress, or {@code null} if none is
	 * @param reviewPoints the review point of each category that the user extended
	 * @throws NullPointerException if {@code working}, {@code counts}, {@code failures} or {@code reviewPoints} are
	 *   null
	 * @throws IllegalArgumentException if {@code series} is negative, or {@code author} exists without
	 *   {@code round}
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
	}

	/**
	 * Returns a fresh development series for a working candidate.
	 *
	 * @param working the working candidate
	 * @param series the series number
	 * @return the development state, with no outcomes
	 * @throws NullPointerException if {@code working} is null
	 * @throws IllegalArgumentException if {@code series} is negative
	 */
	public static Development start(Candidate working, int series)
	{
		return new Development(working, series, Map.of(), List.of(), null, null, null, Map.of());
	}

	/**
	 * Returns a category's outcomes.
	 *
	 * @param category the category
	 * @return the outcomes, empty if the category has none
	 * @throws NullPointerException if {@code category} is null
	 */
	public CategoryCounts count(String category)
	{
		requireThat(category, "category").isNotNull();
		return counts.getOrDefault(category, CategoryCounts.EMPTY);
	}

	/**
	 * Returns a category's review point.
	 *
	 * @param category the category
	 * @return the number of outcomes at which the category's review checkpoint is due
	 * @throws NullPointerException if {@code category} is null
	 */
	public int reviewPoint(String category)
	{
		requireThat(category, "category").isNotNull();
		return reviewPoints.getOrDefault(category, DEFAULT_REVIEW_POINT);
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

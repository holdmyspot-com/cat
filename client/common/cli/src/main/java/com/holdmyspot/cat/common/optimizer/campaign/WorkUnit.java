package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A unit of work whose trials share one concurrency window: a working candidate's development series, one cell's
 * part of a certification attempt, one round of a formal failure group's reproduction, or one prompt author
 * attempt's promotion runs.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param purpose the purpose of the unit's trials: {@link Purpose#DEVELOPMENT}, {@link Purpose#FORMAL},
 *   {@link Purpose#REPRODUCTION} or {@link Purpose#PROMOTION}
 * @param series the development series, the certification index, the failure group's 1-based position or the
 *   prompt author attempt number
 * @param stage 0 for a development series, a promotion and the minimum-pass-rate part of a formal cell, 1 for the
 *   paired comparisons of a formal cell, or the 1-based round number of a reproduction
 * @param part the formal cell's identifier, the snapshot that a prompt author attempt's runs test, or empty
 */
public record WorkUnit(Purpose purpose, int series, int stage, String part)
{
	/**
	 * Creates a unit of work.
	 *
	 * @param purpose the purpose of the unit's trials
	 * @param series the development series, the certification index, the failure group's position or the prompt
	 *   author attempt number
	 * @param stage the formal part, the reproduction round, or 0
	 * @param part the formal cell's identifier, the tested snapshot of a prompt author attempt, or empty
	 * @throws NullPointerException if {@code purpose} or {@code part} are null
	 * @throws IllegalArgumentException if {@code series} or {@code stage} are negative
	 */
	public WorkUnit
	{
		requireThat(purpose, "purpose").isNotNull();
		requireThat(series, "series").isNotNegative();
		requireThat(stage, "stage").isNotNegative();
		requireThat(part, "part").isNotNull();
	}

	/**
	 * Returns a working candidate's development series.
	 *
	 * @param series the development series
	 * @return the unit
	 */
	public static WorkUnit development(int series)
	{
		return new WorkUnit(Purpose.DEVELOPMENT, series, 0, "");
	}

	/**
	 * Returns one cell's part of a certification attempt.
	 *
	 * @param attempt the certification index
	 * @param cell the cell's identifier
	 * @param comparing {@code false} for the minimum-pass-rate part, {@code true} for the paired comparisons
	 * @return the unit
	 */
	public static WorkUnit formal(int attempt, String cell, boolean comparing)
	{
		int stage;
		if (comparing)
			stage = 1;
		else
			stage = 0;
		return new WorkUnit(Purpose.FORMAL, attempt, stage, cell);
	}

	/**
	 * Returns one round of a formal failure group's reproduction.
	 *
	 * @param group the group's 1-based position in its attempt
	 * @param round the round's 1-based number
	 * @return the unit
	 */
	public static WorkUnit reproduction(int group, int round)
	{
		return new WorkUnit(Purpose.REPRODUCTION, group, round, "");
	}

	/**
	 * Returns the promotion runs of a prompt author attempt.
	 *
	 * @param attempt the prompt author attempt
	 * @return the unit
	 */
	public static WorkUnit promotion(PromptAuthorAttempt attempt)
	{
		return new WorkUnit(Purpose.PROMOTION, attempt.number(), 0, attempt.snapshot().hex());
	}
}

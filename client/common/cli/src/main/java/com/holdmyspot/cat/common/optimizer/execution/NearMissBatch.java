package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The judging of every near-miss reply of one prompt test by one judge: the frozen judge, which labels the replies, or
 * a candidate, which is checked against the labels.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe. The driver's thread is its only user.
 */
final class NearMissBatch
{
	private final String test;
	private final JudgeProfile judge;
	private final boolean labeling;
	private final int size;
	private final Map<String, Boolean> passed = new LinkedHashMap<>();
	private boolean failed;

	/**
	 * Creates a batch whose replies have not been judged.
	 *
	 * @param test     the prompt test's identifier
	 * @param judge    the judge that decides each reply
	 * @param labeling {@code true} if the judge is the frozen judge labeling the replies, {@code false} if it is a
	 *                 candidate that is checked against the labels
	 * @param size     the number of replies
	 * @throws NullPointerException     if {@code test} or {@code judge} are null
	 * @throws IllegalArgumentException if {@code size} is not positive
	 */
	NearMissBatch(String test, JudgeProfile judge, boolean labeling, int size)
	{
		requireThat(test, "test").isNotBlank();
		requireThat(judge, "judge").isNotNull();
		requireThat(size, "size").isPositive();
		this.test = test;
		this.judge = judge;
		this.labeling = labeling;
		this.size = size;
	}

	/**
	 * Returns the prompt test's identifier.
	 *
	 * @return the identifier
	 */
	String test()
	{
		return test;
	}

	/**
	 * Returns the judge that decides each reply.
	 *
	 * @return the judge
	 */
	JudgeProfile judge()
	{
		return judge;
	}

	/**
	 * Indicates whether the judge labels the replies.
	 *
	 * @return {@code true} for the frozen judge's labeling, {@code false} for a candidate's check
	 */
	boolean isLabeling()
	{
		return labeling;
	}

	/**
	 * Records a reply's verdict.
	 *
	 * @param reply     the reply's key
	 * @param satisfied {@code true} if the judge passed the reply's assertion
	 * @throws NullPointerException if {@code reply} is null
	 */
	void record(String reply, boolean satisfied)
	{
		requireThat(reply, "reply").isNotNull();
		passed.put(reply, satisfied);
	}

	/**
	 * Records that a reply could not be judged, which ends the batch.
	 */
	void fail()
	{
		failed = true;
	}

	/**
	 * Indicates whether a reply could not be judged.
	 *
	 * @return {@code true} if the batch failed
	 */
	boolean hasFailed()
	{
		return failed;
	}

	/**
	 * Indicates whether the batch ended: every reply has a verdict, or one could not be judged.
	 *
	 * @return {@code true} if no more reply needs a judge
	 */
	boolean isDone()
	{
		return failed || passed.size() == size;
	}

	/**
	 * Returns the verdicts so far.
	 *
	 * @return whether each judged reply passed, by the reply's key
	 */
	Map<String, Boolean> verdicts()
	{
		return Map.copyOf(passed);
	}
}

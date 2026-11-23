package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The stored verdict of one judged assertion: the judge's decision, the judge that made it, and the trial whose judge
 * session made it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param verdict     the judge's decision of the assertion
 * @param judgeModel  the model of the judge that made the decision
 * @param judgeEffort the reasoning effort of the judge that made the decision
 * @param trial       a description of the trial whose judge session made the decision
 */
public record VerdictRecord(AssertionVerdict verdict, String judgeModel, String judgeEffort, String trial)
{
	/**
	 * Creates a record.
	 *
	 * @param verdict     the judge's decision of the assertion
	 * @param judgeModel  the model of the judge that made the decision
	 * @param judgeEffort the reasoning effort of the judge that made the decision
	 * @param trial       a description of the trial whose judge session made the decision
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code judgeModel}, {@code judgeEffort} or {@code trial} are blank
	 */
	public VerdictRecord
	{
		requireThat(verdict, "verdict").isNotNull();
		requireThat(judgeModel, "judgeModel").isNotBlank();
		requireThat(judgeEffort, "judgeEffort").isNotBlank();
		requireThat(trial, "trial").isNotBlank();
	}
}

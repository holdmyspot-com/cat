package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.NearMisses;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;

import java.util.List;

/**
 * One near-miss reply that waits for, or is in, its judge session.
 *
 * @param batch           the judging that the reply belongs to
 * @param reply           the reply
 * @param testCase        the test case whose values the reply and its assertion carry
 * @param evidence        the reply as a trial's result, which every judge attempt receives unchanged
 * @param evidenceSection the rendered trial activity and file changes of the reply; empty when the frozen judge
 *                        template does not name it
 * @param assertions      the assertion of the reply, which the judge decides
 * @param attempt         the judge attempt, from 1
 */
record NearMissJob(NearMissBatch batch, NearMisses.Reply reply, DrawnTestCase testCase,
	SessionResult.Completed evidence, String evidenceSection, List<JudgedAssertion> assertions, int attempt)
{
	/**
	 * Creates a job.
	 *
	 * @param batch           the judging that the reply belongs to
	 * @param reply           the reply
	 * @param testCase        the test case whose values the reply and its assertion carry
	 * @param evidence        the reply as a trial's result
	 * @param evidenceSection the rendered evidence
	 * @param assertions      the assertion of the reply
	 * @param attempt         the judge attempt, from 1
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code assertions} is empty or {@code attempt} is not positive
	 */
	NearMissJob
	{
		requireThat(batch, "batch").isNotNull();
		requireThat(reply, "reply").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(evidence, "evidence").isNotNull();
		requireThat(evidenceSection, "evidenceSection").isNotNull();
		requireThat(assertions, "assertions").isNotEmpty();
		requireThat(attempt, "attempt").isPositive();
	}

	/**
	 * Returns the next judge attempt with the same evidence.
	 *
	 * @return the attempt
	 */
	NearMissJob retry()
	{
		return new NearMissJob(batch, reply, testCase, evidence, evidenceSection, assertions, attempt + 1);
	}
}

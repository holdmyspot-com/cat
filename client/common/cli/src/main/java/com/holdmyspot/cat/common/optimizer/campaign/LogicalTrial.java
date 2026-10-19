package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;

import java.util.List;

/**
 * One assigned trial whose result is not yet included in campaign evidence. Retries run the same test case
 * under the same key rather than count as additional trials.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param key the trial's identity
 * @param testCase the assigned test case, which every retry reuses
 * @param identity the measurement conditions that the trial belongs to
 * @param allocation the allocation decision that assigned the trial
 * @param state indicates whether the trial is assigned, running or awaiting inclusion in evidence
 * @param verdict the judged outcome, or {@code null} if the trial is not judged
 * @param rationale the judge's explanation of {@code verdict} or, when no judge ran, the record of the trial's
 *   built-in tools, or {@code null} if the trial is not judged
 * @param failedAssertions the assertions that a failed trial failed, each with its behavior: those of its failed
 *   built-in tools or, when its judge failed it, those that the judge named; empty unless the trial failed
 * @param triage the impact of a failed development trial, or {@code null} if the trial passed, is not judged, has
 *   another purpose or was judged by a build that did not decide impacts
 * @param infrastructureFailures the runs that ended without an outcome and count toward
 *   {@link Campaign#INFRASTRUCTURE_FAILURE_LIMIT}; a {@link Choice#RETRY} resets it to 0
 * @param infrastructureAttempts every run that ended without an outcome, in the order the runs ended
 * @param observations the runs that ended under conditions that differ from the trial's identity
 * @param responses each completed turn's assistant messages in order; empty before the trial is judged
 * @param files where the trial session's harness events and file changes were retained, or {@code null} if the trial
 *   is not judged
 */
public record LogicalTrial(TrialKey key, DrawnTestCase testCase, MeasurementIdentity identity, Allocation allocation,
	TrialState state, Verdict verdict, String rationale, List<FailedAssertion> failedAssertions, Triage triage,
	int infrastructureFailures, List<InfrastructureAttempt> infrastructureAttempts, int observations,
	List<List<String>> responses, TrialFiles files)
{
	/**
	 * Creates a trial.
	 *
	 * @param key the trial's identity
	 * @param testCase the assigned test case, which every retry reuses
	 * @param identity the measurement conditions that the trial belongs to
	 * @param allocation the allocation decision that assigned the trial
	 * @param state whether the trial is assigned, running or awaiting inclusion in evidence
	 * @param verdict the judged outcome, or {@code null} if the trial is not judged
	 * @param rationale the judge's explanation of {@code verdict} or, when no judge ran, the record of the trial's
	 *   built-in tools, or {@code null} if the trial is not judged
	 * @param failedAssertions the assertions that a failed trial failed, each with its behavior: those of its
	 *   failed built-in tools or, when its judge failed it, those that the judge named; empty unless the trial
	 *   failed
	 * @param triage the impact of a failed development trial, or {@code null} if the trial passed, is not judged, has
	 *   another purpose or was judged by a build that did not decide impacts
	 * @param infrastructureFailures the runs that ended without an outcome and count toward
	 *   {@link Campaign#INFRASTRUCTURE_FAILURE_LIMIT}
	 * @param infrastructureAttempts every run that ended without an outcome, in the order the runs ended
	 * @param observations the runs that ended under conditions that differ from the trial's identity
	 * @param responses each completed turn's assistant messages in order; empty before the trial is judged
	 * @param files where the trial session's harness events and file changes were retained, or {@code null} if the
	 *   trial is not judged
	 * @throws NullPointerException if {@code key}, {@code testCase}, {@code identity}, {@code allocation},
	 *   {@code state}, {@code failedAssertions}, {@code infrastructureAttempts} or {@code responses} are null, or a
	 *   failed assertion, a turn's messages or an assistant message is null
	 * @throws IllegalArgumentException if a count is negative, the test case's cell differs from the key's, the
	 *   allocation rule does not assign the key's purpose, a verdict exists without the {@link TrialState#JUDGED} state
	 *   or that state lacks a verdict, only one of {@code rationale}, {@code files} and {@code verdict} has a value,
	 *   {@code failedAssertions} is not empty for a trial that did not fail, a
	 *   {@code triage} exists for a trial that is not a failed {@link Purpose#DEVELOPMENT development} trial, or
	 *   {@code infrastructureFailures} exceeds the number of {@link RunFailure#INFRASTRUCTURE} attempts
	 */
	public LogicalTrial
	{
		requireThat(key, "key").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		requireThat(identity, "identity").isNotNull();
		requireThat(allocation, "allocation").isNotNull();
		requireThat(state, "state").isNotNull();
		requireThat(failedAssertions, "failedAssertions").isNotNull();
		requireThat(infrastructureFailures, "infrastructureFailures").isNotNegative();
		requireThat(infrastructureAttempts, "infrastructureAttempts").isNotNull();
		requireThat(observations, "observations").isNotNegative();
		requireThat(responses, "responses").isNotNull();
		requireThat(testCase.cellId(), "testCase.cellId()").isEqualTo(key.cell(), "key.cell()");
		requireThat(allocation.rule().assigns(key.purpose()), "allocation.rule().assigns(key.purpose())").isTrue();
		requireThat(verdict != null, "hasVerdict").isEqualTo(state == TrialState.JUDGED, "isJudged");
		requireThat(rationale != null, "hasRationale").isEqualTo(verdict != null, "hasVerdict");
		requireThat(files != null, "hasFiles").isEqualTo(verdict != null, "hasVerdict");
		if (verdict != Verdict.FAIL)
			requireThat(failedAssertions, "failedAssertions").isEmpty();
		if (triage != null)
		{
			requireThat(verdict, "verdict").isEqualTo(Verdict.FAIL);
			requireThat(key.purpose(), "key.purpose()").isEqualTo(Purpose.DEVELOPMENT);
		}
		failedAssertions = List.copyOf(failedAssertions);
		infrastructureAttempts = List.copyOf(infrastructureAttempts);
		responses = responses.stream().map(List::copyOf).toList();
		long infrastructure = infrastructureAttempts.stream().
			filter(attempt -> attempt.failure() == RunFailure.INFRASTRUCTURE).
			count();
		requireThat((long) infrastructureFailures, "infrastructureFailures").
			isLessThanOrEqualTo(infrastructure, "infrastructureAttempts(INFRASTRUCTURE)");
	}

	/**
	 * Returns a trial that was just assigned.
	 *
	 * @param key the trial's identity
	 * @param testCase the assigned test case
	 * @param identity the measurement conditions that the trial belongs to
	 * @param allocation the allocation decision that assigned the trial
	 * @return the trial
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the test case's cell differs from the key's, or the allocation rule does
	 *   not assign the key's purpose
	 */
	public static LogicalTrial assigned(TrialKey key, DrawnTestCase testCase, MeasurementIdentity identity,
		Allocation allocation)
	{
		return new LogicalTrial(key, testCase, identity, allocation, TrialState.ASSIGNED, null, null, List.of(), null,
			0, List.of(), 0, List.of(), null);
	}

	/**
	 * Returns this trial in another state, without a verdict.
	 *
	 * @param newState {@link TrialState#ASSIGNED} or {@link TrialState#RUNNING}
	 * @return the updated trial
	 * @throws NullPointerException if {@code newState} is null
	 * @throws IllegalArgumentException if {@code newState} is {@link TrialState#JUDGED}
	 */
	LogicalTrial withState(TrialState newState)
	{
		return new LogicalTrial(key, testCase, identity, allocation, newState, null, null, List.of(), null,
			infrastructureFailures, infrastructureAttempts, observations, List.of(), null);
	}
}

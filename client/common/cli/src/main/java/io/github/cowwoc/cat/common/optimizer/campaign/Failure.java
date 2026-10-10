package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * A failing test case with the judge's rationale and the failure's diagnosis. A prompt author attempt receives a
 * development failure whole, and a certification failure only as its {@link #summary() summary}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param testCase the test case that failed: the targeted copy of a development failure's test case, or the held-out
 *   test case of a certification failure
 * @param rationale the judge's explanation of the failure, or the record of each failed built-in tool
 * @param failedAssertions the assertions that the failure failed, each with its behavior: those of its failed
 *   built-in tools or, when its judge failed it, those that the judge named
 * @param triage the impact of a development failure, or {@code null} for a certification failure or a development
 *   failure that a build without impacts recorded
 * @param reruns the targeted diagnostic reruns of the test case, in the order they were adopted; empty if none ran
 * @param responses each turn's assistant messages in order, ending with its final response
 * @param files where the failing trial session's harness events and file changes were retained
 */
public record Failure(DrawnTestCase testCase, String rationale, List<FailedAssertion> failedAssertions,
	Triage triage, List<DiagnosticRerun> reruns, List<List<String>> responses, TrialFiles files)
{
	/**
	 * Creates a failure.
	 *
	 * @param testCase the test case that failed: the targeted copy of a development failure's test case, or the
	 *   held-out test case of a certification failure
	 * @param rationale the judge's explanation of the failure, or the record of each failed built-in tool
	 * @param failedAssertions the assertions that the failure failed, each with its behavior: those of its
	 *   failed built-in tools or, when its judge failed it, those that the judge named
	 * @param triage the impact of a development failure, or {@code null} for a certification failure or a development
	 *   failure that a build without impacts recorded
	 * @param reruns the targeted diagnostic reruns of the test case, in the order they were adopted; empty if none ran
	 * @param responses each turn's assistant messages in order, ending with its final response
	 * @param files where the failing trial session's harness events and file changes were retained
	 * @throws NullPointerException if an argument other than {@code triage}, a failed assertion, a rerun, a
	 *   turn's messages or an assistant message is null
	 */
	public Failure
	{
		requireThat(testCase, "testCase").isNotNull();
		requireThat(rationale, "rationale").isNotNull();
		requireThat(failedAssertions, "failedAssertions").isNotNull();
		requireThat(reruns, "reruns").isNotNull();
		requireThat(responses, "responses").isNotNull();
		requireThat(files, "files").isNotNull();

		failedAssertions = List.copyOf(failedAssertions);
		reruns = List.copyOf(reruns);
		responses = responses.stream().map(List::copyOf).toList();
	}

	/**
	 * Returns this failure with one more diagnostic rerun.
	 *
	 * @param rerun the rerun's outcome
	 * @return the updated failure
	 * @throws NullPointerException if {@code rerun} is null
	 */
	public Failure withRerun(DiagnosticRerun rerun)
	{
		requireThat(rerun, "rerun").isNotNull();
		List<DiagnosticRerun> updated = new ArrayList<>(reruns);
		updated.add(rerun);
		return new Failure(testCase, rationale, failedAssertions, triage, updated, responses, files);
	}

	/**
	 * Returns what development may learn of this failure when its test case is held out.
	 *
	 * @return the failure's cell, test and failed assertions with their behaviors
	 */
	public FailureSummary summary()
	{
		return new FailureSummary(testCase.cellId(), testCase.testId(), failedAssertions);
	}
}

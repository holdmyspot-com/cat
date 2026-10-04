package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * A failing instance with the judge's rationale and the failure's diagnosis. An author attempt receives a development
 * failure whole, and a certification failure only as its {@link #summary() summary}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param instance the instance that failed: the targeted copy of a development failure's instance, or the held-out
 *   instance of a certification failure
 * @param rationale the judge's explanation of the failure, or the record of each failed deterministic check
 * @param failedRequirements the identifiers of the requirements that the failure violated: those of its failed
 *   deterministic checks or, when its judge failed it, every requirement that the judge assessed
 * @param triage the impact of a development failure, or {@code null} for a certification failure or a development
 *   failure that a build without impacts recorded
 * @param reruns the targeted diagnostic reruns of the instance, in the order they were adopted; empty if none ran
 * @param responses each turn's assistant messages in order, ending with its final response
 */
public record Failure(TrialInstance instance, String rationale, List<String> failedRequirements, Triage triage,
	List<DiagnosticRerun> reruns, List<List<String>> responses)
{
	/**
	 * Creates a failure.
	 *
	 * @param instance the instance that failed: the targeted copy of a development failure's instance, or the
	 *   held-out instance of a certification failure
	 * @param rationale the judge's explanation of the failure, or the record of each failed deterministic check
	 * @param failedRequirements the identifiers of the requirements that the failure violated: those of its failed
	 *   deterministic checks or, when its judge failed it, every requirement that the judge assessed
	 * @param triage the impact of a development failure, or {@code null} for a certification failure or a development
	 *   failure that a build without impacts recorded
	 * @param reruns the targeted diagnostic reruns of the instance, in the order they were adopted; empty if none ran
	 * @param responses each turn's assistant messages in order, ending with its final response
	 * @throws NullPointerException if an argument other than {@code triage}, a requirement identifier, a rerun, a
	 *   turn's messages or an assistant message is null
	 */
	public Failure
	{
		requireThat(instance, "instance").isNotNull();
		requireThat(rationale, "rationale").isNotNull();
		requireThat(failedRequirements, "failedRequirements").isNotNull();
		requireThat(reruns, "reruns").isNotNull();
		requireThat(responses, "responses").isNotNull();

		failedRequirements = List.copyOf(failedRequirements);
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
		return new Failure(instance, rationale, failedRequirements, triage, updated, responses);
	}

	/**
	 * Returns what development may learn of this failure when its instance is held out.
	 *
	 * @return the failure's cell, test and failed requirements
	 */
	public FailureSummary summary()
	{
		return new FailureSummary(instance.cellId(), instance.testId(), failedRequirements);
	}
}

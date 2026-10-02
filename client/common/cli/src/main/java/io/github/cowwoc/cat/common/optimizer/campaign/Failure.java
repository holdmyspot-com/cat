package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * A failing instance that an author attempt receives, with the judge's rationale and the failure's diagnosis.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param instance the targeted copy of the instance that failed
 * @param rationale the judge's explanation of the failure, or the record of each failed deterministic check
 * @param reruns the targeted diagnostic reruns of the instance, in the order they were adopted; empty if none ran
 * @param responses each turn's assistant messages in order, ending with its final response
 */
public record Failure(TrialInstance instance, String rationale, List<DiagnosticRerun> reruns,
	List<List<String>> responses)
{
	/**
	 * Creates a failure.
	 *
	 * @param instance the targeted copy of the instance that failed
	 * @param rationale the judge's explanation of the failure, or the record of each failed deterministic check
	 * @param reruns the targeted diagnostic reruns of the instance, in the order they were adopted; empty if none ran
	 * @param responses each turn's assistant messages in order, ending with its final response
	 * @throws NullPointerException if any argument, a rerun, a turn's messages or an assistant message is null
	 */
	public Failure
	{
		requireThat(instance, "instance").isNotNull();
		requireThat(rationale, "rationale").isNotNull();
		requireThat(reruns, "reruns").isNotNull();
		requireThat(responses, "responses").isNotNull();

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
		return new Failure(instance, rationale, updated, responses);
	}
}

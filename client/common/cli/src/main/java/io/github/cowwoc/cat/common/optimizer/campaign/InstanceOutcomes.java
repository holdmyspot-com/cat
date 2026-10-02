package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * A candidate's adopted outcomes on one trial's content, including representative and targeted runs.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param instance the trial content, matched independently of its draw position or targeting
 * @param passes the adopted passing outcomes on that content
 * @param outcomes all adopted outcomes on that content
 */
public record InstanceOutcomes(TrialInstance instance, int passes, int outcomes)
{
	/**
	 * Creates a candidate's outcome record on one instance.
	 *
	 * @param instance the trial content
	 * @param passes the passing outcomes
	 * @param outcomes all outcomes
	 * @throws NullPointerException if {@code instance} is null
	 * @throws IllegalArgumentException if {@code outcomes} is negative or {@code passes} is outside [0, outcomes]
	 */
	public InstanceOutcomes
	{
		requireThat(instance, "instance").isNotNull();
		requireThat(outcomes, "outcomes").isNotNegative();
		requireThat(passes, "passes").isBetween(0, true, outcomes, true);
	}

	/**
	 * Collects each representative failure and its adopted diagnostic reruns by matching trial content.
	 *
	 * @param failures the candidate's retained failures
	 * @return the aggregated instance records
	 * @throws NullPointerException if {@code failures} or an element is null
	 */
	public static List<InstanceOutcomes> fromFailures(List<Failure> failures)
	{
		requireThat(failures, "failures").isNotNull();

		List<InstanceOutcomes> records = List.of();
		for (Failure failure : failures)
		{
			records = add(records, failure.instance(), Verdict.FAIL);
			for (DiagnosticRerun rerun : failure.reruns())
				records = add(records, failure.instance(), rerun.verdict());
		}
		return records;
	}

	/**
	 * Records an adopted outcome, combining trials with the same content.
	 *
	 * @param records the candidate's existing instance records
	 * @param instance the adopted trial's content
	 * @param verdict the adopted outcome
	 * @return an immutable list containing the updated record in its original position, or appended for new content
	 * @throws NullPointerException if an argument or record is null
	 */
	public static List<InstanceOutcomes> add(List<InstanceOutcomes> records, TrialInstance instance,
		Verdict verdict)
	{
		requireThat(records, "records").isNotNull();
		requireThat(instance, "instance").isNotNull();
		requireThat(verdict, "verdict").isNotNull();

		int passing = 0;
		if (verdict == Verdict.PASS)
			passing = 1;

		List<InstanceOutcomes> updated = new ArrayList<>(records);
		for (int index = 0; index < updated.size(); ++index)
		{
			InstanceOutcomes record = updated.get(index);
			if (!Promotion.isSameContent(record.instance(), instance))
				continue;
			updated.set(index, new InstanceOutcomes(record.instance(), record.passes() + passing,
				record.outcomes() + 1));
			return List.copyOf(updated);
		}
		updated.add(new InstanceOutcomes(instance, passing, 1));
		return List.copyOf(updated);
	}
}

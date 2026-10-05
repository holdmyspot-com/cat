package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.VariationChoice;
import io.github.cowwoc.cat.common.optimizer.contract.VariationRule;
import io.github.cowwoc.cat.common.optimizer.contract.VariationValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One round of a failure group's reproduction: trials of the working candidate on the round's key-value combinations
 * in one cell.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param cellId the cell that the round's trials run in
 * @param combinations the combinations that the round tries, with their counts; empty while the round awaits the
 *   deriver's combinations
 */
public record ReproductionRound(String cellId, List<TriedCombination> combinations)
{
	/**
	 * Creates a round.
	 *
	 * @param cellId the cell that the round's trials run in
	 * @param combinations the combinations that the round tries, with their counts; empty while the round awaits
	 *   the deriver's combinations
	 * @throws NullPointerException if any of the arguments are null or {@code combinations} contains null
	 * @throws IllegalArgumentException if {@code cellId} is blank
	 */
	public ReproductionRound
	{
		requireThat(cellId, "cellId").isNotBlank();
		combinations = List.copyOf(combinations);
	}

	/**
	 * Indicates whether the round awaits the deriver's combinations.
	 *
	 * @return {@code true} if the round has no combination
	 */
	public boolean awaitsValues()
	{
		return combinations.isEmpty();
	}

	/**
	 * Returns the round's adopted trials.
	 *
	 * @return the trials of every combination
	 */
	public int trials()
	{
		int trials = 0;
		for (TriedCombination combination : combinations)
			trials += combination.trials();
		return trials;
	}

	/**
	 * Returns the round's trials that failed the group's requirement through the group's behavior.
	 *
	 * @return the failures of every combination
	 */
	public int failures()
	{
		int failures = 0;
		for (TriedCombination combination : combinations)
			failures += combination.failures();
		return failures;
	}

	/**
	 * Returns the combinations that the round tries, without their counts.
	 *
	 * @return the combinations
	 */
	public List<AddedCombination> added()
	{
		return combinations.stream().map(TriedCombination::combination).toList();
	}

	/**
	 * Finds the combination whose key values an instance holds.
	 *
	 * @param test the definition of the instance's test
	 * @param instance the instance
	 * @return the combination's position in {@link #combinations()}, or {@code -1} if the instance holds none of the
	 *   round's combinations
	 * @throws NullPointerException if any of the arguments are null
	 */
	public int indexOf(TestDefinition test, TrialInstance instance)
	{
		requireThat(test, "test").isNotNull();
		requireThat(instance, "instance").isNotNull();
		if (!test.id().equals(instance.testId()))
			return -1;
		Map<String, String> labelByDimension = new HashMap<>();
		for (VariationChoice choice : instance.choices())
			labelByDimension.put(choice.dimension(), choice.label());
		List<VariationRule> keyRules = test.keyRules();
		for (int i = 0; i < combinations.size(); ++i)
		{
			AddedCombination combination = combinations.get(i).combination();
			if (!combination.testId().equals(test.id()))
				continue;
			List<VariationValue> values = combination.values();
			boolean matches = true;
			for (int j = 0; j < keyRules.size() && matches; ++j)
				matches = values.get(j).label().equals(labelByDimension.get(keyRules.get(j).dimension()));
			if (matches)
				return i;
		}
		return -1;
	}
}

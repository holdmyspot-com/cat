package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.VariationRule;
import io.github.cowwoc.cat.common.optimizer.contract.VariationValue;

import java.util.ArrayList;
import java.util.List;

/**
 * One key-value combination that CAT added to the development part of a test after a certification attempt ended
 * with formal failures.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param attempt the index of the certification attempt after which the combination was added
 * @param testId the identifier of the test that the combination belongs to
 * @param values the combination's value of each key dimension, in the test's key-dimension order
 */
public record AddedCombinationReport(int attempt, String testId, List<KeyValue> values)
{
	/**
	 * Creates a report of one combination.
	 *
	 * @param attempt the index of the certification attempt after which the combination was added
	 * @param testId the identifier of the test that the combination belongs to
	 * @param values the combination's value of each key dimension, in the test's key-dimension order
	 * @throws NullPointerException if {@code testId} or {@code values} are null
	 * @throws IllegalArgumentException if {@code attempt} is not positive, {@code testId} is blank or {@code values}
	 *   is empty
	 */
	public AddedCombinationReport
	{
		requireThat(attempt, "attempt").isPositive();
		requireThat(testId, "testId").isNotBlank();
		requireThat(values, "values").isNotEmpty();
		values = List.copyOf(values);
	}

	/**
	 * Returns the combinations added to the development part of the current lineage's tests.
	 *
	 * @param campaign the campaign
	 * @return the combinations in the order they were added, or an empty list before a contract is confirmed
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static List<AddedCombinationReport> of(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		if (!campaign.isConfirmed())
			return List.of();
		List<AddedCombinationReport> reports = new ArrayList<>();
		for (AddedCombination combination : campaign.lineage().added().combinations())
		{
			TestDefinition test = campaign.contract().findTest(combination.testId()).orElseThrow();
			List<VariationRule> keyRules = test.keyRules();
			List<KeyValue> values = new ArrayList<>();
			for (int i = 0; i < keyRules.size(); ++i)
			{
				VariationValue value = combination.values().get(i);
				values.add(new KeyValue(keyRules.get(i).dimension(), value.label(), value.text()));
			}
			reports.add(new AddedCombinationReport(combination.attempt(), combination.testId(), values));
		}
		return reports;
	}

	/**
	 * The added value of one key dimension.
	 *
	 * @param dimension the key dimension
	 * @param label the value's label
	 * @param text the text that replaces the dimension's placeholder
	 */
	public record KeyValue(String dimension, String label, String text)
	{
		/**
		 * Creates a value.
		 *
		 * @param dimension the key dimension
		 * @param label the value's label
		 * @param text the text that replaces the dimension's placeholder
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IllegalArgumentException if {@code dimension} or {@code label} are blank
		 */
		public KeyValue
		{
			requireThat(dimension, "dimension").isNotBlank();
			requireThat(label, "label").isNotBlank();
			requireThat(text, "text").isNotNull();
		}
	}
}

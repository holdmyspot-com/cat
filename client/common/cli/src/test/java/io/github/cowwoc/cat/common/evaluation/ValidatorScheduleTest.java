package io.github.cowwoc.cat.common.evaluation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.stream.IntStream;

import org.testng.annotations.Test;

/**
 * Tests {@link ValidatorSchedule}.
 */
public final class ValidatorScheduleTest
{
	/**
	 * Creates the TestNG test instance.
	 */
	public ValidatorScheduleTest()
	{
	}

	/**
	 * Verifies validator groups use every available worker when enough semantic evidence exists.
	 */
	@Test
	public void usesAvailableValidatorWorkers()
	{
		List<String> evidence = IntStream.range(0, 17).mapToObj(index -> "case-" + index).toList();

		List<ValidatorSchedule.Group<String>> groups = ValidatorSchedule.groups(evidence, 16, String::toString);

		requireThat(groups.stream().map(group -> group.evidence().size()).toList(), "groupSizes").isEqualTo(List.of(2,
			1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
	}
}

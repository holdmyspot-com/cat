package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashMap;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests the balanced benchmark distribution of a {@link Contract}.
 */
public final class DistributionTest
{
	/**
	 * The chi-square value that a correct sampler exceeds with probability 0.001, for 1 degree of freedom.
	 */
	private static final double CHI_SQUARE_1_DF = 10.828;

	/**
	 * Creates the test fixture.
	 */
	public DistributionTest()
	{
	}

	/**
	 * Verifies that a category gives every test the same probability, and that the contract has no way to give a
	 * test another weight.
	 */
	@Test
	public void categoryWeightsTestsEqually()
	{
		// Requirement: R-C3
		Contract contract = ContractFixtures.contract();

		requireThat(contract.findCategory("formatting").orElseThrow().testProbability(), "formattingProbability").
			isEqualTo(0.5);
		requireThat(contract.findCategory("safety").orElseThrow().testProbability(), "safetyProbability").
			isEqualTo(1.0);
	}

	/**
	 * Verifies that drawn tests are balanced across a category: with a fixed seed, the counts pass a chi-square test
	 * against equal weights.
	 */
	@Test
	public void drawsBalanceTestsWithinCategory()
	{
		// Requirement: R-C3
		Generator generator = new Generator(ContractFixtures.contract());
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, 99L);
		Map<String, Integer> counts = new HashMap<>();
		int draws = 20_000;
		for (int i = 0; i < draws; ++i)
			counts.merge(generator.draw("formatting", stream).testId(), 1, Integer::sum);

		double expected = draws / 2.0;
		double statistic = 0;
		for (int count : counts.values())
			statistic += (count - expected) * (count - expected) / expected;

		requireThat(counts.keySet(), "tests").isEqualTo(java.util.Set.of("styled-summary", "plain-list"));
		requireThat(statistic, "statistic").isLessThan(CHI_SQUARE_1_DF);
	}

	/**
	 * Verifies that the distribution is frozen: drawing and marking instances leave every weight unchanged, and
	 * each instance records the contract's probability for the value it selected.
	 */
	@Test
	public void weightsStayFrozenWhileDrawing()
	{
		// Requirement: R-C3
		Contract contract = ContractFixtures.contract();
		Generator generator = new Generator(contract);
		DrawStream stream = DrawStream.create(DrawStream.Kind.DEVELOPMENT, 5L);
		for (int i = 0; i < 500; ++i)
		{
			TrialInstance instance = generator.draw("formatting", stream);
			generator.markTargeted(instance);
			TestDefinition test = contract.findCategory("formatting").orElseThrow().tests().stream().
				filter(candidate -> candidate.id().equals(instance.testId())).findFirst().orElseThrow();
			for (int dimension = 0; dimension < test.variations().size(); ++dimension)
			{
				VariationRule rule = test.variations().get(dimension);
				VariationChoice choice = instance.choices().get(dimension);
				double frozen = rule.values().stream().filter(value -> value.label().equals(choice.label())).
					findFirst().orElseThrow().probability();
				requireThat(choice.probability(), "probability").isEqualTo(frozen);
			}
		}

		requireThat(contract, "contract").isEqualTo(ContractFixtures.contract());
		requireThat(contract.findCategory("formatting").orElseThrow().testProbability(), "probability").
			isEqualTo(0.5);
	}
}

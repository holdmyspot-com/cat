package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/** Tests intersection-based paired prompt-test-family promotion. */
public final class PairedPromptTestFamilyEngineTest
{
	/** Creates the test fixture. */
	public PairedPromptTestFamilyEngineTest()
	{
	}

	/** Verifies every family must establish a challenger advantage. */
	@Test
	public void requiresEveryFamily()
	{
		PairedPromptTestFamilyEngine.Decision decision = PairedPromptTestFamilyEngine.decide(List.of(
			new DevelopmentPolicy.FamilyPairs("family-a", 100, 0, 0),
			new DevelopmentPolicy.FamilyPairs("family-b", 0, 1, 99)), 0.05);

		requireThat(decision, "decision").isEqualTo(PairedPromptTestFamilyEngine.Decision.MORE_PAIRS);
	}

	/** Verifies tied pairs cannot establish a challenger advantage. */
	@Test
	public void ignoresTies()
	{
		PairedPromptTestFamilyEngine.Decision decision = PairedPromptTestFamilyEngine.decide(List.of(
			new DevelopmentPolicy.FamilyPairs("family-a", 0, 0, 100)), 0.05);

		requireThat(decision, "decision").isEqualTo(PairedPromptTestFamilyEngine.Decision.MORE_PAIRS);
	}

	/** Verifies strong loss-direction evidence requests regression repair. */
	@Test
	public void identifiesRegression()
	{
		PairedPromptTestFamilyEngine.Decision decision = PairedPromptTestFamilyEngine.decide(List.of(
			new DevelopmentPolicy.FamilyPairs("family-a", 0, 100, 0)), 0.05);

		requireThat(decision, "decision").isEqualTo(PairedPromptTestFamilyEngine.Decision.REPAIR_REGRESSION);
	}
}

package io.github.cowwoc.cat.common.runner;

import org.testng.annotations.Test;

import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertEquals;

/** Tests the closed model-profile registry at the typed boundary. */
public final class SupportedModelProfilesTest
{
	/** Prevents construction of this test owner. */
	public SupportedModelProfilesTest()
	{
	}

	/** Verifies the two optimizer profiles. */
	@Test
	public void acceptsOnlyPublishedProfiles()
	{
		assertEquals(SupportedModelProfiles.requireSupported(ProfileRole.OPTIMIZER_EXECUTOR,
			new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM)),
			new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM));
		assertEquals(SupportedModelProfiles.requireSupported(ProfileRole.OPTIMIZER_EXECUTOR,
			new HarnessModelProfile("haiku", ReasoningEffort.MEDIUM)),
			new HarnessModelProfile("haiku", ReasoningEffort.MEDIUM));
	}

	/** Verifies unsupported model and effort combinations are rejected. */
	@Test
	public void rejectsUnsupportedCombinations()
	{
		assertThrows(IllegalArgumentException.class, () -> SupportedModelProfiles.requireSupported(
			ProfileRole.OPTIMIZER_EXECUTOR, new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.HIGH)));
		assertThrows(IllegalArgumentException.class, () -> SupportedModelProfiles.requireSupported(
			ProfileRole.SEMANTIC_EVALUATOR, new HarnessModelProfile("haiku", ReasoningEffort.MEDIUM)));
	}
}

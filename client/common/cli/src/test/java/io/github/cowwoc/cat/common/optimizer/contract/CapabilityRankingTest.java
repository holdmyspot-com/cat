package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link CapabilityRanking}.
 */
public final class CapabilityRankingTest
{
	private static final CapabilityRanking CODEX = new CapabilityRanking(List.of("astra", "sol", "luna"),
		"https://developers.openai.com/api/docs/models, read 2026-10-04");

	/**
	 * Creates the test.
	 */
	public CapabilityRankingTest()
	{
	}

	/**
	 * Verifies that a model's family is its name without its version, and that a name without a version is its own
	 * family.
	 */
	@Test
	public void familyIsNameWithoutVersion()
	{
		// Requirement: R-E22
		requireThat(CapabilityRanking.family("gpt-6.1-sol"), "versioned").isEqualTo("sol");
		requireThat(CapabilityRanking.family("gpt-6-luna"), "majorOnly").isEqualTo("luna");
		requireThat(CapabilityRanking.family("opus"), "alias").isEqualTo("opus");
	}

	/**
	 * Verifies that models rank by family first and, within a family, an older version below a newer one.
	 */
	@Test
	public void familyThenVersionOrder()
	{
		// Requirement: R-E22
		requireThat(CODEX.compare("gpt-6-sol", "gpt-6.1-sol"), "olderVersion").isNegative();
		requireThat(CODEX.compare("gpt-6.1-sol", "gpt-6-sol"), "newerVersion").isPositive();
		requireThat(CODEX.compare("gpt-6.1-luna", "gpt-6-sol"), "familyFirst").isNegative();
		requireThat(CODEX.compare("gpt-6-astra", "gpt-6.1-sol"), "astraAboveSol").isPositive();
		requireThat(CODEX.compare("gpt-6-sol", "gpt-6-sol"), "same").isZero();
		CapabilityRanking claude = new CapabilityRanking(List.of("fable", "opus", "sonnet", "haiku"),
			"https://platform.claude.com/docs/en/models/overview, read 2026-10-04");
		requireThat(claude.compare("haiku", "sonnet"), "haikuBelowSonnet").isNegative();
		requireThat(claude.compare("fable", "opus"), "fableAboveOpus").isPositive();
	}

	/**
	 * Verifies that an inventory model whose family the ranking does not list is named, once, and cannot be compared.
	 */
	@Test
	public void unrankedFamily()
	{
		// Requirement: R-E22
		ProfileCatalog inventory = new ProfileCatalog("codex debug models", List.of(
			new TrialProfile("gpt-6-terra", "low"), new TrialProfile("gpt-6-terra", "high"),
			new TrialProfile("gpt-6-sol", "low"), new TrialProfile("gpt-6.1-nova", "low")));
		requireThat(CODEX.unranked(inventory), "unranked").isEqualTo(List.of("gpt-6-terra", "gpt-6.1-nova"));
		requireThat(CODEX.isRanked("gpt-6-terra"), "terra").isFalse();
		requireThat(CODEX.isRanked("gpt-6.1-sol"), "sol").isTrue();
		try
		{
			CODEX.compare("gpt-6-terra", "gpt-6-sol");
			throw new AssertionError("An unranked model was compared");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains("gpt-6-terra");
		}
	}
}

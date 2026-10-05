package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileBinding;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;

import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests how {@link ProfileEvidence} finds the binding of each requirement's source.
 */
public final class ProfileEvidenceTest
{
	private static final ProfileEvidence EVIDENCE = new ProfileEvidence(ContractFixtures.TWO_PROFILES,
		ContractFixtures.ranking(ContractFixtures.TWO_PROFILES), Map.of(
		"skills/a/SKILL.md", ContractFixtures.BIG,
		"skills/b/SKILL.md", ContractFixtures.SMALL));

	/**
	 * Creates the test.
	 */
	public ProfileEvidenceTest()
	{
	}

	/**
	 * Returns a requirement with the given source.
	 *
	 * @param source the requirement's source
	 * @return the requirement
	 */
	private static Requirement requirement(String source)
	{
		return new Requirement("requirement", "Follows the file.", source, null, List.of("file_ignored"));
	}

	/**
	 * Verifies that a source that names a link and its target binds what the target binds, once, even when the link's
	 * own path binds a different profile.
	 */
	@Test
	public void linkBindsWhatItsTargetBinds()
	{
		// Requirement: R-C10
		String source = "skills/a/SKILL.md -> skills/b/SKILL.md";
		requireThat(EVIDENCE.bindingsOf(List.of(requirement(source))), "bindings").
			isEqualTo(List.of(new ProfileBinding(source, ContractFixtures.SMALL)));
	}

	/**
	 * Verifies that a source path is compared after normalization, and that a source that binds nothing yields no
	 * binding.
	 */
	@Test
	public void sourcePathsAreNormalized()
	{
		// Requirement: R-C10
		requireThat(EVIDENCE.bindingsOf(List.of(requirement("./skills/b/SKILL.md"), requirement("rules/none.md"))),
			"bindings").isEqualTo(List.of(new ProfileBinding("./skills/b/SKILL.md", ContractFixtures.SMALL)));
	}
}

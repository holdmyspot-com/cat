package io.github.cowwoc.cat.common.optimizer.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.AssertionKind;
import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.MappedAssertion;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileBinding;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.RiskRank;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfiles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests the profile-set computation of {@link TrialProfiles}.
 */
public final class ProfileSetTest
{
	private static final ProfileCatalog INVENTORY = new ProfileCatalog("codex debug models", List.of(
		new TrialProfile("big", "low"), new TrialProfile("big", "high"), new TrialProfile("small", "low"),
		new TrialProfile("small", "high"), new TrialProfile("plain", "")));

	/**
	 * Creates the test fixture.
	 */
	public ProfileSetTest()
	{
	}

	/**
	 * Returns a prompt test that checks requirements, each with one assertion named after it.
	 *
	 * @param id the test's identifier
	 * @param requirementIds the requirements that the test checks
	 * @return the test
	 */
	private static PromptTest test(String id, String... requirementIds)
	{
		List<MappedAssertion> assertions = new ArrayList<>();
		for (String requirementId : requirementIds)
			assertions.add(ContractFixtures.judged(requirementId, AssertionKind.EFFECT, "Complies.", requirementId));
		return new PromptTest(id, new RiskRank(1), List.of(requirementIds), List.of(), "Always.", List.of("Do it."),
			Duration.ofMinutes(1), "Complies.", List.of(), List.of(), List.of(), List.of(), assertions, List.of(), List.of());
	}

	/**
	 * Returns the requirements of the tests: {@code skill} from {@code skills/a/SKILL.md}, {@code agent} from
	 * {@code agents/b.md} and {@code rule} from {@code CLAUDE.md}.
	 *
	 * @return the requirements
	 */
	private static List<Requirement> requirements()
	{
		return List.of(new Requirement("skill", "Follows the skill.", "skills/a/SKILL.md", null, List.of("skill_ignored")),
			new Requirement("agent", "Follows the agent.", "agents/b.md", null, List.of("agent_ignored")),
			new Requirement("rule", "Follows the rule.", "CLAUDE.md", null, List.of("rule_ignored")));
	}

	/**
	 * Returns the profiles of a test's cells.
	 *
	 * @param profiles the computed trial profiles
	 * @param test the test
	 * @return the profiles' labels, in cell order
	 */
	private static List<String> labels(TrialProfiles profiles, String test)
	{
		return profiles.cellsOf(test).stream().map(cell -> cell.profile().label()).toList();
	}

	/**
	 * Verifies that a test whose files all bind the same complete profile has one cell under it, named after the
	 * test.
	 */
	@Test
	public void boundFileGivesOneCell()
	{
		// Requirement: R-C10
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY),
			List.of(new ProfileBinding("skills/a/SKILL.md", new TrialProfile("small", "high"))), List.of(), List.of(),
			requirements(), List.of(test("bound", "skill")));
		requireThat(profiles.cells(), "cells").isEqualTo(List.of(new Cell("bound", "bound",
			new TrialProfile("small", "high"))));
	}

	/**
	 * Verifies that a file that binds only its model fixes the model and leaves the effort open.
	 */
	@Test
	public void partialBindingFixesOneDimension()
	{
		// Requirement: R-C10
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY),
			List.of(new ProfileBinding("skills/a/SKILL.md", new TrialProfile("big", ""))), List.of(), List.of(),
			requirements(), List.of(test("partial", "skill")));
		requireThat(labels(profiles, "partial"), "labels").isEqualTo(List.of("big/low", "big/high"));
		requireThat(profiles.cells().getFirst().id(), "id").isEqualTo("partial@big/low");

		TrialProfiles byEffort = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY),
			List.of(new ProfileBinding("skills/a/SKILL.md", new TrialProfile("", "high"))), List.of(), List.of(),
			requirements(), List.of(test("partial", "skill")));
		requireThat(labels(byEffort, "partial"), "labels").isEqualTo(List.of("big/high", "small/high"));
	}

	/**
	 * Verifies that a test whose files bind nothing has one cell per inventory profile, including a model whose
	 * effort support is undocumented.
	 */
	@Test
	public void unboundFileGivesInventory()
	{
		// Requirement: R-C10
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY), List.of(),
			List.of(), List.of(), requirements(),
			List.of(test("open", "rule")));
		requireThat(labels(profiles, "open"), "labels").isEqualTo(List.of("big/low", "big/high", "small/low",
			"small/high", "plain/default"));
	}

	/**
	 * Verifies that each prompt test's profile set comes from the requirements that its own assertions check: a test
	 * of a bound file has one cell while a test of an unbound file in the same contract has a cell per profile.
	 */
	@Test
	public void eachTestHasItsOwnProfileSet()
	{
		// Requirement: R-C10
		// Requirement: R-C11
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY),
			List.of(new ProfileBinding("skills/a/SKILL.md", new TrialProfile("small", "high"))), List.of(), List.of(),
			requirements(), List.of(test("bound", "skill"), test("open", "rule")));
		requireThat(profiles.cellsOf("bound"), "bound").isEqualTo(List.of(new Cell("bound", "bound",
			new TrialProfile("small", "high"))));
		requireThat(profiles.cellsOf("open").stream().map(Cell::id).toList(), "open").isEqualTo(List.of(
			"open@big/low", "open@big/high", "open@small/low", "open@small/high", "open@plain/default"));
	}

	/**
	 * Verifies that a dimension is fixed only when every supplying file binds it to the same value: a file that
	 * leaves it open, or a file that binds another value, opens it.
	 */
	@Test
	public void dimensionNeedsEveryFile()
	{
		// Requirement: R-C10
		List<ProfileBinding> oneBinds = List.of(new ProfileBinding("skills/a/SKILL.md",
			new TrialProfile("small", "high")));
		TrialProfiles mixed = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY), oneBinds,
			List.of(), List.of(), requirements(),
			List.of(test("mixed", "skill", "rule")));
		requireThat(mixed.cellsOf("mixed").size(), "cells").isEqualTo(INVENTORY.profiles().size());

		List<ProfileBinding> disagree = List.of(
			new ProfileBinding("skills/a/SKILL.md", new TrialProfile("small", "high")),
			new ProfileBinding("agents/b.md", new TrialProfile("big", "high")));
		TrialProfiles efforts = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY), disagree,
			List.of(), List.of(), requirements(),
			List.of(test("efforts", "skill", "agent")));
		requireThat(labels(efforts, "efforts"), "labels").isEqualTo(List.of("big/high", "small/high"));
	}

	/**
	 * Verifies that a binding to a value outside the inventory is used as written.
	 */
	@Test
	public void bindingOutsideInventoryIsUsedAsWritten()
	{
		// Requirement: R-C10
		TrialProfile custom = new TrialProfile("custom", "");
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY),
			List.of(new ProfileBinding("agents/b.md", custom)), List.of(), List.of(), requirements(),
			List.of(test("custom", "agent")));
		requireThat(labels(profiles, "custom"), "labels").isEqualTo(List.of("custom/default"));
		requireThat(INVENTORY.isOutside(custom), "isOutside").isTrue();
	}

	/**
	 * Verifies that a restriction narrows every test's profile set and is recorded.
	 */
	@Test
	public void restrictionNarrowsSet()
	{
		// Requirement: R-C10
		List<TrialProfile> restriction = List.of(new TrialProfile("small", ""), new TrialProfile("big", "high"));
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY), List.of(),
			restriction, List.of(), requirements(),
			List.of(test("open", "rule")));
		requireThat(labels(profiles, "open"), "labels").isEqualTo(List.of("big/high", "small/low", "small/high"));
		requireThat(profiles.restriction(), "restriction").isEqualTo(restriction);
	}

	/**
	 * Verifies that a restriction that matches no inventory profile, or that leaves a test without a profile, is
	 * rejected with the reason.
	 */
	@Test
	public void restrictionMustLeaveProfiles()
	{
		// Requirement: R-C10
		String unknown = null;
		try
		{
			TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY), List.of(),
				List.of(new TrialProfile("huge", "")), List.of(), requirements(),
				List.of(test("open", "rule")));
		}
		catch (IllegalArgumentException e)
		{
			unknown = e.getMessage();
		}
		requireThat(unknown, "unknown").contains("huge/default").contains("matches no profile");

		String empty = null;
		try
		{
			TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY),
				List.of(new ProfileBinding("skills/a/SKILL.md", new TrialProfile("big", "low"))),
				List.of(new TrialProfile("small", "")), List.of(), requirements(), List.of(test("bound", "skill")));
		}
		catch (IllegalArgumentException e)
		{
			empty = e.getMessage();
		}
		requireThat(empty, "empty").contains("'bound'").contains("big/low");
	}

	/**
	 * Returns the message of the {@code IllegalArgumentException} that an action throws.
	 *
	 * @param action the action
	 * @return the exception's message
	 * @throws AssertionError if the action does not throw an {@code IllegalArgumentException}
	 */
	private static String rejection(Runnable action)
	{
		try
		{
			action.run();
		}
		catch (IllegalArgumentException e)
		{
			return e.getMessage();
		}
		throw new AssertionError("The action was accepted");
	}

	/**
	 * Verifies that a described certification scope is refused when a described profile is outside the inventory or
	 * when it leaves a test of several cells without a cell, and is recorded otherwise.
	 */
	@Test
	public void describedScopeMustSelectInventoryCells()
	{
		// Requirement: R-C15
		CapabilityRanking ranking = ContractFixtures.ranking(INVENTORY);
		requireThat(rejection(() -> TrialProfiles.compute(INVENTORY, ranking, List.of(), List.of(),
			List.of(new TrialProfile("huge", "low")), requirements(), List.of(test("open", "rule")))),
			"outside").contains("huge/low").contains("matches no profile");

		List<ProfileBinding> big = List.of(new ProfileBinding("skills/a/SKILL.md", new TrialProfile("big", "")));
		requireThat(rejection(() -> TrialProfiles.compute(INVENTORY, ranking, big, List.of(),
			List.of(new TrialProfile("small", "")), requirements(), List.of(test("partial", "skill")))),
			"noCell").contains("'partial'").contains("small/default");

		List<TrialProfile> described = List.of(new TrialProfile("big", "high"));
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ranking, big, List.of(), described, requirements(),
			List.of(test("partial", "skill")));
		requireThat(profiles.described(), "described").isEqualTo(described);
		requireThat(profiles.withScope(CertificationScope.DESCRIBED).scope(), "scope").
			isEqualTo(CertificationScope.DESCRIBED);
	}

	/**
	 * Verifies that choosing a scope is refused while an inventory model's family is unranked, naming the model, and
	 * that choosing the described scope is refused when the user described no cell.
	 */
	@Test
	public void scopeNeedsRankingAndDescription()
	{
		// Requirement: R-E22
		// Requirement: R-C15
		CapabilityRanking partial = new CapabilityRanking(List.of("big", "small"), "the user's revision feedback");
		TrialProfiles unranked = TrialProfiles.compute(INVENTORY, partial, List.of(), List.of(), List.of(),
			requirements(), List.of(test("open", "rule")));
		requireThat(rejection(() -> unranked.withScope(CertificationScope.LATEST_VERSIONS)), "unranked").
			contains("plain");

		TrialProfiles ranked = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY), List.of(),
			List.of(), List.of(), requirements(), List.of(test("open", "rule")));
		requireThat(rejection(() -> ranked.withScope(CertificationScope.DESCRIBED)), "undescribed").
			contains("described");
		requireThat(ranked.withScope(CertificationScope.WEAKEST_CELL).scope(), "weakest").
			isEqualTo(CertificationScope.WEAKEST_CELL);
	}
}

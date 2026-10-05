package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
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
	 * Returns a category whose one test checks requirements.
	 *
	 * @param id the category's identifier
	 * @param requirementIds the requirements that the test checks
	 * @return the category
	 */
	private static Category category(String id, String... requirementIds)
	{
		return new Category(id, "Needs its own claim.", new RiskRank(1), List.of(new TestDefinition(id + "-test",
			List.of(requirementIds), List.of(), "Always.", List.of("Do it."), Duration.ofMinutes(1), "Complies.",
				List.of(),
			List.of(), List.of(), List.of(), List.of(), List.of())));
	}

	/**
	 * Returns the requirements of the tests: {@code skill} from {@code skills/a/SKILL.md}, {@code agent} from
	 * {@code agents/b.md}, {@code rule} from {@code CLAUDE.md} and {@code core} from CAT.
	 *
	 * @return the requirements
	 */
	private static List<Requirement> requirements()
	{
		return List.of(new Requirement("skill", "Follows the skill.", "skills/a/SKILL.md", null, List.of("skill-ignored")),
			new Requirement("agent", "Follows the agent.", "agents/b.md", null, List.of("agent-ignored")),
			new Requirement("rule", "Follows the rule.", "CLAUDE.md", null, List.of("rule-ignored")),
			new Requirement("core", "Does what the turn asked.", "CAT", null, List.of("incorrect-result")));
	}

	/**
	 * Returns the profiles of a category's cells.
	 *
	 * @param profiles the computed trial profiles
	 * @param category the category
	 * @return the profiles' labels, in cell order
	 */
	private static List<String> labels(TrialProfiles profiles, String category)
	{
		return profiles.cellsOf(category).stream().map(cell -> cell.profile().label()).toList();
	}

	/**
	 * Verifies that a category whose files all bind the same complete profile has one cell under it, named after the
	 * category.
	 */
	@Test
	public void boundFileGivesOneCell()
	{
		// Requirement: R-C10
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY),
			List.of(new ProfileBinding("skills/a/SKILL.md", new TrialProfile("small", "high"))), List.of(), List.of(),
			requirements(), List.of(category("bound", "skill")), Set.of());
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
			requirements(), List.of(category("partial", "skill")), Set.of());
		requireThat(labels(profiles, "partial"), "labels").isEqualTo(List.of("big/low", "big/high"));
		requireThat(profiles.cells().getFirst().id(), "id").isEqualTo("partial@big/low");

		TrialProfiles byEffort = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY),
			List.of(new ProfileBinding("skills/a/SKILL.md", new TrialProfile("", "high"))), List.of(), List.of(),
			requirements(), List.of(category("partial", "skill")), Set.of());
		requireThat(labels(byEffort, "partial"), "labels").isEqualTo(List.of("big/high", "small/high"));
	}

	/**
	 * Verifies that a category whose files bind nothing has one cell per inventory profile, including a model whose
	 * effort support is undocumented.
	 */
	@Test
	public void unboundFileGivesInventory()
	{
		// Requirement: R-C10
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY), List.of(),
			List.of(), List.of(), requirements(),
			List.of(category("open", "rule")), Set.of());
		requireThat(labels(profiles, "open"), "labels").isEqualTo(List.of("big/low", "big/high", "small/low",
			"small/high", "plain/default"));
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
			List.of(category("mixed", "skill", "rule")), Set.of());
		requireThat(mixed.cellsOf("mixed").size(), "cells").isEqualTo(INVENTORY.profiles().size());

		List<ProfileBinding> disagree = List.of(
			new ProfileBinding("skills/a/SKILL.md", new TrialProfile("small", "high")),
			new ProfileBinding("agents/b.md", new TrialProfile("big", "high")));
		TrialProfiles efforts = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY), disagree,
			List.of(), List.of(), requirements(),
			List.of(category("efforts", "skill", "agent")), Set.of());
		requireThat(labels(efforts, "efforts"), "labels").isEqualTo(List.of("big/high", "small/high"));
	}

	/**
	 * Verifies that the excluded core-task requirement does not open a bound category's profile set.
	 */
	@Test
	public void excludedRequirementIsIgnored()
	{
		// Requirement: R-C10
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY),
			List.of(new ProfileBinding("skills/a/SKILL.md", new TrialProfile("big", "low"))), List.of(), List.of(),
			requirements(), List.of(category("core", "skill", "core")), Set.of("core"));
		requireThat(labels(profiles, "core"), "labels").isEqualTo(List.of("big/low"));
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
			List.of(category("custom", "agent")), Set.of());
		requireThat(labels(profiles, "custom"), "labels").isEqualTo(List.of("custom/default"));
		requireThat(INVENTORY.isOutside(custom), "isOutside").isTrue();
	}

	/**
	 * Verifies that a restriction narrows every category's profile set and is recorded.
	 */
	@Test
	public void restrictionNarrowsSet()
	{
		// Requirement: R-C10
		List<TrialProfile> restriction = List.of(new TrialProfile("small", ""), new TrialProfile("big", "high"));
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY), List.of(),
			restriction, List.of(), requirements(),
			List.of(category("open", "rule")), Set.of());
		requireThat(labels(profiles, "open"), "labels").isEqualTo(List.of("big/high", "small/low", "small/high"));
		requireThat(profiles.restriction(), "restriction").isEqualTo(restriction);
	}

	/**
	 * Verifies that a restriction that matches no inventory profile, or that leaves a category without a profile, is
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
				List.of(category("open", "rule")), Set.of());
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
				List.of(new TrialProfile("small", "")), List.of(), requirements(), List.of(category("bound", "skill")),
					Set.of());
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
	 * when it leaves a category of several cells without a cell, and is recorded otherwise.
	 */
	@Test
	public void describedScopeMustSelectInventoryCells()
	{
		// Requirement: R-C15
		CapabilityRanking ranking = ContractFixtures.ranking(INVENTORY);
		requireThat(rejection(() -> TrialProfiles.compute(INVENTORY, ranking, List.of(), List.of(),
			List.of(new TrialProfile("huge", "low")), requirements(), List.of(category("open", "rule")), Set.of())),
			"outside").contains("huge/low").contains("matches no profile");

		List<ProfileBinding> big = List.of(new ProfileBinding("skills/a/SKILL.md", new TrialProfile("big", "")));
		requireThat(rejection(() -> TrialProfiles.compute(INVENTORY, ranking, big, List.of(),
			List.of(new TrialProfile("small", "")), requirements(), List.of(category("partial", "skill")), Set.of())),
			"noCell").contains("'partial'").contains("small/default");

		List<TrialProfile> described = List.of(new TrialProfile("big", "high"));
		TrialProfiles profiles = TrialProfiles.compute(INVENTORY, ranking, big, List.of(), described, requirements(),
			List.of(category("partial", "skill")), Set.of());
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
			requirements(), List.of(category("open", "rule")), Set.of());
		requireThat(rejection(() -> unranked.withScope(CertificationScope.LATEST_VERSIONS)), "unranked").
			contains("plain");

		TrialProfiles ranked = TrialProfiles.compute(INVENTORY, ContractFixtures.ranking(INVENTORY), List.of(),
			List.of(), List.of(), requirements(), List.of(category("open", "rule")), Set.of());
		requireThat(rejection(() -> ranked.withScope(CertificationScope.DESCRIBED)), "undescribed").
			contains("described");
		requireThat(ranked.withScope(CertificationScope.WEAKEST_CELL).scope(), "weakest").
			isEqualTo(CertificationScope.WEAKEST_CELL);
	}
}

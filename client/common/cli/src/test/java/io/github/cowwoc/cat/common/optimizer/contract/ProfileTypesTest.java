package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests the rules that the trial-profile value types enforce: {@link TrialProfile}, {@link ProfileBinding},
 * {@link ProfileCatalog} and {@link TrialProfiles}.
 */
public final class ProfileTypesTest
{
	private static final ProfileCatalog INVENTORY = new ProfileCatalog("codex debug models", List.of(
		new TrialProfile("big", "low"), new TrialProfile("big", "high"), new TrialProfile("small", "")));
	private static final TrialProfile BIG_LOW = new TrialProfile("big", "low");

	/**
	 * Creates the test fixture.
	 */
	public ProfileTypesTest()
	{
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
	 * Returns a cell of the category {@code formatting}.
	 *
	 * @param id the cell's identifier
	 * @return the cell
	 */
	private static Cell cell(String id)
	{
		return new Cell(id, "formatting", BIG_LOW);
	}

	/**
	 * Verifies that a model or effort containing whitespace or {@code /} is rejected, because {@code /} separates the
	 * two in a label and a cell identifier.
	 */
	@Test
	public void profileValuesAreSingleTokens()
	{
		// Requirement: R-C10
		requireThat(rejection(() -> new TrialProfile("big/low", "")), "modelSlash").contains("model");
		requireThat(rejection(() -> new TrialProfile("big model", "")), "modelSpace").contains("model");
		requireThat(rejection(() -> new TrialProfile("big", "x/y")), "effortSlash").contains("effort");
		requireThat(rejection(() -> new TrialProfile("big", "very\thigh")), "effortSpace").contains("effort");
	}

	/**
	 * Verifies that a binding must bind a model, an effort or both.
	 */
	@Test
	public void bindingMustBindADimension()
	{
		// Requirement: R-C10
		requireThat(rejection(() -> new ProfileBinding("skills/a/SKILL.md", TrialProfile.HARNESS_DEFAULT)),
			"unbound").contains("isBound");
	}

	/**
	 * Verifies that an inventory lists each profile once and names a model in each.
	 */
	@Test
	public void inventoryListsDistinctProfiles()
	{
		// Requirement: R-C10
		requireThat(rejection(() -> new ProfileCatalog("source", List.of(BIG_LOW, BIG_LOW))), "twice").
			contains("big/low").contains("twice");
		requireThat(rejection(() -> new ProfileCatalog("source", List.of(new TrialProfile("", "low")))),
			"noModel").contains("model");
	}

	/**
	 * Verifies that confirmed trial profiles bind each file once, list each cell once, and restrict only by a pattern
	 * that names a dimension and matches the inventory.
	 */
	@Test
	public void trialProfilesAreConsistent()
	{
		// Requirement: R-C10
		ProfileBinding binding = new ProfileBinding("agents/b.md", BIG_LOW);
		requireThat(rejection(() -> new TrialProfiles(INVENTORY, List.of(binding, binding), List.of(),
			List.of(cell("formatting")))), "boundTwice").contains("agents/b.md");
		requireThat(rejection(() -> new TrialProfiles(INVENTORY, List.of(), List.of(),
			List.of(cell("formatting"), cell("formatting")))), "cellTwice").contains("'formatting'");
		requireThat(rejection(() -> new TrialProfiles(INVENTORY, List.of(), List.of(new TrialProfile("huge", "")),
			List.of(cell("formatting")))), "outside").contains("huge/default");
		requireThat(rejection(() -> new TrialProfiles(INVENTORY, List.of(), List.of(TrialProfile.HARNESS_DEFAULT),
			List.of(cell("formatting")))), "unbound").contains("must name a model, an effort or both");
	}

	/**
	 * Verifies that the rendered profiles name the restriction that narrowed them.
	 */
	@Test
	public void descriptionNamesTheRestriction()
	{
		// Requirement: R-C10
		TrialProfiles profiles = new TrialProfiles(INVENTORY, List.of(), List.of(new TrialProfile("", "low")),
			List.of(cell("formatting")));
		requireThat(profiles.describe(), "describe").contains("Profile restriction: default/low");
	}

	/**
	 * Verifies that a test that checks a requirement the contract does not list is rejected, rather than leaving the
	 * category's profile set without that file.
	 */
	@Test
	public void unknownRequirementIsRejected()
	{
		// Requirement: R-C10
		Category category = new Category("formatting", "Needs its own claim.", new RiskRank(1),
			List.of(new TestDefinition("formatting-test", List.of("missing"), "Always.", List.of("Do it."),
				Duration.ofMinutes(1), "Complies.", List.of(), List.of(), List.of(), List.of(), List.of())));
		List<Requirement> requirements = List.of(new Requirement("skill", "Follows the skill.",
			"skills/a/SKILL.md"));
		requireThat(rejection(() -> TrialProfiles.compute(INVENTORY, List.of(), List.of(), requirements,
			List.of(category), Set.of())), "unknown").contains("'missing'");
	}
}

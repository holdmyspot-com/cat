package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests the invariants of the contract model.
 */
public final class ContractTest
{
	/**
	 * The failure behaviors of a requirement that a test builds.
	 */
	private static final List<String> BEHAVIORS = List.of("skipped_question");

	/**
	 * Creates the test fixture.
	 */
	public ContractTest()
	{
	}

	/**
	 * Builds a test definition that is valid apart from the values under test.
	 *
	 * @param id the identifier
	 * @param turns the turns
	 * @param variations the variation rules
	 * @return the definition
	 */
	private static TestDefinition test(String id, List<String> turns, List<VariationRule> variations)
	{
		return new TestDefinition(id, List.of("r1"), List.of(), "Always.", turns, Duration.ofMinutes(1), "Complies.",
			List.of(), variations, List.of(), List.of(), List.of(), List.of());
	}

	/**
	 * Verifies that a judge profile cannot omit or blank any of its five fields, and that its role profile must bind
	 * a dimension.
	 */
	@Test
	public void judgeProfileRejectsBlankFields()
	{
		// Requirement: R-C5
		JudgeProfile valid = ContractFixtures.judge();
		List<Runnable> blanks = List.of(
			() -> new JudgeProfile(TrialProfile.HARNESS_DEFAULT, valid.prompt(), valid.rubric(), valid.decisionRule(),
				valid.resultSchema()),
			() -> new JudgeProfile(valid.profile(), "", valid.rubric(), valid.decisionRule(), valid.resultSchema()),
			() -> new JudgeProfile(valid.profile(), valid.prompt(), "", valid.decisionRule(), valid.resultSchema()),
			() -> new JudgeProfile(valid.profile(), valid.prompt(), valid.rubric(), "", valid.resultSchema()),
			() -> new JudgeProfile(valid.profile(), valid.prompt(), valid.rubric(), valid.decisionRule(), ""));
		for (Runnable blank : blanks)
		{
			boolean rejected = false;
			try
			{
				blank.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that a judge profile with the same fields equals another, so a frozen profile can be compared with
	 * the one a later trial uses.
	 */
	@Test
	public void judgeProfilesCompareByValue()
	{
		// Requirement: R-C5
		JudgeProfile profile = ContractFixtures.judge();
		JudgeProfile changedRubric = new JudgeProfile(profile.profile(), profile.prompt(), "A different rubric.",
			profile.decisionRule(), profile.resultSchema());

		requireThat(ContractFixtures.judge(), "same").isEqualTo(profile);
		requireThat(changedRubric, "changedRubric").isNotEqualTo(profile);
	}

	/**
	 * Verifies that a contract keeps the values it was built with when the caller later changes its lists, and
	 * that it offers no way to change them.
	 */
	@Test
	public void contractIsImmutable()
	{
		// Requirement: R-C3
		List<Requirement> requirements = new ArrayList<>(ContractFixtures.contract().requirements());
		Contract original = ContractFixtures.contract();
		Contract contract = new Contract(requirements, original.categories(), original.profiles(), original.judge(),
			original.fixtures(), original.limitations(), original.salt());

		requirements.clear();

		requireThat(contract.requirements().size(), "requirements.size()").isEqualTo(3);
		boolean modified = true;
		try
		{
			contract.categories().clear();
		}
		catch (UnsupportedOperationException _)
		{
			modified = false;
		}
		requireThat(modified, "modified").isFalse();
	}

	/**
	 * Verifies that a contract rejects duplicate identifiers and a test that names an unlisted requirement.
	 */
	@Test
	public void contractRejectsInconsistentReferences()
	{
		// Requirement: R-C3
		Contract valid = ContractFixtures.contract();
		Category formatting = valid.categories().get(0);
		Category safety = valid.categories().get(1);
		Requirement r1 = valid.requirements().get(0);
		TestDefinition orphan = new TestDefinition("orphan", List.of("r99"), List.of(), "Always.", List.of("Hello."),
			Duration.ofMinutes(1), "Complies.", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
		Category orphaned = new Category("orphaned", "Why.", new RiskRank(3), List.of(orphan));
		TestDefinition duplicate = new TestDefinition("plain_list", List.of("r1"), List.of(), "Always.", List.of("Hello."),
			Duration.ofMinutes(1), "Complies.", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
		Category duplicating = new Category("duplicating", "Why.", new RiskRank(3), List.of(duplicate));
		List<Runnable> inconsistent = List.of(
			() -> new Contract(List.of(r1, r1), valid.categories(), valid.profiles(), valid.judge(), valid.fixtures(),
				valid.limitations(), valid.salt()),
			() -> new Contract(valid.requirements(), List.of(formatting, formatting), cells(formatting),
				valid.judge(), valid.fixtures(), valid.limitations(), valid.salt()),
			() -> new Contract(valid.requirements(), List.of(formatting, safety, orphaned),
				cells(formatting, safety, orphaned), valid.judge(), valid.fixtures(), valid.limitations(), valid.salt()),
			() -> new Contract(valid.requirements(), List.of(formatting, safety, duplicating),
				cells(formatting, safety, duplicating), valid.judge(), valid.fixtures(), valid.limitations(), valid.salt()),
			() -> new Contract(List.of(), valid.categories(), valid.profiles(), valid.judge(), valid.fixtures(),
				valid.limitations(), valid.salt()),
			() -> new Contract(valid.requirements(), List.of(), valid.profiles(), valid.judge(), valid.fixtures(),
				valid.limitations(), valid.salt()));
		for (Runnable build : inconsistent)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that a requirement lists at least one failure behavior, each a distinct identifier of lowercase words
	 * joined by underscores other than {@code other}, and that CAT's core-task requirement lists its own.
	 */
	@Test
	public void requirementListsItsFailureBehaviors()
	{
		// Requirement: R-C18
		requireThat(Requirement.CORE_TASK.behaviors(), "coreTask").isNotEmpty();
		requireThat(new Requirement("r", "Asks first.", "CLAUDE.md", null, BEHAVIORS).behaviors(), "behaviors").
			isEqualTo(BEHAVIORS);
		List<List<String>> invalid = List.of(List.of(), List.of("skipped_question", "skipped_question"),
			List.of(Requirement.OTHER_BEHAVIOR), List.of("Skipped question"), List.of("skipped-question"));
		for (List<String> behaviors : invalid)
		{
			boolean rejected = false;
			try
			{
				new Requirement("r", "Asks first.", "CLAUDE.md", null, behaviors);
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").withContext(behaviors, "behaviors").isTrue();
		}
	}

	/**
	 * Verifies that a contract refuses a deterministic check whose behavior its requirement does not list, whether
	 * the check is kept or discarded, and accepts a check that names a listed behavior.
	 */
	@Test
	public void checkNamesAListedBehavior()
	{
		// Requirement: R-C18
		Contract valid = ContractFixtures.contract();
		ControlEvidence asks = new ControlEvidence(List.of(List.of("Delete it?")), List.of(), List.of(), Map.of());
		ControlEvidence deletes = new ControlEvidence(List.of(List.of("Deleted.")), List.of(), List.of(), Map.of());
		CheckCriterion criterion = new CheckCriterion.PatternMatch(1, "\\?");
		DeterministicCheck listed = new DeterministicCheck("r2", "deleted_without_asking", criterion, asks, deletes);
		DeterministicCheck unlisted = new DeterministicCheck("r2", "wrong_tone", criterion, asks, deletes);

		requireThat(withDeletionCheck(valid, List.of(listed), List.of()).findTest("delete_directory").orElseThrow().
			checks(), "checks").isEqualTo(List.of(listed));
		List<Runnable> invalid = List.of(
			() -> withDeletionCheck(valid, List.of(unlisted), List.of()),
			() -> withDeletionCheck(valid, List.of(), List.of(new DiscardedCheck(unlisted, "It failed a control."))));
		for (Runnable build : invalid)
		{
			String message = "";
			try
			{
				build.run();
			}
			catch (IllegalArgumentException e)
			{
				message = e.getMessage();
			}
			requireThat(message, "message").contains("wrong_tone").contains("r2");
		}
	}

	/**
	 * Returns a contract whose deletion test has the given checks.
	 *
	 * @param valid a valid contract that has the {@code delete_directory} test of {@link ContractFixtures#contract()}
	 * @param checks the test's checks
	 * @param discarded the test's discarded checks
	 * @return the contract
	 */
	private static Contract withDeletionCheck(Contract valid, List<DeterministicCheck> checks,
		List<DiscardedCheck> discarded)
	{
		TestDefinition deletion = valid.findTest("delete_directory").orElseThrow();
		TestDefinition checked = new TestDefinition(deletion.id(), deletion.requirementIds(), deletion.absentTriggers(),
			deletion.applicability(), deletion.turns(), deletion.timeLimit(), deletion.expectedBehaviour(),
			deletion.uncoveredInteractions(), deletion.variations(), deletion.keyDimensions(), checks, discarded,
			deletion.regressionScenarios());
		Category safety = valid.categories().get(1);
		Category withCheck = new Category(safety.id(), safety.rationale(), safety.riskRank(), List.of(checked));
		return new Contract(valid.requirements(), List.of(valid.categories().get(0), withCheck), valid.profiles(),
			valid.judge(), valid.fixtures(), valid.limitations(), valid.salt());
	}

	/**
	 * Verifies that a contract lists CAT's core-task requirement and that every test checks it.
	 */
	@Test
	public void everyTestChecksTheCoreTask()
	{
		// Requirement: R-C8
		Contract valid = ContractFixtures.contract();
		requireThat(valid.requirements(), "requirements").contains(Requirement.CORE_TASK);
		requireThat(Requirement.CORE_TASK.source(), "source").isEqualTo("CAT");
		Category formatting = valid.categories().get(0);
		TestDefinition unchecked = new TestDefinition("unchecked", List.of("r1"), List.of(), "Always.", List.of("Hello."),
			Duration.ofMinutes(1), "Complies.", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
		Category withUnchecked = new Category("formatting", formatting.rationale(), formatting.riskRank(),
			List.of(formatting.tests().get(0), unchecked));
		List<Requirement> withoutCoreTask = valid.requirements().stream().
			filter(requirement -> !requirement.equals(Requirement.CORE_TASK)).toList();
		List<Requirement> rewritten = new ArrayList<>(withoutCoreTask);
		rewritten.add(new Requirement(Requirement.CORE_TASK.id(), "Answers the question.", "CLAUDE.md", null,
			List.of("incorrect_result")));
		List<Runnable> invalid = List.of(
			() -> new Contract(withoutCoreTask, valid.categories(), valid.profiles(), valid.judge(), valid.fixtures(),
				valid.limitations(), valid.salt()),
			() -> new Contract(rewritten, valid.categories(), valid.profiles(), valid.judge(), valid.fixtures(),
				valid.limitations(), valid.salt()),
			() -> new Contract(valid.requirements(), List.of(withUnchecked, valid.categories().get(1)),
				valid.profiles(), valid.judge(), valid.fixtures(), valid.limitations(), valid.salt()));
		for (Runnable build : invalid)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException e)
			{
				rejected = e.getMessage().contains(Requirement.CORE_TASK.id());
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that the probabilities of a variation rule must sum to 1 and that a dimension name must be usable as a
	 * placeholder.
	 */
	@Test
	public void variationRuleRejectsInvalidInput()
	{
		// Requirement: R-C3
		VariationValue half = new VariationValue("a", "A", 0.5);
		VariationValue otherHalf = new VariationValue("b", "B", 0.5);
		new VariationRule("tone", List.of(half, otherHalf));
		List<Runnable> invalid = List.of(
			() -> new VariationRule("tone", List.of(half)),
			() -> new VariationRule("tone", List.of(half, new VariationValue("b", "B", 0.4))),
			() -> new VariationRule("tone", List.of(half, new VariationValue("a", "A again", 0.5))),
			() -> new VariationRule("tone", List.of()),
			() -> new VariationRule("two words", List.of(half, otherHalf)),
			() -> new VariationRule("{{tone}}", List.of(half, otherHalf)),
			() -> new VariationValue("a", "A", 0.0),
			() -> new VariationValue("a", "A", 1.1),
			() -> new VariationValue("", "A", 0.5));
		for (Runnable build : invalid)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that a test definition rejects an unusable turn sequence, time limit or variation set.
	 */
	@Test
	public void testDefinitionRejectsInvalidInput()
	{
		// Requirement: R-C3
		VariationRule tone = new VariationRule("tone", List.of(new VariationValue("a", "A", 1.0)));
		test("ok", List.of("Say {{tone}}."), List.of(tone));
		List<Runnable> invalid = List.of(
			() -> test("t", List.of(), List.of()),
			() -> test("t", List.of(" "), List.of()),
			() -> test("t", List.of("Say {{mood}}."), List.of(tone)),
			() -> test("t", List.of("Say {{tone}}."), List.of(tone, tone)),
			() -> new TestDefinition("t", List.of("r1"), List.of(), "Always.", List.of("Hi."), Duration.ZERO,
				"Complies.", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
			() -> new TestDefinition("t", List.of("r1"), List.of(), "Always.", List.of("Hi."), Duration.ofMillis(1500),
				"Complies.", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
			() -> new TestDefinition("t", List.of(), List.of(), "Always.", List.of("Hi."), Duration.ofMinutes(1),
				"Complies.", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
			() -> new TestDefinition("t", List.of("r1"), List.of(), "", List.of("Hi."), Duration.ofMinutes(1),
				"Complies.", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
			() -> new TestDefinition("t", List.of("r1"), List.of(), "Always.", List.of("Hi."), Duration.ofMinutes(1),
				"", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
		for (Runnable build : invalid)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that a test's key dimensions name distinct variation dimensions, that its key rules follow the test's
	 * dimension order, and that a test that declares no key dimension treats every dimension as key.
	 */
	@Test
	public void keyDimensionsAreVariationDimensions()
	{
		// Requirement: R-C16
		VariationRule tone = new VariationRule("tone", List.of(new VariationValue("a", "A", 1.0)));
		VariationRule topic = new VariationRule("topic", List.of(new VariationValue("b", "B", 1.0)));
		List<VariationRule> rules = List.of(tone, topic);
		requireThat(keyed(rules, List.of("topic", "tone")).keyRules(), "keyed").isEqualTo(rules);
		requireThat(keyed(rules, List.of("topic")).keyRules(), "topicOnly").isEqualTo(List.of(topic));
		requireThat(keyed(rules, List.of()).keyRules(), "undeclared").isEqualTo(rules);
		List<Runnable> invalid = List.of(
			() -> keyed(rules, List.of("mood")),
			() -> keyed(rules, List.of("tone", "tone")));
		for (Runnable build : invalid)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Returns a test definition with key dimensions.
	 *
	 * @param variations the variation rules
	 * @param keyDimensions the key dimensions
	 * @return the test definition
	 */
	private static TestDefinition keyed(List<VariationRule> variations, List<String> keyDimensions)
	{
		return new TestDefinition("t", List.of("r1"), List.of(), "Always.", List.of("Say {{tone}} {{topic}}."),
			Duration.ofMinutes(1), "Complies.", List.of(), variations, keyDimensions, List.of(), List.of(), List.of());
	}

	/**
	 * Verifies that a requirement either always applies or names a non-blank trigger, and that a test rejects a
	 * blank entry among the triggers it leaves absent.
	 */
	@Test
	public void triggerIsAbsentOrText()
	{
		// Requirement: R-D17
		requireThat(new Requirement("r", "Asks first.", "CLAUDE.md", null, BEHAVIORS).trigger(), "always").isNull();
		requireThat(new Requirement("r", "Asks first.", "CLAUDE.md", "A deletion.", BEHAVIORS).trigger(), "triggered").
			isEqualTo("A deletion.");
		List<Runnable> invalid = List.of(
			() -> new Requirement("r", "Asks first.", "CLAUDE.md", " ", BEHAVIORS),
			() -> new TestDefinition("t", List.of("r1"), List.of(" "), "Always.", List.of("Hi."),
				Duration.ofMinutes(1), "Complies.", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
		for (Runnable build : invalid)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that risk ranks order the riskier category first and reject a position below 1.
	 */
	@Test
	public void riskRankOrdersRiskiestFirst()
	{
		// Requirement: R-C3
		requireThat(new RiskRank(1).compareTo(new RiskRank(2)), "riskierFirst").isLessThan(0);
		requireThat(new RiskRank(2).compareTo(new RiskRank(1)), "lessRiskyLast").isGreaterThan(0);
		requireThat(new RiskRank(2).compareTo(new RiskRank(2)), "tie").isEqualTo(0);
		boolean rejected = false;
		try
		{
			new RiskRank(0);
		}
		catch (IllegalArgumentException _)
		{
			rejected = true;
		}
		requireThat(rejected, "rejected").isTrue();
	}

	/**
	 * Verifies that a category is found by identifier, and that a missing identifier yields no category.
	 */
	@Test
	public void findsCategoryById()
	{
		// Requirement: R-C3
		Contract contract = ContractFixtures.contract();

		requireThat(contract.findCategory("safety").orElseThrow().id(), "id").isEqualTo("safety");
		requireThat(contract.findCategory("missing").isPresent(), "present").isFalse();
	}

	/**
	 * Returns trial profiles with one cell per distinct category, whose identifier is the category's.
	 *
	 * @param categories the categories
	 * @return the trial profiles
	 */
	private static TrialProfiles cells(Category... categories)
	{
		Set<String> ids = new LinkedHashSet<>();
		for (Category category : categories)
			ids.add(category.id());
		List<Cell> cells = new ArrayList<>();
		for (String id : ids)
			cells.add(new Cell(id, id, ContractFixtures.PROFILE));
		ProfileCatalog inventory = new ProfileCatalog("test", List.of(ContractFixtures.PROFILE));
		return new TrialProfiles(inventory, ContractFixtures.ranking(inventory), List.of(), List.of(), List.of(), null,
			cells);
	}

	/**
	 * Verifies that every category needs a cell and every cell a listed category.
	 */
	@Test
	public void contractRejectsInconsistentCells()
	{
		// Requirement: R-C11
		Contract valid = ContractFixtures.contract();
		Category formatting = valid.categories().get(0);
		Category safety = valid.categories().get(1);
		Category extra = new Category("extra", "Why.", new RiskRank(3), formatting.tests());
		List<Runnable> inconsistent = List.of(
			() -> new Contract(valid.requirements(), valid.categories(), cells(formatting), valid.judge(),
				valid.fixtures(), valid.limitations(), valid.salt()),
			() -> new Contract(valid.requirements(), valid.categories(), cells(formatting, safety, extra),
				valid.judge(), valid.fixtures(), valid.limitations(), valid.salt()));
		for (Runnable build : inconsistent)
		{
			boolean rejected = false;
			try
			{
				build.run();
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isTrue();
		}
	}

	/**
	 * Verifies that a contract finds each cell's category, and that a two-profile category has a cell per profile.
	 */
	@Test
	public void contractMapsCellsToCategories()
	{
		// Requirement: R-C11
		Contract contract = ContractFixtures.twoCellContract();
		requireThat(contract.cells().stream().map(Cell::id).toList(), "cells").isEqualTo(List.of(
			"formatting@big/high", "formatting@small/high", "safety"));
		Cell small = contract.findCell("formatting@small/high").orElseThrow();
		requireThat(small.profile(), "small.profile()").isEqualTo(ContractFixtures.SMALL);
		requireThat(contract.categoryOf(small).id(), "categoryOf(small)").isEqualTo("formatting");
		requireThat(contract.findCell("formatting"), "findCell(formatting)").isEmpty();
	}
}

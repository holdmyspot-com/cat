package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One kind of trial that checks one or more requirements.
 *
 * @param id the identifier that is unique within the contract
 * @param requirementIds the identifiers of the requirements that this test checks
 * @param applicability the conditions under which the checked requirements apply to a trial
 * @param turns the complete prewritten user turns, which may contain {@code {{dimension}}} placeholders
 * @param timeLimit the time that one trial may run before it is judged on the behaviour it has shown
 * @param expectedBehaviour what a compliant response does
 * @param uncoveredInteractions the material interactions that this test cannot cover
 * @param variations the dimensions along which instances vary
 * @param checks the deterministic checks that passed both of their controls. A requirement that one of them decides
 *   is deterministic in this test; every other requirement is semantic.
 * @param discardedChecks the proposed checks that failed a control, with the reason
 */
public record TestDefinition(String id, List<String> requirementIds, String applicability, List<String> turns,
	Duration timeLimit, String expectedBehaviour, List<String> uncoveredInteractions,
	List<VariationRule> variations, List<DeterministicCheck> checks, List<DiscardedCheck> discardedChecks)
{
	/**
	 * Creates a test definition.
	 *
	 * @param id the identifier that is unique within the contract
	 * @param requirementIds the identifiers of the requirements that this test checks
	 * @param applicability the conditions under which the checked requirements apply to a trial
	 * @param turns the complete prewritten user turns, which may contain {@code {{dimension}}} placeholders
	 * @param timeLimit the time that one trial may run before it is judged on the behaviour it has shown
	 * @param expectedBehaviour what a compliant response does
	 * @param uncoveredInteractions the material interactions that this test cannot cover
	 * @param variations the dimensions along which instances vary
	 * @param checks the deterministic checks that passed both of their controls
	 * @param discardedChecks the proposed checks that failed a control, with the reason
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if a text is blank, a list that must not be empty is empty, {@code timeLimit}
	 *   is not positive, two variation rules share a dimension, a turn or a check parameter names a placeholder that
	 *   no variation rule defines, a check decides a requirement that the test does not check, two checks decide the
	 *   same requirement, a check examines a turn that the test does not have, or a control does not choose a
	 *   permitted value for every dimension that its check names
	 */
	public TestDefinition
	{
		requireThat(id, "id").isNotBlank();
		requireThat(requirementIds, "requirementIds").isNotEmpty();
		requireThat(applicability, "applicability").isNotBlank();
		requireThat(turns, "turns").isNotEmpty();
		requireThat(timeLimit, "timeLimit").isGreaterThan(Duration.ZERO);
		requireThat(timeLimit.getNano(), "timeLimit.getNano()").isEqualTo(0);
		requireThat(expectedBehaviour, "expectedBehaviour").isNotBlank();
		requireThat(uncoveredInteractions, "uncoveredInteractions").isNotNull();
		requireThat(variations, "variations").isNotNull();
		requireThat(checks, "checks").isNotNull();
		requireThat(discardedChecks, "discardedChecks").isNotNull();
		requirementIds = List.copyOf(requirementIds);
		turns = List.copyOf(turns);
		uncoveredInteractions = List.copyOf(uncoveredInteractions);
		variations = List.copyOf(variations);
		checks = List.copyOf(checks);
		discardedChecks = List.copyOf(discardedChecks);
		for (String requirementId : requirementIds)
			requireThat(requirementId, "requirementId").isNotBlank();
		for (String turn : turns)
			requireThat(turn, "turn").isNotBlank();
		for (String interaction : uncoveredInteractions)
			requireThat(interaction, "uncoveredInteraction").isNotBlank();

		Set<String> dimensions = new HashSet<>();
		for (VariationRule rule : variations)
		{
			if (!dimensions.add(rule.dimension()))
				throw new IllegalArgumentException("Test '" + id + "' defines the dimension '" + rule.dimension() +
					"' twice");
		}
		for (String turn : turns)
		{
			for (String name : Placeholders.names(turn))
			{
				if (!dimensions.contains(name))
				{
					throw new IllegalArgumentException("Test '" + id + "' has a turn with the placeholder {{" + name +
						"}}, but no variation defines that dimension. Defined dimensions: " + dimensions);
				}
			}
		}

		Set<String> decided = new HashSet<>();
		List<DeterministicCheck> proposed = new ArrayList<>(checks);
		for (DiscardedCheck discarded : discardedChecks)
			proposed.add(discarded.check());
		for (DeterministicCheck check : proposed)
		{
			String requirementId = check.requirementId();
			if (!requirementIds.contains(requirementId))
			{
				throw new IllegalArgumentException("Test '" + id + "' has a check for the requirement '" +
					requirementId + "', which the test does not check. Checked requirements: " + requirementIds);
			}
			if (!decided.add(requirementId))
			{
				throw new IllegalArgumentException("Test '" + id + "' has two checks for the requirement '" +
					requirementId + "', but a requirement has at most one check per test");
			}
			requireValid(id, turns.size(), variations, check);
		}
	}

	/**
	 * Rejects a check that names a turn or a variation dimension that its test does not have, or whose control does
	 * not choose a permitted value for every dimension that the check names.
	 *
	 * @param id the test's identifier
	 * @param turnCount the number of turns that the test has
	 * @param variations the test's variation rules
	 * @param check the check
	 * @throws IllegalArgumentException if the check does not fit the test
	 */
	private static void requireValid(String id, int turnCount, List<VariationRule> variations,
		DeterministicCheck check)
	{
		String requirementId = check.requirementId();
		if (check.criterion() instanceof CheckCriterion.ReplyCriterion reply && reply.turn() != null &&
			reply.turn() > turnCount)
		{
			throw new IllegalArgumentException("The check for '" + requirementId + "' in test '" + id +
				"' examines turn " + reply.turn() + ", but the test has " + turnCount + " turns");
		}
		Map<String, VariationRule> ruleByDimension = new HashMap<>();
		for (VariationRule rule : variations)
			ruleByDimension.put(rule.dimension(), rule);
		Set<String> named = check.criterion().dimensions();
		for (String dimension : named)
		{
			if (!ruleByDimension.containsKey(dimension))
			{
				throw new IllegalArgumentException("The check for '" + requirementId + "' in test '" + id +
					"' has the placeholder {{" + dimension + "}}, but no variation defines that dimension. Defined " +
					"dimensions: " + ruleByDimension.keySet());
			}
		}
		for (ControlEvidence control : List.of(check.knownGood(), check.knownBad()))
		{
			for (String dimension : named)
			{
				if (!control.choices().containsKey(dimension))
				{
					throw new IllegalArgumentException("A control of the check for '" + requirementId + "' in test '" +
						id + "' does not choose a value for the dimension '" + dimension + "', which the check names");
				}
			}
			for (Map.Entry<String, String> choice : control.choices().entrySet())
			{
				VariationRule rule = ruleByDimension.get(choice.getKey());
				if (rule == null || rule.values().stream().noneMatch(value -> value.label().equals(choice.getValue())))
				{
					throw new IllegalArgumentException("A control of the check for '" + requirementId + "' in test '" +
						id + "' chooses '" + choice.getValue() + "' for the dimension '" + choice.getKey() +
						"', which the test does not permit");
				}
			}
		}
	}

	/**
	 * Replaces every placeholder in the turns.
	 *
	 * @param textByDimension the replacement text of each dimension
	 * @return the complete turn sequence of one instance
	 */
	List<String> expandTurns(Map<String, String> textByDimension)
	{
		return turns.stream().map(turn -> Placeholders.fill(turn, textByDimension)).toList();
	}

	/**
	 * Returns the requirements that the judge decides in this test: those that no check decides.
	 *
	 * @return the requirement identifiers, in the order the test lists them
	 */
	public List<String> semanticRequirementIds()
	{
		Set<String> deterministic = new HashSet<>();
		for (DeterministicCheck check : checks)
			deterministic.add(check.requirementId());
		return requirementIds.stream().filter(requirementId -> !deterministic.contains(requirementId)).toList();
	}

	/**
	 * Runs every check against its controls (design &sect;6.1). A check that rejects its known-good control or
	 * accepts its known-bad control is discarded, so its requirement is judged semantically.
	 *
	 * @return the test with each failing check moved to {@link #discardedChecks()}
	 */
	public TestDefinition withValidatedControls()
	{
		List<DeterministicCheck> kept = new ArrayList<>();
		List<DiscardedCheck> discarded = new ArrayList<>(discardedChecks);
		for (DeterministicCheck check : checks)
		{
			List<String> reasons = new ArrayList<>();
			CheckResult good = decideControl(check, check.knownGood());
			if (!good.passed())
				reasons.add("It rejected its known-good control: " + good.observed() + ".");
			CheckResult bad = decideControl(check, check.knownBad());
			if (bad.passed())
				reasons.add("It accepted its known-bad control: " + bad.observed() + ".");
			if (reasons.isEmpty())
				kept.add(check);
			else
				discarded.add(new DiscardedCheck(check, String.join(" ", reasons)));
		}
		if (discarded.size() == discardedChecks.size())
			return this;
		return new TestDefinition(id, requirementIds, applicability, turns, timeLimit, expectedBehaviour,
			uncoveredInteractions, variations, kept, discarded);
	}

	/**
	 * Decides a check on one of its controls.
	 *
	 * @param check the check
	 * @param control the control
	 * @return the result
	 */
	private CheckResult decideControl(DeterministicCheck check, ControlEvidence control)
	{
		try
		{
			return check.decide(control, textByDimension(control.choices()));
		}
		catch (IOException | UndecidableCheckException e)
		{
			// A control is held in memory and records its tool calls
			throw new AssertionError("A control's evidence was unreadable", e);
		}
	}

	/**
	 * Runs every check on a trial's evidence.
	 *
	 * @param choices the variation values that the trial drew
	 * @param evidence the trial's evidence
	 * @return one result per check, in check order
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if a choice names a dimension or value that the test does not define
	 * @throws IOException if the evidence cannot be read
	 * @throws UndecidableCheckException if the evidence does not record what a check's kind observes
	 */
	public List<CheckResult> decide(List<VariationChoice> choices, CheckEvidence evidence)
		throws IOException, UndecidableCheckException
	{
		requireThat(choices, "choices").isNotNull();
		requireThat(evidence, "evidence").isNotNull();
		Map<String, String> labelByDimension = new HashMap<>();
		for (VariationChoice choice : choices)
			labelByDimension.put(choice.dimension(), choice.label());
		Map<String, String> textByDimension = textByDimension(labelByDimension);
		List<CheckResult> results = new ArrayList<>();
		for (DeterministicCheck check : checks)
			results.add(check.decide(evidence, textByDimension));
		return results;
	}

	/**
	 * Returns the text of each chosen variation value.
	 *
	 * @param labelByDimension the label of the value chosen for each dimension
	 * @return the text of each chosen value, by dimension
	 * @throws IllegalArgumentException if a dimension is not defined or a label names no value of its dimension
	 */
	private Map<String, String> textByDimension(Map<String, String> labelByDimension)
	{
		Map<String, String> textByDimension = new HashMap<>();
		for (Map.Entry<String, String> entry : labelByDimension.entrySet())
		{
			VariationRule rule = variations.stream().filter(candidate -> candidate.dimension().equals(entry.getKey())).
				findFirst().orElseThrow(() -> new IllegalArgumentException("Test '" + id + "' has no dimension '" +
					entry.getKey() + "'"));
			VariationValue value = rule.values().stream().
				filter(candidate -> candidate.label().equals(entry.getValue())).findFirst().
				orElseThrow(() -> new IllegalArgumentException("The dimension '" + rule.dimension() + "' of test '" +
					id + "' has no value '" + entry.getValue() + "'"));
			textByDimension.put(rule.dimension(), value.text());
		}
		return textByDimension;
	}
}

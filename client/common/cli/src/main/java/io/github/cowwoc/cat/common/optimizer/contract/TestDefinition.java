package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 */
public record TestDefinition(String id, List<String> requirementIds, String applicability, List<String> turns,
	Duration timeLimit, String expectedBehaviour, List<String> uncoveredInteractions,
	List<VariationRule> variations)
{
	/**
	 * Matches {@code {{dimension}}}, capturing the dimension name.
	 */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

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
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if a text is blank, a list that must not be empty is empty, {@code timeLimit}
	 *   is not positive, two variation rules share a dimension, or a turn names a placeholder that no variation
	 *   rule defines
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
		requirementIds = List.copyOf(requirementIds);
		turns = List.copyOf(turns);
		uncoveredInteractions = List.copyOf(uncoveredInteractions);
		variations = List.copyOf(variations);
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
			Matcher placeholder = PLACEHOLDER.matcher(turn);
			while (placeholder.find())
			{
				String name = placeholder.group(1);
				if (!dimensions.contains(name))
				{
					throw new IllegalArgumentException("Test '" + id + "' has a turn with the placeholder {{" + name +
						"}}, but no variation defines that dimension. Defined dimensions: " + dimensions);
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
		return turns.stream().
			map(turn -> PLACEHOLDER.matcher(turn).
				replaceAll(placeholder -> Matcher.quoteReplacement(textByDimension.get(placeholder.group(1))))).
			toList();
	}
}

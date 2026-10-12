package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The requirements and prompt tests used to decide whether each trial passes. Each cell pairs a prompt test with a
 * model and reasoning-effort profile and keeps its trial counts and decisions separate.
 *
 * @param requirements every requirement and its source
 * @param tests every prompt test, in the order used to break otherwise equal allocation choices
 * @param profiles the trial profiles and the cells, which are the statistical units
 * @param judge the judge's model, effort and instructions, fixed when the user confirms the contract
 * @param fixtures the ignored files that trials need
 * @param limitations the material interactions and conditions that the contract does not cover
 * @param minimumPassRate the minimum pass rate {@code p_min}: a cell certifies only when its pass probability is proven
 *   to exceed it
 * @param salt the random value, frozen at confirmation, under which each test's outcome-changing combinations are split
 *   into the development part and the held-out part
 * @param plugin the harness plugin that holds the target prompt files, or empty if they are not in a plugin
 */
public record Contract(List<Requirement> requirements, List<PromptTest> tests, TrialProfiles profiles,
	JudgeProfile judge, TrialFixtures fixtures, List<String> limitations, BigDecimal minimumPassRate, long salt,
	Optional<PluginUnderTest> plugin)
{
	/**
	 * The minimum pass rate when the contract author proposes none.
	 */
	public static final BigDecimal DEFAULT_MINIMUM_PASS_RATE = new BigDecimal("0.95");
	/**
	 * The lowest minimum pass rate that a contract accepts.
	 */
	public static final BigDecimal LOWEST_MINIMUM_PASS_RATE = new BigDecimal("0.80");
	/**
	 * The highest minimum pass rate that a contract accepts.
	 */
	public static final BigDecimal HIGHEST_MINIMUM_PASS_RATE = new BigDecimal("0.99");
	/**
	 * The most decimal places that a minimum pass rate may have.
	 */
	public static final int MINIMUM_PASS_RATE_DECIMALS = 2;

	/**
	 * Creates a contract whose target prompt files are not in a plugin.
	 *
	 * @param requirements every requirement and its source
	 * @param tests every prompt test, in the order used to break otherwise equal allocation choices
	 * @param profiles the trial profiles and the cells, which are the statistical units
	 * @param judge the judge's model, effort and instructions, fixed when the user confirms the contract
	 * @param fixtures the ignored files that trials need
	 * @param limitations the material interactions and conditions that the contract does not cover
	 * @param minimumPassRate the minimum pass rate {@code p_min}: a cell certifies only when its pass probability is
	 *   proven to exceed it
	 * @param salt the random value, frozen at confirmation, under which each test's outcome-changing combinations are
	 *   split into the development part and the held-out part
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the canonical constructor rejects the values
	 */
	public Contract(List<Requirement> requirements, List<PromptTest> tests, TrialProfiles profiles, JudgeProfile judge,
		TrialFixtures fixtures, List<String> limitations, BigDecimal minimumPassRate, long salt)
	{
		this(requirements, tests, profiles, judge, fixtures, limitations, minimumPassRate, salt, Optional.empty());
	}

	/**
	 * Creates a contract.
	 *
	 * @param requirements every requirement and its source
	 * @param tests every prompt test, in the order used to break otherwise equal allocation choices
	 * @param profiles the trial profiles and the cells, which are the statistical units
	 * @param judge the judge's model, effort and instructions, fixed when the user confirms the contract
	 * @param fixtures the ignored files that trials need
	 * @param limitations the material interactions and conditions that the contract does not cover
	 * @param minimumPassRate the minimum pass rate {@code p_min}: a cell certifies only when its pass probability is
	 *   proven to exceed it
	 * @param salt the random value, frozen at confirmation, under which each test's outcome-changing combinations are
	 *   split into the development part and the held-out part
	 * @param plugin the harness plugin that holds the target prompt files, or empty if they are not in a plugin
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code requirements} or {@code tests} are empty, two requirements or two tests
	 *   share an identifier, a test names a requirement that the contract does not list, {@code minimumPassRate} is
	 *   outside
	 *   [{@link #LOWEST_MINIMUM_PASS_RATE}, {@link #HIGHEST_MINIMUM_PASS_RATE}] or has more than
	 *   {@value #MINIMUM_PASS_RATE_DECIMALS} decimal places, a test leaves absent the trigger of a requirement that the
	 *   contract does not list or that always applies, a requirement with a trigger is left absent by no test, a
	 *   test has no cell, or a cell names a test that the contract does not list
	 */
	public Contract
	{
		requireThat(requirements, "requirements").isNotEmpty();
		requireThat(tests, "tests").isNotEmpty();
		requireThat(profiles, "profiles").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(fixtures, "fixtures").isNotNull();
		requireThat(limitations, "limitations").isNotNull();
		requireThat(plugin, "plugin").isNotNull();
		requireThat(minimumPassRate, "minimumPassRate").
			isBetween(LOWEST_MINIMUM_PASS_RATE, true, HIGHEST_MINIMUM_PASS_RATE, true);
		requireThat(minimumPassRate.stripTrailingZeros().scale(), "minimumPassRate.stripTrailingZeros().scale()").
			isLessThanOrEqualTo(MINIMUM_PASS_RATE_DECIMALS);
		requirements = List.copyOf(requirements);
		tests = List.copyOf(tests);
		limitations = List.copyOf(limitations);
		for (String limitation : limitations)
			requireThat(limitation, "limitation").isNotBlank();

		Map<String, Requirement> requirementById = new HashMap<>();
		for (Requirement requirement : requirements)
		{
			if (requirementById.putIfAbsent(requirement.id(), requirement) != null)
				throw new IllegalArgumentException("The requirement '" + requirement.id() + "' is listed twice");
		}
		Set<String> requirementIds = requirementById.keySet();
		Set<String> testIds = new HashSet<>();
		Set<String> absentTriggers = new HashSet<>();
		for (PromptTest test : tests)
		{
			if (!testIds.add(test.id()))
				throw new IllegalArgumentException("The test '" + test.id() + "' is listed twice");
			for (String requirementId : test.requirementIds())
			{
				if (!requirementIds.contains(requirementId))
				{
					throw new IllegalArgumentException("The test '" + test.id() + "' checks the requirement '" +
						requirementId + "', which the contract does not list. Listed requirements: " + requirementIds);
				}
			}
			for (String absentTrigger : test.absentTriggers())
			{
				Requirement requirement = requirementById.get(absentTrigger);
				if (requirement == null)
				{
					throw new IllegalArgumentException("The test '" + test.id() + "' leaves absent the trigger of the " +
						"requirement '" + absentTrigger + "', which the contract does not list. Listed requirements: " +
						requirementIds);
				}
				if (requirement.trigger() == null)
				{
					throw new IllegalArgumentException("The test '" + test.id() + "' leaves absent the trigger of the " +
						"requirement '" + absentTrigger + "', which has no trigger because it always applies");
				}
				absentTriggers.add(absentTrigger);
			}
			if (profiles.cellsOf(test.id()).isEmpty())
				throw new IllegalArgumentException("The test '" + test.id() + "' has no cell");
		}
		for (Requirement requirement : requirements)
		{
			if (requirement.trigger() != null && !absentTriggers.contains(requirement.id()))
			{
				throw new IllegalArgumentException("The requirement '" + requirement.id() + "' applies only when its " +
					"trigger occurs (" + requirement.trigger() + "), but no test leaves that trigger absent. List it in " +
					"the absent_triggers of a test in which the trigger does not occur and the obligated action must not " +
					"happen");
			}
		}
		for (Cell cell : profiles.cells())
		{
			if (!testIds.contains(cell.testId()))
			{
				throw new IllegalArgumentException("The cell '" + cell.id() + "' belongs to the test '" + cell.testId() +
					"', which the contract does not list");
			}
		}
	}

	/**
	 * Finds a prompt test.
	 *
	 * @param id a test identifier
	 * @return the test with that identifier, or empty if the contract has none
	 */
	public Optional<PromptTest> findTest(String id)
	{
		return tests.stream().filter(test -> test.id().equals(id)).findFirst();
	}

	/**
	 * Returns the split of each prompt test's test cases into the development part and the held-out part.
	 *
	 * @return the split under this contract's salt
	 */
	public Split split()
	{
		return new Split(salt);
	}

	/**
	 * Returns this contract with a certification scope.
	 *
	 * @param scope the certification scope
	 * @return the contract
	 * @throws NullPointerException if {@code scope} is null
	 * @throws IllegalArgumentException if the trial profiles reject the scope
	 */
	public Contract withScope(CertificationScope scope)
	{
		return new Contract(requirements, tests, profiles.withScope(scope), judge, fixtures, limitations,
			minimumPassRate, salt, plugin);
	}

	/**
	 * Returns this contract with a plugin under test.
	 *
	 * @param value the harness plugin that holds the target prompt files, or empty if they are not in a plugin
	 * @return the contract
	 * @throws NullPointerException if {@code value} is null
	 */
	public Contract withPlugin(Optional<PluginUnderTest> value)
	{
		return new Contract(requirements, tests, profiles, judge, fixtures, limitations, minimumPassRate, salt, value);
	}

	/**
	 * Returns the test/profile pairs whose trial counts and decisions are kept separately.
	 *
	 * @return every cell, grouped by test in test order
	 */
	public List<Cell> cells()
	{
		return profiles.cells();
	}

	/**
	 * Returns the identifiers of the cells.
	 *
	 * @return the identifier of every cell, in cell order
	 */
	public List<String> cellIds()
	{
		return cells().stream().map(Cell::id).toList();
	}

	/**
	 * Finds a cell: one of the contract's cells, or one of them under a tool-output token limit.
	 *
	 * @param id a cell identifier
	 * @return the cell with that identifier, or empty if the contract has none
	 * @throws NullPointerException if {@code id} is null
	 * @see TrialProfiles#findCell(String)
	 */
	public Optional<Cell> findCell(String id)
	{
		return profiles.findCell(id);
	}

	/**
	 * Returns the prompt test of a cell.
	 *
	 * @param cell a cell of this contract
	 * @return the cell's test
	 * @throws NullPointerException if {@code cell} is null
	 * @throws IllegalArgumentException if the contract has no such test
	 */
	public PromptTest testOf(Cell cell)
	{
		requireThat(cell, "cell").isNotNull();
		return findTest(cell.testId()).orElseThrow(() -> new IllegalArgumentException(
			"The contract has no test '" + cell.testId() + "'"));
	}
}

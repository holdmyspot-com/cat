package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * @param limitations the material interactions and conditions that the contract does not cover
 * @param minimumPassRate the minimum pass rate {@code p_min}: a cell certifies only when its pass probability is proven
 *   to exceed it
 * @param salt the random value, frozen at confirmation, under which each test's outcome-changing combinations are split
 *   into the development part and the held-out part
 * @param dependents the parts of the prompt system that exist only to serve the target prompt file, which control
 *   trials remove
 */
public record Contract(List<Requirement> requirements, List<PromptTest> tests, TrialProfiles profiles,
	JudgeProfile judge, List<String> limitations, BigDecimal minimumPassRate, long salt,
	List<Dependent> dependents)
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
	 * Creates a contract whose target prompt file has no dependents.
	 *
	 * @param requirements every requirement and its source
	 * @param tests every prompt test, in the order used to break otherwise equal allocation choices
	 * @param profiles the trial profiles and the cells, which are the statistical units
	 * @param judge the judge's model, effort and instructions, fixed when the user confirms the contract
		 * @param limitations the material interactions and conditions that the contract does not cover
	 * @param minimumPassRate the minimum pass rate {@code p_min}: a cell certifies only when its pass probability is
	 *   proven to exceed it
	 * @param salt the random value, frozen at confirmation, under which each test's outcome-changing combinations are
	 *   split into the development part and the held-out part
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the canonical constructor rejects the values
	 */
	public Contract(List<Requirement> requirements, List<PromptTest> tests, TrialProfiles profiles, JudgeProfile judge,
		List<String> limitations, BigDecimal minimumPassRate, long salt)
	{
		this(requirements, tests, profiles, judge, limitations, minimumPassRate, salt, List.of());
	}

	/**
	 * Creates a contract.
	 *
	 * @param requirements every requirement and its source
	 * @param tests every prompt test, in the order used to break otherwise equal allocation choices
	 * @param profiles the trial profiles and the cells, which are the statistical units
	 * @param judge the judge's model, effort and instructions, fixed when the user confirms the contract
		 * @param limitations the material interactions and conditions that the contract does not cover
	 * @param minimumPassRate the minimum pass rate {@code p_min}: a cell certifies only when its pass probability is
	 *   proven to exceed it
	 * @param salt the random value, frozen at confirmation, under which each test's outcome-changing combinations are
	 *   split into the development part and the held-out part
	 * @param dependents the parts of the prompt system that exist only to serve the target prompt file, which control
	 *   trials remove
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code requirements} or {@code tests} are empty, two dependents name one
	 *   removal, a dependent removes a whole file that another dependent edits, two requirements or two tests share an
	 *   identifier, a test names a requirement that the contract does not list, no test names a requirement that the
	 *   contract lists, {@code minimumPassRate} is outside
	 *   [{@link #LOWEST_MINIMUM_PASS_RATE}, {@link #HIGHEST_MINIMUM_PASS_RATE}] or has more than
	 *   {@value #MINIMUM_PASS_RATE_DECIMALS} decimal places, a test leaves absent the trigger of a requirement that the
	 *   contract does not list or that always applies, a requirement with a trigger is left absent by no test, a test
	 *   has no cell, or a cell names a test that the contract does not list
	 */
	public Contract
	{
		requireThat(requirements, "requirements").isNotEmpty();
		requireThat(tests, "tests").isNotEmpty();
		requireThat(profiles, "profiles").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(limitations, "limitations").isNotNull();
		requireThat(dependents, "dependents").isNotNull();
		requireThat(minimumPassRate, "minimumPassRate").
			isBetween(LOWEST_MINIMUM_PASS_RATE, true, HIGHEST_MINIMUM_PASS_RATE, true);
		requireThat(minimumPassRate.stripTrailingZeros().scale(), "minimumPassRate.stripTrailingZeros().scale()").
			isLessThanOrEqualTo(MINIMUM_PASS_RATE_DECIMALS);
		requirements = List.copyOf(requirements);
		tests = List.copyOf(tests);
		limitations = List.copyOf(limitations);
		for (String limitation : limitations)
			requireThat(limitation, "limitation").isNotBlank();
		dependents = List.copyOf(dependents);
		requireDependents(dependents);

		Map<String, Requirement> requirementById = new HashMap<>();
		for (Requirement requirement : requirements)
		{
			if (requirementById.putIfAbsent(requirement.id(), requirement) != null)
				throw new IllegalArgumentException("The requirement '" + requirement.id() + "' is listed twice");
		}
		Set<String> requirementIds = requirementById.keySet();
		Set<String> testIds = new HashSet<>();
		Set<String> testedRequirements = new HashSet<>();
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
				testedRequirements.add(requirementId);
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
			if (!testedRequirements.contains(requirement.id()))
			{
				throw new IllegalArgumentException("The requirement '" + requirement.id() + "' is checked by no test. " +
					"List it in the requirement_ids of a test that can prove it");
			}
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
		return new Contract(requirements, tests, profiles.withScope(scope), judge, limitations,
			minimumPassRate, salt, dependents);
	}

	/**
	 * Requires dependents that a contract can remove.
	 *
	 * @param dependents the dependents
	 * @throws IllegalArgumentException if two dependents name one removal, or a dependent removes a whole file that
	 *   another dependent edits
	 */
	private static void requireDependents(List<Dependent> dependents)
	{
		// A dependent in the project has the empty plugin name, so the project and each plugin have their own paths
		Map<String, Map<Path, List<Dependent.Removal>>> removals = new HashMap<>();
		for (Dependent dependent : dependents)
		{
			List<Dependent.Removal> ofFile = removals.computeIfAbsent(dependent.plugin(),
				_ -> new HashMap<>()).computeIfAbsent(dependent.path(), _ -> new ArrayList<>());
			if (ofFile.contains(dependent.removal()))
			{
				throw new IllegalArgumentException("The dependent " + dependent.path() + " lists " +
					dependent.removal() + " twice");
			}
			ofFile.add(dependent.removal());
			if (ofFile.size() > 1 && ofFile.stream().anyMatch(Dependent.WholeFile.class::isInstance))
			{
				throw new IllegalArgumentException("The dependents of " + dependent.path() + " remove the whole file " +
					"and also a part of it. List the file once, as a whole file");
			}
		}
	}

	/**
	 * Returns the removals that control trials apply to the project's files.
	 *
	 * @return each dependent in the project, relative to the project root, in contract order
	 */
	public List<Dependent> projectRemovals()
	{
		return dependents.stream().filter(dependent -> dependent.location() == Dependent.Location.PROJECT).toList();
	}

	/**
	 * Returns the removals that control trials apply to their copy of a project-built trial plugin: each dependent in
	 * the plugin, and each whole project file that a mapping copies into it, because a file missing from a staged
	 * source leaves the base's copy in place. A span or hook registration in a mapped project file reaches the plugin
	 * with the file.
	 *
	 * @param plugin a trial plugin
	 * @return the dependents, each relative to the plugin directory, in contract order; empty unless the project
	 *   builds the plugin
	 * @throws NullPointerException if {@code plugin} is null
	 */
	public List<Dependent> pluginRemovals(TrialPlugin plugin)
	{
		requireThat(plugin, "plugin").isNotNull();
		if (!plugin.isProjectBuilt())
			return List.of();
		List<Dependent> removals = new ArrayList<>();
		for (Dependent dependent : dependents)
		{
			switch (dependent.location())
			{
				case PLUGIN ->
				{
					if (dependent.plugin().equals(plugin.name()))
						removals.add(dependent);
				}
				case PROJECT ->
				{
					if (dependent.removal() instanceof Dependent.WholeFile)
					{
						plugin.mappedPath(dependent.path()).ifPresent(path ->
							removals.add(dependent.inPlugin(plugin.name(), path)));
					}
				}
			}
		}
		return removals;
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

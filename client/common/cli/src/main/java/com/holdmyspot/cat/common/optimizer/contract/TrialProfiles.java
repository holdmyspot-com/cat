package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model and reasoning-effort combinations used to measure a contract's pass rates. It records the combinations
 * available from the harness, their capability ranking, the choices selected by prompt files, the user's restriction,
 * the resulting pairs of prompt test and trial profile, called cells, and the certification scope.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param inventory the harness's profile inventory and its source
 * @param ranking the model families ranked by advertised capability
 * @param bindings every prompt file that binds a model or an effort
 * @param restriction the patterns that the user restricted the profile set to, or empty if the user did not restrict
 *   it. An empty dimension of a pattern matches every value.
 * @param described the patterns of the profiles that the user described as the certification scope, or empty if the
 *   user described none. An empty dimension of a pattern matches every value.
 * @param scope the certification scope, or null until the user chooses it at confirmation
 * @param cells every cell, grouped by test in test order
 */
public record TrialProfiles(ProfileCatalog inventory, CapabilityRanking ranking, List<ProfileBinding> bindings,
	List<TrialProfile> restriction, List<TrialProfile> described, CertificationScope scope, List<Cell> cells)
{
	/**
	 * The identifier of a contract cell under a tool-output token limit: the contract cell's identifier, then
	 * {@code /tool-output-} and the token count.
	 */
	private static final Pattern LIMITED_CELL = Pattern.compile("(.+)/" + TrialProfile.TOOL_OUTPUT_LABEL +
		"([1-9][0-9]*)");

	/**
	 * Creates the trial profiles.
	 *
	 * @param inventory the harness's profile inventory and its source
	 * @param ranking the model families ranked by advertised capability
	 * @param bindings every prompt file that binds a model or an effort
	 * @param restriction the patterns that the user restricted the profile set to, or empty
	 * @param described the patterns of the profiles that the user described as the certification scope, or empty
	 * @param scope the certification scope, or null until the user chooses it
	 * @param cells every cell, grouped by test in test order
	 * @throws NullPointerException if any of the arguments other than {@code scope} are null
	 * @throws IllegalArgumentException if {@code cells} is empty, two cells share an identifier, two bindings name the
	 *   same file, a restriction or described pattern binds nothing or matches no inventory profile, the described
	 *   patterns leave a test of several cells without a cell, {@code scope} is
	 *   {@link CertificationScope#DESCRIBED} and no pattern is described, or a scope is chosen while an inventory
	 *   model's family is missing from {@code ranking}
	 */
	public TrialProfiles
	{
		requireThat(inventory, "inventory").isNotNull();
		requireThat(ranking, "ranking").isNotNull();
		requireThat(bindings, "bindings").isNotNull();
		requireThat(restriction, "restriction").isNotNull();
		requireThat(described, "described").isNotNull();
		requireThat(cells, "cells").isNotEmpty();
		bindings = List.copyOf(bindings);
		restriction = List.copyOf(restriction);
		described = List.copyOf(described);
		cells = List.copyOf(cells);
		Set<String> files = new HashSet<>();
		for (ProfileBinding binding : bindings)
		{
			if (!files.add(binding.file()))
				throw new IllegalArgumentException("The file '" + binding.file() + "' is bound twice");
		}
		for (TrialProfile pattern : restriction)
			requireRestrictable(inventory, pattern);
		Set<String> ids = new HashSet<>();
		for (Cell cell : cells)
		{
			if (!ids.add(cell.id()))
				throw new IllegalArgumentException("The cell '" + cell.id() + "' is listed twice");
		}
		requireDescribable(inventory, described, cells);
		if (scope == CertificationScope.DESCRIBED && described.isEmpty())
		{
			throw new IllegalArgumentException("The described certification scope needs the cells that the user " +
				"described in revision feedback, and none are described");
		}
		List<String> unranked = ranking.unranked(inventory);
		if (scope != null && !unranked.isEmpty())
		{
			throw new IllegalArgumentException("The capability ranking (" + ranking.source() + ") does not rank the " +
				"family of the inventory models " + String.join(", ", unranked) + ". Rank them in revision feedback " +
				"before confirming.");
		}
	}

	/**
	 * Requires the described patterns to select inventory profiles and to leave every test of several cells with a
	 * cell.
	 *
	 * @param inventory the inventory
	 * @param described the described patterns, or empty
	 * @param cells every cell, grouped by test
	 * @throws IllegalArgumentException if a pattern binds nothing or matches no inventory profile, or the patterns
	 *   leave a test of several cells without a cell
	 */
	private static void requireDescribable(ProfileCatalog inventory, List<TrialProfile> described, List<Cell> cells)
	{
		if (described.isEmpty())
			return;
		for (TrialProfile pattern : described)
		{
			if (!pattern.isBound())
				throw new IllegalArgumentException("A described certification cell must name a model, an effort or both");
			if (inventory.isOutside(pattern))
			{
				throw new IllegalArgumentException("The described certification cell " + pattern.label() + " matches " +
					"no profile in the inventory (" + inventory.source() + ")");
			}
		}
		Map<String, List<TrialProfile>> byTest = new LinkedHashMap<>();
		for (Cell cell : cells)
			byTest.computeIfAbsent(cell.testId(), _ -> new ArrayList<>()).add(cell.profile());
		for (Map.Entry<String, List<TrialProfile>> entry : byTest.entrySet())
		{
			List<TrialProfile> profiles = entry.getValue();
			if (profiles.size() > 1 && profiles.stream().noneMatch(profile -> isDescribed(profile, described)))
			{
				throw new IllegalArgumentException("The described certification cells " + labels(described) +
					" leave the test '" + entry.getKey() + "' with no cell to certify. Its cells are " +
					labels(profiles));
			}
		}
	}

	/**
	 * Indicates whether a profile matches a described pattern.
	 *
	 * @param profile a profile
	 * @param described the described patterns
	 * @return {@code true} if a pattern matches the profile
	 */
	private static boolean isDescribed(TrialProfile profile, List<TrialProfile> described)
	{
		return described.stream().anyMatch(profile::matches);
	}

	/**
	 * Indicates whether the user described a cell's profile as part of the certification scope.
	 *
	 * @param cell a cell
	 * @return {@code true} if a described pattern matches the cell's profile
	 * @throws NullPointerException if {@code cell} is null
	 */
	public boolean isDescribed(Cell cell)
	{
		requireThat(cell, "cell").isNotNull();
		return isDescribed(cell.profile(), described);
	}

	/**
	 * Requires a restriction pattern to select part of the inventory.
	 *
	 * @param inventory the inventory
	 * @param pattern the pattern
	 * @throws IllegalArgumentException if {@code pattern} binds nothing or matches no inventory profile
	 */
	private static void requireRestrictable(ProfileCatalog inventory, TrialProfile pattern)
	{
		if (!pattern.isBound())
			throw new IllegalArgumentException("A profile restriction must name a model, an effort or both");
		if (inventory.isOutside(pattern))
		{
			throw new IllegalArgumentException("The profile restriction " + pattern.label() + " matches no " +
				"profile in the inventory (" + inventory.source() + ")");
		}
	}

	/**
	 * Describes the trial profiles for the user: the inventory and its source, the capability ranking and its source
	 * with any unranked models, the bindings, marking each value outside the inventory, the restriction, the
	 * described certification cells and each test's cells.
	 *
	 * @return the lines of the description
	 */
	public List<String> describe()
	{
		List<String> lines = new ArrayList<>();
		lines.add("Trial profile inventory (" + inventory.source() + "): " + labels(inventory.profiles()));
		lines.add(ranking.describe());
		List<String> unranked = unranked();
		if (!unranked.isEmpty())
		{
			lines.add("Unranked models: " + String.join(", ", unranked) + " (confirmation is refused until revision " +
				"feedback ranks their families)");
		}
		if (bindings.isEmpty())
			lines.add("Profile bindings: none");
		else
		{
			lines.add("Profile bindings:");
			for (ProfileBinding binding : bindings)
			{
				String outside = "";
				if (inventory.isOutside(binding.profile()))
					outside = " (outside the inventory, used as written)";
				lines.add("  - " + binding.file() + " binds " + binding.profile().label() + outside);
			}
		}
		if (!restriction.isEmpty())
			lines.add("Profile restriction: " + labels(restriction));
		if (!described.isEmpty())
			lines.add("Described certification cells: " + labels(described));
		lines.add("Cells:");
		Map<String, List<TrialProfile>> byTest = new LinkedHashMap<>();
		for (Cell cell : cells)
			byTest.computeIfAbsent(cell.testId(), _ -> new ArrayList<>()).add(cell.profile());
		Map<List<TrialProfile>, List<String>> byProfiles = new LinkedHashMap<>();
		for (Map.Entry<String, List<TrialProfile>> entry : byTest.entrySet())
			byProfiles.computeIfAbsent(entry.getValue(), _ -> new ArrayList<>()).add(entry.getKey());
		for (Map.Entry<List<TrialProfile>, List<String>> entry : byProfiles.entrySet())
			lines.add("  - " + describeGroup(entry.getValue(), entry.getKey()));
		return lines;
	}

	/**
	 * Describes the prompt tests that share a profile set: how many cells each has, how the cells are named and the
	 * profiles.
	 *
	 * @param testIds the identifiers of the tests
	 * @param profiles the profiles of each test's cells
	 * @return the description
	 */
	private static String describeGroup(List<String> testIds, List<TrialProfile> profiles)
	{
		String tests;
		String each;
		if (testIds.size() == 1)
		{
			tests = "test ";
			each = "";
		}
		else
		{
			tests = "tests ";
			each = " each";
		}
		String count;
		String naming;
		if (profiles.size() == 1)
		{
			count = "1 cell";
			naming = "named by the test";
		}
		else
		{
			count = profiles.size() + " cells";
			naming = "named `<test>@<profile>`";
		}
		return tests + String.join(", ", testIds) + " (" + count + each + ", " + naming + "): " +
			labels(profiles);
	}

	/**
	 * Returns the labels of profiles.
	 *
	 * @param profiles the profiles
	 * @return the labels, separated by commas
	 */
	private static String labels(List<TrialProfile> profiles)
	{
		return String.join(", ", profiles.stream().map(TrialProfile::label).toList());
	}

	/**
	 * Computes each prompt test's profile set and the resulting cells.
	 * <p>
	 * A test's profile set comes from the files that supply the requirements that the proposal maps to its assertions.
	 * When every such file binds the same complete profile, the test has one cell under that profile. Otherwise it has
	 * one cell for each inventory profile that matches every dimension that the files all bind to the same value; if no
	 * inventory profile matches, the bound values are used as written. The user's restriction then narrows each set.
	 *
	 * @param inventory the harness's profile inventory
	 * @param ranking the model families ranked by advertised capability
	 * @param bindings every prompt file that binds a model or an effort
	 * @param restriction the patterns that the user restricted the profile set to, or empty
	 * @param described the patterns of the profiles that the user described as the certification scope, or empty
	 * @param requirements every requirement of the contract
	 * @param tests every prompt test of the contract
	 * @return the trial profiles, without a certification scope
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a restriction pattern binds nothing or matches no inventory profile, the
	 *   restriction leaves a test without a profile, or a test names a requirement that {@code requirements} does
	 *   not list
	 */
	public static TrialProfiles compute(ProfileCatalog inventory, CapabilityRanking ranking,
		List<ProfileBinding> bindings, List<TrialProfile> restriction, List<TrialProfile> described,
		List<Requirement> requirements, List<PromptTest> tests)
	{
		requireThat(inventory, "inventory").isNotNull();
		requireThat(ranking, "ranking").isNotNull();
		requireThat(described, "described").isNotNull();
		requireThat(bindings, "bindings").isNotNull();
		requireThat(restriction, "restriction").isNotNull();
		requireThat(requirements, "requirements").isNotNull();
		requireThat(tests, "tests").isNotNull();
		for (TrialProfile pattern : restriction)
			requireRestrictable(inventory, pattern);
		Map<String, String> sources = new HashMap<>();
		for (Requirement requirement : requirements)
			sources.put(requirement.id(), requirement.source());
		Map<String, TrialProfile> bound = new HashMap<>();
		for (ProfileBinding binding : bindings)
			bound.put(binding.file(), binding.profile());

		List<Cell> cells = new ArrayList<>();
		for (PromptTest test : tests)
		{
			Set<String> files = new LinkedHashSet<>();
			for (String requirementId : test.requirementIds())
			{
				String source = sources.get(requirementId);
				if (source == null)
				{
					throw new IllegalArgumentException("The test '" + test.id() + "' checks the requirement '" +
						requirementId + "', which the contract does not list");
				}
				files.add(source);
			}
			List<TrialProfile> available = profileSet(inventory, files, bound);
			List<TrialProfile> set = restrict(available, restriction);
			if (set.isEmpty())
			{
				throw new IllegalArgumentException("The profile restriction leaves the test '" + test.id() +
					"' with no trial profile. Its profile set is " +
					available.stream().map(TrialProfile::label).toList());
			}
			if (set.size() == 1)
				cells.add(new Cell(test.id(), test.id(), set.getFirst()));
			else
			{
				for (TrialProfile profile : set)
					cells.add(new Cell(test.id() + "@" + profile.label(), test.id(), profile));
			}
		}
		return new TrialProfiles(inventory, ranking, bindings, restriction, described, null, cells);
	}

	/**
	 * Returns the profile set of the files that supply a test's requirements, before any restriction.
	 *
	 * @param inventory the harness's profile inventory
	 * @param files the files
	 * @param bound the profile that each binding file binds
	 * @return the matching profiles in inventory order, or the files' shared selections when no inventory profile
	 *   matches
	 */
	private static List<TrialProfile> profileSet(ProfileCatalog inventory, Set<String> files,
		Map<String, TrialProfile> bound)
	{
		TrialProfile fixed = new TrialProfile(common(files, bound, TrialProfile::model),
			common(files, bound, TrialProfile::effort));
		if (fixed.isComplete())
			return List.of(fixed);
		List<TrialProfile> matching = inventory.profiles().stream().filter(profile -> profile.matches(fixed)).
			toList();
		if (matching.isEmpty())
		{
			// A binding to a value outside the inventory is used as written
			return List.of(fixed);
		}
		return matching;
	}

	/**
	 * Returns the value that every file binds one dimension to.
	 *
	 * @param files the files
	 * @param bound the profile that each binding file binds
	 * @param dimension returns one dimension of a profile
	 * @return the common value, or empty if there are no files, a file leaves the dimension open or two files bind
	 *   different values
	 */
	private static String common(Set<String> files, Map<String, TrialProfile> bound,
		Function<TrialProfile, String> dimension)
	{
		String common = null;
		for (String file : files)
		{
			String value = dimension.apply(bound.getOrDefault(file, TrialProfile.HARNESS_DEFAULT));
			if (value.isEmpty() || (common != null && !common.equals(value)))
				return "";
			common = value;
		}
		if (common == null)
			return "";
		return common;
	}

	/**
	 * Narrows a profile set to the profiles that match a restriction.
	 *
	 * @param set the profile set
	 * @param restriction the restriction patterns, or empty for no restriction
	 * @return {@code set} when unrestricted, otherwise its profiles that match a pattern
	 */
	private static List<TrialProfile> restrict(List<TrialProfile> set, List<TrialProfile> restriction)
	{
		if (restriction.isEmpty())
			return set;
		return set.stream().filter(profile -> restriction.stream().anyMatch(profile::matches)).toList();
	}

	/**
	 * Returns the inventory models whose family the capability ranking does not list.
	 *
	 * @return the models, each once, in inventory order
	 */
	public List<String> unranked()
	{
		return ranking.unranked(inventory);
	}

	/**
	 * Returns these trial profiles with a certification scope.
	 *
	 * @param scope the certification scope
	 * @return the trial profiles; the described patterns are kept only for {@link CertificationScope#DESCRIBED}
	 * @throws NullPointerException if {@code scope} is null
	 * @throws IllegalArgumentException if {@code scope} is {@link CertificationScope#DESCRIBED} and the user described
	 *   no profile, or an inventory model's family is missing from the capability ranking
	 */
	public TrialProfiles withScope(CertificationScope scope)
	{
		requireThat(scope, "scope").isNotNull();
		List<TrialProfile> kept = described;
		if (scope != CertificationScope.DESCRIBED)
			kept = List.of();
		return new TrialProfiles(inventory, ranking, bindings, restriction, kept, scope, cells);
	}

	/**
	 * Returns a prompt test's cells.
	 *
	 * @param testId the test
	 * @return the cells, in contract order; empty if the test has none
	 * @throws NullPointerException if {@code testId} is null
	 */
	public List<Cell> cellsOf(String testId)
	{
		requireThat(testId, "testId").isNotNull();
		return cells.stream().filter(cell -> cell.testId().equals(testId)).toList();
	}

	/**
	 * Finds a cell: a contract cell with the identifier, or else, for an identifier that ends with
	 * {@code /tool-output-} and a positive token count, that contract cell under the tool-output token limit.
	 *
	 * @param id a cell identifier
	 * @return the cell, or empty if there is none
	 * @throws NullPointerException if {@code id} is null
	 */
	public Optional<Cell> findCell(String id)
	{
		requireThat(id, "id").isNotNull();
		Optional<Cell> contractCell = findContractCell(id);
		if (contractCell.isPresent())
			return contractCell;
		Matcher limited = LIMITED_CELL.matcher(id);
		if (!limited.matches())
			return Optional.empty();
		int tokens;
		try
		{
			tokens = Integer.parseInt(limited.group(2));
		}
		catch (NumberFormatException _)
		{
			// The token count exceeds the largest limit
			return Optional.empty();
		}
		return findContractCell(limited.group(1)).map(cell -> cell.withToolOutputTokenLimit(tokens));
	}

	/**
	 * Finds a cell of the contract.
	 *
	 * @param id a cell identifier
	 * @return the cell, or empty if the contract has none with that identifier
	 */
	private Optional<Cell> findContractCell(String id)
	{
		return cells.stream().filter(cell -> cell.id().equals(id)).findFirst();
	}
}

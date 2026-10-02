package io.github.cowwoc.cat.common.optimizer.contract;

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

/**
 * The model and reasoning-effort combinations used to measure a contract's compliance. It records the combinations
 * available from the harness, the choices selected by prompt files, the user's restriction and the resulting pairs
 * of category and trial profile, called cells.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param inventory the harness's profile inventory and its source
 * @param bindings every prompt file that binds a model or an effort
 * @param restriction the patterns that the user restricted the profile set to, or empty if the user did not restrict
 *   it. An empty dimension of a pattern matches every value.
 * @param cells every cell, grouped by category in category order
 */
public record TrialProfiles(ProfileCatalog inventory, List<ProfileBinding> bindings, List<TrialProfile> restriction,
	List<Cell> cells)
{
	/**
	 * Creates the trial profiles.
	 *
	 * @param inventory the harness's profile inventory and its source
	 * @param bindings every prompt file that binds a model or an effort
	 * @param restriction the patterns that the user restricted the profile set to, or empty
	 * @param cells every cell, grouped by category in category order
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code cells} is empty, two cells share an identifier, two bindings name the
	 *   same file, or a restriction pattern binds nothing or matches no inventory profile
	 */
	public TrialProfiles
	{
		requireThat(inventory, "inventory").isNotNull();
		requireThat(bindings, "bindings").isNotNull();
		requireThat(restriction, "restriction").isNotNull();
		requireThat(cells, "cells").isNotEmpty();
		bindings = List.copyOf(bindings);
		restriction = List.copyOf(restriction);
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
	 * Describes the trial profiles for the user: the inventory and its source, the bindings, marking each value
	 * outside the inventory, the restriction and each category's cells.
	 *
	 * @return the lines of the description
	 */
	public List<String> describe()
	{
		List<String> lines = new ArrayList<>();
		lines.add("Trial profile inventory (" + inventory.source() + "): " + labels(inventory.profiles()));
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
		lines.add("Cells:");
		Map<String, List<TrialProfile>> byCategory = new LinkedHashMap<>();
		for (Cell cell : cells)
			byCategory.computeIfAbsent(cell.categoryId(), _ -> new ArrayList<>()).add(cell.profile());
		Map<List<TrialProfile>, List<String>> byProfiles = new LinkedHashMap<>();
		for (Map.Entry<String, List<TrialProfile>> entry : byCategory.entrySet())
			byProfiles.computeIfAbsent(entry.getValue(), _ -> new ArrayList<>()).add(entry.getKey());
		for (Map.Entry<List<TrialProfile>, List<String>> entry : byProfiles.entrySet())
			lines.add("  - " + describeGroup(entry.getValue(), entry.getKey()));
		return lines;
	}

	/**
	 * Describes the categories that share a profile set: how many cells each has, how the cells are named and the
	 * profiles.
	 *
	 * @param categoryIds the identifiers of the categories
	 * @param profiles the profiles of each category's cells
	 * @return the description
	 */
	private static String describeGroup(List<String> categoryIds, List<TrialProfile> profiles)
	{
		String categories;
		String each;
		if (categoryIds.size() == 1)
		{
			categories = "category ";
			each = "";
		}
		else
		{
			categories = "categories ";
			each = " each";
		}
		String count;
		String naming;
		if (profiles.size() == 1)
		{
			count = "1 cell";
			naming = "named by the category";
		}
		else
		{
			count = profiles.size() + " cells";
			naming = "named `<category>@<profile>`";
		}
		return categories + String.join(", ", categoryIds) + " (" + count + each + ", " + naming + "): " +
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
	 * Computes each category's profile set and the resulting cells.
	 * <p>
	 * A category's profile set comes from the files that supply the requirements its tests check, excluding the
	 * excluded requirements. When every such file binds the same complete profile, the category has one cell under
	 * that profile. Otherwise it has one cell for each inventory profile that matches every dimension that the files
	 * all bind to the same value; if no inventory profile matches, the bound values are used as written. The user's
	 * restriction then narrows each set.
	 *
	 * @param inventory the harness's profile inventory
	 * @param bindings every prompt file that binds a model or an effort
	 * @param restriction the patterns that the user restricted the profile set to, or empty
	 * @param requirements every requirement of the contract
	 * @param categories every category of the contract
	 * @param excludedRequirements the requirements that come from CAT rather than from a file, such as the core-task
	 *   requirement
	 * @return the trial profiles
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a restriction pattern binds nothing or matches no inventory profile, the
	 *   restriction leaves a category without a profile, or a test names a requirement that {@code requirements} does
	 *   not list
	 */
	public static TrialProfiles compute(ProfileCatalog inventory, List<ProfileBinding> bindings,
		List<TrialProfile> restriction, List<Requirement> requirements, List<Category> categories,
		Set<String> excludedRequirements)
	{
		requireThat(inventory, "inventory").isNotNull();
		requireThat(bindings, "bindings").isNotNull();
		requireThat(restriction, "restriction").isNotNull();
		requireThat(requirements, "requirements").isNotNull();
		requireThat(categories, "categories").isNotNull();
		requireThat(excludedRequirements, "excludedRequirements").isNotNull();
		for (TrialProfile pattern : restriction)
			requireRestrictable(inventory, pattern);
		Map<String, String> sources = new HashMap<>();
		for (Requirement requirement : requirements)
			sources.put(requirement.id(), requirement.source());
		Map<String, TrialProfile> bound = new HashMap<>();
		for (ProfileBinding binding : bindings)
			bound.put(binding.file(), binding.profile());

		List<Cell> cells = new ArrayList<>();
		for (Category category : categories)
		{
			Set<String> files = new LinkedHashSet<>();
			for (TestDefinition test : category.tests())
			{
				for (String requirementId : test.requirementIds())
				{
					if (excludedRequirements.contains(requirementId))
						continue;
					String source = sources.get(requirementId);
					if (source == null)
					{
						throw new IllegalArgumentException("The test '" + test.id() + "' checks the requirement '" +
							requirementId + "', which the contract does not list");
					}
					files.add(source);
				}
			}
			List<TrialProfile> available = profileSet(inventory, files, bound);
			List<TrialProfile> set = restrict(available, restriction);
			if (set.isEmpty())
			{
				throw new IllegalArgumentException("The profile restriction leaves the category '" + category.id() +
					"' with no trial profile. Its profile set is " +
					available.stream().map(TrialProfile::label).toList());
			}
			if (set.size() == 1)
				cells.add(new Cell(category.id(), category.id(), set.getFirst()));
			else
			{
				for (TrialProfile profile : set)
					cells.add(new Cell(category.id() + "@" + profile.label(), category.id(), profile));
			}
		}
		return new TrialProfiles(inventory, bindings, restriction, cells);
	}

	/**
	 * Returns the profile set of the files that supply a category's requirements, before any restriction.
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
	 * Returns a category's cells.
	 *
	 * @param categoryId the category
	 * @return the cells, in contract order; empty if the category has none
	 * @throws NullPointerException if {@code categoryId} is null
	 */
	public List<Cell> cellsOf(String categoryId)
	{
		requireThat(categoryId, "categoryId").isNotNull();
		return cells.stream().filter(cell -> cell.categoryId().equals(categoryId)).toList();
	}

	/**
	 * Finds a cell.
	 *
	 * @param id a cell identifier
	 * @return the cell, or empty if there is none
	 * @throws NullPointerException if {@code id} is null
	 */
	public Optional<Cell> findCell(String id)
	{
		requireThat(id, "id").isNotNull();
		return cells.stream().filter(cell -> cell.id().equals(id)).findFirst();
	}
}

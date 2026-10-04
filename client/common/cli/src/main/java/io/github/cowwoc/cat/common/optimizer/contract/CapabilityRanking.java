package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model families of a harness ranked by advertised capability, with the source of the ranking.
 * <p>
 * A model's family is its name without its version: {@code gpt-6.1-sol} has the version {@code 6.1} and the family
 * {@code sol}, while a name without a version, such as the alias {@code opus}, is its own family. Models rank by
 * family first and, within a family, an older version below a newer one; a model without a version ranks below every
 * versioned model of its family.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param families the families, most capable first
 * @param source where the ranking came from, such as the documentation's address and the date it was read
 */
public record CapabilityRanking(List<String> families, String source)
{
	/**
	 * A versioned model name: a prefix, the dotted version and the family.
	 */
	private static final Pattern VERSIONED = Pattern.compile("^[^-]+-(\\d+(?:\\.\\d+)*)-(.+)$");

	/**
	 * Creates a ranking.
	 *
	 * @param families the families, most capable first
	 * @param source where the ranking came from
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code families} is empty, a family is blank or contains whitespace, a
	 *   family is listed twice, or {@code source} is blank
	 */
	public CapabilityRanking
	{
		requireThat(families, "families").isNotEmpty();
		requireThat(source, "source").isNotBlank();
		families = List.copyOf(families);
		Set<String> unique = new HashSet<>();
		for (String family : families)
		{
			requireThat(family, "family").isNotEmpty().doesNotContainWhitespace();
			if (!unique.add(family))
				throw new IllegalArgumentException("The family '" + family + "' is ranked twice");
		}
	}

	/**
	 * Returns a model's family.
	 *
	 * @param model the model's name
	 * @return the name without its version, or the whole name if it has no version
	 * @throws NullPointerException if {@code model} is null
	 */
	public static String family(String model)
	{
		requireThat(model, "model").isNotNull();
		Matcher matcher = VERSIONED.matcher(model);
		if (matcher.matches())
			return matcher.group(2);
		return model;
	}

	/**
	 * Returns a model's version.
	 *
	 * @param model the model's name
	 * @return the components of the version, or empty if the model has no version
	 */
	private static List<Integer> version(String model)
	{
		Matcher matcher = VERSIONED.matcher(model);
		if (!matcher.matches())
			return List.of();
		List<Integer> components = new ArrayList<>();
		for (String component : matcher.group(1).split("\\."))
			components.add(Integer.parseInt(component));
		return components;
	}

	/**
	 * Indicates whether a model's family is ranked.
	 *
	 * @param model the model's name
	 * @return {@code true} if the ranking lists the model's family
	 * @throws NullPointerException if {@code model} is null
	 */
	public boolean isRanked(String model)
	{
		return families.contains(family(model));
	}

	/**
	 * Compares the capability of two models.
	 *
	 * @param first a model's name
	 * @param second another model's name
	 * @return a negative number if {@code first} is less capable than {@code second}, a positive number if it is more
	 *   capable, or 0 if both rank the same
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a model's family is unranked
	 */
	public int compare(String first, String second)
	{
		int byFamily = Integer.compare(position(second), position(first));
		if (byFamily != 0)
			return byFamily;
		return compareVersions(first, second);
	}

	/**
	 * Compares the versions of two models, whatever their families.
	 *
	 * @param first a model's name
	 * @param second another model's name
	 * @return a negative number if {@code first} has an older version than {@code second}, a positive number if it
	 *   has a newer one, or 0 if both have the same version; a model without a version is older than every model
	 *   with one
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static int compareVersions(String first, String second)
	{
		requireThat(first, "first").isNotNull();
		requireThat(second, "second").isNotNull();
		List<Integer> firstVersion = version(first);
		List<Integer> secondVersion = version(second);
		if (firstVersion.isEmpty() || secondVersion.isEmpty())
			return Boolean.compare(!firstVersion.isEmpty(), !secondVersion.isEmpty());
		for (int i = 0; i < Math.max(firstVersion.size(), secondVersion.size()); ++i)
		{
			int byComponent = Integer.compare(component(firstVersion, i), component(secondVersion, i));
			if (byComponent != 0)
				return byComponent;
		}
		return 0;
	}

	/**
	 * Returns the position of a model's family in the ranking.
	 *
	 * @param model the model's name
	 * @return the position, 0 for the most capable family
	 * @throws IllegalArgumentException if the model's family is unranked
	 */
	private int position(String model)
	{
		int position = families.indexOf(family(model));
		if (position == -1)
		{
			throw new IllegalArgumentException("The family of the model '" + model + "' is missing from the " +
				"capability ranking " + families);
		}
		return position;
	}

	/**
	 * Returns one component of a version.
	 *
	 * @param version the components of a version
	 * @param index the index of a component
	 * @return the component, or 0 if the version has fewer components
	 */
	private static int component(List<Integer> version, int index)
	{
		if (index < version.size())
			return version.get(index);
		return 0;
	}

	/**
	 * Describes the ranking for the user and the deriver.
	 *
	 * @return a line that lists the families, most capable first, with the source
	 */
	public String describe()
	{
		return "Capability ranking, most capable first (" + source + "): " + String.join(", ", families);
	}

	/**
	 * Returns the inventory models whose family is unranked.
	 *
	 * @param inventory a profile inventory
	 * @return the models, each once, in inventory order
	 * @throws NullPointerException if {@code inventory} is null
	 */
	public List<String> unranked(ProfileCatalog inventory)
	{
		requireThat(inventory, "inventory").isNotNull();
		Set<String> models = new LinkedHashSet<>();
		for (TrialProfile profile : inventory.profiles())
		{
			if (!isRanked(profile.model()))
				models.add(profile.model());
		}
		return List.copyOf(models);
	}
}

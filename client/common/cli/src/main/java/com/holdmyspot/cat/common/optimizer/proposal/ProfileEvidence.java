package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.ProfileBinding;
import com.holdmyspot.cat.common.optimizer.contract.CapabilityRanking;
import com.holdmyspot.cat.common.optimizer.contract.ProfileCatalog;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The available model and effort combinations (trial profiles), the capability ranking of their model families, and
 * the model or effort that each prompt file selects (its binding), recorded when the contract author ran.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param inventory the harness's profile inventory and its source
 * @param ranking the capability ranking that CAT ships for the harness
 * @param bindings the profile that each file binds, keyed by the file's path as the fork names it: relative to the
 *   project, or absolute outside it. A file that binds nothing is absent.
 */
public record ProfileEvidence(ProfileCatalog inventory, CapabilityRanking ranking, Map<String, TrialProfile> bindings)
{
	/**
	 * The separator between a link's path and its resolved target in a requirement's source.
	 */
	private static final String LINK_SEPARATOR = "->";

	/**
	 * Creates the evidence.
	 *
	 * @param inventory the harness's profile inventory and its source
	 * @param ranking the capability ranking that CAT ships for the harness
	 * @param bindings the profile that each file binds, keyed by the file's path as the fork names it
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a binding binds nothing
	 */
	public ProfileEvidence
	{
		requireThat(inventory, "inventory").isNotNull();
		requireThat(ranking, "ranking").isNotNull();
		requireThat(bindings, "bindings").isNotNull();
		for (Map.Entry<String, TrialProfile> entry : bindings.entrySet())
			requireThat(entry.getValue().isBound(), "bindings[" + entry.getKey() + "].isBound()").isTrue();
		bindings = Map.copyOf(bindings);
	}

	/**
	 * Returns the bindings of the files that requirements name as their source.
	 * <p>
	 * A source written as {@code link -> target} uses the target's recorded binding when available. Otherwise, it
	 * uses the binding recorded at the link path, where staging copied the target's content. If neither path has a
	 * recorded binding, the source yields none.
	 *
	 * @param requirements the requirements whose source files are searched, in result order
	 * @return one binding per distinct source that binds a profile, named as the requirements name it, in requirement
	 *   order
	 * @throws NullPointerException if {@code requirements} is null or contains null
	 */
	public List<ProfileBinding> bindingsOf(List<Requirement> requirements)
	{
		requireThat(requirements, "requirements").isNotNull();
		Set<String> sources = new LinkedHashSet<>();
		for (Requirement requirement : requirements)
			sources.add(requirement.source());
		List<ProfileBinding> found = new ArrayList<>();
		for (String source : sources)
		{
			String[] parts = source.split(LINK_SEPARATOR);
			for (int i = parts.length - 1; i >= 0; --i)
			{
				TrialProfile profile = bindings.get(normalize(parts[i]));
				if (profile != null)
				{
					found.add(new ProfileBinding(source, profile));
					break;
				}
			}
		}
		return found;
	}

	/**
	 * Writes this evidence into a JSON object: {@code inventory_source}, {@code inventory} as an array of profiles,
	 * {@code ranking_source}, {@code ranking} as an array of model families and {@code bindings} as an array of
	 * profiles, each with its {@code file}, in file order. Each profile is an object with its {@code model} and
	 * {@code effort}.
	 *
	 * @param node the object that receives the properties
	 * @throws NullPointerException if {@code node} is null
	 */
	public void write(ObjectNode node)
	{
		requireThat(node, "node").isNotNull();
		node.put("inventory_source", inventory.source());
		ArrayNode profiles = node.putArray("inventory");
		for (TrialProfile profile : inventory.profiles())
			writeProfile(profile, profiles.addObject());
		node.put("ranking_source", ranking.source());
		ArrayNode families = node.putArray("ranking");
		ranking.families().forEach(families::add);
		ArrayNode bindingNodes = node.putArray("bindings");
		for (Map.Entry<String, TrialProfile> entry : bindings.entrySet().stream().
			sorted(Map.Entry.comparingByKey()).toList())
		{
			ObjectNode binding = bindingNodes.addObject();
			writeProfile(entry.getValue(), binding);
			binding.put("file", entry.getKey());
		}
	}

	/**
	 * Writes a profile's dimensions into a JSON object.
	 *
	 * @param profile the profile
	 * @param node    the object that receives its {@code model} and {@code effort}
	 */
	private static void writeProfile(TrialProfile profile, ObjectNode node)
	{
		node.put("model", profile.model());
		node.put("effort", profile.effort());
	}

	/**
	 * Reads the evidence that {@link #write(ObjectNode)} wrote.
	 *
	 * @param node the object
	 * @return the evidence
	 * @throws NullPointerException     if {@code node} is null
	 * @throws IllegalArgumentException if a property is missing or invalid
	 */
	public static ProfileEvidence read(JsonNode node)
	{
		requireThat(node, "node").isNotNull();
		if (!node.path("inventory_source").isString() || !node.path("inventory").isArray() ||
			!node.path("ranking_source").isString() || !node.path("ranking").isArray() ||
			!node.path("bindings").isArray())
		{
			throw new IllegalArgumentException("The profile evidence needs string \"inventory_source\" and " +
				"\"ranking_source\" properties and \"inventory\", \"ranking\" and \"bindings\" arrays");
		}
		List<TrialProfile> profiles = new ArrayList<>();
		for (JsonNode profile : node.get("inventory"))
			profiles.add(readProfile(profile));
		List<String> families = new ArrayList<>();
		for (JsonNode family : node.get("ranking"))
			families.add(family.asString());
		Map<String, TrialProfile> bindings = new LinkedHashMap<>();
		for (JsonNode binding : node.get("bindings"))
			bindings.put(binding.path("file").asString(), readProfile(binding));
		return new ProfileEvidence(new ProfileCatalog(node.get("inventory_source").asString(), profiles),
			new CapabilityRanking(families, node.get("ranking_source").asString()), bindings);
	}

	/**
	 * Reads a profile's dimensions.
	 *
	 * @param node an object with string {@code model} and {@code effort} properties
	 * @return the profile
	 * @throws IllegalArgumentException if a property is missing or invalid
	 */
	private static TrialProfile readProfile(JsonNode node)
	{
		if (!node.path("model").isString() || !node.path("effort").isString())
			throw new IllegalArgumentException("A profile needs string \"model\" and \"effort\" properties: " + node);
		return new TrialProfile(node.get("model").asString(), node.get("effort").asString());
	}

	/**
	 * Normalizes a path that a requirement's source names.
	 *
	 * @param path the path
	 * @return the normalized path, or the stripped text if it is not a valid path
	 */
	private static String normalize(String path)
	{
		String stripped = path.strip();
		try
		{
			return PortablePaths.text(Path.of(stripped).normalize());
		}
		catch (InvalidPathException _)
		{
			return stripped;
		}
	}
}

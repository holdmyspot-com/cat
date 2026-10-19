package com.holdmyspot.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.CapabilityRanking;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the capability ranking resource that a harness module ships.
 * <p>
 * The resource is a JSON object with exactly the fields {@code format_version} (1), {@code address} (the vendor
 * documentation that the ranking was taken from), {@code read} (the date it was read) and {@code families} (the
 * model families, most capable first). The ranking's source is {@code <address>, read <date>}.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class RankingResource
{
	private static final int FORMAT_VERSION = 1;
	private static final Set<String> FIELDS = Set.of("format_version", "address", "read", "families");

	/**
	 * Prevents construction.
	 */
	private RankingResource()
	{
	}

	/**
	 * Parses a ranking resource.
	 *
	 * @param in the content, or null if the resource is missing
	 * @param name the resource's name, for messages
	 * @return the ranking
	 * @throws NullPointerException if {@code name} is null
	 * @throws IOException if the resource is missing, cannot be read or departs from the resource format
	 */
	public static CapabilityRanking parse(InputStream in, String name) throws IOException
	{
		requireThat(name, "name").isNotNull();
		if (in == null)
			throw new IOException("The capability ranking resource " + name + " is missing.");
		JsonNode root;
		try
		{
			root = JsonMapper.builder().build().readTree(in);
		}
		catch (JacksonException e)
		{
			throw new IOException("The capability ranking resource " + name + " is not JSON.", e);
		}
		if (!root.isObject() || !new HashSet<>(root.propertyNames()).equals(FIELDS))
		{
			throw new IOException("The capability ranking resource " + name + " does not have exactly the fields " +
				FIELDS + ".");
		}
		if (root.path("format_version").asInt(-1) != FORMAT_VERSION)
		{
			throw new IOException("The capability ranking resource " + name + " has format_version " +
				root.path("format_version") + "; expected " + FORMAT_VERSION + ".");
		}
		List<String> families = new ArrayList<>();
		for (JsonNode family : root.path("families"))
			families.add(family.asString());
		try
		{
			return new CapabilityRanking(families, text(root, "address", name) + ", read " +
				text(root, "read", name));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("The capability ranking resource " + name + " is invalid.", e);
		}
	}

	/**
	 * Returns a non-blank text field.
	 *
	 * @param node the object
	 * @param field the field's name
	 * @param name the resource's name, for messages
	 * @return the value
	 * @throws IOException if the field is not non-blank text
	 */
	private static String text(JsonNode node, String field, String name) throws IOException
	{
		JsonNode value = node.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("The field " + field + " in " + name + " is not non-blank text.");
		return value.asString();
	}
}

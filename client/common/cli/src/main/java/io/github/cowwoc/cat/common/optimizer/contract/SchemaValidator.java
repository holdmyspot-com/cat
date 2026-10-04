package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoublePredicate;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Checks a JSON value against the subset of JSON Schema that CAT's packaged schemas for session output use, such
 * as {@code proposal.schema.json}, including references to the root schema's {@code $defs}.
 * <p>
 * Each schema is the single definition of its output's shape, so the validator reads it rather than repeating it. A
 * keyword outside the subset is an error, because ignoring it would make the schema promise a check that never runs.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class SchemaValidator
{
	/**
	 * Keywords that describe a schema without constraining a value.
	 */
	private static final Set<String> ANNOTATIONS = Set.of("$schema", "$id", "title", "description");
	private final JsonNode rootSchema;

	/**
	 * Creates a validator.
	 *
	 * @param rootSchema the schema that every value is checked against
	 * @throws NullPointerException if {@code rootSchema} is null
	 */
	public SchemaValidator(JsonNode rootSchema)
	{
		requireThat(rootSchema, "rootSchema").isNotNull();
		this.rootSchema = rootSchema;
	}

	/**
	 * Reports violations of the supported schema constraints.
	 *
	 * @param value the value to check
	 * @return one message per violation, each beginning with the location of the offending value, such as
	 *   {@code $.categories[0].risk_rank}. Empty if the value is valid.
	 * @throws IllegalArgumentException if the schema uses a keyword outside the supported subset
	 */
	public List<String> violations(JsonNode value)
	{
		List<String> violations = new ArrayList<>();
		check(rootSchema, value, "$", violations);
		return violations;
	}

	/**
	 * Checks one value against one schema and its sub-schemas.
	 *
	 * @param schema the schema
	 * @param value the value
	 * @param location the location of the value
	 * @param violations receives each violation
	 */
	private void check(JsonNode schema, JsonNode value, String location, List<String> violations)
	{
		for (Map.Entry<String, JsonNode> keyword : schema.properties())
		{
			String name = keyword.getKey();
			JsonNode argument = keyword.getValue();
			switch (name)
			{
				case "type" -> checkType(argument, value, location, violations);
				case "pattern" -> checkPattern(argument.asString(), value, location, violations);
				case "minimum" -> checkBound(value, location, violations, "at least " + argument.asString(),
					number -> number >= argument.asDouble());
				case "exclusiveMinimum" -> checkBound(value, location, violations, "greater than " + argument.asString(),
					number -> number > argument.asDouble());
				case "maximum" -> checkBound(value, location, violations, "at most " + argument.asString(),
					number -> number <= argument.asDouble());
				case "minItems" -> checkMinItems(argument.asInt(), value, location, violations);
				case "required" -> checkRequired(argument, value, location, violations);
				// Both keywords are checked together below, because additionalProperties depends on properties.
				case "properties", "additionalProperties" ->
				{
				}
				case "items" ->
				{
					if (value.isArray())
					{
						for (int i = 0; i < value.size(); ++i)
							check(argument, value.get(i), location + "[" + i + "]", violations);
					}
				}
				case "$ref" -> check(definition(argument.asString(), location), value, location, violations);
				// Definitions constrain a value only where a $ref names them.
				case "$defs" ->
				{
				}
				default ->
				{
					if (!ANNOTATIONS.contains(name))
						throw new IllegalArgumentException("Unsupported schema keyword '" + name + "' at " + location);
				}
			}
		}
		if (value.isObject())
			checkProperties(schema, value, location, violations);
	}

	/**
	 * Resolves a {@code $ref} to a definition in the root schema's {@code $defs}.
	 *
	 * @param reference the reference, such as {@code #/$defs/check}
	 * @param location the location of the value
	 * @return the definition's schema
	 * @throws IllegalArgumentException if the reference does not name a definition of the root schema
	 */
	private JsonNode definition(String reference, String location)
	{
		String prefix = "#/$defs/";
		if (reference.startsWith(prefix))
		{
			JsonNode definitions = rootSchema.get("$defs");
			if (definitions != null)
			{
				JsonNode definition = definitions.get(reference.substring(prefix.length()));
				if (definition != null)
					return definition;
			}
		}
		throw new IllegalArgumentException("Unsupported schema reference '" + reference + "' at " + location);
	}

	/**
	 * Checks the {@code type} keyword.
	 *
	 * @param type the expected JSON type, or an array of the JSON types that are allowed
	 * @param value the value
	 * @param location the location of the value
	 * @param violations receives a violation if the value has none of the types
	 */
	private static void checkType(JsonNode type, JsonNode value, String location, List<String> violations)
	{
		List<String> types = new ArrayList<>();
		if (type.isArray())
		{
			for (JsonNode element : type)
				types.add(element.asString());
		}
		else
			types.add(type.asString());
		boolean matches = false;
		for (String name : types)
			matches |= hasType(name, value, location);
		if (!matches)
			violations.add(location + ": expected " + String.join(" or ", types) + " but found " + describe(value));
	}

	/**
	 * Tells whether a value has a JSON type.
	 *
	 * @param type the JSON type
	 * @param value the value
	 * @param location the location of the value
	 * @return {@code true} if the value has the type
	 * @throws IllegalArgumentException if the type is outside the supported subset
	 */
	private static boolean hasType(String type, JsonNode value, String location)
	{
		return switch (type)
		{
			case "object" -> value.isObject();
			case "array" -> value.isArray();
			case "string" -> value.isString();
			case "integer" -> value.isIntegralNumber();
			case "number" -> value.isNumber();
			case "boolean" -> value.isBoolean();
			case "null" -> value.isNull();
			default -> throw new IllegalArgumentException("Unsupported schema type '" + type + "' at " + location);
		};
	}

	/**
	 * Describes the JSON type of a value.
	 *
	 * @param value the value
	 * @return the type name
	 */
	private static String describe(JsonNode value)
	{
		if (value.isObject())
			return "object";
		if (value.isArray())
			return "array";
		if (value.isString())
			return "string";
		if (value.isNumber())
			return "number";
		if (value.isBoolean())
			return "boolean";
		return "null";
	}

	/**
	 * Checks the {@code pattern} keyword, which applies to strings and matches anywhere in the string.
	 *
	 * @param pattern the regular expression
	 * @param value the value
	 * @param location the location of the value
	 * @param violations receives a violation if a string does not match
	 */
	private static void checkPattern(String pattern, JsonNode value, String location, List<String> violations)
	{
		if (value.isString() && !Pattern.compile(pattern).matcher(value.asString()).find())
		{
			if (pattern.equals("\\S"))
				violations.add(location + ": must not be blank");
			else
				violations.add(location + ": must match " + pattern);
		}
	}

	/**
	 * Checks a numeric bound, which applies to numbers.
	 *
	 * @param value the value
	 * @param location the location of the value
	 * @param violations receives a violation if the bound is broken
	 * @param requirement the bound in words, such as {@code at least 1}
	 * @param withinBound tells whether a number satisfies the bound
	 */
	private static void checkBound(JsonNode value, String location, List<String> violations, String requirement,
		DoublePredicate withinBound)
	{
		if (value.isNumber() && !withinBound.test(value.asDouble()))
			violations.add(location + ": must be " + requirement + " but is " + value.asString());
	}

	/**
	 * Checks the {@code minItems} keyword, which applies to arrays.
	 *
	 * @param minimum the smallest allowed length
	 * @param value the value
	 * @param location the location of the value
	 * @param violations receives a violation if the array is shorter
	 */
	private static void checkMinItems(int minimum, JsonNode value, String location, List<String> violations)
	{
		if (value.isArray() && value.size() < minimum)
			violations.add(location + ": must list at least " + minimum + " item(s) but lists " + value.size());
	}

	/**
	 * Checks the {@code required} keyword, which applies to objects.
	 *
	 * @param required the names of the required properties
	 * @param value the value
	 * @param location the location of the value
	 * @param violations receives a violation for each missing property
	 */
	private static void checkRequired(JsonNode required, JsonNode value, String location, List<String> violations)
	{
		if (!value.isObject())
			return;
		for (JsonNode name : required)
		{
			if (!value.has(name.asString()))
				violations.add(location + "." + name.asString() + ": required property is missing");
		}
	}

	/**
	 * Checks an object's properties against the {@code properties} and {@code additionalProperties} keywords.
	 *
	 * @param schema the object's schema
	 * @param value the object
	 * @param location the location of the object
	 * @param violations receives each violation
	 */
	private void checkProperties(JsonNode schema, JsonNode value, String location, List<String> violations)
	{
		JsonNode properties = schema.get("properties");
		JsonNode additional = schema.get("additionalProperties");
		if (additional != null && !additional.isBoolean())
			throw new IllegalArgumentException("Unsupported additionalProperties schema at " + location);
		boolean closed = additional != null && !additional.asBoolean();
		for (Map.Entry<String, JsonNode> property : value.properties())
		{
			String propertyLocation = location + "." + property.getKey();
			JsonNode propertySchema = null;
			if (properties != null)
				propertySchema = properties.get(property.getKey());
			if (propertySchema != null)
				check(propertySchema, property.getValue(), propertyLocation, violations);
			else if (closed)
				violations.add(propertyLocation + ": unexpected property");
		}
	}
}

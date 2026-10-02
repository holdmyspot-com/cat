package io.github.cowwoc.cat.common.optimizer.store;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Reads the fields of the JSON documents that campaign storage writes, reporting a document that does not have the
 * expected shape with an {@link IllegalArgumentException} whose message starts with the field's name.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class JsonFields
{
	/**
	 * The name of the field that holds a document's layout version.
	 */
	static final String VERSION = "format_version";

	/**
	 * Prevents instantiation.
	 */
	private JsonFields()
	{
	}

	/**
	 * Checks a document's format version.
	 *
	 * @param root the document
	 * @param expected the version that this version of CAT reads
	 * @throws IllegalArgumentException if the document is not an object or has a different format version
	 */
	static void requireVersion(JsonNode root, int expected)
	{
		requireObject(root, "$");
		int version = integer(root, VERSION);
		if (version != expected)
		{
			throw new IllegalArgumentException(VERSION + " is " + version + ", but this version of CAT reads " +
				expected);
		}
	}

	/**
	 * Checks that a value is an object.
	 *
	 * @param node the value
	 * @param name the value's name
	 * @throws IllegalArgumentException if the value is not an object
	 */
	static void requireObject(JsonNode node, String name)
	{
		if (node == null || !node.isObject())
			throw new IllegalArgumentException(name + ": must be an object");
	}

	/**
	 * Returns a field that may not be missing or null.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value
	 * @throws IllegalArgumentException if the field is missing or null
	 */
	static JsonNode field(JsonNode node, String name)
	{
		JsonNode value = nullable(node, name);
		if (value == null)
			throw new IllegalArgumentException(name + ": must have a value");
		return value;
	}

	/**
	 * Returns a field that must be present but may be null.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value, or null if it is JSON {@code null}
	 * @throws IllegalArgumentException if the field is missing
	 */
	static JsonNode nullable(JsonNode node, String name)
	{
		requireObject(node, name + "'s parent");
		JsonNode value = node.get(name);
		if (value == null)
			throw new IllegalArgumentException(name + ": is missing");
		if (value.isNull())
			return null;
		return value;
	}

	/**
	 * Returns the elements of an array field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the elements
	 * @throws IllegalArgumentException if the field is not an array
	 */
	static List<JsonNode> elements(JsonNode node, String name)
	{
		JsonNode value = field(node, name);
		if (!value.isArray())
			throw new IllegalArgumentException(name + ": must be an array");
		return value.valueStream().toList();
	}

	/**
	 * Returns a string field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the string
	 * @throws IllegalArgumentException if the field is not a string
	 */
	static String text(JsonNode node, String name)
	{
		return stringValue(field(node, name), name);
	}

	/**
	 * Returns a string value.
	 *
	 * @param value the value
	 * @param name the value's name
	 * @return the string
	 * @throws IllegalArgumentException if the value is not a string
	 */
	static String stringValue(JsonNode value, String name)
	{
		if (value == null || !value.isString())
			throw new IllegalArgumentException(name + ": must be a string");
		return value.asString();
	}

	/**
	 * Returns an {@code int} field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value
	 * @throws IllegalArgumentException if the field is not an {@code int}
	 */
	static int integer(JsonNode node, String name)
	{
		return intValue(field(node, name), name);
	}

	/**
	 * Returns an {@code int} value.
	 *
	 * @param value the value
	 * @param name the value's name
	 * @return the value
	 * @throws IllegalArgumentException if the value is not an {@code int}
	 */
	static int intValue(JsonNode value, String name)
	{
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
			throw new IllegalArgumentException(name + ": must be a 32-bit integer");
		return value.asInt();
	}
}

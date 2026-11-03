package com.holdmyspot.cat.common.updater.trust;

import com.holdmyspot.cat.common.updater.trust.TrustException.Reason;
import java.util.Set;
import java.util.StringJoiner;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the fields of the updater's signed manifest and trust policy, refusing every value that their formats do not
 * define with the format's reason and a message that names the field.
 */
final class TrustJson
{
	/**
	 * Parses and writes manifests and policies. It refuses a repeated key, so that two readers of the same signed bytes
	 * cannot disagree on a field's value, and refuses content after the top-level value.
	 */
	static final JsonMapper MAPPER = JsonMapper.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
		build();
	/**
	 * The reason for every refusal.
	 */
	private final Reason reason;
	/**
	 * The name of the document in messages, such as {@code release manifest}.
	 */
	private final String document;

	/**
	 * Creates a reader.
	 *
	 * @param reason   the reason for every refusal
	 * @param document the name of the document in messages
	 */
	TrustJson(Reason reason, String document)
	{
		this.reason = reason;
		this.document = document;
	}

	/**
	 * Parses a document whose top level is an object.
	 *
	 * @param bytes the document's UTF-8 bytes
	 * @return the object
	 * @throws TrustException if the bytes are not one JSON object
	 */
	JsonNode parseObject(byte[] bytes) throws TrustException
	{
		JsonNode root;
		try
		{
			root = MAPPER.readTree(bytes);
		}
		catch (JacksonException e)
		{
			throw new TrustException(reason, "The " + document + " is not valid JSON: " +
				TrustText.excerpt(e.getOriginalMessage()));
		}
		if (root == null || !root.isObject())
			throw refusal("The " + document + " must be a JSON object");
		return root;
	}

	/**
	 * Refuses an object that has a field other than those that its format defines.
	 *
	 * @param object  the object
	 * @param path    the object's location in the document, such as {@code assets[0]}, or empty for the top level
	 * @param allowed the fields that the format defines
	 * @throws TrustException if the object has another field
	 */
	void requireOnly(JsonNode object, String path, Set<String> allowed) throws TrustException
	{
		for (String name : object.propertyNames())
		{
			if (!allowed.contains(name))
			{
				throw refusal("The " + document + " has an unknown field \"" + TrustText.excerpt(name) + "\" " +
					where(path) + ". Allowed fields: " + String.join(", ", allowed.stream().sorted().toList()));
			}
		}
	}

	/**
	 * Returns a required field.
	 *
	 * @param object the object
	 * @param path   the object's location in the document, or empty for the top level
	 * @param name   the field's name
	 * @return the field's value
	 * @throws TrustException if the field is absent
	 */
	JsonNode required(JsonNode object, String path, String name) throws TrustException
	{
		JsonNode value = object.get(name);
		if (value == null)
			throw refusal("The " + document + " lacks the field \"" + name + "\" " + where(path));
		return value;
	}

	/**
	 * Returns a required object field.
	 *
	 * @param object the object
	 * @param path   the object's location in the document, or empty for the top level
	 * @param name   the field's name
	 * @return the field's value
	 * @throws TrustException if the field is absent or is not an object
	 */
	JsonNode requiredObject(JsonNode object, String path, String name) throws TrustException
	{
		JsonNode value = required(object, path, name);
		if (!value.isObject())
			throw refusal(field(path, name) + " must be a JSON object");
		return value;
	}

	/**
	 * Returns a required array field.
	 *
	 * @param object the object
	 * @param path   the object's location in the document, or empty for the top level
	 * @param name   the field's name
	 * @return the field's value
	 * @throws TrustException if the field is absent or is not an array
	 */
	JsonNode requiredArray(JsonNode object, String path, String name) throws TrustException
	{
		JsonNode value = required(object, path, name);
		if (!value.isArray())
			throw refusal(field(path, name) + " must be a JSON array");
		return value;
	}

	/**
	 * Returns a required string field.
	 *
	 * @param object the object
	 * @param path   the object's location in the document, or empty for the top level
	 * @param name   the field's name
	 * @return the field's value
	 * @throws TrustException if the field is absent or is not a string
	 */
	String requiredString(JsonNode object, String path, String name) throws TrustException
	{
		JsonNode value = required(object, path, name);
		if (!value.isString())
			throw refusal(field(path, name) + " must be a JSON string");
		return value.stringValue();
	}

	/**
	 * Returns a required integer field.
	 *
	 * @param object  the object
	 * @param path    the object's location in the document, or empty for the top level
	 * @param name    the field's name
	 * @param minimum the lowest value allowed
	 * @param maximum the highest value allowed
	 * @return the field's value
	 * @throws TrustException if the field is absent, is not an integer written without a fraction or exponent, or lies
	 *                        outside the range
	 */
	long requiredInteger(JsonNode object, String path, String name, long minimum, long maximum)
		throws TrustException
	{
		JsonNode value = required(object, path, name);
		if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum ||
			value.longValue() > maximum)
		{
			throw refusal(field(path, name) + " must be an integer from " + minimum + " to " + maximum + ": " +
				TrustText.excerpt(value.toString()));
		}
		return value.longValue();
	}

	/**
	 * Refuses a document whose {@code format} field is not {@code 1}.
	 *
	 * @param root the document's top-level object
	 * @throws TrustException if the field is absent or is not {@code 1}
	 */
	void requireFormat(JsonNode root) throws TrustException
	{
		JsonNode format = required(root, "", "format");
		if (!format.isIntegralNumber() || !format.canConvertToLong() || format.longValue() != 1)
		{
			throw refusal("The " + document + " has format " + TrustText.excerpt(format.toString()) +
				", but this updater reads only format 1");
		}
	}

	/**
	 * Returns the name of a field in messages.
	 *
	 * @param path the object's location in the document, or empty for the top level
	 * @param name the field's name
	 * @return the field's name, qualified with its location
	 */
	String field(String path, String name)
	{
		StringJoiner joiner = new StringJoiner(".", "The " + document + "'s field \"", "\"");
		if (!path.isEmpty())
			joiner.add(path);
		joiner.add(name);
		return joiner.toString();
	}

	/**
	 * Returns an exception that refuses the document.
	 *
	 * @param message the field or rule that the document broke
	 * @return the exception
	 */
	TrustException refusal(String message)
	{
		return new TrustException(reason, message);
	}

	/**
	 * Returns where an object lies in messages.
	 *
	 * @param path the object's location in the document, or empty for the top level
	 * @return the location
	 */
	private static String where(String path)
	{
		if (path.isEmpty())
			return "at its top level";
		return "in " + path;
	}
}

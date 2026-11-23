package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records which credential a nested session's harness uses: the source home, the name of each setting or file that the
 * runner copied from it into the session or shared with the session, and the class of the login that the harness
 * selects among them. It never holds a value of a setting or file. {@code session.json}, the receipt and the response
 * to a session request write it as the JSON object of the field {@value #FIELD}:
 * {@code {"home": "<source home>", "parts": ["<name>", ...], "login_class": "<subscription|api_key|unknown>"}}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param home       the absolute source home
 * @param parts      the names of the copied or shared settings and files, such as {@code apiKeyHelper} or
 *                   {@code .credentials.json}, in the order that the harness ranks them
 * @param loginClass the class of the login that the harness selects
 */
public record AuthenticationEvidence(Path home, List<String> parts, HostLogin loginClass)
{
	/**
	 * The field that holds the evidence in a record.
	 */
	public static final String FIELD = "authentication";
	/**
	 * The fields of the evidence's JSON object.
	 */
	private static final Set<String> FIELDS = Set.of("home", "parts", "login_class");

	/**
	 * Creates evidence.
	 *
	 * @param home       the absolute source home
	 * @param parts      the names of the copied or shared settings and files
	 * @param loginClass the class of the login that the harness selects
	 * @throws NullPointerException     if any of the arguments are null, or {@code parts} contains null
	 * @throws IllegalArgumentException if {@code home} is not absolute, or a part is blank
	 */
	public AuthenticationEvidence
	{
		requireThat(home, "home").isNotNull();
		if (!home.isAbsolute())
			throw new IllegalArgumentException("home must be absolute: " + home);
		requireThat(parts, "parts").isNotNull();
		for (String part : parts)
			requireThat(part, "part").isNotBlank();
		parts = List.copyOf(parts);
		requireThat(loginClass, "loginClass").isNotNull();
	}

	/**
	 * Returns the evidence as a JSON object.
	 *
	 * @param json the JSON mapper
	 * @return the object
	 * @throws NullPointerException if {@code json} is null
	 */
	public ObjectNode toJson(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		ObjectNode node = json.createObjectNode();
		node.put("home", home.toString());
		ArrayNode names = node.putArray("parts");
		for (String part : parts)
			names.add(part);
		node.put("login_class", AuthenticationOptions.classValue(loginClass));
		return node;
	}

	/**
	 * Reads evidence from its JSON object.
	 *
	 * @param node the object
	 * @return the evidence
	 * @throws NullPointerException if {@code node} is null
	 * @throws IOException          if {@code node} is not an object with exactly the evidence's fields, each of its
	 *                              type, or names an unknown class
	 */
	public static AuthenticationEvidence fromJson(JsonNode node) throws IOException
	{
		requireThat(node, "node").isNotNull();
		if (!node.isObject())
			throw new IOException("The authentication record is not a JSON object");
		for (String field : node.propertyNames())
		{
			if (!FIELDS.contains(field))
				throw new IOException("The authentication record has an unknown field: " + field);
		}
		JsonNode home = node.path("home");
		JsonNode names = node.path("parts");
		JsonNode loginClass = node.path("login_class");
		if (!home.isString() || !names.isArray() || !loginClass.isString())
			throw new IOException("The authentication record lacks a text home, an array of parts or a text login_class");
		List<String> parts = new ArrayList<>();
		for (JsonNode name : names)
		{
			if (!name.isString())
				throw new IOException("The authentication record has a part that is not text");
			parts.add(name.stringValue());
		}
		for (HostLogin login : HostLogin.values())
		{
			if (AuthenticationOptions.classValue(login).equals(loginClass.stringValue()))
			{
				try
				{
					return new AuthenticationEvidence(Path.of(home.stringValue()), parts, login);
				}
				catch (IllegalArgumentException e)
				{
					throw new IOException("The authentication record is not valid: " + e.getMessage(), e);
				}
			}
		}
		throw new IOException("The authentication record names an unknown login_class: " + loginClass.stringValue());
	}
}

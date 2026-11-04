package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java runtime that one application needs: a minimum Java feature release and the system modules that its module
 * graph resolves to. A plugin declares it in {@code client/runtime-requirement.json}, the updater application in
 * {@code <version>/runtime-requirement.json}.
 *
 * @param javaFeature the minimum Java feature release, such as 27
 * @param modules     the system modules that the runtime must contain
 */
public record RuntimeRequirement(int javaFeature, SortedSet<String> modules)
{
	/**
	 * The plugin-relative requirement file.
	 */
	public static final Path PLUGIN_FILE = Path.of("client/runtime-requirement.json");
	/**
	 * Runtime descriptions and requirements are small; a larger file is not one CAT wrote.
	 */
	private static final int MAXIMUM_BYTES = 1024 * 1024;
	/**
	 * Refuses duplicate members and trailing content so that one file has exactly one meaning.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
		enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	/**
	 * Creates a requirement.
	 *
	 * @param javaFeature the minimum Java feature release, such as 27
	 * @param modules     the system modules that the runtime must contain
	 * @throws NullPointerException     if {@code modules} is null
	 * @throws IllegalArgumentException if {@code javaFeature} is not positive
	 */
	public RuntimeRequirement
	{
		requireThat(javaFeature, "javaFeature").isPositive();
		modules = new TreeSet<>(modules);
	}

	/**
	 * Reads a requirement file of format 1: {@code {"format": 1, "java_feature": N, "modules": [...]}}.
	 *
	 * @param file the requirement file
	 * @return the requirement
	 * @throws NullPointerException if {@code file} is null
	 * @throws IOException          if the file is absent, a symbolic link, unreadable or malformed
	 */
	public static RuntimeRequirement read(Path file) throws IOException
	{
		JsonNode root = readObject(file, Set.of("format", "java_feature", "modules"), "runtime requirement");
		return new RuntimeRequirement(feature(root, file), modules(root, file));
	}

	/**
	 * Reads one strict JSON object of format 1 with exactly the given members, as CAT reads each small description
	 * file that it writes into a release: duplicate members, trailing content and symbolic links are refused.
	 *
	 * @param file        the file to read
	 * @param members     the complete member names
	 * @param description the document's name in diagnostics
	 * @return the object
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file is absent, a symbolic link, unreadable or malformed
	 */
	public static JsonNode readObject(Path file, Set<String> members, String description) throws IOException
	{
		requireThat(file, "file").isNotNull();
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("The " + description + " is missing or is not a regular file: " + file);
		byte[] bytes;
		try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))
		{
			bytes = BoundedInput.readBytes(input, file, MAXIMUM_BYTES, "The " + description);
		}
		JsonNode root;
		try
		{
			root = MAPPER.readTree(bytes);
		}
		catch (JacksonException e)
		{
			throw new IOException("The " + description + " is not valid JSON: " + file, e);
		}
		if (root == null || !root.isObject() || !root.propertyNames().equals(members))
			throw new IOException("The " + description + " must have exactly the members " + members + ": " + file);
		JsonNode format = root.get("format");
		if (!format.isIntegralNumber() || !format.canConvertToInt() || format.intValue() != 1)
			throw new IOException("The " + description + " must have format 1: " + file);
		return root;
	}

	/**
	 * Returns the positive {@code java_feature} member.
	 *
	 * @param root the document
	 * @param file the document's file, for diagnostics
	 * @return the feature release
	 * @throws IOException if the member is not a positive integer
	 */
	static int feature(JsonNode root, Path file) throws IOException
	{
		JsonNode feature = root.get("java_feature");
		if (!feature.isIntegralNumber() || !feature.canConvertToInt() || feature.intValue() <= 0)
			throw new IOException("The member java_feature must be a positive integer: " + file);
		return feature.intValue();
	}

	/**
	 * Returns the {@code modules} member.
	 *
	 * @param root the document
	 * @param file the document's file, for diagnostics
	 * @return the distinct module names
	 * @throws IOException if the member is not an array of distinct, non-blank strings
	 */
	static SortedSet<String> modules(JsonNode root, Path file) throws IOException
	{
		JsonNode array = root.get("modules");
		if (!array.isArray())
			throw new IOException("The member modules must be an array: " + file);
		SortedSet<String> modules = new TreeSet<>();
		for (JsonNode module : array)
		{
			if (!module.isString() || module.stringValue().isBlank() || !modules.add(module.stringValue()))
				throw new IOException("The member modules must list distinct, non-blank module names: " + file);
		}
		return modules;
	}
}

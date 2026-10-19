package com.holdmyspot.cat.common.optimizer.port.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks that a harness registers each version that it declares with the hash of the content that the version stands
 * for, so that a change to that content cannot ship without a decision about the version.
 * <p>
 * A registry is a UTF-8 text file. Each line that is neither blank nor starts with {@code #} holds a version and the
 * lowercase hexadecimal SHA-256 hash of its content, separated by a space, for example
 * {@code 1 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08}.
 */
public final class VersionRegistry
{
	/**
	 * Prevents instantiation.
	 */
	private VersionRegistry()
	{
	}

	/**
	 * Returns the hash of content in the form that a registry records.
	 *
	 * @param content the content
	 * @return the lowercase hexadecimal SHA-256 hash of the content's UTF-8 encoding
	 * @throws NullPointerException if {@code content} is null
	 */
	public static String hash(String content)
	{
		requireThat(content, "content").isNotNull();
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("Every Java platform supports SHA-256", e);
		}
	}

	/**
	 * Requires that a registry assigns one hash to each version and registers a version with a hash.
	 *
	 * @param registry the registry file
	 * @param subject what the versions identify, such as {@code "session environment"}, for the failure message
	 * @param version the version that the harness declares
	 * @param hash the hash of the content that the harness has now
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the registry cannot be read
	 * @throws AssertionError if a line is malformed, a version is registered with two hashes, or {@code version} is
	 *   not registered with {@code hash}
	 */
	public static void requireRegistered(Path registry, String subject, int version, String hash)
		throws IOException
	{
		requireThat(registry, "registry").isNotNull();
		requireThat(subject, "subject").isNotNull();
		requireThat(hash, "hash").isNotNull();
		Map<Integer, String> hashes = new TreeMap<>();
		List<String> lines = Files.readAllLines(registry, UTF_8);
		for (int index = 0; index < lines.size(); ++index)
		{
			String line = lines.get(index).strip();
			if (line.isEmpty() || line.startsWith("#"))
				continue;
			String[] fields = line.split(" ");
			if (fields.length != 2 || !fields[0].matches("[0-9]+") || !fields[1].matches("[0-9a-f]{64}"))
			{
				throw new AssertionError(registry + ", line " + (index + 1) + ": expected a version and a " +
					"lowercase hexadecimal SHA-256 hash separated by a space, but was: " + line);
			}
			int registered = Integer.parseInt(fields[0]);
			String previous = hashes.putIfAbsent(registered, fields[1]);
			if (previous != null && !previous.equals(fields[1]))
			{
				throw new AssertionError(registry + " registers " + subject + " version " + registered +
					" with two hashes: " + previous + " and " + fields[1] + ". Each version must stand for one " +
					"content.");
			}
		}
		if (!hash.equals(hashes.get(version)))
		{
			throw new AssertionError("The " + subject + " content hashes to " + hash + ", which " + registry +
				" does not register under the declared " + subject + " version " + version + ". Increment the " +
				"version and register the new version with this hash.");
		}
	}
}

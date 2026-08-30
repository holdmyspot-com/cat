package io.github.cowwoc.cat.common.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Function;

/**
 * Partitions semantic validator evidence into stable capacity-bounded groups.
 */
public final class ValidatorSchedule
{
	/**
	 * Prevents instantiation.
	 */
	private ValidatorSchedule()
	{
	}

	/**
	 * Balances ordered semantic evidence across the available validator workers.
	 *
	 * @param evidence the semantic evidence
	 * @param maximumConcurrency the shared validator-worker capacity
	 * @param key the stable key for one evidence item
	 * @return the ordered nonempty validator groups
	 * @param <T> the opaque evidence type
	 */
	public static <T> List<Group<T>> groups(List<T> evidence, int maximumConcurrency, Function<T, String> key)
	{
		if (evidence.isEmpty())
			return List.of();
		List<T> ordered = evidence.stream().sorted(Comparator.comparing(key)).toList();
		int groupCount = Math.min(maximumConcurrency, ordered.size());
		int smallerGroupSize = ordered.size() / groupCount;
		int largerGroupCount = ordered.size() % groupCount;
		List<Group<T>> result = new ArrayList<>();
		int first = 0;
		for (int ordinal = 1; ordinal <= groupCount; ++ordinal)
		{
			int groupSize = smallerGroupSize;
			if (ordinal <= largerGroupCount)
				++groupSize;
			result.add(new Group<>(ordinal, ordered.subList(first, first + groupSize)));
			first += groupSize;
		}
		return result;
	}

	/**
	 * Returns the stable workspace identity for one immutable validator input set.
	 *
	 * @param workspaceRoot the evaluator-owned round workspace
	 * @param evidence the semantic evidence
	 * @param key the stable key for one evidence item
	 * @return the initially absent validator round workspace
	 * @throws java.io.IOException if the evidence key cannot be fingerprinted
	 * @param <T> the opaque evidence type
	 */
	public static <T> Path workspace(Path workspaceRoot, List<T> evidence, Function<T, String> key)
		throws java.io.IOException
	{
		String keys = evidence.stream().map(key).sorted().collect(
			java.util.stream.Collectors.joining("\n"));
		return workspaceRoot.resolve("validator-round-" + sha256(keys).substring(0, 12));
	}

	/**
	 * Holds one stable nonempty validator work assignment.
	 *
	 * @param ordinal the stable group order
	 * @param evidence the assigned evidence
	 * @param <T> the opaque evidence type
	 */
	public record Group<T>(int ordinal, List<T> evidence)
	{
		/**
		 * Validates one work assignment.
		 *
		 * @param ordinal the stable group order
		 * @param evidence the assigned evidence
		 */
		public Group
		{
			if (ordinal < 1)
				throw new IllegalArgumentException("ordinal must be positive");
			evidence = List.copyOf(evidence);
			if (evidence.isEmpty())
				throw new IllegalArgumentException("evidence must not be empty");
		}
	}

	/**
	 * Hashes one stable workspace key.
	 *
	 * @param value the key text
	 * @return its SHA-256 fingerprint
	 * @throws java.io.IOException if SHA-256 is unavailable
	 */
	private static String sha256(String value) throws java.io.IOException
	{
		try
		{
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
				value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new java.io.IOException("SHA-256 is unavailable", e);
		}
	}
}

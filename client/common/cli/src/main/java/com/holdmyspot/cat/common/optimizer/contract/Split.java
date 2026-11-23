package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Assigns each test case of a prompt test to the development part or the held-out part by its expected outcome,
 * stratified by boundary class.
 * <p>
 * Test cases that share a normalized expected outcome share a part. A committed test case, and a new one that shares a
 * committed test case's expected outcome, belong to the development part, because an earlier campaign already showed
 * them to the prompt author. The other distinct outcomes of a prompt test are taken in the order of a SHA-256 hash of
 * the salt and the outcome's normalized form, and each goes to the part that holds fewer distinct outcomes of its
 * boundary class so far, where a committed outcome counts for development; a tie goes to the part that the hash's
 * lowest bit names. So every class with 2 new outcomes has one in each part, whatever the salt, and the assignment
 * depends only on the test's test cases and the salt, never on their order.
 * <p>
 * The outcome of untagged test cases, which a values file that predates boundary classes holds, goes to the part that
 * the hash's lowest bit names, so a campaign that was confirmed before boundary classes keeps its parts.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class Split
{
	/**
	 * A run of Unicode whitespace.
	 */
	private static final Pattern WHITESPACE = Pattern.compile("\\p{IsWhite_Space}+");
	private final long salt;

	/**
	 * Creates a split.
	 *
	 * @param salt the contract's salt
	 */
	public Split(long salt)
	{
		this.salt = salt;
	}

	/**
	 * Returns the form under which two expected outcomes are the same: Unicode canonical composition, each run of
	 * whitespace replaced by one space, no leading or trailing whitespace, and lower case.
	 *
	 * @param expectedOutcome an expected outcome
	 * @return the normalized outcome
	 * @throws NullPointerException if {@code expectedOutcome} is null
	 */
	public static String normalize(String expectedOutcome)
	{
		String composed = Normalizer.normalize(expectedOutcome, Normalizer.Form.NFC);
		return WHITESPACE.matcher(composed).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
	}

	/**
	 * Returns the part that the salted hash assigns to an expected outcome, ignoring committed test cases.
	 *
	 * @param expectedOutcome an expected outcome
	 * @return the part
	 * @throws NullPointerException if {@code expectedOutcome} is null
	 */
	public Part partOf(String expectedOutcome)
	{
		return hashPart(hash(normalize(expectedOutcome)));
	}

	/**
	 * Returns the SHA-256 hash of the salt and a normalized expected outcome.
	 *
	 * @param normalizedOutcome a normalized expected outcome
	 * @return the hash
	 */
	private byte[] hash(String normalizedOutcome)
	{
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			// Every Java platform provides SHA-256
			throw new AssertionError(e);
		}
		digest.update(ByteBuffer.allocate(Long.BYTES).putLong(salt).array());
		digest.update(normalizedOutcome.getBytes(StandardCharsets.UTF_8));
		return digest.digest();
	}

	/**
	 * Returns the part that a hash's lowest bit names.
	 *
	 * @param hash the hash of the salt and an expected outcome
	 * @return the part
	 */
	private static Part hashPart(byte[] hash)
	{
		if ((hash[0] & 1) == 0)
			return Part.DEVELOPMENT;
		return Part.HELD_OUT;
	}

	/**
	 * Returns the part of each distinct normalized expected outcome of a prompt test.
	 *
	 * @param test the prompt test
	 * @return the part of each normalized outcome
	 */
	private Map<String, Part> parts(PromptTest test)
	{
		Map<String, String> classes = classes(test);
		Set<String> committed = committedOutcomes(test);
		Map<String, Part> parts = new HashMap<>();
		Map<String, int[]> counts = new HashMap<>();
		for (String outcome : committed)
		{
			parts.put(outcome, Part.DEVELOPMENT);
			String boundaryClass = classes.get(outcome);
			if (!boundaryClass.isEmpty())
				++counts.computeIfAbsent(boundaryClass, _ -> new int[2])[Part.DEVELOPMENT.ordinal()];
		}
		record Hashed(String outcome, byte[] hash)
		{
		}
		List<Hashed> tagged = new ArrayList<>();
		for (String outcome : classes.keySet())
		{
			if (committed.contains(outcome))
				continue;
			byte[] hash = hash(outcome);
			if (classes.get(outcome).isEmpty())
				parts.put(outcome, hashPart(hash));
			else
				tagged.add(new Hashed(outcome, hash));
		}
		tagged.sort(Comparator.<Hashed, byte[]>comparing(Hashed::hash, Arrays::compareUnsigned).
			thenComparing(Hashed::outcome));
		for (Hashed hashed : tagged)
		{
			int[] count = counts.computeIfAbsent(classes.get(hashed.outcome()), _ -> new int[2]);
			int development = count[Part.DEVELOPMENT.ordinal()];
			int heldOut = count[Part.HELD_OUT.ordinal()];
			Part part;
			if (development < heldOut)
				part = Part.DEVELOPMENT;
			else if (heldOut < development)
				part = Part.HELD_OUT;
			else
				part = hashPart(hashed.hash());
			++count[part.ordinal()];
			parts.put(hashed.outcome(), part);
		}
		return parts;
	}

	/**
	 * Returns the boundary class of each distinct normalized expected outcome of a prompt test. Test cases that share
	 * an outcome share a class; where they do not, which a proposal refuses, the outcome takes the least of their
	 * classes, so the split still ignores the test cases' order.
	 *
	 * @param test the prompt test
	 * @return the class of each normalized outcome, or an empty string if no test case of the outcome has one
	 */
	private static Map<String, String> classes(PromptTest test)
	{
		Map<String, String> classes = new HashMap<>();
		for (TestCase testCase : test.allTestCases())
		{
			classes.merge(normalize(testCase.expectedOutcome()), testCase.boundaryClass(), (first, second) ->
			{
				if (first.isEmpty())
					return second;
				if (second.isEmpty() || first.compareTo(second) <= 0)
					return first;
				return second;
			});
		}
		return classes;
	}

	/**
	 * Returns the part of a test case of a prompt test.
	 *
	 * @param test     the prompt test
	 * @param testCase a test case of the prompt test
	 * @return {@code DEVELOPMENT} if the test case is committed or shares a committed test case's expected outcome;
	 *   otherwise the part that the stratified split assigns to its expected outcome
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code testCase} is not a test case of {@code test}
	 */
	public Part partOf(PromptTest test, TestCase testCase)
	{
		requireThat(test, "test").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		Part part = parts(test).get(normalize(testCase.expectedOutcome()));
		if (part == null)
		{
			throw new IllegalArgumentException("testCase is not a test case of " + test.id() + ". Expected outcome: " +
				testCase.expectedOutcome());
		}
		return part;
	}

	/**
	 * Returns the normalized expected outcomes of a test's committed test cases.
	 *
	 * @param test the prompt test
	 * @return the outcomes
	 */
	private static Set<String> committedOutcomes(PromptTest test)
	{
		Set<String> outcomes = new HashSet<>();
		for (TestCase committed : test.committedTestCases())
			outcomes.add(normalize(committed.expectedOutcome()));
		return outcomes;
	}

	/**
	 * Returns the test cases of a prompt test that belong to a part.
	 *
	 * @param test the prompt test
	 * @param part the part
	 * @return the test cases, committed ones first, each in the test's order
	 * @throws NullPointerException if any argument is null
	 */
	public List<TestCase> testCases(PromptTest test, Part part)
	{
		requireThat(test, "test").isNotNull();
		requireThat(part, "part").isNotNull();
		Map<String, Part> parts = parts(test);
		return test.allTestCases().stream().
			filter(testCase -> parts.get(normalize(testCase.expectedOutcome())) == part).toList();
	}

	/**
	 * Returns the number of distinct normalized expected outcomes among a prompt test's test cases in a part.
	 *
	 * @param test the prompt test
	 * @param part the part
	 * @return the number of distinct outcomes
	 * @throws NullPointerException if any argument is null
	 */
	public int outcomeCount(PromptTest test, Part part)
	{
		Set<String> outcomes = new HashSet<>();
		for (TestCase testCase : testCases(test, part))
			outcomes.add(normalize(testCase.expectedOutcome()));
		return outcomes.size();
	}

	/**
	 * Returns the number of distinct normalized expected outcomes among the test cases of one boundary class of a
	 * prompt test in a part.
	 *
	 * @param test          the prompt test
	 * @param part          the part
	 * @param boundaryClass the boundary class, or an empty string for the untagged test cases
	 * @return the number of distinct outcomes
	 * @throws NullPointerException if any argument is null
	 */
	public int outcomeCount(PromptTest test, Part part, String boundaryClass)
	{
		requireThat(boundaryClass, "boundaryClass").isNotNull();
		Set<String> outcomes = new HashSet<>();
		for (TestCase testCase : testCases(test, part))
		{
			if (testCase.boundaryClass().equals(boundaryClass))
				outcomes.add(normalize(testCase.expectedOutcome()));
		}
		return outcomes.size();
	}
}

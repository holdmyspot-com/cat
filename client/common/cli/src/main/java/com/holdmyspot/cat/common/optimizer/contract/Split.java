package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Assigns each test case of a prompt test to the development part or the held-out part by its expected outcome.
 * <p>
 * The part of an expected outcome is the lowest bit of a SHA-256 hash of the salt and the outcome's normalized form, so
 * each distinct outcome lands in either part with probability ½, independently of the others, and test cases that
 * share an expected outcome share a part. A committed test case, and a new one that shares a committed test case's
 * expected outcome, belong to the development part, because an earlier campaign already showed them to the prompt
 * author.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
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
		digest.update(normalize(expectedOutcome).getBytes(StandardCharsets.UTF_8));
		if ((digest.digest()[0] & 1) == 0)
			return Part.DEVELOPMENT;
		return Part.HELD_OUT;
	}

	/**
	 * Returns the part of a test case of a prompt test.
	 *
	 * @param test     the prompt test
	 * @param testCase a test case of the prompt test
	 * @return {@code DEVELOPMENT} if the test case is committed or shares a committed test case's expected outcome;
	 *   otherwise the part of its expected outcome
	 * @throws NullPointerException if any argument is null
	 */
	public Part partOf(PromptTest test, TestCase testCase)
	{
		requireThat(test, "test").isNotNull();
		requireThat(testCase, "testCase").isNotNull();
		if (committedOutcomes(test).contains(normalize(testCase.expectedOutcome())))
			return Part.DEVELOPMENT;
		return partOf(testCase.expectedOutcome());
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
		Set<String> committed = committedOutcomes(test);
		return test.allTestCases().stream().filter(testCase ->
		{
			String outcome = normalize(testCase.expectedOutcome());
			if (committed.contains(outcome))
				return part == Part.DEVELOPMENT;
			return partOf(outcome) == part;
		}).toList();
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
}

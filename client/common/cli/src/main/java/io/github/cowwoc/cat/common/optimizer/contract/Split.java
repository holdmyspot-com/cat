package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assigns each combination of a test definition's key values to the development part or the held-out part.
 * <p>
 * The part is the lowest bit of a SHA-256 hash of the salt, the test's identifier and each key dimension with its
 * chosen label, so each combination lands in either part with probability ½, independently of the others, and
 * instances that share key values share a part.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class Split
{
	/**
	 * One combination of a test definition's key values.
	 *
	 * @param values the value of each key dimension, in the test's dimension order
	 * @param probability the product of the values' frozen probabilities
	 */
	public record KeyCombination(List<VariationValue> values, double probability)
	{
		/**
		 * Creates a combination.
		 *
		 * @param values the value of each key dimension, in the test's dimension order
		 * @param probability the product of the values' frozen probabilities
		 * @throws NullPointerException if {@code values} is null
		 * @throws IllegalArgumentException if {@code probability} is outside (0, 1]
		 */
		public KeyCombination
		{
			requireThat(values, "values").isNotNull();
			values = List.copyOf(values);
			VariationValue.requireProbability(probability);
		}
	}

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
	 * Returns the part of an instance's key values.
	 *
	 * @param test the test definition
	 * @param labelByDimension the label of the value chosen for each of the test's dimensions; only key dimensions
	 *   are read
	 * @return the part
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code labelByDimension} has no label for a key dimension
	 */
	public Part partOf(TestDefinition test, Map<String, String> labelByDimension)
	{
		requireThat(test, "test").isNotNull();
		requireThat(labelByDimension, "labelByDimension").isNotNull();
		List<VariationRule> keyRules = test.keyRules();
		List<String> labels = new ArrayList<>(keyRules.size());
		for (VariationRule rule : keyRules)
		{
			String label = labelByDimension.get(rule.dimension());
			if (label == null)
			{
				throw new IllegalArgumentException("The test '" + test.id() + "' has the key dimension '" +
					rule.dimension() + "', but no label was chosen for it. Labels: " + labelByDimension);
			}
			labels.add(label);
		}
		return partOf(test, keyRules, labels);
	}

	/**
	 * Returns the part of one combination of key values.
	 *
	 * @param test     the test definition
	 * @param keyRules the test's key rules
	 * @param labels   the label chosen for each key rule, in the same order
	 * @return the part
	 */
	private Part partOf(TestDefinition test, List<VariationRule> keyRules, List<String> labels)
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
		update(digest, test.id());
		for (int i = 0; i < keyRules.size(); ++i)
		{
			update(digest, keyRules.get(i).dimension());
			update(digest, labels.get(i));
		}
		if ((digest.digest()[0] & 1) == 0)
			return Part.DEVELOPMENT;
		return Part.HELD_OUT;
	}

	/**
	 * Adds a text to a digest, preceded by its length so that consecutive texts cannot run together.
	 *
	 * @param digest the digest
	 * @param text   the text
	 */
	private static void update(MessageDigest digest, String text)
	{
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	/**
	 * Returns the combinations of a test definition's key values that belong to a part.
	 *
	 * @param test the test definition
	 * @param part the part
	 * @return the combinations, in the order that varies the last key dimension fastest
	 * @throws NullPointerException if any argument is null
	 */
	public List<KeyCombination> combinations(TestDefinition test, Part part)
	{
		requireThat(test, "test").isNotNull();
		requireThat(part, "part").isNotNull();
		List<VariationRule> keyRules = test.keyRules();
		List<KeyCombination> combinations = new ArrayList<>();
		// The index of the chosen value of each key rule, advanced like an odometer whose last digit turns fastest
		int[] indices = new int[keyRules.size()];
		while (true)
		{
			List<VariationValue> values = new ArrayList<>(keyRules.size());
			List<String> labels = new ArrayList<>(keyRules.size());
			double probability = 1;
			for (int i = 0; i < keyRules.size(); ++i)
			{
				VariationValue value = keyRules.get(i).values().get(indices[i]);
				values.add(value);
				labels.add(value.label());
				probability *= value.probability();
			}
			if (partOf(test, keyRules, labels) == part)
				combinations.add(new KeyCombination(values, probability));

			int digit = keyRules.size() - 1;
			while (digit >= 0 && indices[digit] == keyRules.get(digit).values().size() - 1)
			{
				indices[digit] = 0;
				--digit;
			}
			if (digit < 0)
				return combinations;
			++indices[digit];
		}
	}
}

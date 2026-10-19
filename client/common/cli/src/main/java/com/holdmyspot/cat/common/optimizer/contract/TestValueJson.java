package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and reads a test case's values as a JSON array with one object per variable: {@code {"variable": name,
 * "text": text}} for a text value, and {@code {"variable": name, "call": {"generator": name, "version": n,
 * "seed": s, ...parameters}}} for a generated value. A call that a model writes leaves out {@code version} and
 * {@code seed}, which CAT adds, unless CAT already wrote them into the values file that the call comes from.
 */
public final class TestValueJson
{
	/**
	 * The property that names the variable.
	 */
	private static final String VARIABLE = "variable";
	/**
	 * The property that holds a text value.
	 */
	private static final String TEXT = "text";
	/**
	 * The property that holds a generator call.
	 */
	private static final String CALL = "call";
	/**
	 * The property of a call that names the generator.
	 */
	private static final String GENERATOR = "generator";
	/**
	 * The property of a call that holds the generator's version.
	 */
	private static final String VERSION = "version";
	/**
	 * The property of a call that holds the seed.
	 */
	private static final String SEED = "seed";

	/**
	 * Prevents instantiation.
	 */
	private TestValueJson()
	{
	}

	/**
	 * Writes each value.
	 *
	 * @param values the value of each variable, by name
	 * @param array  receives one object per variable, in the map's order
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static void write(Map<String, TestValue> values, ArrayNode array)
	{
		requireThat(values, "values").isNotNull();
		requireThat(array, "array").isNotNull();
		for (Map.Entry<String, TestValue> entry : values.entrySet())
		{
			ObjectNode node = array.addObject();
			node.put(VARIABLE, entry.getKey());
			switch (entry.getValue())
			{
				case TestValue.Text text -> node.put(TEXT, text.text());
				case TestValue.Generated generated ->
				{
					ObjectNode call = node.putObject(CALL);
					call.put(GENERATOR, generated.generator());
					call.put(VERSION, generated.version());
					call.put(SEED, generated.seed());
					for (Map.Entry<String, Long> parameter : new TreeMap<>(generated.parameters()).entrySet())
						call.put(parameter.getKey(), parameter.getValue().longValue());
				}
			}
		}
	}

	/**
	 * Reads values that CAT wrote, whose calls carry their version and seed.
	 *
	 * @param array one object per variable
	 * @return the value of each variable, by name, in the array's order
	 * @throws NullPointerException     if {@code array} is null
	 * @throws IllegalArgumentException if an object is malformed, a call lacks its version or seed, a generator
	 *                                  refuses its call, or two objects name the same variable
	 */
	public static Map<String, TestValue> read(JsonNode array)
	{
		return read(array, null, null);
	}

	/**
	 * Reads values that a model wrote, adding the generator's current version and a new seed to each call that states
	 * neither. A call that states both keeps them.
	 *
	 * @param array  one object per variable
	 * @param random the source of seeds
	 * @param taken  the seeds of the campaign's other generated values; receives each seed that a call receives or
	 *               states
	 * @return the value of each variable, by name, in the array's order
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if an object is malformed, a call states only one of its version and seed, a
	 *                                  generator refuses its call, or two objects name the same variable
	 */
	public static Map<String, TestValue> readCalls(JsonNode array, RandomGenerator random, Set<Long> taken)
	{
		requireThat(random, "random").isNotNull();
		requireThat(taken, "taken").isNotNull();
		return read(array, random, taken);
	}

	/**
	 * Reads values.
	 *
	 * @param array  one object per variable
	 * @param random the source of seeds, or {@code null} if each call carries its version and seed
	 * @param taken  the seeds of the campaign's other generated values, or {@code null} if each call carries its
	 *               version and seed
	 * @return the value of each variable, by name, in the array's order
	 * @throws IllegalArgumentException if the values are malformed
	 */
	private static Map<String, TestValue> read(JsonNode array, RandomGenerator random, Set<Long> taken)
	{
		requireThat(array, "array").isNotNull();
		if (!array.isArray())
			throw new IllegalArgumentException("The values must be an array");
		Map<String, TestValue> values = new LinkedHashMap<>();
		for (JsonNode node : array)
		{
			JsonNode variableNode = node.get(VARIABLE);
			if (variableNode == null || !variableNode.isString())
				throw new IllegalArgumentException("Each value must name its variable");
			String variable = variableNode.asString();
			JsonNode text = node.get(TEXT);
			JsonNode call = node.get(CALL);
			TestValue value;
			if (text != null && call == null && text.isString())
				value = new TestValue.Text(text.asString());
			else if (call != null && text == null && call.isObject())
				value = call(variable, call, random, taken);
			else
			{
				throw new IllegalArgumentException("The value of " + variable + " must have either a " + TEXT +
					" string or a " + CALL + " object");
			}
			if (values.put(variable, value) != null)
				throw new IllegalArgumentException("The variable '" + variable + "' has two values");
		}
		return values;
	}

	/**
	 * Reads a generator call.
	 *
	 * @param variable the variable that the call gives a value
	 * @param call     the call
	 * @param random   the source of seeds, or {@code null} if the call carries its version and seed
	 * @param taken    the campaign's other seeds, or {@code null} if the call carries its version and seed
	 * @return the generated value
	 * @throws IllegalArgumentException if the call is malformed or its generator refuses it
	 */
	private static TestValue.Generated call(String variable, JsonNode call, RandomGenerator random, Set<Long> taken)
	{
		JsonNode generatorNode = call.get(GENERATOR);
		if (generatorNode == null || !generatorNode.isString())
		{
			throw new IllegalArgumentException("The call of " + variable + " must name its " + GENERATOR + ", such " +
				"as {\"generator\": \"log_lines\", \"count\": 18000}");
		}
		Map<String, Long> parameters = new LinkedHashMap<>();
		Long version = null;
		Long seed = null;
		for (Map.Entry<String, JsonNode> property : call.properties())
		{
			String name = property.getKey();
			if (name.equals(GENERATOR))
				continue;
			JsonNode number = property.getValue();
			if (!number.isIntegralNumber() || !number.canConvertToLong())
			{
				throw new IllegalArgumentException("The parameter " + name + " of the call of " + variable +
					" must be a whole number");
			}
			switch (name)
			{
				case VERSION -> version = number.asLong();
				case SEED -> seed = number.asLong();
				default -> parameters.put(name, number.asLong());
			}
		}
		String generator = generatorNode.asString();
		if (random != null && version == null && seed == null)
		{
			try
			{
				return ValueGenerators.create(generator, parameters, random, taken);
			}
			catch (IllegalArgumentException e)
			{
				throw new IllegalArgumentException("The call of " + variable + " is refused: " + e.getMessage(), e);
			}
		}
		if (random != null && (version == null || seed == null))
		{
			throw new IllegalArgumentException("The call of " + variable + " states only one of its " + VERSION +
				" and " + SEED + ". Write only the generator and its parameters, and CAT adds both.");
		}
		if (version == null || seed == null)
		{
			throw new IllegalArgumentException("The call of " + variable + " must have its " + VERSION + " and " +
				SEED);
		}
		if (version < 1 || version > Integer.MAX_VALUE)
			throw new IllegalArgumentException("The call of " + variable + " has the invalid version " + version);
		TestValue.Generated generated = new TestValue.Generated(generator, version.intValue(), seed, parameters);
		if (taken != null)
			taken.add(seed);
		return generated;
	}
}

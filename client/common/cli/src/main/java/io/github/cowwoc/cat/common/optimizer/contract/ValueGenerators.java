package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.random.RandomGenerator;

/**
 * The value generators built into CAT.
 */
public final class ValueGenerators
{
	/**
	 * Every generator, in the order that the contract author's instructions list them.
	 */
	private static final List<ValueGenerator> GENERATORS = List.of(LogLines.INSTANCE);

	/**
	 * Prevents instantiation.
	 */
	private ValueGenerators()
	{
	}

	/**
	 * Returns every generator.
	 *
	 * @return the generators
	 */
	public static List<ValueGenerator> all()
	{
		return GENERATORS;
	}

	/**
	 * Returns the description of every generator that a contract author's instructions list.
	 *
	 * @return each generator's {@link ValueGenerator#describe() description}, in order, separated by blank lines
	 */
	public static String describeAll()
	{
		StringJoiner descriptions = new StringJoiner("\n\n");
		for (ValueGenerator generator : GENERATORS)
			descriptions.add(generator.describe());
		return descriptions.toString();
	}

	/**
	 * Returns the generator with a name.
	 *
	 * @param name the generator's name
	 * @return the generator, or an empty value if CAT has no generator with that name
	 * @throws NullPointerException if {@code name} is null
	 */
	public static Optional<ValueGenerator> find(String name)
	{
		requireThat(name, "name").isNotNull();
		return GENERATORS.stream().filter(generator -> generator.name().equals(name)).findFirst();
	}

	/**
	 * Returns the generator with a name.
	 *
	 * @param name the generator's name
	 * @return the generator
	 * @throws NullPointerException     if {@code name} is null
	 * @throws IllegalArgumentException if CAT has no generator with that name
	 */
	static ValueGenerator require(String name)
	{
		return find(name).orElseThrow(() -> new IllegalArgumentException("CAT has no value generator named '" +
			name + "'. Generators: " + GENERATORS.stream().map(ValueGenerator::name).toList()));
	}

	/**
	 * Creates the value of a generator call that the contract author wrote, at the generator's current version and
	 * with a seed that no other generated value of the campaign has, so that two identical calls yield different
	 * values.
	 *
	 * @param generator  the generator's name
	 * @param parameters the parameters that the contract author wrote, by name
	 * @param random     the source of seeds
	 * @param taken      the seeds of the campaign's other generated values; receives the new seed
	 * @return the generated value
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if CAT has no generator with that name or its current version does not accept
	 *                                  the parameters
	 */
	public static TestValue.Generated create(String generator, Map<String, Long> parameters, RandomGenerator random,
		Set<Long> taken)
	{
		requireThat(parameters, "parameters").isNotNull();
		requireThat(random, "random").isNotNull();
		requireThat(taken, "taken").isNotNull();
		ValueGenerator found = require(generator);
		int version = found.currentVersion();
		found.validate(version, parameters);
		long seed = random.nextLong();
		while (!taken.add(seed))
			seed = random.nextLong();
		return new TestValue.Generated(found.name(), version, seed, parameters);
	}
}

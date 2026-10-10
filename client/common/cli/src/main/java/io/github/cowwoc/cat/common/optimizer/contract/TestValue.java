package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * The value that a test case gives one variable: text, or the output of a value generator built into CAT.
 */
public sealed interface TestValue
{
	/**
	 * Returns the value as a person reads it: the text itself, or the generator call with its version, seed and
	 * parameters.
	 *
	 * @return the description
	 */
	String describe();

	/**
	 * A value written as text.
	 *
	 * @param text the text that replaces the variable's placeholders
	 */
	record Text(String text) implements TestValue
	{
		/**
		 * Creates a text value.
		 *
		 * @param text the text that replaces the variable's placeholders
		 * @throws NullPointerException if {@code text} is null
		 */
		public Text
		{
			requireThat(text, "text").isNotNull();
		}

		@Override
		public String describe()
		{
			return text;
		}
	}

	/**
	 * A value that a generator expands from its seed and parameters. The same generator, version, seed and parameters
	 * always expand to the same bytes.
	 *
	 * @param generator  the generator's name
	 * @param version    the generator version that expands the value
	 * @param seed       the seed that CAT drew when it created the test case
	 * @param parameters the parameters that the contract author wrote, by name
	 */
	record Generated(String generator, int version, long seed, Map<String, Long> parameters) implements TestValue
	{
		/**
		 * Creates a generated value.
		 *
		 * @param generator  the generator's name
		 * @param version    the generator version that expands the value
		 * @param seed       the seed that CAT drew when it created the test case
		 * @param parameters the parameters that the contract author wrote, by name
		 * @throws NullPointerException     if {@code generator} or {@code parameters} are null
		 * @throws IllegalArgumentException if no generator has the name, the generator has no such version, or the
		 *                                  version does not accept the parameters
		 */
		public Generated
		{
			requireThat(parameters, "parameters").isNotNull();
			ValueGenerators.require(generator).validate(version, parameters);
			parameters = Map.copyOf(new TreeMap<>(parameters));
		}

		/**
		 * Writes the generated text.
		 *
		 * @param out the writer that receives the text
		 * @throws NullPointerException if {@code out} is null
		 * @throws IOException          if {@code out} fails
		 */
		public void write(Writer out) throws IOException
		{
			ValueGenerators.require(generator).write(version, seed, parameters, out);
		}

		@Override
		public String describe()
		{
			StringJoiner call = new StringJoiner(", ", generator + "(", ")");
			call.add("version " + version);
			call.add("seed " + seed);
			for (Map.Entry<String, Long> parameter : new TreeMap<>(parameters).entrySet())
				call.add(parameter.getKey() + " " + parameter.getValue());
			return call.toString();
		}
	}
}

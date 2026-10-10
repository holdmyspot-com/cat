package io.github.cowwoc.cat.common.optimizer.contract;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/**
 * A value generator built into CAT. It expands a seed and parameters deterministically into UTF-8 text with
 * {@code \n} line endings. A generator keeps every version: a change to its output adds a version and leaves the
 * earlier ones expanding as before, so that a committed values file rebuilds the same files.
 * <p>
 * <b>Thread Safety:</b> Implementations must be immutable and thread-safe.
 */
public interface ValueGenerator
{
	/**
	 * Returns the name that a generator call uses.
	 *
	 * @return the name
	 */
	String name();

	/**
	 * Returns the version that new test cases record.
	 *
	 * @return the latest version
	 */
	int currentVersion();

	/**
	 * Returns the description that the contract author's instructions list: how to call the current version, what its
	 * output looks like, and every word, date and number that the output can contain, so that a contract author can
	 * write facts that the output never holds.
	 *
	 * @return the description, in Markdown
	 */
	String describe();

	/**
	 * Rejects a version that the generator does not have or parameters that the version does not accept.
	 *
	 * @param version    the version
	 * @param parameters the parameters, by name
	 * @throws NullPointerException     if {@code parameters} is null
	 * @throws IllegalArgumentException if the generator has no such version, a parameter is missing or unknown, or a
	 *                                  parameter's value is out of range
	 */
	void validate(int version, Map<String, Long> parameters);

	/**
	 * Writes the text that a version expands from a seed and parameters.
	 *
	 * @param version    the version
	 * @param seed       the seed
	 * @param parameters the parameters, by name
	 * @param out        the writer that receives the text
	 * @throws NullPointerException     if {@code parameters} or {@code out} are null
	 * @throws IllegalArgumentException if {@link #validate(int, Map)} rejects the version or parameters
	 * @throws IOException              if {@code out} fails
	 */
	void write(int version, long seed, Map<String, Long> parameters, Writer out) throws IOException;
}

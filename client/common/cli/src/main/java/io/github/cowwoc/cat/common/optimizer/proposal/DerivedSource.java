package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A prompt file that loads for the prompt system although no harness event reports it, such as an instruction file
 * that the harness reads by convention (design &sect;6.1, H21).
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param path the file's path, as the deriver reported it
 * @param rule the documented loading rule that loads the file
 */
public record DerivedSource(String path, String rule)
{
	/**
	 * Creates a derived source.
	 *
	 * @param path the file's path, as the deriver reported it
	 * @param rule the documented loading rule that loads the file
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if any of the arguments are blank
	 */
	public DerivedSource
	{
		requireThat(path, "path").isNotBlank();
		requireThat(rule, "rule").isNotBlank();
	}
}

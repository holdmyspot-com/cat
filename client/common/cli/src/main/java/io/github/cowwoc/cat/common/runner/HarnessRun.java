package io.github.cowwoc.cat.common.runner;

import java.nio.file.Path;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Identifies the isolated directory and generated identifier for one harness execution.
 *
 * @param harness the harness name
 *
 * @param id the generated run identifier
 *
 * @param directory the owned run directory
 */
public record HarnessRun(String harness, String id, Path directory)
{
	/**
	 * Validates the owned run identity.
	 *
	 * @param harness the harness name
	 *
	 * @param id the generated run identifier
	 *
	 * @param directory the owned run directory
	 *
	 * @throws NullPointerException if {@code harness}, {@code id}, or {@code directory} are null
	 *
	 * @throws IllegalArgumentException if {@code harness} or {@code id} are blank
	 */
	public HarnessRun
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(id, "id").isNotBlank();
		requireThat(directory, "directory").isNotNull();
		if (!harness.matches("[a-z0-9]+"))
			throw new IllegalArgumentException("Harness name must be a generated runner identifier");
		requireValidId(id);
	}

	/**
	 * Validates the run identifier before an untyped CLI argument becomes runner authority.
	 *
	 * @param id the proposed runner identifier
	 */
	public static void requireValidId(String id)
	{
		requireThat(id, "id").isNotBlank();
		if (!id.matches("[a-z0-9]+-[a-z0-9]+"))
			throw new IllegalArgumentException("Run id must be a generated runner identifier");
	}
}

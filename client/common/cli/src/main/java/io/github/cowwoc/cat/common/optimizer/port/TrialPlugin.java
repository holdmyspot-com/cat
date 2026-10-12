package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * The plugin under test that one trial installs: the copy of the plugin that belongs to the trial's staged source.
 * <p>
 * When the copy's manifest name is the name of the installed CAT artifact, the plugin under test is CAT and the trial
 * installs CAT from the copy alone.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param copy the plugin directory that the trial installs
 * @param dependsOnCat {@code true} if the trial installs the CAT artifact beside a plugin under test that is not CAT
 */
public record TrialPlugin(Path copy, boolean dependsOnCat)
{
	/**
	 * Creates a plugin under test.
	 *
	 * @param copy the plugin directory that the trial installs
	 * @param dependsOnCat {@code true} if the trial installs the CAT artifact beside a plugin under test that is not
	 *   CAT
	 * @throws NullPointerException if {@code copy} is null
	 */
	public TrialPlugin
	{
		requireThat(copy, "copy").isNotNull();
	}
}

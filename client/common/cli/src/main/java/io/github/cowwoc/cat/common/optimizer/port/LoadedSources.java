package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;

import java.util.List;

/**
 * The prompt files that load although no harness event reports them, in production and in trials.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param production the files that a session of the user's harness loads, with the rule that loads each
 * @param trials the files that a trial loads with the campaign's trial plugins, with the rule that loads each; a file
 *   that a mapping copies from the project into a plugin is named as the project's file
 * @param productionOnly the names, as {@code production} lists them, of the files that load in production but not in
 *   trials
 */
public record LoadedSources(List<DerivedSource> production, List<DerivedSource> trials,
	List<String> productionOnly)
{
	/**
	 * Creates the loaded sources.
	 *
	 * @param production the files that a session of the user's harness loads, with the rule that loads each
	 * @param trials the files that a trial loads with the campaign's trial plugins, with the rule that loads each
	 * @param productionOnly the names of the files that load in production but not in trials
	 * @throws NullPointerException if any argument is null
	 */
	public LoadedSources
	{
		requireThat(production, "production").isNotNull();
		requireThat(trials, "trials").isNotNull();
		requireThat(productionOnly, "productionOnly").isNotNull();
		production = List.copyOf(production);
		trials = List.copyOf(trials);
		productionOnly = List.copyOf(productionOnly);
	}
}

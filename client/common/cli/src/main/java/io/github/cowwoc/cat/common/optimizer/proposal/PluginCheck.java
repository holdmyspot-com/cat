package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;

import java.nio.file.Path;
import java.util.List;

/**
 * What CAT finds when it checks a campaign's trial plugins against the project before a proposal.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param plugins the trial plugins that every trial installs, in configuration order
 * @param uncovered the prompt files that do not load from the project and that no mapping covers, in path order; a
 *   candidate's edit to one of them never reaches trials
 * @param differing the mapped files whose content differs from the base's file at their mapped path
 * @param productionOnly the files that load in the user's sessions of the harness but not in trials, in loading order
 * @param unapprovedHooks the trial plugins' hooks that trials do not run because the user has not approved them in the
 *   harness, each as its plugin, event and command
 */
public record PluginCheck(List<TrialPlugin> plugins, List<Path> uncovered, List<PluginMapping.Difference> differing,
	List<String> productionOnly, List<String> unapprovedHooks)
{
	/**
	 * The check of a campaign whose trials install no plugin and load what production loads.
	 */
	public static final PluginCheck NONE = new PluginCheck(List.of(), List.of(), List.of(), List.of(), List.of());

	/**
	 * Creates a check.
	 *
	 * @param plugins the trial plugins that every trial installs, in configuration order
	 * @param uncovered the prompt files that do not load from the project and that no mapping covers, in path order
	 * @param differing the mapped files whose content differs from the base's file at their mapped path
	 * @param productionOnly the files that load in the user's sessions of the harness but not in trials, in loading
	 *   order
	 * @param unapprovedHooks the trial plugins' hooks that trials do not run because the user has not approved them in
	 *   the harness, each as its plugin, event and command
	 * @throws NullPointerException if any of the arguments are null
	 */
	public PluginCheck
	{
		requireThat(plugins, "plugins").isNotNull();
		plugins = List.copyOf(plugins);
		uncovered = List.copyOf(uncovered);
		differing = List.copyOf(differing);
		productionOnly = List.copyOf(productionOnly);
		unapprovedHooks = List.copyOf(unapprovedHooks);
	}
}

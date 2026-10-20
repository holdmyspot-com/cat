package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
 * @param homeOnly each hook, plugin and MCP server of the user's harness configuration that the user's sessions run
 *   and trials do not, as a sentence fragment that names it and the file that declares it
 * @param unapprovedHooks the trial plugins' hooks that trials do not run because the user has not approved them in the
 *   harness, each as its plugin, event and command
 * @param baseDirectories the absolute directory of each trial plugin's base copy, by the plugin's name; the proposal
 *   names a file inside one of them by the plugin and the file's path inside the plugin
 */
public record PluginCheck(List<TrialPlugin> plugins, List<Path> uncovered, List<PluginMapping.Difference> differing,
	List<String> productionOnly, List<String> homeOnly, List<String> unapprovedHooks,
	Map<String, Path> baseDirectories)
{
	/**
	 * The check of a campaign whose trials install no plugin and load what production loads.
	 */
	public static final PluginCheck NONE = new PluginCheck(List.of(), List.of(), List.of(), List.of(), List.of(),
		List.of(), Map.of());

	/**
	 * Creates a check.
	 *
	 * @param plugins the trial plugins that every trial installs, in configuration order
	 * @param uncovered the prompt files that do not load from the project and that no mapping covers, in path order
	 * @param differing the mapped files whose content differs from the base's file at their mapped path
	 * @param productionOnly the files that load in the user's sessions of the harness but not in trials, in loading
	 *   order
	 * @param homeOnly each hook, plugin and MCP server of the user's harness configuration that the user's sessions
	 *   run and trials do not, as a sentence fragment that names it and the file that declares it
	 * @param unapprovedHooks the trial plugins' hooks that trials do not run because the user has not approved them in
	 *   the harness, each as its plugin, event and command
	 * @param baseDirectories the absolute directory of each trial plugin's base copy, by the plugin's name; the
	 *   proposal names a file inside one of them by the plugin and the file's path inside the plugin
	 * @throws NullPointerException if any of the arguments are null
	 */
	public PluginCheck
	{
		requireThat(plugins, "plugins").isNotNull();
		plugins = List.copyOf(plugins);
		uncovered = List.copyOf(uncovered);
		differing = List.copyOf(differing);
		productionOnly = List.copyOf(productionOnly);
		homeOnly = List.copyOf(homeOnly);
		unapprovedHooks = List.copyOf(unapprovedHooks);
		baseDirectories = Map.copyOf(baseDirectories);
	}
}

package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What CAT finds when it checks a campaign's trial plugins against the project before a proposal.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
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
 * @param byEvaluator the local checks of a shared campaign, in harness order, or empty for a single check
 */
public record PluginCheck(List<TrialPlugin> plugins, List<Path> uncovered, List<PluginMapping.Difference> differing,
	List<String> productionOnly, List<String> homeOnly, List<String> unapprovedHooks,
	Map<String, Path> baseDirectories, Map<Harness, PluginCheck> byEvaluator)
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
	 * @param byEvaluator the local checks of a shared campaign, or empty for a single check
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
		Map<Harness, PluginCheck> local = new EnumMap<>(Harness.class);
		local.putAll(Map.copyOf(byEvaluator));
		byEvaluator = Collections.unmodifiableMap(local);
	}

	/**
	 * Creates a check of a single evaluator.
	 *
	 * @param plugins the evaluator's trial plugins, in configuration order
	 * @param uncovered the prompt files whose project changes do not reach trials
	 * @param differing the mapped files whose content differs from the plugin base
	 * @param productionOnly the files that the user's own sessions alone load
	 * @param homeOnly the hooks, plugins and MCP servers that the user's own sessions alone run
	 * @param unapprovedHooks the trial plugin hooks that the user has not approved
	 * @param baseDirectories the base directory of each trial plugin, by name
	 * @throws NullPointerException if any argument is null
	 */
	public PluginCheck(List<TrialPlugin> plugins, List<Path> uncovered, List<PluginMapping.Difference> differing,
		List<String> productionOnly, List<String> homeOnly, List<String> unapprovedHooks,
		Map<String, Path> baseDirectories)
	{
		this(plugins, uncovered, differing, productionOnly, homeOnly, unapprovedHooks, baseDirectories, Map.of());
	}

	/**
	 * Groups the checks of a shared campaign and collects its evaluator-labelled hook limitations.
	 *
	 * @param checks the local check for each evaluator, including evaluators with no configured plugins
	 * @return the campaign's grouped checks
	 * @throws NullPointerException if {@code checks} contains a null key or value
	 * @throws IllegalArgumentException if {@code checks} is empty or contains an already grouped check
	 */
	public static PluginCheck forEvaluators(Map<Harness, PluginCheck> checks)
	{
		requireThat(checks, "checks").isNotEmpty();
		Map<Harness, PluginCheck> local = new EnumMap<>(Harness.class);
		local.putAll(Map.copyOf(checks));
		List<String> hooks = new ArrayList<>();
		for (Map.Entry<Harness, PluginCheck> entry : local.entrySet())
		{
			requireThat(entry.getValue().byEvaluator(), "nestedChecks").isEmpty();
			for (String hook : entry.getValue().unapprovedHooks())
				hooks.add(entry.getKey().productName() + " — " + hook);
		}
		return new PluginCheck(List.of(), List.of(), List.of(), List.of(), List.of(), hooks, Map.of(), local);
	}
}

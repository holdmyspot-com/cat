package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.port.PluginRemovals;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds the plugin directories that the trials of a staged source install.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class TrialPlugins
{
	/**
	 * Prevents construction.
	 */
	private TrialPlugins()
	{
	}

	/**
	 * Returns the campaign's trial plugins as the trials of a staged source install them.
	 *
	 * @param evaluator the trial's evaluator, or null for an unassigned singleton
	 * @param plugins the evaluator's trial plugins
	 * @param contract the contract, whose dependents the copies of a control source leave out
	 * @param workspace the workspace that builds the staged source's copies of the plugins
	 * @param source the staged source
	 * @param control {@code true} if {@code source} is a control source
	 * @param json the JSON mapper that edits a dependent hook registration
	 * @return the input for each plugin in campaign order, with exact installed provenance when available
	 * @throws NullPointerException if an argument other than the evaluator is null
	 * @throws IOException if a copy cannot be built, if a plugin's base has no copy, or if a copy does not hold a
	 *   dependent
	 */
	static List<TrialPluginInput> of(Harness evaluator, List<TrialPlugin> plugins, Contract contract,
		WorkspacePort workspace,
		Path source, boolean control, JsonMapper json) throws IOException
	{
		requireThat(plugins, "plugins").isNotNull();
		requireThat(contract, "contract").isNotNull();
		requireThat(workspace, "workspace").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(json, "json").isNotNull();
		List<TrialPluginInput> inputs = new ArrayList<>(plugins.size());
		for (TrialPlugin plugin : plugins)
		{
			PluginRemovals removals = PluginRemovals.NONE;
			if (control)
				removals = PluginRemovals.of(contract.pluginRemovals(plugin, evaluator), json);
			Optional<String> installedId = Optional.empty();
			if (plugin.source() instanceof TrialPlugin.Installed)
				installedId = Optional.of(plugin.name());
			inputs.add(new TrialPluginInput(workspace.pluginCopy(plugin, source, removals), installedId));
		}
		return List.copyOf(inputs);
	}
}

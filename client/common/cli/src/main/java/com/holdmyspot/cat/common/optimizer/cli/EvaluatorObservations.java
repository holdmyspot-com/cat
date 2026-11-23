package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.port.HookRegistration;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.UnreachableReference;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The selected evaluator's local loader differences and hook registrations.
 *
 * @param projectSources direct project sources when plugin mappings are absent
 * @param productionOnly the prompt source files loaded only in the user's sessions
 * @param homeOnly the hooks, plugins and servers that the user's sessions alone use
 * @param unreachable references in trial prompt files that their projects cannot receive
 * @param hookRegistrations hooks from the staged project and each selected trial plugin
 * @param unapprovedHooks hooks from selected trial plugin bases that the native harness disables
 */
public record EvaluatorObservations(List<DerivedSource> projectSources, List<String> productionOnly,
	List<String> homeOnly,
	List<UnreachableReference> unreachable, List<HookRegistration> hookRegistrations, List<String> unapprovedHooks)
{
	/**
	 * Creates a snapshot of the evaluator's observations.
	 *
	 * @throws NullPointerException when a list or element is null
	 */
	public EvaluatorObservations
	{
		projectSources = List.copyOf(projectSources);
		productionOnly = List.copyOf(productionOnly);
		homeOnly = List.copyOf(homeOnly);
		unreachable = List.copyOf(unreachable);
		hookRegistrations = List.copyOf(hookRegistrations);
		unapprovedHooks = List.copyOf(unapprovedHooks);
	}

	/**
	 * Reads the native evaluator's effects without executing any hook.
	 *
	 * @param sessions the read-only native adapter
	 * @param staged the staged trial project
	 * @param plugins the selected plugin bases in trial order
	 * @param loaded the native loader comparison
	 * @return the observations that support proposal review
	 * @throws IOException if native metadata cannot be read
	 */
	static EvaluatorObservations inspect(SessionPort sessions, Path staged, List<PluginBase> plugins,
		LoadedSources loaded) throws IOException
	{
		List<String> unapproved = new ArrayList<>();
		for (PluginBase base : plugins)
		{
			for (String hook : sessions.unapprovedHooks(base.directory()))
				unapproved.add(base.plugin().name() + " " + hook);
		}
		List<DerivedSource> direct = directSources(sessions, staged, plugins, loaded);
		return new EvaluatorObservations(direct, loaded.productionOnly(), loaded.homeOnly(), loaded.unreachable(),
			sessions.hookRegistrations(staged, plugins), unapproved);
	}

	/**
	 * Finds the project files loaded without help from a plugin mapping.
	 *
	 * @param sessions the read-only native adapter
	 * @param staged the staged trial project
	 * @param bases the selected plugin bases in trial order
	 * @param loaded the native loader comparison including any mapped files
	 * @return the directly loaded project sources
	 * @throws IOException if the native loader cannot be inspected
	 */
	private static List<DerivedSource> directSources(SessionPort sessions, Path staged, List<PluginBase> bases,
		LoadedSources loaded) throws IOException
	{
		if (bases.stream().anyMatch(base -> !base.plugin().mappings().isEmpty()))
		{
			List<PluginBase> unmapped = new ArrayList<>();
			for (PluginBase base : bases)
			{
				TrialPlugin plugin = base.plugin();
				TrialPlugin descriptor = new TrialPlugin(plugin.name(), plugin.baseDigest(), plugin.source(),
					List.of(), plugin.repository());
				unmapped.add(new PluginBase(descriptor, base.directory()));
			}
			loaded = sessions.loadedSources(staged, unmapped);
		}
		return loaded.trials().stream().filter(source -> !PortablePaths.isRooted(Path.of(source.path()))).toList();
	}
}

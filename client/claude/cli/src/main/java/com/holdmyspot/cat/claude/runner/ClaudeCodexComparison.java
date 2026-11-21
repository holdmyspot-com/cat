package com.holdmyspot.cat.claude.runner;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HookComparisonLaunch;
import com.holdmyspot.cat.common.runner.HookComparisonRequest;
import com.holdmyspot.cat.common.runner.RequestServer.ComparisonHost;
import com.holdmyspot.cat.common.runner.RequestedRunner.Selection;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Binds the Claude Code server's Codex comparison to its authenticated owner and protected native installation.
 */
final class ClaudeCodexComparison
{
	/**
	 * Supplies the comparison service using the same host environment as the selected runner.
	 *
	 * @param environment the request server's environment
	 * @return the service factory for the authenticated Claude Code owner
	 */
	static ComparisonHost host(Map<String, String> environment)
	{
		return (owner, bound, runners) -> (request, scratch) ->
		{
			Path nativeHome = CodexInstallation.resolveHome(environment);
			Path executable = executable("codex", environment);
			Path sandbox = executable("bwrap", environment);
			List<Path> sources = sources(request, new CodexInstallation(nativeHome), executable, sandbox);
			Selection selected = runners.select("codex");
			ParentPolicy policy = bound.parentPolicy(owner.askingThread(), readings ->
			{
				selected.requireTrusted(readings);
				for (Path source : sources)
					Selection.requireTrustedSource(source, readings);
				for (ParentPolicy reading : readings)
				{
					reading.requireTrustedTree(Path.of("/usr/bin"));
					reading.requireTrustedTree(Path.of("/bin"));
				}
			});
			HookComparisonLaunch launch = new HookComparisonLaunch(owner.project(), request.workspace().getParent(),
				nativeHome, executable.toRealPath(), sandbox.toRealPath(), policy);
			return selected.compareHooks(launch, scratch);
		};
	}

	/**
	 * Resolves the location selected from the authenticated host environment.
	 *
	 * @param name the native program's basename
	 * @param environment the serving launcher environment
	 * @return the absolute executable path
	 * @throws IOException if the native executable is unavailable
	 */
	private static Path executable(String name, Map<String, String> environment) throws IOException
	{
		Path found = Path.of(Platform.current().findProgram(name, environment));
		if (!found.isAbsolute())
			throw new IOException("Codex hook comparison could not find " + name + " on PATH");
		return found;
	}

	/**
	 * Names only the original installations that the peer may use as approval sources.
	 *
	 * @param request the captured trial plugin inputs
	 * @param installation the native home resolved by the broker
	 * @param executable the native Codex executable
	 * @param sandbox the native sandbox executable
	 * @return sources to authenticate before the runner starts
	 */
	private static List<Path> sources(HookComparisonRequest request, CodexInstallation installation, Path executable,
		Path sandbox)
	{
		List<Path> result = new ArrayList<>(List.of(executable, sandbox));
		for (TrialPluginInput plugin : request.plugins())
		{
			if (plugin.installedId().isEmpty())
				continue;
			String id = plugin.installedId().orElseThrow();
			int separator = id.indexOf('@');
			result.add(installation.cacheRoot().resolve(id.substring(separator + 1)).
				resolve(id.substring(0, separator)));
		}
		if (request.plugins().stream().anyMatch(plugin -> plugin.installedId().isPresent()))
			result.addAll(List.of(installation.configuration(), installation.home().resolve("installation_id")));
		return result;
	}

	/**
	 * Prevents construction.
	 */
	private ClaudeCodexComparison()
	{
	}
}

package com.holdmyspot.cat.codex.optimizer;

import com.holdmyspot.cat.codex.runner.TomlStrings;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.RealPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Starts optimizer hooks under an explicit Codex sandbox when the optimizer itself is exempt.
 */
public final class CodexHookProcess
{
	private static final String CODEX_HOME = "CODEX_HOME";
	private static final String PATH = "PATH";
	private static final List<String> HOME_VARIABLES = List.of(CodexOptimizerCommandScope.HOME_VARIABLE, CODEX_HOME);
	private static final List<String> HOOK_VARIABLES =
		List.of(CodexOptimizerCommandScope.HOME_VARIABLE, CODEX_HOME, PATH);
	private static final String NATIVE_PATH = "/usr/bin:/bin";
	private static final String SANDBOX_HOME_PREFIX = "codex-hook-home-";

	/**
	 * Prevents instantiation.
	 */
	private CodexHookProcess()
	{
	}

	/**
	 * Configures the hook builder to start the native sandbox before the hook, leaving its stream redirects intact.
	 *
	 * @param builder the original hook's command, environment, working directory and redirects
	 * @param scratch the optimizer-owned directory that holds the comparison's copies and temporary configuration
	 * @param trialHome the isolated hook home to grant writes to, or empty for the production comparison
	 * @param ownerEnvironment the validated launcher's environment used to find the native executables
	 * @param policy the validated owner's command policy
	 * @param json the optimizer's mapper
	 * @throws IOException if the paths or network permissions cannot be confined by the native sandbox
	 * @throws PolicyRefusal if the owner could replace a native executable
	 */
	static void prepare(ProcessBuilder builder, Path scratch, Optional<Path> trialHome,
		Map<String, String> ownerEnvironment, ParentPolicy policy, JsonMapper json) throws IOException, PolicyRefusal
	{
		Path selected = executable(Platform.current(), ownerEnvironment, policy, "codex");
		prepare(builder, scratch, trialHome, selected, policy, json);
	}

	/**
	 * Configures a hook builder with the native Codex executable selected for a brokered comparison.
	 *
	 * @param builder the original hook's command, environment, working directory and redirects
	 * @param scratch the directory holding both comparison workspaces
	 * @param trialHome the isolated hook home to grant writes to, or empty for production
	 * @param selected the broker-selected native Codex executable
	 * @param policy the authenticated owner's command policy
	 * @param json the sandbox state mapper
	 * @throws IOException if the paths or network permissions cannot be confined by the native sandbox
	 * @throws PolicyRefusal if the owner could replace a native executable
	 */
	public static void prepare(ProcessBuilder builder, Path scratch, Optional<Path> trialHome, Path selected,
		ParentPolicy policy, JsonMapper json) throws IOException, PolicyRefusal
	{
		Platform platform = Platform.current();
		if (platform.isWindows())
			throw new IOException("The Codex optimizer cannot confine startup hooks on native Windows. Use WSL2.");

		Path workspace = builder.directory().toPath().toRealPath();
		Path comparison = scratch.toRealPath();
		List<Path> writable = new ArrayList<>();
		writable.add(workspace);
		if (trialHome.isPresent())
			writable.add(trialHome.orElseThrow().toRealPath());
		for (Path path : writable)
		{
			if (!path.startsWith(comparison) || !policy.mayWrite(path))
				throw new IOException("The Codex startup-hook comparison path must be writable by the owner and inside " +
					"the comparison directory: " + path);
		}
		Path nativeCodex = selected.toRealPath();
		policy.requireTrusted(nativeCodex);
		if (!policy.mayRead(nativeCodex))
			throw new IOException("The owner cannot read the Codex sandbox executable: " + nativeCodex);
		for (String directory : NATIVE_PATH.split(":"))
			policy.requireTrustedTree(Path.of(directory));
		Path env = executable(platform, Map.of(PATH, NATIVE_PATH), policy, "env");
		String state = state(json, workspace, writable, policy).toString();

		String projectTrust = "projects={\"" + TomlStrings.escape(workspace.toString()) +
			"\"={trust_level=\"untrusted\"}}";
		List<String> command = new ArrayList<>(List.of(nativeCodex.toString(), "sandbox", "--sandbox-state-json",
			state, "-c", "project_root_markers=[]", "-c", projectTrust, "-c", "features.network_proxy=false",
			"-c", "shell_environment_policy.inherit=\"all\"", "--", env.toString()));
		Map<String, String> environment = builder.environment();
		for (String key : HOOK_VARIABLES)
		{
			if (!environment.containsKey(key))
				command.addAll(List.of("-u", key));
		}
		command.add("--");
		for (String key : HOOK_VARIABLES)
		{
			if (environment.containsKey(key))
				command.add(key + "=" + environment.get(key));
		}
		command.addAll(builder.command());
		Path sandboxHome = Files.createTempDirectory(comparison, SANDBOX_HOME_PREFIX);
		for (String key : HOME_VARIABLES)
			environment.put(key, sandboxHome.toString());
		// The native launcher resolves Linux sandbox helpers before enforcing the permission profile.
		environment.put(PATH, NATIVE_PATH);
		builder.directory(workspace.toFile());
		builder.command(command);
	}

	/**
	 * Resolves an executable whose real path the owner cannot replace through its tooling.
	 *
	 * @param platform the operating system
	 * @param environment the executable search environment
	 * @param policy the owner's command policy
	 * @param program the program name
	 * @return the absolute real executable path
	 * @throws IOException if the executable cannot be resolved
	 * @throws PolicyRefusal if the executable is writable by the owner
	 */
	private static Path executable(Platform platform, Map<String, String> environment, ParentPolicy policy,
		String program) throws IOException, PolicyRefusal
	{
		Path path = Path.of(platform.findProgram(program, environment));
		if (!path.isAbsolute())
			throw new IOException("The Codex startup-hook comparison could not find " + program + " on PATH.");
		Path executable = path.toRealPath();
		policy.requireTrusted(executable);
		return executable;
	}

	/**
	 * Renders the exact filesystem and network permissions for the native sandbox.
	 *
	 * @param json the optimizer's mapper
	 * @param workspace the hook's working directory
	 * @param writable the comparison paths permitted to accept writes
	 * @param policy the owner's command policy
	 * @return the native sandbox state
	 * @throws IOException if the native sandbox cannot express the required permissions
	 */
	private static ObjectNode state(JsonMapper json, Path workspace, List<Path> writable, ParentPolicy policy)
		throws IOException
	{
		ObjectNode state = json.createObjectNode();
		ObjectNode profile = state.putObject("permissionProfile");
		profile.put("type", "managed");
		ObjectNode filesystem = profile.putObject("file_system");
		filesystem.put("type", "restricted");
		ArrayNode entries = filesystem.putArray("entries");
		for (Map.Entry<Path, String> permission : filesystem(workspace, writable, policy).entrySet())
		{
			ObjectNode entry = entries.addObject();
			entry.putObject("path").put("type", "path").put("path", permission.getKey().toString());
			entry.put("access", permission.getValue());
		}
		profile.put("network", network(policy));
		state.put("sandboxCwd", workspace.toUri().toString());
		return state;
	}

	/**
	 * Applies the owner's protected paths to the comparison-only write grants.
	 *
	 * @param workspace the hook's working directory
	 * @param writable the comparison paths permitted to accept writes
	 * @param policy the owner's command policy
	 * @return the ordered paths and their access modes
	 * @throws IOException if a policy path cannot be resolved
	 */
	private static Map<Path, String> filesystem(Path workspace, List<Path> writable, ParentPolicy policy)
		throws IOException
	{
		Map<Path, String> entries = new LinkedHashMap<>();
		entries.put(workspace.getRoot(), "read");
		List<Path> denials = RealPaths.resolveAll(policy.readDenials());
		List<Path> reallowances = RealPaths.resolveAll(policy.readReallowances());
		for (Path reallowance : reallowances)
			entries.put(reallowance, "read");
		for (Path path : writable)
		{
			if (readable(path, denials, reallowances))
				entries.put(path, "write");
		}
		List<Path> protectedPaths = new ArrayList<>(policy.writeDenials());
		protectedPaths.addAll(policy.protectedSources());
		for (Path path : RealPaths.resolveAll(protectedPaths))
		{
			if (readable(path, denials, reallowances) && writable.stream().anyMatch(path::startsWith))
				entries.put(path, "read");
		}
		for (Path denial : denials)
		{
			if (reallowances.stream().noneMatch(denial::startsWith))
				entries.put(denial, "deny");
		}
		return entries;
	}

	/**
	 * Checks whether an owner read exception reopens the path or no owner denial covers it.
	 *
	 * @param path the resolved path
	 * @param denials the resolved paths the owner cannot read
	 * @param reallowances the resolved paths the owner explicitly allows reading
	 * @return whether the path is readable by the owner
	 */
	private static boolean readable(Path path, List<Path> denials, List<Path> reallowances)
	{
		return reallowances.stream().anyMatch(path::startsWith) || denials.stream().noneMatch(path::startsWith);
	}

	/**
	 * Encodes the owner's network permission when the native command sandbox can enforce it.
	 *
	 * @param policy the owner's command policy
	 * @return the native network permission mode
	 * @throws IOException if the owner's network restrictions cannot be enforced
	 */
	private static String network(ParentPolicy policy) throws IOException
	{
		if (policy.hosts() instanceof HostAccess.NoHosts && !policy.allowLocalBinding())
			return "restricted";
		if (policy.hosts() instanceof HostAccess.AllHosts && policy.allowLocalBinding())
			return "enabled";
		throw new IOException("The Codex startup-hook comparison cannot reproduce the owner's managed network " +
			"allowlist or local-binding restriction in the native command sandbox.");
	}
}

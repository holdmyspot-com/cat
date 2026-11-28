package com.holdmyspot.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.codex.runner.CodexSessionSandbox;
import com.holdmyspot.cat.codex.runner.TomlStrings;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.runner.policy.RealPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Starts optimizer hooks under an explicit Codex sandbox when the optimizer itself is exempt. Codex gives the hook its
 * own home and search path, so that no other program runs inside the sandbox before the hook.
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
	private static final String OPTION = "-c";
	private static final String ENVIRONMENT_POLICY = "shell_environment_policy.";

	/**
	 * Prevents instantiation.
	 */
	private CodexHookProcess()
	{
	}

	/**
	 * The native Codex executable that starts a hook's sandbox and the settings that choose its Windows
	 * implementation.
	 *
	 * @param platform   the platform that the hook runs on
	 * @param executable the native Codex executable
	 * @param selection  the owner's settings that select the Windows sandbox implementation, by dotted setting name,
	 *                   each value as TOML would spell a string or a boolean
	 * @param system     Codex's machine-wide folder, whose layers apply under the selection
	 */
	public record Sandbox(Platform platform, Path executable, Map<String, String> selection, CodexSystemFolder system)
	{
		/**
		 * Creates a sandbox.
		 *
		 * @param platform   the platform that the hook runs on
		 * @param executable the native Codex executable
		 * @param selection  the owner's settings that select the Windows sandbox implementation, by dotted setting
		 *                   name, each value as TOML would spell a string or a boolean
		 * @param system     Codex's machine-wide folder, whose layers apply under the selection
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Sandbox
		{
			requireThat(platform, "platform").isNotNull();
			requireThat(executable, "executable").isNotNull();
			requireThat(selection, "selection").isNotNull();
			selection = Map.copyOf(selection);
			requireThat(system, "system").isNotNull();
		}
	}

	/**
	 * Returns the sandbox that the owner's Codex launches for a hook. On native Windows it starts from the owner
	 * session's own Codex executable, because an npm install puts only {@code codex.cmd} on {@code PATH}, and Java
	 * refuses a batch-file argument that holds a quote, which the sandbox state does; elsewhere from {@code codex} on
	 * the owner's {@code PATH}.
	 *
	 * @param platform         the platform that the hook runs on
	 * @param ownerEnvironment the validated launcher's environment used to find the native executable
	 * @param policy           the validated owner's command policy
	 * @param selection        the owner's settings that select the Windows sandbox implementation
	 * @param owner            the owner session's harness process
	 * @return the sandbox
	 * @throws IOException   if the executable cannot be found
	 * @throws PolicyRefusal if the owner could replace the executable
	 */
	static Sandbox ownerSandbox(Platform platform, Map<String, String> ownerEnvironment, ParentPolicy policy,
		Map<String, String> selection, ProcessIdentity owner) throws IOException, PolicyRefusal
	{
		Path selected;
		if (platform.isWindows())
			selected = ownerExecutable(HarnessProcesses.system(), owner);
		else
			selected = executable(platform, ownerEnvironment, policy, "codex");
		return new Sandbox(platform, selected, selection, CodexSystemFolder.ofThisMachine());
	}

	/**
	 * Returns the program of the owner session's harness process.
	 *
	 * @param processes the system's processes
	 * @param owner     the owner session's harness process
	 * @return the owner's executable
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the owner's process has ended or its program cannot be read
	 */
	public static Path ownerExecutable(HarnessProcesses processes, ProcessIdentity owner) throws IOException
	{
		requireThat(processes, "processes").isNotNull();
		requireThat(owner, "owner").isNotNull();
		Optional<ProcessDescription> description = processes.find(owner.pid());
		if (description.isEmpty() || !description.orElseThrow().start().equals(Optional.of(owner.start())))
			throw new IOException("The startup-hook comparison cannot start Codex's sandbox: the Codex process of " +
				"the session that runs the optimizer has ended.");
		Optional<String> command = description.orElseThrow().command();
		if (command.isEmpty())
			throw new IOException("The startup-hook comparison cannot start Codex's sandbox: the system does not " +
				"report the program of the Codex process that runs the optimizer.");
		return Path.of(command.orElseThrow());
	}

	/**
	 * Configures the hook builder to start the native sandbox before the hook, leaving its stream redirects intact.
	 *
	 * @param builder   the original hook's command, environment, working directory and redirects
	 * @param scratch   the directory holding both comparison workspaces
	 * @param trialHome the isolated hook home to grant writes to, or empty for production
	 * @param sandbox   the native Codex executable and the settings that choose its Windows implementation
	 * @param policy    the authenticated owner's command policy
	 * @param json      the sandbox state mapper
	 * @throws IOException   if the paths or network permissions cannot be confined by the native sandbox
	 * @throws PolicyRefusal if the owner could replace a native executable or the helpers that it starts
	 */
	public static void prepare(ProcessBuilder builder, Path scratch, Optional<Path> trialHome, Sandbox sandbox,
		ParentPolicy policy, JsonMapper json) throws IOException, PolicyRefusal
	{
		Platform platform = sandbox.platform();

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
		Path nativeCodex = sandbox.executable().toRealPath();
		policy.requireTrusted(nativeCodex);
		if (!policy.mayRead(nativeCodex))
			throw new IOException("The owner cannot read the Codex sandbox executable: " + nativeCodex);
		// Codex takes its Windows sandbox helpers from its own folder, and its Linux helper from the search path
		if (platform.isWindows())
			policy.requireTrustedTree(nativeCodex.getParent());
		else
		{
			for (String directory : NATIVE_PATH.split(":"))
				policy.requireTrustedTree(Path.of(directory));
		}
		// Codex's unelevated Windows implementation cannot deny a read and refuses a profile that denies one
		boolean deniesReads = CodexSessionSandbox.deniesReads(platform, sandbox.selection(), sandbox.system());
		String state = state(json, workspace, writable, policy, deniesReads).toString();

		String projectTrust = "projects={\"" + TomlStrings.escape(workspace.toString()) +
			"\"={trust_level=\"untrusted\"}}";
		List<String> command = new ArrayList<>(List.of(nativeCodex.toString(), "sandbox", "--sandbox-state-json",
			state, OPTION, "project_root_markers=[]", OPTION, projectTrust, OPTION, "features.network_proxy=false",
			OPTION, ENVIRONMENT_POLICY + "inherit=\"all\""));
		// The hook runs in the Windows implementation that wins for the owner's launch, as a child session does
		if (platform.isWindows())
		{
			for (Map.Entry<String, String> setting : new TreeMap<>(sandbox.selection()).entrySet())
				command.addAll(List.of(OPTION, setting.getKey() + "=" + tomlValue(setting.getValue())));
		}
		Map<String, String> environment = builder.environment();
		List<String> absent = new ArrayList<>();
		for (String key : HOOK_VARIABLES)
		{
			if (environment.containsKey(key))
			{
				command.addAll(List.of(OPTION, ENVIRONMENT_POLICY + "set." + key + "=\"" +
					TomlStrings.escape(environment.get(key)) + "\""));
			}
			else
				absent.add("\"" + key + "\"");
		}
		if (!absent.isEmpty())
			command.addAll(List.of(OPTION, ENVIRONMENT_POLICY + "exclude=[" + String.join(",", absent) + "]"));
		command.add("--");
		command.addAll(builder.command());
		Path sandboxHome = Files.createTempDirectory(comparison, SANDBOX_HOME_PREFIX);
		for (String key : HOME_VARIABLES)
			environment.put(key, sandboxHome.toString());
		// The native launcher resolves Linux sandbox helpers before enforcing the permission profile
		if (!platform.isWindows())
			environment.put(PATH, NATIVE_PATH);
		builder.directory(workspace.toFile());
		builder.command(command);
	}

	/**
	 * Spells a setting's value as TOML.
	 *
	 * @param value the value, {@code true} or {@code false} for a boolean
	 * @return the TOML boolean or string
	 */
	private static String tomlValue(String value)
	{
		if (value.equals("true") || value.equals("false"))
			return value;
		return "\"" + TomlStrings.escape(value) + "\"";
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
	 * @param deniesReads {@code false} to leave out the owner's denied reads
	 * @return the native sandbox state
	 * @throws IOException if the native sandbox cannot express the required permissions
	 */
	private static ObjectNode state(JsonMapper json, Path workspace, List<Path> writable, ParentPolicy policy,
		boolean deniesReads) throws IOException
	{
		ObjectNode state = json.createObjectNode();
		ObjectNode profile = state.putObject("permissionProfile");
		profile.put("type", "managed");
		ObjectNode filesystem = profile.putObject("file_system");
		filesystem.put("type", "restricted");
		ArrayNode entries = filesystem.putArray("entries");
		for (Map.Entry<Path, String> permission : filesystem(workspace, writable, policy, deniesReads).entrySet())
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
	 * @param deniesReads {@code false} to leave out the owner's denied reads and the exceptions inside them
	 * @return the ordered paths and their access modes
	 * @throws IOException if a policy path cannot be resolved
	 */
	private static Map<Path, String> filesystem(Path workspace, List<Path> writable, ParentPolicy policy,
		boolean deniesReads) throws IOException
	{
		Map<Path, String> entries = new LinkedHashMap<>();
		entries.put(workspace.getRoot(), "read");
		List<Path> denials = List.of();
		List<Path> reallowances = List.of();
		if (deniesReads)
		{
			denials = RealPaths.resolveAll(policy.readDenials());
			reallowances = RealPaths.resolveAll(policy.readReallowances());
		}
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

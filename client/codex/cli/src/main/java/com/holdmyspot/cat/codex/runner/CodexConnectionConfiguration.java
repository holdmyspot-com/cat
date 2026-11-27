package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.ProtectedProjectFiles;
import com.holdmyspot.cat.common.runner.SessionCommandLimits;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.RealPaths;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Copies connection, project instruction and multi-agent settings for an isolated Codex process.
 */
public final class CodexConnectionConfiguration
{
	private static final int MAXIMUM_CONFIGURATION_BYTES = 1_048_576;
	private static final String STARTUP_UPDATE_CHECK = "check_for_update_on_startup = false";
	static final String RUNNER_PERMISSION_PROFILE = "cat-runner";
	private static final String VALIDATOR_PERMISSION_PROFILE = "cat-validator";
	private static final String DENIED_READS_PERMISSION_PROFILE = "cat-denied-reads";
	private static final String TEMPORARY_DIRECTORY = ":tmpdir";
	/**
	 * The {@code [features]} entry that makes Codex limit a profile's network to its {@code domains}.
	 */
	static final String NETWORK_PROXY_FEATURE = "network_proxy";
	/**
	 * The permission profiles that CAT gives a session's thread.
	 */
	private static final String MOUNTED_RUNNER_PERMISSION_PROFILE = "cat-mounted-runner";
	private static final List<String> SESSION_PERMISSION_PROFILES = List.of("cat-runner", "cat-validator",
		"cat-denied-reads", MOUNTED_RUNNER_PERMISSION_PROFILE);
	/**
	 * The top-level settings of the runner's own configuration that a session keeps. The settings that choose the
	 * session's credential and model host come from the launch's source home instead.
	 */
	private static final Set<String> CONNECTION_SETTINGS = Set.of("model", "model_reasoning_effort");
	/**
	 * The settings that choose a session's credential or its model host, which the session takes from its source home
	 * alone.
	 */
	private static final List<String> CREDENTIAL_SETTINGS = List.of("openai_base_url", "forced_login_method",
		"model_provider", "model_providers");
	/**
	 * The {@code [features]} entries that decide whether and how a session may start subagents.
	 */
	private static final List<String> MULTI_AGENT_FEATURES = List.of("multi_agent", "multi_agent_v2");
	/**
	 * The {@code [agents]} entries that enable the multi-agent tools and limit the subagents a session may start.
	 * {@code max_concurrent_threads_per_session} is another name of {@code max_threads}.
	 */
	private static final Set<String> AGENT_LIMITS = Set.of("enabled", "max_threads",
		"max_concurrent_threads_per_session", "max_depth", "job_max_runtime_seconds", "interrupt_message");
	static final List<String> CAT_HOOK_KEYS = List.of("cat@cat:hooks/hooks.json:session_start:0:0",
		"cat@cat:hooks/hooks.json:subagent_start:0:0", "cat@cat:hooks/hooks.json:post_tool_use:0:0");
	private static final Pattern TRUSTED_HASH = Pattern.compile("sha256:[0-9a-f]{64}");

	/**
	 * Returns the parent's explicitly configured project instruction settings as TOML. An absent setting stays absent;
	 * configured filename lists and byte limits retain their values regardless of their original TOML layout.
	 *
	 * @param source the host configuration file
	 * @return the configured instruction settings, or empty when none are set
	 * @throws NullPointerException if {@code source} is null
	 * @throws IOException if the configuration cannot be read or parsed
	 */
	public static Optional<String> instructionSettings(Path source) throws IOException
	{
		requireThat(source, "source").isNotNull();
		if (!Files.isRegularFile(source))
			return Optional.empty();
		String configuration = BoundedInput.readUtf8(source, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		return instructionSettings(parse(source, configuration, "instruction settings"));
	}

	/**
	 * Parses an already bounded Codex configuration.
	 *
	 * @param source        the source file, named when parsing fails
	 * @param configuration the configuration
	 * @param description   the part of the configuration that the caller needs
	 * @return the configuration's values
	 * @throws IOException if the configuration cannot be parsed
	 */
	private static JsonNode parse(Path source, String configuration, String description) throws IOException
	{
		assert source != null;
		assert configuration != null;
		try
		{
			return TomlMapper.builder().build().readTree(configuration);
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot parse Codex " + description + " from " + source, e);
		}
	}

	/**
	 * Extracts the explicitly configured instruction settings from a parsed host configuration.
	 *
	 * @param values the host configuration's values
	 * @return the configured instruction settings, or empty when none are set
	 */
	private static Optional<String> instructionSettings(JsonNode values)
	{
		assert values != null;
		TomlMapper mapper = TomlMapper.builder().build();
		ObjectNode selected = mapper.createObjectNode();
		for (String name : List.of("project_doc_fallback_filenames", "project_doc_max_bytes"))
		{
			if (values.has(name))
				selected.set(name, values.get(name));
		}
		if (selected.isEmpty())
			return Optional.empty();
		return Optional.of(mapper.writeValueAsString(selected));
	}

	/**
	 * Refuses a session whose system or managed Codex configuration layer would give it more than its policy allows.
	 * Codex merges each layer into the session's own configuration: the system layer under it and the managed layer
	 * over it. A layer widens the session if it lets one of the session's permission profiles write outside the
	 * policy's writes or inside its denials, read inside its read denials, reach a host outside its hosts, turn on its
	 * network without hosts or bind local addresses without the policy's permission, or names a profile entry whose
	 * effect CAT does not know. The managed layer also widens it if it replaces a profile's {@code extends}, names
	 * another {@code default_permissions} or a configuration {@code profile}, or sets {@code sandbox_mode} to
	 * {@code danger-full-access}. Either layer is also refused if it sets {@code openai_base_url},
	 * {@code forced_login_method}, {@code model_provider} or {@code model_providers}, which would replace the credential
	 * or the model host that the session takes from its source home.
	 *
	 * @param system the machine-wide folder, which holds the system layer, and on a platform that has one the managed
	 *               layer, each of which may not exist
	 * @param policy the session's policy
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if a layer cannot be parsed, would widen the session or sets a credential setting
	 * @throws IOException             if a layer cannot be read
	 */
	public static void requireNarrowingLayers(CodexSystemFolder system, ChildPolicy policy) throws IOException
	{
		requireThat(system, "system").isNotNull();
		requireThat(policy, "policy").isNotNull();
		Optional<String> unsure = system.fallbackCause();
		if (unsure.isPresent())
		{
			throw new SessionRefusedException("CAT does not start the session: " + unsure.get() + ", so it cannot " +
				"tell whether the Codex configuration layers that Codex merges into the session's configuration give the " +
				"session more access than its sandbox policy allows. Make Windows name the program data folder, then " +
				"start the session again.");
		}
		requireNarrowingLayer(system.config(), false, policy);
		Optional<Path> managed = system.managedConfig();
		if (managed.isPresent())
			requireNarrowingLayer(managed.get(), true, policy);
	}

	/**
	 * Refuses a session whose Codex configuration layer would give it more than its policy allows.
	 *
	 * @param layer   the layer, which may not exist
	 * @param managed {@code true} if the layer's values override the session's own configuration
	 * @param policy  the session's policy
	 * @throws SessionRefusedException if the layer cannot be parsed or would widen the session
	 * @throws IOException             if the layer cannot be read
	 */
	private static void requireNarrowingLayer(Path layer, boolean managed, ChildPolicy policy) throws IOException
	{
		if (!Files.isRegularFile(layer))
			return;
		String text = BoundedInput.readUtf8(layer, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration layer");
		JsonNode values;
		try
		{
			values = TomlMapper.builder().build().readTree(text);
		}
		catch (JacksonException e)
		{
			throw new SessionRefusedException("CAT does not start the session: it cannot parse the Codex " +
				"configuration layer " + layer + ", which Codex merges into the session's configuration, so it cannot " +
				"tell whether the layer gives the session more access than its sandbox policy allows. Fix the file, " +
				"then start the session again.", e);
		}
		for (String setting : CREDENTIAL_SETTINGS)
		{
			if (values.has(setting))
				throw credentialLayer(layer, setting);
		}
		if (managed)
		{
			if (values.path("sandbox_mode").asString("").equals("danger-full-access"))
				throw wideningLayer(layer, "sandbox_mode", values.get("sandbox_mode"));
			if (values.has("default_permissions") &&
				!values.path("default_permissions").asString("").equals(RUNNER_PERMISSION_PROFILE))
			{
				throw wideningLayer(layer, "default_permissions", values.get("default_permissions"));
			}
			if (values.has("profile"))
				throw wideningLayer(layer, "profile", values.get("profile"));
		}
		JsonNode permissions = values.path("permissions");
		for (String profile : SESSION_PERMISSION_PROFILES)
		{
			JsonNode entries = permissions.path(profile);
			String setting = "permissions." + profile;
			if (entries.isMissingNode())
				continue;
			if (!entries.isObject())
				throw wideningLayer(layer, setting, entries);
			for (Entry<String, JsonNode> entry : entries.properties())
			{
				String name = setting + "." + entry.getKey();
				switch (entry.getKey())
				{
					case "extends" ->
					{
						if (managed)
							throw wideningLayer(layer, name, entry.getValue());
					}
					case "filesystem" -> requireNarrowingFilesystem(layer, name, entry.getValue(), policy);
					case "network" -> requireNarrowingNetwork(layer, name, entry.getValue(), policy);
					default -> throw wideningLayer(layer, name, entry.getValue());
				}
			}
		}
	}

	/**
	 * Refuses a layer's filesystem entries of a session permission profile that would widen the session.
	 *
	 * @param layer      the layer
	 * @param setting    the name of the profile's filesystem table
	 * @param filesystem the table
	 * @param policy     the session's policy
	 * @throws SessionRefusedException if an entry would widen the session
	 * @throws IOException             if the real path of an entry cannot be read
	 */
	private static void requireNarrowingFilesystem(Path layer, String setting, JsonNode filesystem,
		ChildPolicy policy) throws IOException
	{
		if (!filesystem.isObject())
			throw wideningLayer(layer, setting, filesystem);
		for (Entry<String, JsonNode> entry : filesystem.properties())
		{
			String name = setting + ".\"" + entry.getKey() + '"';
			String access = entry.getValue().asString("");
			if (!entry.getValue().isString())
				throw wideningLayer(layer, name, entry.getValue());
			switch (access)
			{
				case "deny", "none" ->
				{
					// Denying a path only narrows the session
				}
				case "read" ->
				{
					if (!entry.getKey().startsWith(":") &&
						RealPaths.isInsideAny(RealPaths.resolve(Path.of(entry.getKey()).toAbsolutePath()),
							policy.readDenials()))
					{
						throw wideningLayer(layer, name, entry.getValue());
					}
				}
				case "write" ->
				{
					if (entry.getKey().equals(TEMPORARY_DIRECTORY))
						continue;
					if (entry.getKey().startsWith(":"))
						throw wideningLayer(layer, name, entry.getValue());
					Path path = RealPaths.resolve(Path.of(entry.getKey()).toAbsolutePath());
					if (!RealPaths.isInsideAny(path, policy.writeRoots()) ||
						RealPaths.isInsideAny(path, policy.writeDenials()) ||
						RealPaths.isInsideAny(path, policy.readDenials()))
					{
						throw wideningLayer(layer, name, entry.getValue());
					}
				}
				default -> throw wideningLayer(layer, name, entry.getValue());
			}
		}
	}

	/**
	 * Refuses a layer's network entries of a session permission profile that would widen the session.
	 *
	 * @param layer   the layer
	 * @param setting the name of the profile's network table
	 * @param network the table
	 * @param policy  the session's policy
	 * @throws SessionRefusedException if an entry would widen the session
	 */
	private static void requireNarrowingNetwork(Path layer, String setting, JsonNode network, ChildPolicy policy)
		throws SessionRefusedException
	{
		if (!network.isObject())
			throw wideningLayer(layer, setting, network);
		for (Entry<String, JsonNode> entry : network.properties())
		{
			String name = setting + "." + entry.getKey();
			JsonNode value = entry.getValue();
			switch (entry.getKey())
			{
				case "enabled" ->
				{
					if (!value.isBoolean() || value.asBoolean() && policy.hosts().isEmpty())
						throw wideningLayer(layer, name, value);
				}
				case "allow_local_binding" ->
				{
					if (!value.isBoolean() || value.asBoolean() && !policy.allowLocalBinding())
						throw wideningLayer(layer, name, value);
				}
				case "domains" ->
				{
					if (!value.isObject())
						throw wideningLayer(layer, name, value);
					for (Entry<String, JsonNode> domain : value.properties())
					{
						String access = domain.getValue().asString("");
						boolean listed = policy.hosts().contains(domain.getKey().strip().toLowerCase(Locale.ROOT));
						if (!access.equals("deny") && !(access.equals("allow") && listed))
							throw wideningLayer(layer, name + ".\"" + domain.getKey() + '"', domain.getValue());
					}
				}
				default -> throw wideningLayer(layer, name, value);
			}
		}
	}

	/**
	 * Returns the refusal of a session whose Codex configuration layer sets a setting that chooses the session's
	 * credential or its model host, which would replace the source home's. The refusal does not quote the setting's
	 * value, which may hold a secret.
	 *
	 * @param layer   the layer
	 * @param setting the setting
	 * @return the refusal
	 */
	private static SessionRefusedException credentialLayer(Path layer, String setting)
	{
		return new SessionRefusedException("CAT does not start the session: the Codex configuration layer " + layer +
			" sets " + setting + ", which Codex merges into the session's configuration and which would replace the " +
			"credential or the model host that the session takes from its source home. Remove the setting from " +
			layer + ", then start the session again.");
	}

	/**
	 * Returns the refusal of a session whose Codex configuration layer would widen it.
	 *
	 * @param layer   the layer
	 * @param setting the setting that would widen the session
	 * @param value   the setting's value
	 * @return the refusal
	 */
	private static SessionRefusedException wideningLayer(Path layer, String setting, JsonNode value)
	{
		return new SessionRefusedException("CAT does not start the session: the Codex configuration " +
			"layer " + layer + " sets " + setting + " = " + value + ", which Codex merges into the session's " +
			"configuration and which would give the session more access than its sandbox policy allows. Remove the " +
			"setting from " + layer + ", then start the session again.");
	}

	/**
	 * Copies supported connection and project instruction settings and disables startup update checks in the isolated
	 * configuration, which also makes each session's working directory its project root, so that Codex looks for no
	 * root marker above it. On non-Windows platforms, the copied project's own Git directory is writable, except for
	 * its configuration and hooks; projects without a Git directory receive no Git grant.
	 *
	 * @param source      the host configuration file
	 * @param destination the isolated configuration file
	 * @param project     the copied project that the isolated session runs in
	 * @throws IOException if configuration I/O fails
	 */
	public static void copy(Path source, Path destination, Path project) throws IOException
	{
		copy(source, destination, project, HarnessDependencyCaches.NONE, destination.getParent().resolve("tmp"));
	}

	/**
	 * Copies supported connection and project instruction settings and makes verified dependency caches available to
	 * shell commands.
	 *
	 * <p>
	 * Codex starts its own sandboxed shell processes. Passing a cache variable to the app server alone does not make
	 * it available to those shells, so a plain {@code npm install} can instead select the read-only default cache and
	 * fail before it reaches the registry. This configuration gives every shell the variables that name the caches and
	 * grants the caches the matching workspace-write authority.
	 *
	 * @param source             the host configuration file
	 * @param destination        the isolated configuration file
	 * @param project            the copied project that the isolated session runs in
	 * @param caches             the verified writable dependency caches
	 * @param temporaryDirectory the fresh temporary directory owned by the isolated runner
	 * @throws IOException if configuration I/O fails
	 */
	public static void copy(Path source, Path destination, Path project, HarnessDependencyCaches caches,
		Path temporaryDirectory)
		throws IOException
	{
		copy(source, destination, project, caches, temporaryDirectory, 0, Platform.current());
	}

	/**
	 * Copies supported connection and project instruction settings, exposes verified dependency caches to shell
	 * commands and limits each tool output's retained tokens. On Windows, it prefers Codex's MXC sandbox where the
	 * system offers it.
	 *
	 * @param source               the host configuration file
	 * @param destination          the isolated configuration file
	 * @param project              the copied project that the isolated session runs in
	 * @param caches               the verified writable dependency caches
	 * @param temporaryDirectory   the fresh temporary directory owned by the isolated runner
	 * @param toolOutputTokenLimit the most tokens of each tool output that the session keeps, or {@code 0} for
	 *   Codex's default
	 * @param platform             the platform that the session runs on
	 * @throws IOException if configuration I/O fails
	 */
	public static void copy(Path source, Path destination, Path project, HarnessDependencyCaches caches,
		Path temporaryDirectory, int toolOutputTokenLimit, Platform platform) throws IOException
	{
		copy(source, destination, project, caches, temporaryDirectory, toolOutputTokenLimit, null, platform);
	}

	/**
	 * Copies the configuration as {@link #copy(Path, Path, Path, HarnessDependencyCaches, Path, int, Platform)} does,
	 * except that a session that may request sessions can also write the nested session's requests directory. Its
	 * commands receive the variables that name that directory and the {@code request-session} launcher.
	 *
	 * @param source               the host configuration file
	 * @param destination          the isolated configuration file
	 * @param project              the copied project that the isolated session runs in
	 * @param caches               the verified writable dependency caches
	 * @param temporaryDirectory   the fresh temporary directory owned by the isolated runner
	 * @param toolOutputTokenLimit the most tokens of each tool output that the session keeps, or {@code 0} for
	 *   Codex's default
	 * @param requestAccess        the session's access to requested sessions, or {@code null} if it may request none
	 * @param platform             the platform that the session runs on
	 * @throws IOException if configuration I/O fails
	 */
	public static void copy(Path source, Path destination, Path project, HarnessDependencyCaches caches,
		Path temporaryDirectory, int toolOutputTokenLimit, SessionRequestAccess requestAccess, Platform platform)
		throws IOException
	{
		copy(source, destination, project, caches, temporaryDirectory, toolOutputTokenLimit, requestAccess, platform,
			CodexParentMultiAgentState.NONE);
	}

	/**
	 * Copies the configuration as
	 * {@link #copy(Path, Path, Path, HarnessDependencyCaches, Path, int, SessionRequestAccess, Platform)} does, and
	 * gives the session the multi-agent settings of the Codex thread that starts it, so that the session may start
	 * subagents exactly when that thread may: on its own initiative or only when asked.
	 * <p>
	 * The session gets the parent configuration's {@code [features]} entries {@code multi_agent} and
	 * {@code multi_agent_v2}, in whichever form they take, and its {@code [agents]} entries {@code enabled},
	 * {@code max_threads} (or its other name {@code max_concurrent_threads_per_session}), {@code max_depth},
	 * {@code job_max_runtime_seconds} and {@code interrupt_message}. It gets an {@code [agents.<role>]} table only if the
	 * table names no {@code config_file} or names one inside the session's own home or project, because the session
	 * cannot use a role whose file lies elsewhere. It gets no other entry of those tables.
	 * <p>
	 * The thread's recorded state then applies over those entries, as
	 * {@link CodexParentMultiAgentState#applyTo(ObjectNode, ObjectNode)} describes: where a configured entry and the
	 * recorded state contradict each other, the recorded state wins, because it is what the thread actually runs with.
	 *
	 * @param source               the host configuration file
	 * @param destination          the isolated configuration file
	 * @param project              the copied project that the isolated session runs in
	 * @param caches               the verified writable dependency caches
	 * @param temporaryDirectory   the fresh temporary directory owned by the isolated runner
	 * @param toolOutputTokenLimit the most tokens of each tool output that the session keeps, or {@code 0} for
	 *   Codex's default
	 * @param requestAccess        the session's access to requested sessions, or {@code null} if it may request none
	 * @param platform             the platform that the session runs on
	 * @param parentState          the multi-agent state that the starting Codex thread recorded, or
	 *   {@link CodexParentMultiAgentState#NONE} if no Codex thread starts the session
	 * @throws IOException if configuration I/O fails
	 */
	public static void copy(Path source, Path destination, Path project, HarnessDependencyCaches caches,
		Path temporaryDirectory, int toolOutputTokenLimit, SessionRequestAccess requestAccess, Platform platform,
		CodexParentMultiAgentState parentState) throws IOException
	{
		requireThat(destination, "destination").isNotNull();
		requireThat(caches, "caches").isNotNull();
		copy(source, destination, project, caches, temporaryDirectory, toolOutputTokenLimit, requestAccess, platform,
			parentState, ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(
				destination.toAbsolutePath().getParent(), caches.directories(), List.of(), Set.of())));
	}

	/**
	 * Copies the configuration as
	 * {@link #copy(Path, Path, Path, HarnessDependencyCaches, Path, int, SessionRequestAccess, Platform,
	 * CodexParentMultiAgentState, ChildPolicy, ExtraPermissions)} does for a session that asks for no extra permissions.
	 *
	 * @param source               the host configuration file
	 * @param destination          the isolated configuration file
	 * @param project              the copied project that the isolated session runs in
	 * @param caches               the verified writable dependency caches
	 * @param temporaryDirectory   the fresh temporary directory owned by the isolated runner
	 * @param toolOutputTokenLimit the most tokens of each tool output that the session keeps, or {@code 0} for
	 *   Codex's default
	 * @param requestAccess        the session's access to requested sessions, or {@code null} if it may request none
	 * @param platform             the platform that the session runs on
	 * @param parentState          the multi-agent state that the starting Codex thread recorded, or
	 *   {@link CodexParentMultiAgentState#NONE} if no Codex thread starts the session
	 * @param policy               the session's policy
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} are null
	 * @throws IOException          if configuration I/O fails
	 */
	public static void copy(Path source, Path destination, Path project, HarnessDependencyCaches caches,
		Path temporaryDirectory, int toolOutputTokenLimit, SessionRequestAccess requestAccess, Platform platform,
		CodexParentMultiAgentState parentState, ChildPolicy policy) throws IOException
	{
		copy(source, destination, project, caches, temporaryDirectory, toolOutputTokenLimit, requestAccess, platform,
			parentState, policy, ExtraPermissions.NONE);
	}

	/**
	 * Copies the configuration as
	 * {@link #copy(Path, Path, Path, HarnessDependencyCaches, Path, int, SessionRequestAccess, Platform,
	 * CodexParentMultiAgentState)} does, within a session policy.
	 * <p>
	 * The session may write a dependency cache only if the policy keeps it among its writes, may only read each path
	 * that the policy keeps it from writing, and may not read a path that the policy denies it reading. The session's
	 * commands may reach exactly the policy's hosts, through Codex's network proxy, and bind local addresses only if
	 * the policy allows it; a policy without hosts turns their network off. No network setting of the host
	 * configuration applies to the session.
	 *
	 * @param source               the host configuration file
	 * @param destination          the isolated configuration file
	 * @param project              the copied project that the isolated session runs in
	 * @param caches               the verified writable dependency caches
	 * @param temporaryDirectory   the fresh temporary directory owned by the isolated runner
	 * @param toolOutputTokenLimit the most tokens of each tool output that the session keeps, or {@code 0} for
	 *   Codex's default
	 * @param requestAccess        the session's access to requested sessions, or {@code null} if it may request none
	 * @param platform             the platform that the session runs on
	 * @param parentState          the multi-agent state that the starting Codex thread recorded, or
	 *   {@link CodexParentMultiAgentState#NONE} if no Codex thread starts the session
	 * @param policy               the session's policy
	 * @param extraPermissions     the writes and hosts that the session's launch asks for beyond what Codex needs; the
	 *   session may write each of the writes that the policy keeps, as it may write a dependency cache
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} are null
	 * @throws IOException          if configuration I/O fails
	 */
	public static void copy(Path source, Path destination, Path project, HarnessDependencyCaches caches,
		Path temporaryDirectory, int toolOutputTokenLimit, SessionRequestAccess requestAccess, Platform platform,
		CodexParentMultiAgentState parentState, ChildPolicy policy, ExtraPermissions extraPermissions)
		throws IOException
	{
		copy(source, destination, project, caches, temporaryDirectory, toolOutputTokenLimit, requestAccess, platform,
			parentState, policy, extraPermissions, null);
	}

	/**
	 * Generates the session's current configuration and retains the native plugin registrations and trust from its
	 * previous launch, if any. The runner stages the new configuration in the same home as the retained configuration
	 * before replacing it.
	 *
	 * @param source                the host configuration file
	 * @param destination           the staged configuration in the session's home
	 * @param project               the session's project
	 * @param caches                the verified writable dependency caches
	 * @param temporaryDirectory    the session's temporary directory
	 * @param toolOutputTokenLimit  the maximum tokens of each tool output, or {@code 0} for Codex's default
	 * @param requestAccess         the session's request access, or {@code null} if it may request no sessions
	 * @param platform              the platform that the session runs on
	 * @param parentState           the parent thread's recorded multi-agent settings
	 * @param policy                the current sandbox policy
	 * @param extraPermissions      the writes and hosts the launch asks for beyond what Codex needs
	 * @param retainedConfiguration the session's previous configuration on resume, or {@code null} on a fresh launch
	 * @throws IOException if a configuration cannot be read, parsed or written
	 */
	public static void copy(Path source, Path destination, Path project, HarnessDependencyCaches caches,
		Path temporaryDirectory, int toolOutputTokenLimit, SessionRequestAccess requestAccess, Platform platform,
		CodexParentMultiAgentState parentState, ChildPolicy policy, ExtraPermissions extraPermissions,
		Path retainedConfiguration) throws IOException
	{
		copy(source, destination, destination.getParent(), "", project, caches, temporaryDirectory,
			toolOutputTokenLimit, requestAccess, platform, parentState, policy, extraPermissions, retainedConfiguration);
	}

	/**
	 * Generates a session's configuration in the session's Codex folder, as
	 * {@link #copy(Path, Path, Path, HarnessDependencyCaches, Path, int, SessionRequestAccess, Platform,
	 * CodexParentMultiAgentState, ChildPolicy, ExtraPermissions, Path)} does, except that the session's commands get
	 * the session home as their home folder and its Codex folder, the configuration's folder, as their
	 * {@code CODEX_HOME}, and that the configuration takes the source home's settings that choose the session's
	 * credential and model host. The runner's own configuration gives the session no such setting.
	 *
	 * @param source                the runner's own Codex configuration, which may not exist
	 * @param destination           the staged configuration in the session's Codex folder
	 * @param sessionHome           the session's home, which holds its Codex folder and which its commands receive as
	 *                              their home folder and may write
	 * @param connectionSettings    the TOML of the source home's settings, as dotted keys of the configuration's root,
	 *                              or an empty string
	 * @param project               the session's project
	 * @param caches                the verified writable dependency caches
	 * @param temporaryDirectory    the session's temporary directory
	 * @param toolOutputTokenLimit  the maximum tokens of each tool output, or {@code 0} for Codex's default
	 * @param requestAccess         the session's request access, or {@code null} if it may request no sessions
	 * @param platform              the platform that the session runs on
	 * @param parentState           the parent thread's recorded multi-agent settings
	 * @param policy                the current sandbox policy
	 * @param extraPermissions      the writes and hosts the launch asks for beyond what Codex needs
	 * @param retainedConfiguration the session's previous configuration on resume, or {@code null} on a fresh launch
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} and
	 *                              {@code retainedConfiguration} are null
	 * @throws IOException          if a configuration cannot be read, parsed or written
	 */
	public static void copy(Path source, Path destination, Path sessionHome, String connectionSettings, Path project,
		HarnessDependencyCaches caches, Path temporaryDirectory, int toolOutputTokenLimit,
		SessionRequestAccess requestAccess, Platform platform, CodexParentMultiAgentState parentState,
		ChildPolicy policy, ExtraPermissions extraPermissions, Path retainedConfiguration) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(destination, "destination").isNotNull();
		requireThat(sessionHome, "sessionHome").isNotNull();
		requireThat(connectionSettings, "connectionSettings").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(caches, "caches").isNotNull();
		requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(parentState, "parentState").isNotNull();
		requireThat(toolOutputTokenLimit, "toolOutputTokenLimit").isNotNegative();
		requireThat(policy, "policy").isNotNull();
		requireThat(extraPermissions, "extraPermissions").isNotNull();
		// The session's working directory, the copied project, holds its own Git repository and is therefore its
		// project root anyway. Looking for a root marker in each directory above it costs one sandboxed helper process
		// per directory whenever a thread starts, and under concurrent sessions that delayed each thread's first
		// command by many seconds.
		StringBuilder copied = new StringBuilder(256).append(STARTUP_UPDATE_CHECK).append(System.lineSeparator()).
			append("default_permissions = \"").append(RUNNER_PERMISSION_PROFILE).append('"').
			append(System.lineSeparator()).append("project_root_markers = []").append(System.lineSeparator());
		// A top-level key must precede every table
		if (toolOutputTokenLimit > 0)
			copied.append("tool_output_token_limit = ").append(toolOutputTokenLimit).append(System.lineSeparator());
		JsonNode parentConfiguration = null;
		if (Files.isRegularFile(source))
		{
			String configuration = BoundedInput.readUtf8(source, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
			parentConfiguration = parse(source, configuration, "connection settings");
			instructionSettings(parentConfiguration).ifPresent(copied::append);
			for (String line : configuration.lines().toList())
			{
				String trimmed = line.stripLeading();
				if (trimmed.startsWith("["))
					break;
				int equals = trimmed.indexOf('=');
				if (equals < 1)
					continue;
				String name = trimmed.substring(0, equals).strip();
				if (CONNECTION_SETTINGS.contains(name))
					copied.append(line).append(System.lineSeparator());
			}
		}
		copied.append(connectionSettings);
		TomlMapper mapper = TomlMapper.builder().build();
		if (retainedConfiguration != null)
		{
			JsonNode previous = parse(retainedConfiguration, BoundedInput.readUtf8(retainedConfiguration,
				MAXIMUM_CONFIGURATION_BYTES, "Codex configuration"), "retained configuration");
			ObjectNode state = mapper.createObjectNode();
			for (String table : List.of("plugins", "marketplaces", "projects"))
			{
				if (previous.has(table))
					state.set(table, previous.get(table));
			}
			JsonNode hookState = previous.path("hooks").path("state");
			if (hookState.isObject())
				state.putObject("hooks").set("state", hookState);
			// The mapper writes dotted keys, which belong at the root before any table header.
			if (!state.isEmpty())
				copied.append(System.lineSeparator()).append(mapper.writeValueAsString(state));
		}
		List<Path> keptWrites = new ArrayList<>();
		for (Path cache : caches.directories())
		{
			if (policy.writeRoots().contains(RealPaths.resolve(cache.toAbsolutePath())))
				keptWrites.add(cache);
		}
		for (Path write : extraPermissions.writes())
		{
			if (policy.writeRoots().contains(RealPaths.resolve(write)) && !keptWrites.contains(write))
				keptWrites.add(write);
		}
		if (!keptWrites.isEmpty())
		{
			StringJoiner roots = new StringJoiner(", ", "[", "]");
			for (Path cache : keptWrites)
				roots.add('"' + toml(cache) + '"');
			copied.append(System.lineSeparator()).append("[sandbox_workspace_write]").append(System.lineSeparator()).
				append("writable_roots = ").append(roots).append(System.lineSeparator());
		}
		// The commands that a session runs get the nested session's home as their home and no harness credential, so that a
		// harness started from a command finds neither the user's login nor the user's configuration
		StringJoiner credentials = new StringJoiner(", ", "[", "]");
		for (String credential : new TreeSet<>(SessionCommandLimits.CREDENTIAL_VARIABLES))
			credentials.add('"' + credential + '"');
		copied.append(System.lineSeparator()).append("[shell_environment_policy]").append(System.lineSeparator()).
			append("exclude = ").append(credentials).append(System.lineSeparator()).
			append(System.lineSeparator()).append("[shell_environment_policy.set]").append(System.lineSeparator());
		Path codexFolder = destination.toAbsolutePath().getParent();
		Map<String, String> commandEnvironment = new TreeMap<>(caches.environment());
		commandEnvironment.putAll(SessionCommandLimits.homeEnvironment(sessionHome, platform));
		// A Codex that a command starts, such as a prompt optimizer's session, finds the session's own Codex folder
		commandEnvironment.put("CODEX_HOME", codexFolder.toString());
		List<Path> writable = new ArrayList<>(keptWrites);
		if (requestAccess != null)
		{
			commandEnvironment.putAll(requestAccess.environment());
			writable.add(requestAccess.requests());
		}
		for (Entry<String, String> variable : commandEnvironment.entrySet())
		{
			String value = TomlStrings.escape(variable.getValue());
			copied.append(variable.getKey()).append(" = \"").append(value).append('"').append(System.lineSeparator());
		}
		copied.append("TMPDIR = \"").append(toml(temporaryDirectory)).append('"').append(System.lineSeparator());
		ObjectNode features = mapper.createObjectNode();
		if (platform.isWindows())
		{
			// Without an administrator's setup, Windows offers only Codex's unelevated sandbox, which refuses a profile
			// that denies reads, and MXC, which enforces it
			features.put("prefer_mxc", true);
		}
		ObjectNode agents = mapper.createObjectNode();
		if (parentConfiguration != null)
			copyMultiAgentSettings(parentConfiguration, features, agents, sessionHome, project);
		parentState.applyTo(features, agents);
		// Without its network proxy, Codex ignores the profile's host list and lets the commands reach every host
		if (!policy.hosts().isEmpty())
			features.put(NETWORK_PROXY_FEATURE, true);
		appendTable(copied, mapper, "features", features);
		appendTable(copied, mapper, "agents", agents);
		appendRunnerProfile(copied, sessionHome, codexFolder, writable, temporaryDirectory, project, platform, policy);
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, copied.toString());
	}

	/**
	 * Copies the parent configuration's multi-agent entries.
	 *
	 * @param parent       the parent configuration's values
	 * @param features     receives the {@code [features]} entries
	 * @param agents       receives the {@code [agents]} entries
	 * @param isolatedHome the session's own home
	 * @param project      the copied project that the session runs in
	 */
	private static void copyMultiAgentSettings(JsonNode parent, ObjectNode features, ObjectNode agents,
		Path isolatedHome, Path project)
	{
		assert parent != null;
		assert features != null;
		assert agents != null;
		JsonNode parentFeatures = parent.path("features");
		for (String name : MULTI_AGENT_FEATURES)
		{
			if (parentFeatures.has(name))
				features.set(name, parentFeatures.get(name));
		}
		JsonNode parentAgents = parent.path("agents");
		if (!parentAgents.isObject())
			return;
		for (Entry<String, JsonNode> entry : parentAgents.properties())
		{
			String name = entry.getKey();
			JsonNode value = entry.getValue();
			if (AGENT_LIMITS.contains(name))
				agents.set(name, value);
			else if (value.isObject() && isAvailableRole(value, isolatedHome, project))
				agents.set(name, value);
		}
	}

	/**
	 * Returns whether a session can use an agent role that the parent configures.
	 *
	 * @param role         the role's {@code [agents.<role>]} table
	 * @param isolatedHome the session's own home
	 * @param project      the copied project that the session runs in
	 * @return {@code true} if the role names no {@code config_file}, or names one inside {@code isolatedHome} or
	 *   {@code project}
	 */
	private static boolean isAvailableRole(JsonNode role, Path isolatedHome, Path project)
	{
		JsonNode configFile = role.get("config_file");
		if (configFile == null)
			return true;
		if (!configFile.isString())
			return false;
		Path file;
		try
		{
			file = Path.of(configFile.asString());
		}
		catch (InvalidPathException _)
		{
			return false;
		}
		if (!file.isAbsolute())
			return false;
		Path normalized = file.normalize();
		return normalized.startsWith(isolatedHome.toAbsolutePath().normalize()) ||
			normalized.startsWith(project.toAbsolutePath().normalize());
	}

	/**
	 * Appends a table to the isolated configuration.
	 *
	 * @param configuration the isolated configuration being assembled
	 * @param mapper        the TOML mapper
	 * @param name          the table's name
	 * @param entries       the table's entries; an empty table is not appended
	 */
	private static void appendTable(StringBuilder configuration, TomlMapper mapper, String name, ObjectNode entries)
	{
		if (entries.isEmpty())
			return;
		configuration.append(System.lineSeparator()).append('[').append(name).append(']').
			append(System.lineSeparator()).append(mapper.writeValueAsString(entries).lines().
				collect(Collectors.joining(System.lineSeparator()))).append(System.lineSeparator());
	}

	/**
	 * Gives the nested process's own runtime state the same narrowly scoped write authority as its project workspace.
	 *
	 * <p>
	 * The harness shell starts from the copied project, while the runner deliberately puts its credentials, session
	 * state, plugins, and configuration in a sibling isolated home. A workspace-only profile cannot initialize that
	 * shell because its required home lies outside the workspace. The child profile extends the built-in workspace
	 * profile and adds the newly created isolated home, the harness temporary directory, the shared dependency caches,
	 * and the copied project's Git permissions. It never extends the host's own permission profiles: they grant the
	 * host's Codex home, CAT user directory and other workspaces, which a nested session must neither change nor see.
	 * Codex's Linux sandbox creates
	 * its process lock and scratch state under the harness temporary directory before a shell command can run, so it
	 * needs the same temporary-directory authority as the parent workspace-write sandbox. Its temporary directory is
	 * created inside the runner-owned home beneath CAT work storage, rather than granting global {@code /tmp}; the
	 * harness sandbox uses it before the first shell command can run.
	 * <p>
	 * A thread that names a profile ignores {@code [sandbox_workspace_write]}, so the profile carries the session's
	 * whole sandbox policy: the policy's write denials that exist stay readable only, its read denials that exist are
	 * denied, and its network rules apply, as {@link #appendNetwork(StringBuilder, Platform, ChildPolicy)} renders them.
	 * A denied path that is missing is left out, because Codex's Linux sandbox would create a placeholder there. The
	 * files that the session's own Codex app server executes or trusts inside the home stay read-only to the commands,
	 * as {@link #addIsolatedHomeProtections(Map, Path)} describes.
	 *
	 * @param configuration      the isolated configuration being assembled
	 * @param isolatedHome       the fresh runtime home that the runner owns
	 * @param codexFolder        the session's Codex folder, inside or equal to {@code isolatedHome}, whose
	 *                           configuration, hooks and plugins stay read-only to the commands
	 * @param writable           the other directories that the session may write: the verified shared dependency caches
	 *                           and the extra writes that its launch asks for, which the policy keeps, and the nested
	 *                           session's requests directory when the session may request sessions
	 * @param temporaryDirectory the fresh harness temporary directory owned by the runner
	 * @param project            the copied project that the isolated session runs in
	 * @param platform           the platform that the session runs on
	 * @param policy             the session's policy
	 * @throws SessionRefusedException if the session must bind local addresses on macOS
	 * @throws IOException             if the real path of a policy denial cannot be read
	 */
	private static void appendRunnerProfile(StringBuilder configuration, Path isolatedHome, Path codexFolder,
		List<Path> writable, Path temporaryDirectory, Path project, Platform platform, ChildPolicy policy)
		throws IOException
	{
		assert configuration != null;
		assert isolatedHome != null;
		assert codexFolder != null;
		assert writable != null;
		assert temporaryDirectory != null;
		configuration.append(System.lineSeparator()).append("[permissions.").append(RUNNER_PERMISSION_PROFILE).
			append(']').append(System.lineSeparator()).append("extends = \":workspace\"").
			append(System.lineSeparator()).append("[permissions.").append(RUNNER_PERMISSION_PROFILE).
			append(".filesystem]").append(System.lineSeparator());
		// A TOML table may not name a key twice, so an entry for a path that an earlier one names replaces it
		Map<String, String> filesystem = new LinkedHashMap<>();
		filesystem.put(toml(isolatedHome), "write");
		filesystem.put(TEMPORARY_DIRECTORY, "write");
		for (Path directory : writable)
			filesystem.put(toml(directory), "write");
		addIsolatedHomeProtections(filesystem, codexFolder);
		addProjectGitPermissions(filesystem, project, platform);
		for (Path denial : policy.writeDenials())
		{
			// Codex's Linux sandbox creates an empty placeholder at a denied path that is missing, which would add an
			// entry to a folder of the user's, such as a dependency cache
			if (Files.exists(denial))
				filesystem.put(toml(denial), "read");
		}
		// The project is the working directory, which the built-in workspace profile lets the session write
		List<Path> grants = new ArrayList<>(List.of(isolatedHome, temporaryDirectory, project));
		grants.addAll(writable);
		grants.addAll(policy.writeDenials());
		for (Path denial : outermostExisting(policy.readDenials(), grants))
			filesystem.put(toml(denial), "deny");
		for (Entry<String, String> entry : filesystem.entrySet())
		{
			configuration.append('"').append(entry.getKey()).append("\" = \"").append(entry.getValue()).append('"').
				append(System.lineSeparator());
		}
		appendNetwork(configuration, platform, policy);
	}

	/**
	 * Appends the runner profile's network rules. With hosts, the session's commands may reach exactly the policy's
	 * hosts, which Codex enforces only while its {@code network_proxy} feature is on, and may bind local addresses
	 * only if the policy allows it; Codex's proxy refuses a host that
	 * {@link ChildPolicy#needsLocalBinding(String) needs local binding} without that permission. Without hosts, their
	 * network is off.
	 * <p>
	 * On macOS, Codex's sandbox lets a session that may bind local addresses connect directly to every loopback port,
	 * bypassing the proxy's host list, so the runner refuses such a session rather than widen it.
	 *
	 * @param configuration the isolated configuration being assembled
	 * @param platform      the platform that the session runs on
	 * @param policy        the session's policy
	 * @throws SessionRefusedException if the session must bind local addresses on macOS
	 */
	private static void appendNetwork(StringBuilder configuration, Platform platform, ChildPolicy policy)
		throws SessionRefusedException
	{
		configuration.append("[permissions.").append(RUNNER_PERMISSION_PROFILE).append(".network]").
			append(System.lineSeparator());
		if (policy.hosts().isEmpty())
		{
			configuration.append("enabled = false").append(System.lineSeparator());
			return;
		}
		configuration.append("enabled = true").append(System.lineSeparator());
		if (policy.allowLocalBinding())
		{
			if (platform == Platform.MACOS_X64 || platform == Platform.MACOS_ARM64)
			{
				throw new SessionRefusedException("CAT does not start the session: its hosts " +
					new TreeSet<>(policy.hosts()) + " include localhost or a loopback, private or link-local " +
					"address, which Codex reaches only if the session may bind local addresses, and on macOS that " +
					"would let the session's commands reach every loopback port. Use hosts that are none of these.");
			}
			configuration.append("allow_local_binding = true").append(System.lineSeparator());
		}
		StringJoiner domains = new StringJoiner(", ", "domains = { ", " }");
		for (String host : new TreeSet<>(policy.hosts()))
			domains.add('"' + TomlStrings.escape(host) + "\" = \"allow\"");
		configuration.append(domains).append(System.lineSeparator());
	}

	/**
	 * Makes read-only the files that the session's own Codex app server executes or trusts inside its isolated home,
	 * while leaving the rest of the home writable.
	 * <p>
	 * The session's commands may write the home so that the harness and its tools keep their scratch state, but they
	 * may not rewrite the configuration, the hooks file beside it, or the plugin cache that holds the hook scripts.
	 * The app server reads a hook's declaration and its approval but never its script, so a command that rewrote the
	 * script would have the app server run the new program outside the command sandbox. The runner writes these files
	 * before the session starts, from outside the command sandbox, so making them read-only to the commands leaves the
	 * nested session's setup unaffected. A read entry also forbids creating the path, so a command cannot plant a
	 * user-level {@code hooks.json} that Codex would trust without an approval.
	 * <p>
	 * The sandbox puts an empty regular file at a read entry's path when nothing exists there, and a configuration
	 * reader rejects an empty file. The method therefore first writes a {@code hooks.json} that defines no hooks when
	 * the home has none, and leaves an existing one as it is. Codex also keeps a writable root's {@code .codex} folder
	 * read-only, so its sandbox cannot create that placeholder inside the session's Codex folder at all and refuses
	 * to start the thread. The method therefore also creates an empty plugin folder when the session installs no
	 * plugins.
	 *
	 * @param filesystem   the runner profile's filesystem entries, by TOML-escaped path
	 * @param isolatedHome the session's Codex folder, which holds its configuration, hooks and plugins
	 * @throws IOException if the hooks file or the plugin folder cannot be created
	 */
	private static void addIsolatedHomeProtections(Map<String, String> filesystem, Path isolatedHome)
		throws IOException
	{
		Files.createDirectories(isolatedHome);
		Path hooks = isolatedHome.resolve("hooks.json");
		if (!Files.exists(hooks, LinkOption.NOFOLLOW_LINKS))
			Files.writeString(hooks, "{\"hooks\":{}}" + System.lineSeparator());
		Path plugins = isolatedHome.resolve("plugins");
		if (!Files.exists(plugins, LinkOption.NOFOLLOW_LINKS))
			Files.createDirectory(plugins);
		for (String trusted : List.of("config.toml", "hooks.json", "plugins"))
			filesystem.put(toml(isolatedHome.resolve(trusted)), "read");
	}

	/**
	 * Grants the copied project's Git metadata write access while protecting configuration and hooks.
	 *
	 * @param filesystem the runner profile's filesystem entries, by TOML-escaped path
	 * @param project    the copied project that the isolated session runs in
	 * @param platform   the platform that the session runs on
	 */
	private static void addProjectGitPermissions(Map<String, String> filesystem, Path project, Platform platform)
	{
		// Windows uses MXC or the native Windows sandbox rather than Linux's read-only mounts. Without verified
		// protection for config and hooks there, keep Git metadata under the built-in workspace restrictions.
		if (platform.isWindows())
			return;
		Path git = project.resolve(".git");
		// A linked worktree or symbolic link can point outside the copied project; only its own directory is granted.
		if (!Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS))
			return;

		// Configuration and hooks can make a later Git command execute session-written code. Give the session the
		// refs, objects and index it needs for commits and worktrees, while leaving those execution settings read-only.
		filesystem.put(toml(git), "write");
		for (String protectedPath : ProtectedProjectFiles.gitNames())
			filesystem.put(toml(git.resolve(protectedPath)), "read");
	}

	/**
	 * Escapes one filesystem path for a TOML basic string.
	 *
	 * @param path the path to render
	 * @return a TOML-safe absolute path value
	 */
	private static String toml(Path path)
	{
		assert path != null;
		return TomlStrings.escape(path.toAbsolutePath().normalize().toString());
	}

	/**
	 * Adds the permission profile that a session's thread runs under.
	 *
	 * @param destination the isolated Codex configuration
	 * @param readOnlyEvidence the completed evidence directories and files that the session may read but not change
	 * @param deniedReads the existing files and directories that the session must not read
	 * @return the profile that the thread names, or an empty string if the thread uses the workspace-write sandbox
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the isolated configuration cannot be read, parsed or updated
	 */
	public static String addSessionProfile(Path destination, List<Path> readOnlyEvidence, List<Path> deniedReads)
		throws IOException
	{
		return addSessionProfile(destination, readOnlyEvidence, deniedReads, RUNNER_PERMISSION_PROFILE);
	}

	/**
	 * Adds the thread profile above the runner profile selected for its app-server command.
	 *
	 * @param destination the isolated Codex configuration
	 * @param readOnlyEvidence the evidence the thread may read
	 * @param deniedReads the additional paths the thread must not read
	 * @param runnerProfile the base profile of the thread
	 * @return the thread's profile, or empty when the default runner sandbox applies
	 * @throws IOException if the configuration cannot be updated
	 */
	static String addSessionProfile(Path destination, List<Path> readOnlyEvidence, List<Path> deniedReads,
		String runnerProfile) throws IOException
	{
		requireThat(destination, "destination").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		String profile = runnerProfile;
		if (!readOnlyEvidence.isEmpty())
		{
			addValidatorProfile(destination, readOnlyEvidence, runnerProfile);
			profile = VALIDATOR_PERMISSION_PROFILE;
		}
		if (!deniedReads.isEmpty())
		{
			addDeniedReadsProfile(destination, profile, deniedReads);
			return DENIED_READS_PERMISSION_PROFILE;
		}
		if (RUNNER_PERMISSION_PROFILE.equals(profile))
			return "";
		return profile;
	}

	/**
	 * Selects a runner profile for a Linux app-server that already masks the named directories. Keep the default
	 * profile for other commands. Repeating the same mask in the thread would cover the evidence mounts. Denials
	 * below an outer mask still apply to the paths that the app-server reopens.
	 *
	 * @param destination the isolated Codex configuration
	 * @param maskedDirectories the real directory paths masked by the app-server command
	 * @param reopenedPaths the paths the command reopens inside those masks
	 * @param policy the launch's full read and write restrictions
	 * @return the runner profile for the app-server's thread
	 * @throws IOException if the configuration cannot be read or updated
	 */
	static String addMountedRunnerProfile(Path destination, List<Path> maskedDirectories, List<Path> reopenedPaths,
		ChildPolicy policy) throws IOException
	{
		if (maskedDirectories.isEmpty() || policy == null)
			return RUNNER_PERMISSION_PROFILE;
		Set<Path> repeatedDenials = new HashSet<>(policy.readDenials());
		repeatedDenials.retainAll(maskedDirectories);
		if (repeatedDenials.isEmpty())
			return RUNNER_PERMISSION_PROFILE;
		String configuration = BoundedInput.readUtf8(destination, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		JsonNode permissions = parse(destination, configuration, "runner profile").path("permissions");
		if (!(permissions.path(RUNNER_PERMISSION_PROFILE) instanceof ObjectNode runner))
			throw new IOException("The mounted session is missing its CAT runner permission profile");
		ObjectNode mounted = runner.deepCopy();
		if (!(mounted.path("filesystem") instanceof ObjectNode filesystem))
			throw new IOException("The mounted session is missing its CAT runner filesystem permissions");
		List<String> keys = new ArrayList<>(filesystem.propertyNames());
		for (String key : keys)
		{
			if (filesystem.path(key).asString("").equals("deny"))
				filesystem.remove(key);
		}
		List<Path> grants = new ArrayList<>(reopenedPaths);
		grants.addAll(profileGrants(permissions, RUNNER_PERMISSION_PROFILE));
		for (Path denial : outermostExisting(policy.readDenials(), grants, repeatedDenials))
			filesystem.put(denial.toString(), "deny");
		StringBuilder additions = new StringBuilder();
		appendTable(additions, TomlMapper.builder().build(), "permissions." + MOUNTED_RUNNER_PERMISSION_PROFILE, mounted);
		Files.writeString(destination, configuration + additions);
		return MOUNTED_RUNNER_PERMISSION_PROFILE;
	}

	/**
	 * Adds the profile that denies a session reading files and directories.
	 *
	 * @param destination the isolated Codex configuration
	 * @param parentProfile the profile whose permissions the session otherwise has
	 * @param deniedReads the files and directories to deny; a path that no longer exists is skipped
	 * @throws IOException if the isolated configuration cannot be read, parsed or updated
	 */
	private static void addDeniedReadsProfile(Path destination, String parentProfile, List<Path> deniedReads)
		throws IOException
	{
		String configuration = BoundedInput.readUtf8(destination, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		if (!configuration.contains("[permissions." + RUNNER_PERMISSION_PROFILE + "]"))
			throw new IOException("Denying reads requires the CAT runner permission profile");
		String section = "[permissions." + DENIED_READS_PERMISSION_PROFILE;
		StringBuilder profile = new StringBuilder(configuration).append(System.lineSeparator()).append(section).
			append(']').append(System.lineSeparator()).append("extends = \"").append(parentProfile).append('"').
			append(System.lineSeparator()).append(section).append(".filesystem]").append(System.lineSeparator());
		JsonNode permissions;
		try
		{
			permissions = TomlMapper.builder().build().readTree(configuration).path("permissions");
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot parse the permission profiles of " + destination, e);
		}
		for (Path deniedRead : outermostExisting(deniedReads, profileGrants(permissions, parentProfile)))
			profile.append('"').append(toml(deniedRead)).append("\" = \"deny\"").append(System.lineSeparator());
		Files.writeString(destination, profile.toString());
	}

	/**
	 * Returns the paths that a permission profile and the profiles that it extends let the session read or write. The
	 * built-in profiles, whose names start with {@code :}, and the special paths, whose names do the same, add none.
	 *
	 * @param permissions the configuration's {@code permissions} table
	 * @param profile     the profile
	 * @return the paths, each resolved to the file that it reaches
	 * @throws IOException if a symbolic link in a path cannot be read
	 */
	private static List<Path> profileGrants(JsonNode permissions, String profile) throws IOException
	{
		List<Path> grants = new ArrayList<>();
		Set<String> visited = new HashSet<>();
		String name = profile;
		while (!name.startsWith(":") && visited.add(name))
		{
			JsonNode entries = permissions.path(name).path("filesystem");
			for (Entry<String, JsonNode> entry : entries.properties())
			{
				if (entry.getKey().startsWith(":") || !entry.getValue().isString() ||
					entry.getValue().asString().equals("deny"))
				{
					continue;
				}
				Path path = Path.of(entry.getKey());
				if (path.isAbsolute())
					grants.add(RealPaths.resolve(path));
			}
			name = permissions.path(name).path("extends").asString(":");
		}
		return grants;
	}

	/**
	 * Returns the read denials that Codex's sandbox can apply. Codex's Linux sandbox creates an empty placeholder at a
	 * missing denied path while the session runs, which would block CAT or the parent from creating that path, so a
	 * missing path is left out. It cannot hide a file inside a folder that it already hides, so a denial inside
	 * another one is left to the outer one, unless the profile grants a folder between them or the denied path
	 * itself: Codex applies the most specific entry, so that grant would let the session read the inner path, and the
	 * Linux sandbox hides a path inside a writable folder after it exposes that folder. Each denial is named by its
	 * real path, which also covers every symbolic-link alias.
	 *
	 * @param denials the files and directories that the session must not read
	 * @param grants  the absolute paths that the profile lets the session read or write
	 * @return the real paths of the existing denials that lie inside no other one, or inside a grant inside it, each
	 *   once, in their order
	 * @throws IOException if the real path of a denial or a grant cannot be read
	 */
	private static List<Path> outermostExisting(List<Path> denials, List<Path> grants) throws IOException
	{
		return outermostExisting(denials, grants, Set.of());
	}

	/**
	 * Finds the effective existing denials after excluding exact paths masked by the app-server.
	 *
	 * @param denials the paths to deny
	 * @param grants the paths the thread may read or write
	 * @param masked the real paths already masked outside the thread
	 * @return the real paths to deny in the thread
	 * @throws IOException if a denial or a grant cannot be resolved
	 */
	private static List<Path> outermostExisting(List<Path> denials, List<Path> grants, Set<Path> masked)
		throws IOException
	{
		List<Path> resolvedGrants = RealPaths.resolveAll(grants);
		List<Path> existing = new ArrayList<>();
		for (Path denial : denials)
		{
			if (Files.exists(denial))
			{
				Path realPath = denial.toRealPath();
				if (!masked.contains(realPath) && !existing.contains(realPath))
					existing.add(realPath);
			}
		}
		List<Path> outermost = new ArrayList<>();
		for (Path denial : existing)
		{
			boolean nested = false;
			for (Path other : existing)
			{
				if (!other.equals(denial) && denial.startsWith(other) &&
					resolvedGrants.stream().noneMatch(grant -> !grant.equals(other) && grant.startsWith(other) &&
						denial.startsWith(grant)))
				{
					nested = true;
					break;
				}
			}
			if (!nested)
				outermost.add(denial);
		}
		return outermost;
	}

	/**
	 * Adds the profile used when an independent validator reads completed executor evidence.
	 *
	 * @param destination the isolated Codex configuration
	 * @param evidenceRoots the completed evidence directories and files
	 * @throws IOException if the isolated configuration cannot be updated
	 */
	public static void addValidatorProfile(Path destination, List<Path> evidenceRoots) throws IOException
	{
		addValidatorProfile(destination, evidenceRoots, RUNNER_PERMISSION_PROFILE);
	}

	/**
	 * Adds the read-only evidence grant above the runner profile selected for the thread.
	 *
	 * @param destination the isolated Codex configuration
	 * @param evidenceRoots the evidence paths
	 * @param runnerProfile the base profile of the thread
	 * @throws IOException if the configuration cannot be updated
	 */
	private static void addValidatorProfile(Path destination, List<Path> evidenceRoots, String runnerProfile)
		throws IOException
	{
		requireThat(evidenceRoots, "evidenceRoots").isNotEmpty();
		String configuration = BoundedInput.readUtf8(destination, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		if (!configuration.contains("[permissions." + RUNNER_PERMISSION_PROFILE + "]"))
			throw new IOException("Read-only validator evidence requires the CAT runner permission profile");
		// A validator's verdict is credible only if the harness can read executor evidence but cannot rewrite it.
		Files.writeString(destination, configuration + System.lineSeparator() + "[permissions." +
			VALIDATOR_PERMISSION_PROFILE + "]" + System.lineSeparator() + "extends = \"" + runnerProfile +
			"\"" + System.lineSeparator() + "[permissions." + VALIDATOR_PERMISSION_PROFILE + ".filesystem]" +
			System.lineSeparator() + evidencePermissions(evidenceRoots));
	}

	/**
	 * Renders the exact read-only authority needed for completed evidence.
	 *
	 * @param evidenceRoots the completed evidence directories and files
	 * @return TOML filesystem permission entries
	 * @throws IOException if an evidence entry cannot be canonicalized
	 */
	private static String evidencePermissions(List<Path> evidenceRoots) throws IOException
	{
		StringBuilder result = new StringBuilder();
		for (Path evidenceRoot : evidenceRoots)
		{
			String quotedEvidenceRoot = evidenceRoot.toRealPath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
			result.append('"').append(quotedEvidenceRoot).append("\" = \"read\"").append(System.lineSeparator());
		}
		return result.toString();
	}

	/**
	 * Copies the host's existing approvals for CAT's hooks into an isolated Codex home.
	 * <p>
	 * Codex checks the copied approval against the installed declaration. Explicit host disablement is preserved even
	 * if the host has no approval for the hook.
	 *
	 * @param source the host Codex configuration file
	 * @param destination the isolated Codex configuration file
	 * @throws IOException if configuration I/O fails
	 */
	public static void copyTrustedCatHooks(Path source, Path destination) throws IOException
	{
		if (!Files.isRegularFile(source))
			return;
		JsonNode state = hookState(source);
		StringBuilder approvals = new StringBuilder(256);
		for (String key : CAT_HOOK_KEYS)
		{
			JsonNode hook = state.path(key);
			String hash = hook.path("trusted_hash").asString("");
			boolean approved = TRUSTED_HASH.matcher(hash).matches();
			boolean disabled = hook.path("enabled").isBoolean() && !hook.path("enabled").booleanValue();
			if (!approved && !disabled)
				continue;
			approvals.append(System.lineSeparator()).append("[hooks.state.\"").append(key).append("\"]").
				append(System.lineSeparator());
			if (approved)
				approvals.append("trusted_hash = \"").append(hash).append('"').append(System.lineSeparator());
			if (disabled)
				approvals.append("enabled = false").append(System.lineSeparator());
		}
		if (approvals.isEmpty())
			return;
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, approvals.toString(), java.nio.file.StandardOpenOption.CREATE,
			java.nio.file.StandardOpenOption.APPEND);
	}

	/**
	 * Records a set of approvals whose native source and destination records have already been matched.
	 *
	 * @param destination the isolated configuration file
	 * @param bound exact trial hook keys mapped to their full native hashes
	 * @throws IOException if configuration I/O fails
	 */
	static void copyBoundHooks(Path destination, Map<String, String> bound) throws IOException
	{
		StringBuilder approvals = new StringBuilder(256);
		for (Entry<String, String> approval : bound.entrySet())
		{
			String key = TomlStrings.escape(approval.getKey());
			String hash = CodexPluginHooks.declarationHash(approval.getValue());
			approvals.append(System.lineSeparator()).append("[hooks.state.\"").append(key).
				append("\"]").append(System.lineSeparator()).
				append("trusted_hash = \"").append(hash).append('"').append(System.lineSeparator());
		}
		if (approvals.isEmpty())
			return;
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, approvals.toString(), java.nio.file.StandardOpenOption.CREATE,
			java.nio.file.StandardOpenOption.APPEND);
	}

	/**
	 * Reads enabled host approval records without removing the native marketplace or source identity from their keys.
	 *
	 * @param source the host configuration file
	 * @return exact keys mapped to existing declaration approvals
	 * @throws IOException if the configuration cannot be read
	 */
	static Map<String, String> configuredHookApprovals(Path source) throws IOException
	{
		Map<String, String> approvals = new LinkedHashMap<>();
		if (!Files.isRegularFile(source))
			return approvals;
		for (Entry<String, JsonNode> entry : hookState(source).properties())
		{
			JsonNode hook = entry.getValue();
			String hash = hook.path("trusted_hash").asString("");
			JsonNode enabled = hook.path("enabled");
			if (TRUSTED_HASH.matcher(hash).matches() && (enabled.isMissingNode() ||
				(enabled.isBoolean() && enabled.booleanValue())))
			{
				approvals.put(entry.getKey(), hash);
			}
		}
		return approvals;
	}

	/**
	 * Returns the user's approvals of a plugin's hooks, from any marketplace. Codex keys an approval by
	 * {@code <plugin>@<marketplace>:<hook>}, where {@code <hook>} is the hooks file relative to the plugin directory,
	 * the event in snake case, the matcher group's index and the handler's index, separated by colons.
	 *
	 * @param source the host configuration file
	 * @param plugin the plugin's manifest name
	 * @return each approved hook's key without the plugin and marketplace, mapped to the hash that the user approved,
	 *   in configuration order; empty if {@code source} is not a file
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code plugin} is blank
	 * @throws IOException if the configuration cannot be read
	 */
	public static Map<String, String> pluginHookApprovals(Path source, String plugin) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(plugin, "plugin").isNotBlank();
		Map<String, String> approvals = new LinkedHashMap<>();
		if (!Files.isRegularFile(source))
			return approvals;
		Pattern key = Pattern.compile(Pattern.quote(plugin) + "@[^:\"]+:([^\"]+)");
		for (Entry<String, JsonNode> approval : hookState(source).properties())
		{
			Matcher match = key.matcher(approval.getKey());
			String hash = approval.getValue().path("trusted_hash").asString("");
			if (match.matches() && TRUSTED_HASH.matcher(hash).matches())
				approvals.put(match.group(1), hash);
		}
		return approvals;
	}

	/**
	 * Returns the hook state in an existing Codex configuration.
	 *
	 * @param source the configuration file
	 * @return the hook state, or a missing node
	 * @throws IOException if the configuration cannot be read or parsed
	 */
	private static JsonNode hookState(Path source) throws IOException
	{
		return parse(source, BoundedInput.readUtf8(source, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration"),
			"hook state").
			path("hooks").path("state");
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private CodexConnectionConfiguration()
	{
	}
}

package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.SessionCommandLimits;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.RealPaths;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
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
import tools.jackson.databind.json.JsonMapper;
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
	 * The host that Codex sends model requests to under a ChatGPT login when no base URL is configured.
	 */
	private static final String CHATGPT_MODEL_HOST = "chatgpt.com";
	/**
	 * The host that Codex sends model requests to under an API key when no base URL is configured.
	 */
	private static final String API_MODEL_HOST = "api.openai.com";
	/**
	 * The Codex configuration layer that a system administrator installs on Linux and macOS. The session's own
	 * configuration overrides its values, but Codex merges its tables, such as a permission profile's, into the
	 * session's.
	 */
	public static final Path SYSTEM_LAYER = Path.of("/etc/codex/config.toml");
	/**
	 * The Codex configuration layer that an administrator manages on Linux and macOS. Its values override the
	 * session's own configuration.
	 */
	public static final Path MANAGED_LAYER = Path.of("/etc/codex/managed_config.toml");
	/**
	 * The permission profiles that CAT gives a session's thread.
	 */
	private static final List<String> SESSION_PERMISSION_PROFILES = List.of("cat-runner", "cat-validator",
		"cat-denied-reads");
	private static final Set<String> CONNECTION_SETTINGS = Set.of("forced_login_method", "model",
		"model_reasoning_effort", "openai_base_url");
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
	private static final Pattern TRUSTED_HASH = Pattern.compile(
		"(?m)^trusted_hash\\s*=\\s*\\\"(sha256:[0-9a-f]{64})\\\"\\s*$");

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
		return instructionSettings(parse(source, configuration));
	}

	/**
	 * Parses an already bounded host configuration.
	 *
	 * @param source        the source file, named when parsing fails
	 * @param configuration the host configuration
	 * @return the configuration's values
	 * @throws IOException if the configuration cannot be parsed
	 */
	private static JsonNode parse(Path source, String configuration) throws IOException
	{
		assert source != null;
		assert configuration != null;
		try
		{
			return TomlMapper.builder().build().readTree(configuration);
		}
		catch (JacksonException e)
		{
			throw new IOException("Cannot parse Codex instruction settings from " + source, e);
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
	 * Returns the host that a session's Codex sends its model requests to: the host of the {@code openai_base_url}
	 * that the session copies from the host configuration, otherwise the host that Codex reaches for the session's
	 * login. That is the API host when the configuration forces an API login, the environment holds an API key or the
	 * login file holds one, and the ChatGPT host otherwise.
	 *
	 * @param source      the host configuration file
	 * @param environment the runner's environment
	 * @param login       the session's login file, which may not exist
	 * @return the host, in lower case
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the configuration or the login file cannot be read or parsed, or the base URL
	 *                              names no host
	 */
	public static String modelHost(Path source, Map<String, String> environment, Path login) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(login, "login").isNotNull();
		if (Files.isRegularFile(source))
		{
			JsonNode configuration = parse(source, BoundedInput.readUtf8(source, MAXIMUM_CONFIGURATION_BYTES,
				"Codex configuration"));
			JsonNode baseUrl = configuration.path("openai_base_url");
			if (baseUrl.isString())
				return urlHost(source, baseUrl.asString());
			if (configuration.path("forced_login_method").asString("").equals("api"))
				return API_MODEL_HOST;
		}
		if (!CodexRunner.requiresCredentialFiles(environment))
			return API_MODEL_HOST;
		if (Files.isRegularFile(login))
		{
			String text = BoundedInput.readUtf8(login, MAXIMUM_CONFIGURATION_BYTES, "Codex login");
			JsonNode apiKey;
			try
			{
				apiKey = JsonMapper.builder().build().readTree(text).path("OPENAI_API_KEY");
			}
			catch (JacksonException e)
			{
				throw new IOException("Cannot parse the Codex login " + login, e);
			}
			if (apiKey.isString() && !apiKey.asString().isBlank())
				return API_MODEL_HOST;
		}
		return CHATGPT_MODEL_HOST;
	}

	/**
	 * Returns the host of a base URL.
	 *
	 * @param source  the configuration that names the URL
	 * @param baseUrl the URL
	 * @return the host, in lower case
	 * @throws IOException if the URL is malformed or names no host
	 */
	private static String urlHost(Path source, String baseUrl) throws IOException
	{
		String host;
		try
		{
			host = URI.create(baseUrl.strip()).getHost();
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(source + " sets openai_base_url to a malformed URL: " + baseUrl, e);
		}
		if (host == null || host.isBlank())
			throw new IOException(source + " sets openai_base_url to a URL without a host: " + baseUrl);
		return host.toLowerCase(Locale.ROOT);
	}

	/**
	 * Refuses a session whose system or managed Codex configuration layer would give it more than its policy allows.
	 * Codex merges each layer into the session's own configuration: the system layer under it and the managed layer
	 * over it. A layer widens the session if it lets one of the session's permission profiles write outside the
	 * policy's writes or inside its denials, read inside its read denials, reach a host outside its hosts, turn on its
	 * network without hosts or bind local addresses without the policy's permission, or names a profile entry whose
	 * effect CAT does not know. The managed layer also widens it if it replaces a profile's {@code extends}, names
	 * another {@code default_permissions} or a configuration {@code profile}, or sets {@code sandbox_mode} to
	 * {@code danger-full-access}.
	 *
	 * @param systemLayer  the system layer, which may not exist
	 * @param managedLayer the managed layer, which may not exist
	 * @param policy       the session's policy
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if a layer cannot be parsed or would widen the session
	 * @throws IOException             if a layer cannot be read
	 */
	public static void requireNarrowingLayers(Path systemLayer, Path managedLayer, ChildPolicy policy)
		throws IOException
	{
		requireThat(systemLayer, "systemLayer").isNotNull();
		requireThat(managedLayer, "managedLayer").isNotNull();
		requireThat(policy, "policy").isNotNull();
		requireNarrowingLayer(systemLayer, false, policy);
		requireNarrowingLayer(managedLayer, true, policy);
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
	 * except that a session that may request sessions can also write the run's requests directory. Its commands receive
	 * the variables that name that directory and the {@code request-session} launcher.
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
	 * @throws NullPointerException if any of the arguments other than {@code requestAccess} are null
	 * @throws IOException          if configuration I/O fails
	 */
	public static void copy(Path source, Path destination, Path project, HarnessDependencyCaches caches,
		Path temporaryDirectory, int toolOutputTokenLimit, SessionRequestAccess requestAccess, Platform platform,
		CodexParentMultiAgentState parentState, ChildPolicy policy) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(caches, "caches").isNotNull();
		requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(parentState, "parentState").isNotNull();
		requireThat(toolOutputTokenLimit, "toolOutputTokenLimit").isNotNegative();
		requireThat(policy, "policy").isNotNull();
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
			parentConfiguration = parse(source, configuration);
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
		List<Path> keptCaches = new ArrayList<>();
		for (Path cache : caches.directories())
		{
			if (policy.writeRoots().contains(RealPaths.resolve(cache.toAbsolutePath())))
				keptCaches.add(cache);
		}
		if (!keptCaches.isEmpty())
		{
			StringJoiner roots = new StringJoiner(", ", "[", "]");
			for (Path cache : keptCaches)
				roots.add('"' + toml(cache) + '"');
			copied.append(System.lineSeparator()).append("[sandbox_workspace_write]").append(System.lineSeparator()).
				append("writable_roots = ").append(roots).append(System.lineSeparator());
		}
		// The commands that a session runs get the run's home as their home and no harness credential, so that a
		// harness started from a command finds neither the user's login nor the user's configuration
		StringJoiner credentials = new StringJoiner(", ", "[", "]");
		for (String credential : new TreeSet<>(SessionCommandLimits.CREDENTIAL_VARIABLES))
			credentials.add('"' + credential + '"');
		copied.append(System.lineSeparator()).append("[shell_environment_policy]").append(System.lineSeparator()).
			append("exclude = ").append(credentials).append(System.lineSeparator()).
			append(System.lineSeparator()).append("[shell_environment_policy.set]").append(System.lineSeparator());
		Map<String, String> commandEnvironment = new TreeMap<>(caches.environment());
		commandEnvironment.putAll(SessionCommandLimits.homeEnvironment(destination.getParent(), platform));
		List<Path> writable = new ArrayList<>(keptCaches);
		if (requestAccess != null)
		{
			commandEnvironment.putAll(requestAccess.environment());
			writable.add(requestAccess.requests());
		}
		for (Entry<String, String> variable : commandEnvironment.entrySet())
		{
			copied.append(variable.getKey()).append(" = \"").append(toml(variable.getValue())).append('"').
				append(System.lineSeparator());
		}
		copied.append("TMPDIR = \"").append(toml(temporaryDirectory)).append('"').append(System.lineSeparator());
		TomlMapper mapper = TomlMapper.builder().build();
		ObjectNode features = mapper.createObjectNode();
		if (platform.isWindows())
		{
			// Without an administrator's setup, Windows offers only Codex's unelevated sandbox, which refuses a profile
			// that denies reads, and MXC, which enforces it
			features.put("prefer_mxc", true);
		}
		ObjectNode agents = mapper.createObjectNode();
		if (parentConfiguration != null)
			copyMultiAgentSettings(parentConfiguration, features, agents, destination.getParent(), project);
		parentState.applyTo(features, agents);
		// Without its network proxy, Codex ignores the profile's host list and lets the commands reach every host
		if (!policy.hosts().isEmpty())
			features.put(NETWORK_PROXY_FEATURE, true);
		appendTable(copied, mapper, "features", features);
		appendTable(copied, mapper, "agents", agents);
		appendRunnerProfile(copied, destination.getParent(), writable, temporaryDirectory, project, platform, policy);
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
	 * whole sandbox policy: the policy's write denials stay readable only, its read denials that exist are denied,
	 * and its network rules apply, as {@link #appendNetwork(StringBuilder, Platform, ChildPolicy)} renders them.
	 *
	 * @param configuration      the isolated configuration being assembled
	 * @param isolatedHome       the fresh runtime home that the runner owns
	 * @param writable           the other directories that the session may write: the verified shared dependency caches
	 *                           that the policy keeps, and the run's requests directory when the session may request
	 *                           sessions
	 * @param temporaryDirectory the fresh harness temporary directory owned by the runner
	 * @param project            the copied project that the isolated session runs in
	 * @param platform           the platform that the session runs on
	 * @param policy             the session's policy
	 * @throws SessionRefusedException if the session must bind local addresses on macOS
	 * @throws IOException             if the real path of a policy denial cannot be read
	 */
	private static void appendRunnerProfile(StringBuilder configuration, Path isolatedHome, List<Path> writable,
		Path temporaryDirectory, Path project, Platform platform, ChildPolicy policy) throws IOException
	{
		assert configuration != null;
		assert isolatedHome != null;
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
		addProjectGitPermissions(filesystem, project, platform);
		for (Path denial : policy.writeDenials())
			filesystem.put(toml(denial), "read");
		for (Path denial : policy.readDenials())
		{
			// Codex's Linux sandbox creates an empty placeholder at a missing denied path while the session runs, which
			// would block the parent from creating that path
			if (Files.exists(denial))
				filesystem.put(toml(denial.toRealPath()), "deny");
		}
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
			domains.add('"' + toml(host) + "\" = \"allow\"");
		configuration.append(domains).append(System.lineSeparator());
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
		for (String protectedPath : List.of("config", "hooks"))
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
		return toml(path.toAbsolutePath().normalize().toString());
	}

	/**
	 * Escapes one value for a TOML basic string.
	 *
	 * @param value the value to render
	 * @return the TOML-safe value
	 */
	private static String toml(String value)
	{
		assert value != null;
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/**
	 * Adds the permission profile that a session's thread runs under.
	 *
	 * @param destination the isolated Codex configuration
	 * @param readOnlyEvidence the completed evidence directories and files that the session may read but not change
	 * @param deniedReads the existing files and directories that the session must not read
	 * @return the profile that the thread names, or an empty string if the thread uses the workspace-write sandbox
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the isolated configuration cannot be updated
	 */
	public static String addSessionProfile(Path destination, List<Path> readOnlyEvidence, List<Path> deniedReads)
		throws IOException
	{
		requireThat(destination, "destination").isNotNull();
		requireThat(readOnlyEvidence, "readOnlyEvidence").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		String profile = "";
		if (!readOnlyEvidence.isEmpty())
		{
			addValidatorProfile(destination, readOnlyEvidence);
			profile = VALIDATOR_PERMISSION_PROFILE;
		}
		if (deniedReads.isEmpty())
			return profile;
		if (profile.isEmpty())
			profile = RUNNER_PERMISSION_PROFILE;
		addDeniedReadsProfile(destination, profile, deniedReads);
		return DENIED_READS_PERMISSION_PROFILE;
	}

	/**
	 * Adds the profile that denies a session reading files and directories.
	 *
	 * @param destination the isolated Codex configuration
	 * @param parentProfile the profile whose permissions the session otherwise has
	 * @param deniedReads the files and directories to deny; a path that no longer exists is skipped
	 * @throws IOException if the isolated configuration cannot be updated
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
		for (Path deniedRead : deniedReads)
		{
			// Codex's Linux sandbox creates an empty placeholder at a missing denied path while the session runs, which
			// would block CAT from creating that path. Denying the real path also covers every symbolic-link alias.
			if (Files.notExists(deniedRead))
				continue;
			profile.append('"').append(toml(deniedRead.toRealPath())).append("\" = \"deny\"").
				append(System.lineSeparator());
		}
		Files.writeString(destination, profile.toString());
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
		requireThat(evidenceRoots, "evidenceRoots").isNotEmpty();
		String configuration = BoundedInput.readUtf8(destination, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		if (!configuration.contains("[permissions." + RUNNER_PERMISSION_PROFILE + "]"))
			throw new IOException("Read-only validator evidence requires the CAT runner permission profile");
		// A validator's verdict is credible only if the harness can read executor evidence but cannot rewrite it.
		Files.writeString(destination, configuration + System.lineSeparator() + "[permissions." +
			VALIDATOR_PERMISSION_PROFILE + "]" + System.lineSeparator() + "extends = \"" + RUNNER_PERMISSION_PROFILE +
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
	 * The copied value is only an expected hook hash. Codex compares it against the installed artifact's current hook
	 * hash before execution, so a changed hook remains untrusted.
	 *
	 * @param source the host Codex configuration file
	 * @param destination the isolated Codex configuration file
	 * @throws IOException if configuration I/O fails
	 */
	public static void copyTrustedCatHooks(Path source, Path destination) throws IOException
	{
		if (!Files.isRegularFile(source))
			return;
		String configuration = BoundedInput.readUtf8(source, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		StringBuilder approvals = new StringBuilder(256);
		for (String key : CAT_HOOK_KEYS)
		{
			Pattern statePattern = Pattern.compile("(?ms)^\\[hooks\\.state\\.\\\"" + Pattern.quote(key) +
				"\\\"]\\R(.*?)(?=^\\[|\\z)");
			Matcher state = statePattern.matcher(configuration);
			if (!state.find())
				continue;
			Matcher hash = TRUSTED_HASH.matcher(state.group(1));
			if (!hash.find())
				continue;
			approvals.append(System.lineSeparator()).append("[hooks.state.\"").append(key).append("\"]").
				append(System.lineSeparator()).append("trusted_hash = \"").append(hash.group(1)).append('"').
				append(System.lineSeparator());
		}
		if (approvals.isEmpty())
			return;
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, approvals.toString(), java.nio.file.StandardOpenOption.CREATE,
			java.nio.file.StandardOpenOption.APPEND);
	}

	/**
	 * Copies the user's approvals of a plugin's hooks into an isolated configuration, under the run-local marketplace
	 * that bears the plugin's name. Codex keys an approval by the plugin, its marketplace and the hook, and rejects an
	 * approval whose recorded hash differs from the hook's normalized declaration, so an approval of another version of
	 * the hook gives it no trust.
	 *
	 * @param source      the host configuration file
	 * @param destination the isolated configuration file
	 * @param plugin      the plugin's manifest name
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code plugin} is blank
	 * @throws IOException if configuration I/O fails
	 */
	public static void copyTrustedHooks(Path source, Path destination, String plugin) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(destination, "destination").isNotNull();
		requireThat(plugin, "plugin").isNotBlank();
		// The user's approval names the marketplace that the user installed the plugin from; the run-local marketplace
		// bears the plugin's name instead, so each approval is rewritten under it
		StringBuilder approvals = new StringBuilder(256);
		for (Entry<String, String> approval : pluginHookApprovals(source, plugin).entrySet())
		{
			approvals.append(System.lineSeparator()).append("[hooks.state.\"").append(plugin).append('@').
				append(plugin).append(':').append(approval.getKey()).append("\"]").append(System.lineSeparator()).
				append("trusted_hash = \"").append(approval.getValue()).append('"').append(System.lineSeparator());
		}
		if (approvals.isEmpty())
			return;
		Files.createDirectories(destination.getParent());
		Files.writeString(destination, approvals.toString(), java.nio.file.StandardOpenOption.CREATE,
			java.nio.file.StandardOpenOption.APPEND);
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
		String configuration = BoundedInput.readUtf8(source, MAXIMUM_CONFIGURATION_BYTES, "Codex configuration");
		Matcher state = Pattern.compile("(?ms)^\\[hooks\\.state\\.\"" + Pattern.quote(plugin) +
			"@[^:\"]+:([^\"]+)\"]\\R(.*?)(?=^\\[|\\z)").matcher(configuration);
		while (state.find())
		{
			Matcher hash = TRUSTED_HASH.matcher(state.group(2));
			if (hash.find())
				approvals.put(state.group(1), hash.group(1));
		}
		return approvals;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private CodexConnectionConfiguration()
	{
	}
}

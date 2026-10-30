package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;

import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Owns values and services shared for one harness hook invocation, including the CAT session directory of the session
 * that the hook input names.
 */
public abstract class AbstractHookScope extends AbstractJvmScope implements HookScope
{
	private static final int MAXIMUM_EVENT_BYTES = 1_048_576;
	/**
	 * The variables in which a jlink launcher saves the caller's locale, whenever that locale is not UTF-8.
	 */
	private static final Set<String> SAVED_LOCALE = Set.of("CAT_CALLER_LC_ALL", "CAT_CALLER_LC_CTYPE");
	/**
	 * The variables that a CAT runner sets in a session that it lets start sessions of its own.
	 */
	private static final Set<String> SESSION_REQUESTS = Set.of(SessionRequestAccess.REQUESTS_VARIABLE,
		SessionRequestAccess.LAUNCHER_VARIABLE);
	private final Path pluginRoot;
	private final Path userRulesRoot;
	private final SessionHarness harness;
	private final Path configurationDirectory;

	/**
	 * Creates an open hook scope from explicit directories.
	 *
	 * @param parent the owned process scope
	 * @param pluginRoot a non-null CAT plugin root
	 * @param userHome the current user's non-null home directory
	 * @param harness the harness that runs the hook, whose sessions own the CAT session directories that the hook
	 *   obtains
	 * @param configurationDirectory the harness's configuration directory, which a new session marker records
	 * @throws NullPointerException if any argument is null
	 */
	protected AbstractHookScope(JvmScope parent, Path pluginRoot, Path userHome, SessionHarness harness,
		Path configurationDirectory)
	{
		super(parent);
		requireThat(pluginRoot, "pluginRoot").isNotNull();
		requireThat(userHome, "userHome").isNotNull();
		requireThat(harness, "harness").isNotNull();
		requireThat(configurationDirectory, "configurationDirectory").isNotNull();
		this.pluginRoot = pluginRoot.toAbsolutePath().normalize();
		this.userRulesRoot = ProjectRuleLoader.rulesDirectory(userHome.toAbsolutePath().normalize());
		this.harness = harness;
		this.configurationDirectory = configurationDirectory.toAbsolutePath().normalize();
	}

	/**
	 * Creates an open hook scope from the environment of a harness hook invocation.
	 * <p>
	 * Every error names the harness and the variable that it concerns.
	 *
	 * @param environment the hook environment
	 * @param harness the harness's display name
	 * @param pluginRootVariable the variable through which the harness supplies CAT's installation directory
	 * @param homeVariable the variable that names the current user's home directory
	 * @param sessionHarness the harness's session values; its configuration variable, when set, names the harness's
	 *   configuration directory
	 * @param waiting how the hook waits between the attempts of a retried operation
	 * @return the scope
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code harness}, {@code pluginRootVariable} or {@code homeVariable} are
	 *   blank
	 * @throws IOException if either variable is unset or blank, or if the environment sets a {@code CAT_} variable
	 *   other than a {@code CAT_INSTALL_DIR} that names the plugin root, a variable in which the hook's launcher
	 *   saved the caller's locale or a variable through which a CAT runner lets the session start sessions
	 */
	public static HookScope fromEnvironment(Map<String, String> environment, String harness, String pluginRootVariable,
		String homeVariable, SessionHarness sessionHarness, Waiting waiting) throws IOException
	{
		requireThat(sessionHarness, "sessionHarness").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		requireThat(pluginRootVariable, "pluginRootVariable").isNotBlank();
		requireThat(homeVariable, "homeVariable").isNotBlank();
		String pluginRoot = environment.get(pluginRootVariable);
		if (pluginRoot == null || pluginRoot.isBlank())
			throw new IOException(harness + " " + pluginRootVariable + " is not set");
		Path resolvedPluginRoot = Path.of(pluginRoot.strip()).toAbsolutePath().normalize();
		String home = environment.get(homeVariable);
		if (home == null || home.isBlank())
			throw new IOException(harness + " " + homeVariable + " is not set");
		Path userHome = Path.of(home.strip());
		for (Map.Entry<String, String> entry : environment.entrySet())
		{
			String name = entry.getKey();
			if (!name.startsWith("CAT_") || SAVED_LOCALE.contains(name) || SESSION_REQUESTS.contains(name))
				continue;
			if (name.equals("CAT_INSTALL_DIR") &&
				Path.of(entry.getValue().strip()).toAbsolutePath().normalize().equals(resolvedPluginRoot))
			{
				continue;
			}
			throw new IOException(name + " conflicts with " + harness + " " + pluginRootVariable);
		}
		return new MainHookScope(new MainJvmScope(environment, waiting), resolvedPluginRoot, userHome, sessionHarness,
			sessionHarness.configurationDirectory(environment, homeVariable));
	}

	@Override
	public Path pluginRoot()
		throws IOException
	{
		ensureOpen();
		if (!Files.isDirectory(pluginRoot))
			throw new IOException("CAT installation directory is not a directory");
		return pluginRoot;
	}

	@Override
	public Path userRulesRoot()
	{
		ensureOpen();
		return userRulesRoot;
	}

	@Override
	public JsonNode readEvent(String eventName, InputStream input) throws IOException
	{
		requireThat(eventName, "eventName").isNotBlank();
		requireThat(input, "input").isNotNull();
		ensureOpen();
		byte[] bytes = input.readNBytes(MAXIMUM_EVENT_BYTES + 1);
		if (bytes.length > MAXIMUM_EVENT_BYTES)
			throw new IOException(eventName + " input is too large");
		JsonNode event = json().createObjectNode();
		if (bytes.length != 0)
			event = json().readTree(bytes);
		if (event == null || !event.isObject())
			throw new IOException(eventName + " input must be a JSON object");
		return event;
	}

	@Override
	public String subagentType(JsonNode event)
	{
		requireThat(event, "event").isNotNull();
		ensureOpen();
		JsonNode agentType = event.path("agent_type");
		if (!agentType.isString())
			return "";
		return agentType.stringValue();
	}

	@Override
	public Path projectDir(JsonNode event, Path workingDirectory) throws IOException
	{
		ensureOpen();
		String candidate = text(event, "cwd");
		Path result = workingDirectory;
		if (!candidate.isEmpty())
			result = Path.of(candidate);
		result = result.toAbsolutePath().normalize();
		if (!Files.isDirectory(result))
			throw new IOException("Project directory is not a directory");
		return result;
	}

	@Override
	public SessionDirectory sessionDirectory(Path projectDir, JsonNode event, String property)
		throws IOException
	{
		requireThat(projectDir, "projectDir").isNotNull();
		requireThat(event, "event").isNotNull();
		requireThat(property, "property").isNotBlank();
		ensureOpen();
		JsonNode sessionId = event.path(property);
		if (!sessionId.isString())
			throw new IOException("The hook input lacks " + property);
		return SessionDirectory.open(projectDir, sessionId.stringValue(), harness, configurationDirectory,
			clock().instant(), json());
	}

	/**
	 * Returns a stripped textual JSON property, or an empty string.
	 *
	 * @param object a non-null JSON object
	 * @param name a non-blank property name
	 * @return the property value
	 */
	private static String text(JsonNode object, String name)
	{
		assert that(name, "name").isNotBlank().elseThrow();
		JsonNode value = object.get(name);
		if (value == null || !value.isString())
			return "";
		return value.asString().strip();
	}
}

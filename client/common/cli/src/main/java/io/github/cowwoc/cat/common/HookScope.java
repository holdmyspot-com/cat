package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;

import io.github.cowwoc.pouch10.core.LazyReference;
import io.github.cowwoc.pouch10.core.Reference;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Owns values and services shared for one harness hook invocation.
 */
public final class HookScope implements AutoCloseable
{
	private static final int MAXIMUM_EVENT_BYTES = 1_048_576;
	/**
	 * The variables in which a jlink launcher saves the caller's locale, whenever that locale is not UTF-8.
	 */
	private static final Set<String> SAVED_LOCALE = Set.of("CAT_CALLER_LC_ALL", "CAT_CALLER_LC_CTYPE");
	private boolean closed;
	private final Path pluginRoot;
	private final Path userRulesRoot;
	private final Reference<JsonMapper> json = LazyReference.create(() -> JsonMapper.builder().
		build());
	private final Reference<YAMLMapper> yaml = LazyReference.create(() -> YAMLMapper.builder().
		build());

	/**
	 * Creates an open hook scope from explicit directories.
	 *
	 * @param pluginRoot a non-null CAT plugin root
	 * @param userHome the current user's non-null home directory
	 * @throws NullPointerException if any argument is null
	 */
	public HookScope(Path pluginRoot, Path userHome)
	{
		requireThat(pluginRoot, "pluginRoot").isNotNull();
		requireThat(userHome, "userHome").isNotNull();
		this.pluginRoot = pluginRoot.toAbsolutePath().normalize();
		this.userRulesRoot = ProjectRuleLoader.rulesDirectory(userHome.toAbsolutePath().normalize());
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
	 * @return the scope
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code harness}, {@code pluginRootVariable} or {@code homeVariable} are
	 *   blank
	 * @throws IOException if either variable is unset or blank, or if the environment sets a {@code CAT_} variable
	 *   other than a {@code CAT_INSTALL_DIR} that names the plugin root or a variable in which the hook's launcher
	 *   saved the caller's locale
	 */
	public static HookScope fromEnvironment(Map<String, String> environment, String harness, String pluginRootVariable,
		String homeVariable) throws IOException
	{
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
			if (!name.startsWith("CAT_") || SAVED_LOCALE.contains(name))
				continue;
			if (name.equals("CAT_INSTALL_DIR") &&
				Path.of(entry.getValue().strip()).toAbsolutePath().normalize().equals(resolvedPluginRoot))
			{
				continue;
			}
			throw new IOException(name + " conflicts with " + harness + " " + pluginRootVariable);
		}
		return new HookScope(resolvedPluginRoot, userHome);
	}

	/**
	 * Returns the JSON mapper shared by this hook invocation.
	 *
	 * @return the shared JSON mapper
	 * @throws IllegalStateException if this scope is closed
	 */
	public JsonMapper json()
	{
		ensureOpen();
		return json.getValue();
	}

	/**
	 * Returns the YAML mapper shared by this hook invocation.
	 *
	 * @return the shared YAML mapper
	 * @throws IllegalStateException if this scope is closed
	 */
	public YAMLMapper yaml()
	{
		ensureOpen();
		return yaml.getValue();
	}

	/**
	 * Returns CAT's installation directory for this hook invocation.
	 *
	 * @return the normalized CAT installation directory
	 * @throws IllegalStateException if this scope is closed
	 */
	public Path pluginRoot()
		throws IOException
	{
		ensureOpen();
		if (!Files.isDirectory(pluginRoot))
			throw new IOException("CAT installation directory is not a directory");
		return pluginRoot;
	}

	/**
	 * Returns the current user's CAT rule directory.
	 *
	 * @return the normalized user-rule directory, which may not exist yet
	 * @throws IllegalStateException if this scope is closed
	 */
	public Path userRulesRoot()
	{
		ensureOpen();
		return userRulesRoot;
	}

	/**
	 * Reads the event that the harness passed to the hook.
	 *
	 * @param eventName the hook event's name, which prefixes each error
	 * @param input the harness hook input, which this method does not close
	 * @return the event, or an empty object if the input is empty
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code eventName} is blank
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the input cannot be read, is larger than 1 MiB or is not a JSON object
	 * @throws JacksonException if the input is not valid JSON
	 */
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

	/**
	 * Returns the type of the subagent that a hook event concerns, without making malformed optional metadata a hook
	 * failure.
	 *
	 * @param event the harness hook event
	 * @return the subagent type, or blank when it is absent or not textual
	 * @throws NullPointerException if {@code event} is null
	 * @throws IllegalStateException if this scope is closed
	 */
	public String subagentType(JsonNode event)
	{
		requireThat(event, "event").isNotNull();
		ensureOpen();
		JsonNode agentType = event.path("agent_type");
		if (!agentType.isString())
			return "";
		return agentType.stringValue();
	}

	/**
	 * Resolves the harness hook's current working directory.
	 *
	 * @param event the harness SessionStart event
	 * @param workingDirectory the hook process's working directory
	 * @return the normalized current working directory
	 * @throws NullPointerException if {@code event} or {@code workingDirectory} are null
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the resolved project directory is not a directory
	 */
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

	/**
	 * Creates the project's CAT temporary directory if it is missing. The injected rules name it
	 * {@code ${CAT_TEMP}}, {@code .cat/work/temp} below {@code ${CAT_PROJECT_ROOT}}, and the agent creates its temporary
	 * directories inside it.
	 *
	 * @param projectDir the session's project directory
	 * @return the CAT temporary directory
	 * @throws NullPointerException if {@code projectDir} is null
	 * @throws IOException if the directory cannot be created
	 * @throws IllegalStateException if this scope is closed
	 */
	public Path createCatTemp(Path projectDir) throws IOException
	{
		ensureOpen();
		requireThat(projectDir, "projectDir").isNotNull();
		return Files.createDirectories(projectDir.resolve(".cat/work/temp"));
	}

	/**
	 * Prevents further service access.
	 */
	@Override
	public void close()
	{
		if (closed)
			return;
		closed = true;
	}

	/**
	 * Ensures this scope remains open.
	 *
	 * @throws IllegalStateException if this scope is closed
	 */
	private void ensureOpen()
	{
		if (closed)
			throw new IllegalStateException("Scope is closed");
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

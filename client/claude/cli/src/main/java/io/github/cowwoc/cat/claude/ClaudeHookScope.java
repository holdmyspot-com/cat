package io.github.cowwoc.cat.claude;

import io.github.cowwoc.cat.common.HookScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Owns one Claude hook invocation and translates its native environment at the harness boundary.
 */
final class ClaudeHookScope implements AutoCloseable
{
	private final HookScope delegate;
	private final Path userRulesRoot;

	/**
	 * Creates a scope from Claude's native hook environment.
	 *
	 * @param environment the non-null native hook environment
	 * @throws IOException if Claude did not supply a usable plugin root
	 */
	ClaudeHookScope(Map<String, String> environment) throws IOException
	{
		String pluginRoot = environment.get("CLAUDE_PLUGIN_ROOT");
		if (pluginRoot == null || pluginRoot.isBlank())
			throw new IOException("Claude CLAUDE_PLUGIN_ROOT is not set");

		Path resolvedPluginRoot = Path.of(pluginRoot.strip()).toAbsolutePath().normalize();
		String home = environment.get("HOME");
		if (home == null || home.isBlank())
			throw new IOException("Claude HOME is not set");
		Path userHome = Path.of(home.strip()).toAbsolutePath().normalize();
		validateCatVariables(environment, resolvedPluginRoot);
		delegate = new HookScope(resolvedPluginRoot);
		userRulesRoot = userHome.resolve(".cat/rules");
	}

	/**
	 * Rejects CAT variables that disagree with or are not derived from Claude's native plugin root.
	 *
	 * @param environment the native hook environment
	 * @param pluginRoot the resolved native plugin root
	 * @throws IOException if a CAT variable conflicts with the native root
	 */
	private static void validateCatVariables(Map<String, String> environment, Path pluginRoot) throws IOException
	{
		for (Map.Entry<String, String> entry: environment.entrySet())
		{
			String name = entry.getKey();
			if (!name.startsWith("CAT_"))
				continue;
			if (name.equals("CAT_INSTALL_DIR") && Path.of(entry.getValue().strip()).toAbsolutePath().normalize().equals(
				pluginRoot))
				continue;
			throw new IOException(name + " conflicts with Claude CLAUDE_PLUGIN_ROOT");
		}
	}

	/**
	 * Returns this invocation's JSON mapper.
	 *
	 * @return the shared JSON mapper
	 */
	JsonMapper json()
	{
		return delegate.json();
	}

	/**
	 * Returns this invocation's YAML mapper.
	 *
	 * @return the shared YAML mapper
	 */
	YAMLMapper yaml()
	{
		return delegate.yaml();
	}

	/**
	 * Returns CAT's resolved plugin root.
	 *
	 * @return the normalized plugin root
	 * @throws IOException if the root is not a directory
	 */
	Path pluginRoot() throws IOException
	{
		return delegate.pluginRoot();
	}

	/**
	 * Returns the current user's CAT rule directory derived from its native home directory.
	 *
	 * @return the normalized user-rule directory, which may not exist yet
	 */
	Path userRulesRoot()
	{
		return userRulesRoot;
	}

	/**
	 * Resolves the project directory for a Claude hook event.
	 *
	 * @param event the native event
	 * @param workingDirectory the hook process directory
	 * @return the normalized project directory
	 * @throws IOException if the resolved directory is not a directory
	 */
	Path projectDir(JsonNode event, Path workingDirectory) throws IOException
	{
		return delegate.projectDir(event, workingDirectory);
	}

	@Override
	public void close()
	{
		delegate.close();
	}
}

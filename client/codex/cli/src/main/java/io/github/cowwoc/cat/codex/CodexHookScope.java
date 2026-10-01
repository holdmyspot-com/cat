package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.common.HookScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Owns one Codex hook invocation and translates its harness environment at the harness boundary.
 */
final class CodexHookScope implements AutoCloseable
{
	private final HookScope delegate;
	private final Path userRulesRoot;

	/**
	 * Creates a scope from Codex's harness hook environment.
	 *
	 * @param environment the non-null harness hook environment
	 * @throws IOException if Codex did not supply a usable plugin root
	 */
	CodexHookScope(Map<String, String> environment) throws IOException
	{
		String pluginRoot = environment.get("PLUGIN_ROOT");
		if (pluginRoot == null || pluginRoot.isBlank())
			throw new IOException("Codex PLUGIN_ROOT is not set");

		Path resolvedPluginRoot = Path.of(pluginRoot.strip()).toAbsolutePath().normalize();
		String home = environment.get("HOME");
		if (home == null || home.isBlank())
			throw new IOException("Codex HOME is not set");
		Path userHome = Path.of(home.strip()).toAbsolutePath().normalize();
		validateCatVariables(environment, resolvedPluginRoot);
		delegate = new HookScope(resolvedPluginRoot);
		userRulesRoot = userHome.resolve(".cat/rules");
	}

	/**
	 * Rejects CAT variables that disagree with or are not derived from Codex's harness plugin root.
	 *
	 * @param environment the harness hook environment
	 * @param pluginRoot the resolved harness plugin root
	 * @throws IOException if a CAT variable conflicts with the harness root
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
			throw new IOException(name + " conflicts with Codex PLUGIN_ROOT");
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
	 * Returns the current user's CAT rule directory derived from its harness home directory.
	 *
	 * @return the normalized user-rule directory, which may not exist yet
	 */
	Path userRulesRoot()
	{
		return userRulesRoot;
	}

	/**
	 * Resolves the project directory for a Codex hook event.
	 *
	 * @param event the harness event
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

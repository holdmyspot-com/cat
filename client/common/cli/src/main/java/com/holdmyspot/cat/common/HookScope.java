package com.holdmyspot.cat.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Supplies services for one harness hook invocation.
 */
public interface HookScope extends JvmScope
{
	/**
	 * Creates a hook scope from the harness environment.
	 *
	 * @param environment the hook environment
	 * @param harness the harness display name
	 * @param pluginRootVariable the installation variable
	 * @param homeVariable the user home variable
	 * @param sessionHarness the harness session contract
	 * @return the open scope
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if any variable name or harness name is blank
	 * @throws IOException if a required variable is absent or a CAT variable conflicts
	 */
	static HookScope fromEnvironment(Map<String, String> environment, String harness, String pluginRootVariable,
		String homeVariable, SessionHarness sessionHarness) throws IOException
	{
		return AbstractHookScope.fromEnvironment(environment, harness, pluginRootVariable, homeVariable, sessionHarness);
	}

	/**
	 * Returns CAT's installation directory for this hook invocation.
	 *
	 * @return the normalized CAT installation directory
	 * @throws IllegalStateException if this scope is closed
	 */
	Path pluginRoot()
		throws IOException;

	/**
	 * Returns the current user's CAT rule directory.
	 *
	 * @return the normalized user-rule directory, which may not exist yet
	 * @throws IllegalStateException if this scope is closed
	 */
	Path userRulesRoot();

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
	JsonNode readEvent(String eventName, InputStream input) throws IOException;

	/**
	 * Returns the type of the subagent that a hook event concerns, without making malformed optional metadata a hook
	 * failure.
	 *
	 * @param event the harness hook event
	 * @return the subagent type, or blank when it is absent or not textual
	 * @throws NullPointerException if {@code event} is null
	 * @throws IllegalStateException if this scope is closed
	 */
	String subagentType(JsonNode event);

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
	Path projectDir(JsonNode event, Path workingDirectory) throws IOException;

	/**
	 * Returns the CAT session directory of the session that a hook input names, creating it and its marker on first use.
	 * The injected rules name its {@code temp/} directory {@code ${CAT_TEMP}}, and the agent creates its temporary
	 * directories inside it.
	 *
	 * @param projectDir the session's project directory
	 * @param event the hook input
	 * @param property the input's property that holds the session's ID, such as {@code session_id}
	 * @return the CAT session directory
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code property} is blank
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException if the property is not a UUID in canonical form, or if the directory or its marker cannot be
	 *   created
	 */
	SessionDirectory sessionDirectory(Path projectDir, JsonNode event, String property)
		throws IOException;
}

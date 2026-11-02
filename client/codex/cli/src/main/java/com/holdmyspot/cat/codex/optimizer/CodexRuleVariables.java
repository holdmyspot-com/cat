package com.holdmyspot.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexAgentDefinition;
import com.holdmyspot.cat.common.RuleVariableResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The portable rule variables that Codex supplies to the rules it renders, whether for a session-start hook or for the
 * first brief of a nested session.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexRuleVariables
{
	/**
	 * Prevents instantiation.
	 */
	private CodexRuleVariables()
	{
	}

	/**
	 * Returns the variables that Codex supplies for an installation.
	 *
	 * @param pluginRoot the installed plugin directory
	 * @return the variables, by name without the {@code CAT_RULE_} prefix; each resolver runs only when a rule names
	 *   its variable
	 * @throws NullPointerException if {@code pluginRoot} is null
	 */
	public static Map<String, RuleVariableResolver> of(Path pluginRoot)
	{
		requireThat(pluginRoot, "pluginRoot").isNotNull();
		return Map.of("STRONGEST_IMPLEMENTER", () -> strongestImplementer(pluginRoot));
	}

	/**
	 * Reads the strongest implementer's model and effort from the installation's agent definition.
	 *
	 * @param pluginRoot the installed plugin directory
	 * @return the model and effort for portable delegation guidance
	 * @throws IOException if the agent definition is absent or malformed
	 */
	private static String strongestImplementer(Path pluginRoot) throws IOException
	{
		CodexAgentDefinition agent = CodexAgentDefinition.load(pluginRoot, "optimize-xhigh");
		return agent.model() + " at " + agent.effort() + " reasoning";
	}
}

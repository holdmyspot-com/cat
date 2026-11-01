package com.holdmyspot.cat.common;

import java.io.IOException;

/**
 * Supplies a rule variable's value from its owning integration.
 */
@FunctionalInterface
public interface RuleVariableResolver
{
	/**
	 * Resolves the value used in rendered rules and session definitions for lazily read rules.
	 *
	 * @return the non-blank rule variable value
	 * @throws IOException if the integration cannot supply the value
	 */
	String resolve() throws IOException;
}

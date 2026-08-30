package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.util.Locale;

/**
 * Identifies the owner of a retained failed evaluation result.
 */
public enum FailureClass
{
	ROUTING_DELIVERY,
	APPLICATION,
	DETERMINISTIC_TOOL,
	EXTERNAL_PREREQUISITE,
	INFRASTRUCTURE,
	UNRESOLVED;

	/**
	 * Parses one explicit retained failure classification.
	 *
	 * @param value the external classification value
	 * @return the supported classification
	 * @throws IOException if the value is unsupported
	 */
	public static FailureClass parse(String value) throws IOException
	{
		try
		{
			return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Retained failure evidence has an unsupported failure_class: " + value, e);
		}
	}

	/**
	 * Returns the stable external classification value.
	 *
	 * @return the JSON classification value
	 */
	public String externalName()
	{
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	/**
	 * Returns the component that owns the next repair for this classification.
	 *
	 * @return the stable repair-owner value
	 */
	public String repairOwner()
	{
		return externalName();
	}

	/**
	 * Returns the next action that preserves the established repair boundary.
	 *
	 * @return an actionable next step
	 */
	public String nextAction()
	{
		return switch (this)
		{
			case ROUTING_DELIVERY -> "repair the route selector or delivery boundary before another trial";
			case APPLICATION -> "repair the delivered prompt or the tested product behavior established by the evidence";
			case DETERMINISTIC_TOOL -> "repair the deterministic tool that produced this evidence; do not change the " +
				"evaluated prompt to compensate";
			case EXTERNAL_PREREQUISITE -> "restore the required dependency, permission, or authority before another trial";
			case INFRASTRUCTURE -> "repair the runner, validator, or execution environment before another trial";
			case UNRESOLVED -> "create only the diagnostic that answers the remaining bounded question";
		};
	}
}

package io.github.cowwoc.cat.common.optimizer.api;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

/**
 * Tests the tool identity required by a nested native call.
 */
public final class HostToolRequirementTest
{
	/**
	 * Creates a test instance.
	 */
	public HostToolRequirementTest()
	{
	}

	/**
	 * Verifies a named host tool retains both its logical and executable identities.
	 */
	@Test
	public void acceptsNamedHostTool()
	{
		HostToolRequirement requirement = new HostToolRequirement("git", "git");
		requireThat(requirement.toolId(), "toolId").isEqualTo("git");
		requireThat(requirement.executableName(), "executableName").isEqualTo("git");
	}

	/**
	 * Verifies a blank logical identity cannot be requested.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankToolId()
	{
		new HostToolRequirement(" ", "git");
	}

	/**
	 * Verifies a blank executable name cannot be requested.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankExecutableName()
	{
		new HostToolRequirement("git", " ");
	}
}

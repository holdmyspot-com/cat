package io.github.cowwoc.cat.common.optimizer.api;

import java.util.Objects;

/**
 * Names a host tool that must be available to a nested native call.
 *
 * @param toolId the nonblank logical tool identity
 * @param executableName the nonblank executable name
 */
public record HostToolRequirement(String toolId, String executableName)
{
	/**
	 * Validates the identities a runner must expose before a call.
	 *
	 * @param toolId the nonblank logical tool identity
	 * @param executableName the nonblank executable name
	 * @throws NullPointerException if {@code toolId} or {@code executableName} are null
	 * @throws IllegalArgumentException if {@code toolId} or {@code executableName} are blank
	 */
	public HostToolRequirement
	{
		Objects.requireNonNull(toolId, "toolId");
		Objects.requireNonNull(executableName, "executableName");
		if (toolId.isBlank())
			throw new IllegalArgumentException("toolId must not be blank");
		if (executableName.isBlank())
			throw new IllegalArgumentException("executableName must not be blank");
	}
}

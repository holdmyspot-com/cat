package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * A single exact behavioral obligation extracted from a prompt system.
 *
 * @param id stable requirement identity
 * @param text complete behavioral obligation
 * @param source extracted source location
 */
public record Requirement(String id, String text, SourceLocation source)
{
	/**
 * Validates a requirement.
 */
	public Requirement
	{
		ContractValues.nonBlank(id, "requirement id");
		ContractValues.nonBlank(text, "requirement text");
		if (source == null)
			throw new IllegalArgumentException("Requirement source is missing");
	}
}

package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Shared closed-schema value checks.
 */
final class ContractValues
{
	/**
 * Prevents construction.
 */
	private ContractValues()
	{
	}

	/**
	 * Requires nonblank text.
	 *
	 * @param value the text to validate
	 * @param name the field name used in a validation failure
	 */
	static void nonBlank(String value, String name)
	{
		if (value == null || value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
	}

	/**
	 * Indicates whether a value is a lowercase SHA-256 identity.
	 *
	 * @param value the candidate identity
	 * @return {@code true} if the value contains exactly 64 lowercase hexadecimal characters
	 */
	static boolean sha256(String value)
	{
		return value != null && value.matches("[0-9a-f]{64}");
	}
}

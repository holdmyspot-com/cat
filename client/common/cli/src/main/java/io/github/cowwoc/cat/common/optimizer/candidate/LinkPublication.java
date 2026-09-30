package io.github.cowwoc.cat.common.optimizer.candidate;

/**
 * Hash declared for a newly published hard link.
 *
 * @param expectedSha256 lowercase SHA-256 of published bytes
 */
record LinkPublication(String expectedSha256)
{
	/**
	 * Validates the publication hash.
	 */
	LinkPublication
	{
		if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("expectedSha256 must be lowercase SHA-256");
	}
}

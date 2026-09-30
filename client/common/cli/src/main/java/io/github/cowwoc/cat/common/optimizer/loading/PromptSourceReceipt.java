package io.github.cowwoc.cat.common.optimizer.loading;

/**
 * Exact-byte receipt for one source loaded during a conversation.
 *
 * @param conversationId session that loaded the source
 * @param sourceId stable source identity
 * @param origin source kind
 * @param sha256 exact-byte identity
 * @param contentEvidenceId immutable evidence for the exact delivered bytes
 * @param loadOrdinal zero-based loading position
 * @param parentSourceId source that caused this load, or null for the root
 * @param loadMode production loading mechanism
 */
public record PromptSourceReceipt(String conversationId, String sourceId, SourceOrigin origin, String sha256,
	String contentEvidenceId, long loadOrdinal, String parentSourceId, LoadMode loadMode)
{
	/**
 * Validates a loading receipt.
 */
	public PromptSourceReceipt
	{
		if (blank(conversationId) || blank(sourceId) || origin == null || !sha256(sha256) ||
			blank(contentEvidenceId) || loadOrdinal < 0 || loadMode == null)
		{
			throw new IllegalArgumentException("Prompt source receipt is invalid");
		}
		if (loadOrdinal == 0 && parentSourceId != null)
			throw new IllegalArgumentException("First receipt cannot have a parent");
		if (loadOrdinal > 0 && blank(parentSourceId))
			throw new IllegalArgumentException("Nested receipt requires a parent");
	}

	/**
	 * Indicates whether text is absent or blank.
	 *
	 * @param value the text to test
	 * @return {@code true} if the value is absent or blank
 */
	private static boolean blank(String value)
	{
		return value == null || value.isBlank();
	}

	/**
	 * Indicates whether text is a lowercase SHA-256 identity.
	 *
	 * @param value the candidate identity
	 * @return {@code true} if the value contains exactly 64 lowercase hexadecimal characters
 */
	private static boolean sha256(String value)
	{
		return value != null && value.matches("[0-9a-f]{64}");
	}
}

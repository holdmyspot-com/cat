package io.github.cowwoc.cat.common.optimizer.loading;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests production prompt-loading receipt validation.
 */
public final class LoadedPromptContextTest
{
	/**
 * Creates the test fixture.
 */
	public LoadedPromptContextTest()
	{
	}

	/**
 * Rejects a receipt sequence whose load ordinal skips a source.
 */
	@Test
	public void rejectsNonMonotonicReceiptOrdinals()
	{
		PromptSourceReceipt root = receipt("root", "a", "evidence-root", 0, null);
		PromptSourceReceipt child = receipt("child", "b", "evidence-child", 2, "root");
		try
		{
			new LoadedPromptContext("prompt", List.of(root, child));
			throw new AssertionError("Non-monotonic receipts must fail");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains("monotonic");
		}
	}

	/**
	 * Rejects repeated source identities within the same conversation.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*must not repeat a source.*")
	public void rejectsDuplicateReceiptSource()
	{
		PromptSourceReceipt root = receipt("root", "a", "evidence-root", 0, null);
		PromptSourceReceipt repeated = receipt("root", "b", "evidence-repeated", 1, "root");
		new LoadedPromptContext("prompt", List.of(root, repeated));
	}

	/**
	 * Rejects a nested receipt whose listed parent was never delivered.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*parent must precede.*")
	public void rejectsUnlistedReceiptParent()
	{
		PromptSourceReceipt root = receipt("root", "a", "evidence-root", 0, null);
		PromptSourceReceipt child = receipt("child", "b", "evidence-child", 1, "missing");
		new LoadedPromptContext("prompt", List.of(root, child));
	}

	/**
	 * Rejects a receipt that cannot identify its immutable delivered-byte evidence.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Prompt source receipt is invalid.*")
	public void rejectsBlankContentEvidenceId()
	{
		new PromptSourceReceipt("conversation", "root", SourceOrigin.TARGET, "a".repeat(64), "", 0,
			null, LoadMode.DIRECT);
	}

	/**
	 * Rejects a receipt whose immutable delivered-byte evidence is absent.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Prompt source receipt is invalid.*")
	public void rejectsMissingContentEvidenceId()
	{
		new PromptSourceReceipt("conversation", "root", SourceOrigin.TARGET, "a".repeat(64), null, 0,
			null, LoadMode.DIRECT);
	}

	/**
	 * Creates a receipt modeled as a production prompt-loading event.
	 *
	 * @param sourceId the stable source identity
	 * @param hashCharacter the character repeated to form a fixture SHA-256 identity
	 * @param contentEvidenceId the immutable delivered-byte evidence identity
	 * @param ordinal the zero-based loading position
	 * @param parentSourceId the source that caused this load, or {@code null} for the root
	 * @return the prompt-loading receipt
	 */
	private static PromptSourceReceipt receipt(String sourceId, String hashCharacter, String contentEvidenceId,
		long ordinal, String parentSourceId)
	{
		return new PromptSourceReceipt("conversation", sourceId, SourceOrigin.TARGET,
			hashCharacter.repeat(64), contentEvidenceId, ordinal, parentSourceId, LoadMode.DIRECT);
	}
}

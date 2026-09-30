package io.github.cowwoc.cat.common.optimizer.loading;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Prompt text accompanied by the exact receipts for sources that produced it.
 *
 * @param text fully loaded prompt text
 * @param receipts ordered production loading receipts
 */
public record LoadedPromptContext(String text, List<PromptSourceReceipt> receipts)
{
	/**
 * Validates and defensively copies a loaded context.
 */
	public LoadedPromptContext
	{
		if (text == null) throw new NullPointerException("text");
		receipts = List.copyOf(receipts);
		Set<String> sourceIds = new HashSet<>();
		String conversationId = null;
		for (int ordinal = 0; ordinal < receipts.size(); ++ordinal)
		{
			PromptSourceReceipt receipt = receipts.get(ordinal);
			if (receipt.loadOrdinal() != ordinal)
				throw new IllegalArgumentException("Loading receipts must have monotonic ordinals");
			if (!sourceIds.add(receipt.sourceId()))
				throw new IllegalArgumentException("Loading receipts must not repeat a source");
			if (conversationId == null)
				conversationId = receipt.conversationId();
			else if (!conversationId.equals(receipt.conversationId()))
				throw new IllegalArgumentException("Loading receipts must share one conversation");
			if (receipt.parentSourceId() != null && !sourceIds.contains(receipt.parentSourceId()))
				throw new IllegalArgumentException("Loading receipt parent must precede its child");
		}
	}
}

package io.github.cowwoc.cat.common.optimizer.loading;

import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates receipt bindings and exact retained source bytes. */
public final class PromptLoadingEvidenceValidator
{
	/** Prevents construction. */
	private PromptLoadingEvidenceValidator()
	{
	}

	/**
	 * Validates evidence and derives its ordered prompt-source graph.
	 *
	 * @param evidence captured loading evidence
	 * @param reader immutable content reader
	 * @return validated ordered loading graph
	 * @throws IOException if a retained blob is unavailable or mismatched
	 */
	public static ValidatedPromptLoading validate(PromptLoadingEvidence evidence,
		PromptContentEvidenceReader reader) throws IOException
	{
		if (evidence == null || reader == null)
			throw new IllegalArgumentException("Prompt loading evidence or reader is missing");
		List<PromptSourceReceipt> receipts = evidence.context().receipts();
		Map<String, PromptSourceBinding> bindings = bindings(evidence.bindings());
		if (bindings.size() != receipts.size())
			throw new IllegalArgumentException("Loading bindings must match receipts exactly");
		Set<String> sourceIds = new HashSet<>();
		String conversation = null;
		int targets = 0;
		List<PromptSource> sources = new java.util.ArrayList<>();
		List<PromptSourceBinding> canonicalBindings = new java.util.ArrayList<>();
		for (int ordinal = 0; ordinal < receipts.size(); ordinal += 1)
		{
			PromptSourceReceipt receipt = receipts.get(ordinal);
			if (receipt.loadOrdinal() != ordinal || !sourceIds.add(receipt.sourceId()))
				throw new IllegalArgumentException("Loading receipts must have unique contiguous ordinals");
			if (conversation == null)
				conversation = receipt.conversationId();
			else if (!conversation.equals(receipt.conversationId()))
				throw new IllegalArgumentException("Loading receipts must share one conversation");
			if (receipt.parentSourceId() != null && !sourceIds.contains(receipt.parentSourceId()))
				throw new IllegalArgumentException("Loading receipt parent must precede its child");
			PromptSourceBinding binding = bindings.remove(receipt.sourceId());
			if (binding == null)
				throw new IllegalArgumentException("Loading receipt has no path binding");
			canonicalBindings.add(binding);
			if (receipt.origin() == SourceOrigin.TARGET)
			{
				targets += 1;
				if (!binding.relativePath().equals(evidence.targetPath()))
					throw new IllegalArgumentException("Target binding path does not match target path");
			}
			byte[] content = reader.read(receipt.contentEvidenceId());
			if (!sha256(content).equals(receipt.sha256()) || !receipt.contentEvidenceId().equals(receipt.sha256()))
				throw new IOException("Prompt source content does not match receipt SHA-256");
			sources.add(new PromptSource(binding.relativePath(), receipt.sha256()));
		}
		if (targets != 1 || !bindings.isEmpty())
			throw new IllegalArgumentException("Loading evidence must have exactly one target and no unused bindings");
		PromptLoadingRecord unsigned = new PromptLoadingRecord(PromptLoadingRecord.SCHEMA_VERSION, "pending",
			evidence.targetPath(), canonicalBindings, receipts);
		String identity = sha256(LoadingJson.mapper().writeValueAsBytes(unsigned));
		return new ValidatedPromptLoading(evidence.targetPath(), sources, receipts, identity);
	}

	/**
	 * Creates a source-id lookup while rejecting duplicate bindings.
	 *
	 * @param values bindings to index
	 * @return source-id lookup
	 */
	private static Map<String, PromptSourceBinding> bindings(List<PromptSourceBinding> values)
	{
		Map<String, PromptSourceBinding> result = new HashMap<>();
		for (PromptSourceBinding value : values)
			if (result.put(value.sourceId(), value) != null)
				throw new IllegalArgumentException("Loading bindings must not repeat a source");
		return result;
	}

	/**
	 * Computes an exact byte identity.
	 *
	 * @param content source bytes
	 * @return lowercase SHA-256 identity
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static String sha256(byte[] content) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}
}

package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NativeEventEvidence;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Decodes retained native events into observed prompt paths for one run conversation.
 */
@FunctionalInterface
public interface NativePromptPathCollector
{
	/**
	 * Collects paths only from native event evidence already retained by the runner.
	 *
	 * @param runId the retained run identity
	 * @param conversationId the native conversation identity
	 * @param retainedNativeEvents the retained native event index
	 * @param reader the reader for indexed raw events
	 * @return observed paths and coverage
	 * @throws IOException if claimed native evidence cannot be read or validated
	 */
	NativePromptPathObservation collect(String runId, String conversationId,
		List<NativeEventEvidence> retainedNativeEvents, NativeEventReader reader) throws IOException;

	/**
	 * Reads only an indexed native event and verifies its immutable content identity before decoding.
	 *
	 * @param indexedEvent the retained native event index entry
	 * @param reader the immutable evidence reader
	 * @return the validated raw bytes
	 * @throws IOException if the event is missing or its bytes differ from the retained reference
	 */
	static byte[] readIndexedEvent(NativeEventEvidence indexedEvent, NativeEventReader reader) throws IOException
	{
		Objects.requireNonNull(indexedEvent, "indexedEvent");
		Objects.requireNonNull(reader, "reader");
		EvidenceRef reference = indexedEvent.evidence();
		byte[] bytes = reader.read(reference);
		if (bytes == null)
			throw new IOException("Missing retained native event: " + reference);
		try
		{
			String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			if (!actual.equals(reference.evidenceId()))
				throw new IOException("Retained native event hash mismatch: " + reference);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("JDK lacks SHA-256", e);
		}
		return bytes;
	}
}

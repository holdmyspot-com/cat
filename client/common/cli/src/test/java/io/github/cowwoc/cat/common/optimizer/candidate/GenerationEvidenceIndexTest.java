package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/** Tests complete native callback and terminal disposition record shapes. */
public final class GenerationEvidenceIndexTest
{
	/** A native callback index keeps distinct call ids in observed order. */
	@Test
	public void retainsOrderedNativeCalls()
	{
		GenerationNativeCallbackIndex index = new GenerationNativeCallbackIndex(1, "call", ref("run-requests"),
			"run", ref("native-event-roots"), List.of(new NativeCallEvidence("native-a", ref("native-events")),
				new NativeCallEvidence("native-b", ref("native-events"))), List.of());

		requireThat(index.orderedNativeCallIdAndRawEvidenceRefs().get(1).nativeCallId(), "secondCallId").isEqualTo(
			"native-b");
	}

	/** Duplicate native ids cannot masquerade as two independently acknowledged calls. */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*duplicate.*")
	public void rejectsDuplicateNativeCallId()
	{
		new GenerationNativeCallbackIndex(1, "call", ref("run-requests"), "run", ref("native-event-roots"),
			List.of(new NativeCallEvidence("same", ref("native-events")),
				new NativeCallEvidence("same", ref("native-events"))), List.of());
	}

	/** ACCEPTED has exactly one edit receipt and no rejection or unresolved reason. */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*ACCEPTED.*")
	public void rejectsAcceptedDispositionWithoutReceipt()
	{
		new GenerationToolDisposition(1, ref("generation-tool-calls"),
			GenerationToolDisposition.Status.ACCEPTED, Optional.empty(), Optional.empty(), Optional.empty());
	}

	/** The sealed index keeps the exact tool call and disposition refs for the C1 slot. */
	@Test
	public void retainsSealedCallAndDispositionPair()
	{
		GenerationToolCallIndex index = new GenerationToolCallIndex(1, ref("live-generation-checkpoints"),
			ref("run-requests"), "call", ref("call-slot-claims"), "run", ref("generation-native-callback-indexes"),
			List.of(new ToolCallDispositionPair(ref("generation-tool-calls"), ref("generation-tool-dispositions"))));

		requireThat(index.orderedToolCallAndDispositionRefs().size(), "indexedCalls").isEqualTo(1);
	}

	/** An index cannot pair the same recorded call twice. */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*duplicate.*")
	public void rejectsDuplicateIndexedCall()
	{
		ToolCallDispositionPair pair = new ToolCallDispositionPair(ref("generation-tool-calls"),
			ref("generation-tool-dispositions"));
		new GenerationToolCallIndex(1, ref("live-generation-checkpoints"), ref("run-requests"), "call",
			ref("call-slot-claims"), "run", ref("generation-native-callback-indexes"), List.of(pair, pair));
	}

	/**
	 * Builds a typed content-addressed test reference.
	 *
	 * @param collection evidence collection
	 * @return typed test reference
	 */
	private static EvidenceRef ref(String collection)
	{
		return new EvidenceRef(collection, "0".repeat(64));
	}
}

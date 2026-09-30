package io.github.cowwoc.cat.common.optimizer.candidate;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/** Tests the C3-owned immutable gateway evidence shapes. */
public final class GenerationGatewayRecordTest
{
	/** One tool call retains all native and C1 identities before any edit. */
	@Test
	public void retainsNativeToolIdentity()
	{
		EvidenceRef checkpoint = new EvidenceRef("live-generation-checkpoints", "1".repeat(64));
		EvidenceRef request = new EvidenceRef("run-requests", "2".repeat(64));
		EvidenceRef claim = new EvidenceRef("call-slot-claims", "3".repeat(64));
		EvidenceRef raw = new EvidenceRef("native-events", "4".repeat(64));

		GenerationToolCall call = new GenerationToolCall(1, checkpoint, request, "logical", claim,
			"run", "session", "turn", "call", raw);

		requireThat(call.nativeCallId(), "nativeCallId").isEqualTo("call");
		requireThat(call.rawArgumentsRef(), "rawArgumentsRef").isEqualTo(raw);
	}

	/** Accepted edit evidence retains its precise before/after endpoint states. */
	@Test
	public void retainsAcceptedEditStates()
	{
		EvidenceRef checkpoint = new EvidenceRef("live-generation-checkpoints", "1".repeat(64));
		EvidenceRef call = new EvidenceRef("generation-tool-calls", "2".repeat(64));
		EvidenceRef transaction = new EvidenceRef("generation-edit-transactions", "3".repeat(64));
		Path path = Path.of("prompts/rule.md");
		GenerationPathState before = new GenerationPathState(path, GenerationExpectedState.absent());
		GenerationPathState after = new GenerationPathState(path,
			GenerationExpectedState.regular("4".repeat(64)));
		GenerationEditReceipt receipt = new GenerationEditReceipt(1, checkpoint, "logical", "operation", call,
			GenerationEditOperation.Kind.CREATE, List.of(before), List.of(after), List.of(path), transaction);

		requireThat(new GenerationEditDisposition.Accepted(receipt).receipt(), "acceptedReceipt").isEqualTo(receipt);
	}

	/** Denials retain raw attempted path text even when no mutation is authorized. */
	@Test
	public void retainsRejectedRawPath()
	{
		EvidenceRef checkpoint = new EvidenceRef("live-generation-checkpoints", "1".repeat(64));
		EvidenceRef call = new EvidenceRef("generation-tool-calls", "2".repeat(64));
		GenerationEditRejection rejection = new GenerationEditRejection(1, checkpoint, "logical", "operation", call,
			GenerationEditOperation.Kind.DELETE, "../outside.md", Optional.empty(), "OUT_OF_ROOT", List.of());

		requireThat(new GenerationEditDisposition.Rejected(rejection).rejection(), "rejection").isEqualTo(rejection);
	}
}

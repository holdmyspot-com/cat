package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests that a retained native completion identifies the owning campaign.
 */
public final class NativeRunCompletionEvidenceTest
{
	/**
	 * Creates a test instance.
	 */
	public NativeRunCompletionEvidenceTest()
	{
	}

	/**
	 * Rejects a completion that cannot be assigned to a campaign.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankCampaignId()
	{
		new NativeRunCompletionEvidence(" ", "call-1", new EvidenceRef("run-requests", "a".repeat(64)),
			new EvidenceRef("slot-claims", "b".repeat(64)), Optional.empty(),
			NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.empty(), List.of(),
			Optional.empty(), Optional.empty());
	}

	/**
	 * Rejects a failed call whose native failure was not retained.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsFailedOutcomeWithoutFailure()
	{
		new NativeRunCompletionEvidence("campaign-1", "call-1", new EvidenceRef("run-requests", "a".repeat(64)),
			new EvidenceRef("slot-claims", "b".repeat(64)), Optional.empty(),
			NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.empty(), List.of(),
			Optional.empty(), Optional.empty());
	}

	/**
	 * A failed root cannot identify a different call than its retained native failure.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsForeignFailureCall()
	{
		NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.START_FAILED, "other-call",
			Optional.empty(), "start failed", List.of(), Optional.empty(), Optional.empty());
		new NativeRunCompletionEvidence("campaign-1", "call-1", request(), slot(), Optional.empty(),
			NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure), List.of(),
			Optional.empty(), Optional.empty());
	}

	/**
	 * A failed root cannot change the run identity recorded by C0R.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsForeignFailureRun()
	{
		NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call-1",
			Optional.of("run-1"), "transport failed", List.of(), Optional.empty(), Optional.empty());
		new NativeRunCompletionEvidence("campaign-1", "call-1", request(), slot(), Optional.of("run-2"),
			NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure), List.of(),
			Optional.empty(), Optional.empty());
	}

	/**
	 * The root event list must exactly retain the native failure event list.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsDifferentFailureEvents()
	{
		EvidenceRef nativeRef = new EvidenceRef("native-events", "c".repeat(64));
		NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call-1",
			Optional.empty(), "transport failed", List.of(nativeRef), Optional.empty(), Optional.empty());
		new NativeRunCompletionEvidence("campaign-1", "call-1", request(), slot(), Optional.empty(),
			NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure), List.of(),
			Optional.empty(), Optional.empty());
	}

	/**
	 * A native terminal marker cannot coexist with an outcome-free non-dispatch claim.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsProofWithTerminalMarker()
	{
		EvidenceRef nativeRef = new EvidenceRef("native-events", "c".repeat(64));
		NoNativeTurnDispatchProof proof = new NoNativeTurnDispatchProof("call-1", Optional.empty(),
			new EvidenceRef("native-dispatch-completions", "d".repeat(64)));
		NestedRunFailure failure = new NestedRunFailure(NestedRunFailureKind.TRANSPORT_FAILED, "call-1",
			Optional.empty(), "transport failed", List.of(), Optional.of(nativeRef), Optional.of(proof));
		new NativeRunCompletionEvidence("campaign-1", "call-1", request(), slot(), Optional.empty(),
			NativeRunCompletionEvidence.Outcome.FAILED, Optional.empty(), Optional.of(failure), List.of(),
			Optional.of(nativeRef), Optional.of(proof));
	}

	/**
	 * Supplies the fixture run request.
	 *
	 * @return a valid request address
	 */
	private static EvidenceRef request()
	{
		return new EvidenceRef("run-requests", "a".repeat(64));
	}

	/**
	 * Supplies the fixture slot claim.
	 *
	 * @return a valid claim address
	 */
	private static EvidenceRef slot()
	{
		return new EvidenceRef("slot-claims", "b".repeat(64));
	}
}

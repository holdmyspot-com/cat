package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunResult;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Builds post-call measurement conditions from retained admission and native completion evidence. */
final class NativeCompletionMeasurements
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String VALUES = "measurement-values";
	private final CampaignStore store;

	/**
	 * Binds native measurement evidence to its store.
	 *
	 * @param store pinned campaign store
	 */
	NativeCompletionMeasurements(CampaignStore store)
	{
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Preserves admission conditions and replaces model and runner values with native-observed facts or limits.
	 *
	 * @param binding pinned campaign
	 * @param claimed original call claim
	 * @param preStart indexed pre-start and admission observation
	 * @param epochRef original measurement epoch
	 * @param completion exact indexed native completion
	 * @return complete known or limited conditions for after-call observation
	 * @throws IOException if retained identities or the before-call observation conflict
	 */
	MeasurementConditions conditions(CampaignBinding binding, ClaimedSlot claimed, CallPreStartRecord preStart,
		EvidenceRef epochRef, NativeRunCompletionEvidence completion) throws IOException
	{
		EvidenceRef beforeRef = preStart.beforeCallObservationRef().orElseThrow(() ->
			new IOException("Native completion has no retained before-call observation"));
		MeasurementObservation before = store.readMeasurementObservation(beforeRef);
		String callId = claimed.slot().logicalCallId();
		if (before.stage() != MeasurementObservation.Stage.BEFORE_CALL ||
			!before.logicalCallId().equals(callId) || !before.campaignId().equals(binding.campaignId()) ||
			!before.epochRef().equals(epochRef) || !completion.logicalCallId().equals(callId) ||
			!completion.runRequestRef().equals(claimed.slot().requestRef()) ||
			!completion.slotClaimRef().equals(claimed.slotClaimRef()))
			throw new IOException("Native after-call observation conflicts with retained authority");
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> values = new EnumMap<>(before.conditions().values());
		if (completion.outcome() == NativeRunCompletionEvidence.Outcome.SUCCEEDED)
		{
			NestedRunResult result = completion.result().orElseThrow();
			values.put(MeasurementConditions.Kind.MODEL_PROFILE,
				new MeasurementConditions.Value.Known(store.appendImmutableJson(VALUES,
					JSON.writeValueAsBytes(result.observedModelProfile()))));
			values.put(MeasurementConditions.Kind.HARNESS_RUNNER,
				new MeasurementConditions.Value.Known(store.appendImmutableJson(VALUES,
					JSON.writeValueAsBytes(result.runnerIdentity().runtimeManifestSha256()))));
		}
		else
		{
			String actor = switch (preStart.producerAttemptKind())
			{
				case TRIAL -> "Native trial";
				case CANDIDATE -> "Native generation";
				default -> throw new IOException("Native measurement completion requires a trial or generation attempt");
			};
			values.put(MeasurementConditions.Kind.MODEL_PROFILE,
				new MeasurementConditions.Value.Unknown(actor + " did not report a completed model profile"));
			values.put(MeasurementConditions.Kind.HARNESS_RUNNER,
				new MeasurementConditions.Value.Unknown(actor + " did not report a completed runner identity"));
		}
		return new MeasurementConditions(values);
	}
}

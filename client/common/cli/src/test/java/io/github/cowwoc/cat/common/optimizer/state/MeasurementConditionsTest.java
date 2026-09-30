package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.EnumMap;
import java.util.Map;
import org.testng.annotations.Test;

/** Checks condition comparison without treating absent observations as equality. */
public final class MeasurementConditionsTest
{
	/** Creates an isolated measurement comparison test. */
	public MeasurementConditionsTest()
	{
	}

	/** A changed model and an unchanged evaluator remain distinct from unavailable provider evidence. */
	@Test
	public void comparesKnownAndUnknownConditions()
	{
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> before = unknownValues();
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> after = unknownValues();
		EvidenceRef modelBefore = new EvidenceRef("measurement-values", "a".repeat(64));
		EvidenceRef modelAfter = new EvidenceRef("measurement-values", "b".repeat(64));
		EvidenceRef evaluator = new EvidenceRef("measurement-values", "c".repeat(64));
		before.put(MeasurementConditions.Kind.MODEL_PROFILE, new MeasurementConditions.Value.Known(modelBefore));
		after.put(MeasurementConditions.Kind.MODEL_PROFILE, new MeasurementConditions.Value.Known(modelAfter));
		before.put(MeasurementConditions.Kind.EVALUATOR, new MeasurementConditions.Value.Known(evaluator));
		after.put(MeasurementConditions.Kind.EVALUATOR, new MeasurementConditions.Value.Known(evaluator));
		MeasurementConditions.Comparison comparison = new MeasurementConditions(before).
			compare(new MeasurementConditions(after));
		requireThat(comparison.fields().get(MeasurementConditions.Kind.MODEL_PROFILE), "changedModel").
			isEqualTo(MeasurementConditions.Status.CHANGED);
		requireThat(comparison.fields().get(MeasurementConditions.Kind.EVALUATOR), "unchangedEvaluator").
			isEqualTo(MeasurementConditions.Status.UNCHANGED);
		requireThat(comparison.fields().get(MeasurementConditions.Kind.EXTERNAL_SOURCES),
			"unknownProvider").isEqualTo(MeasurementConditions.Status.UNKNOWN);
		requireThat(comparison.hasKnownChange(), "knownChange").isTrue();
	}

	/** An omitted condition cannot silently become a known equal value. */
	@Test
	public void rejectsOmittedCondition()
	{
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> values = unknownValues();
		values.remove(MeasurementConditions.Kind.CONTRACT);
		try
		{
			new MeasurementConditions(values);
			throw new AssertionError("Every condition must be represented");
		}
		catch (IllegalArgumentException expected)
		{
			requireThat(expected.getMessage(), "omittedCondition").contains("Every material condition");
		}
	}

	/**
	 * Creates an explicit unknown value for every product condition.
	 *
	 * @return mutable test observation
	 */
	private static Map<MeasurementConditions.Kind, MeasurementConditions.Value> unknownValues()
	{
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> result =
			new EnumMap<>(MeasurementConditions.Kind.class);
		for (MeasurementConditions.Kind kind : MeasurementConditions.Kind.values())
			result.put(kind, new MeasurementConditions.Value.Unknown("not observed"));
		return result;
	}
}

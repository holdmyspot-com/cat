package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/** Verifies complete durable observations across a cold read. */
public final class MeasurementObservationTest
{
	/** Creates the test fixture. */
	public MeasurementObservationTest()
	{
	}

	/**
	 * A complete known and unknown observation survives a content-addressed round trip.
	 *
	 * @throws IOException if fixture persistence fails
	 */
	@Test
	public void retainsCompleteConditionValues() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("measurement-observation-"));
			CampaignStore store = new CampaignStore(directory);
			ProspectiveCampaignState.initialize(store, "campaign");
			Map<MeasurementConditions.Kind, MeasurementConditions.Value> values = unknownValues();
			EvidenceRef evaluator = new EvidenceRef("measurement-values", "a".repeat(64));
			values.put(MeasurementConditions.Kind.EVALUATOR, new MeasurementConditions.Value.Known(evaluator));
			MeasurementObservation observation = MeasurementObservation.from("campaign", "lineage",
				new EvidenceRef("measurement-epochs", "b".repeat(64)), "call-1",
				MeasurementObservation.Stage.BEFORE_CALL, new MeasurementConditions(values));
			JsonMapper json = JsonMapper.builder().build();
			EvidenceRef stored = store.appendImmutableJson("measurement-observations",
				json.writeValueAsBytes(observation));
			MeasurementObservation restored = json.readValue(CampaignStore.open(directory).readImmutableJson(stored),
				MeasurementObservation.class);
			requireThat(restored, "roundTrip").isEqualTo(observation);
			requireThat(restored.conditions().values().get(MeasurementConditions.Kind.EVALUATOR),
				"knownEvaluator").isEqualTo(new MeasurementConditions.Value.Known(evaluator));
			requireThat(restored.conditions().values().get(MeasurementConditions.Kind.EXTERNAL_SOURCES),
				"unknownExternal").isEqualTo(new MeasurementConditions.Value.Unknown("not observed"));
		}
	}

	/** A missing condition cannot be persisted as a complete observation. */
	@Test
	public void rejectsMissingCondition()
	{
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> values = unknownValues();
		values.remove(MeasurementConditions.Kind.CONTRACT);
		try
		{
			MeasurementObservation.from("campaign", "lineage",
				new EvidenceRef("measurement-epochs", "b".repeat(64)), "call-1",
				MeasurementObservation.Stage.BEFORE_CALL, new MeasurementConditions(values));
			throw new AssertionError("Missing condition must be rejected");
		}
		catch (IllegalArgumentException expected)
		{
			requireThat(expected.getMessage(), "message").contains("Every material condition");
		}
	}

	/**
	 * Creates an explicit unknown for every required condition.
	 *
	 * @return complete mutable condition map
	 */
	private static Map<MeasurementConditions.Kind, MeasurementConditions.Value> unknownValues()
	{
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> values =
			new EnumMap<>(MeasurementConditions.Kind.class);
		for (MeasurementConditions.Kind kind : MeasurementConditions.Kind.values())
			values.put(kind, new MeasurementConditions.Value.Unknown("not observed"));
		return values;
	}
}

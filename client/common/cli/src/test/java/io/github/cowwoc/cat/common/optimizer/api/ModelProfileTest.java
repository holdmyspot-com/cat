package io.github.cowwoc.cat.common.optimizer.api;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.testng.annotations.Test;

/**
 * Tests the model identity recorded with a native run.
 */
public final class ModelProfileTest
{
	/**
	 * Creates a test instance.
	 */
	public ModelProfileTest()
	{
	}

	/**
	 * Verifies the profile retains a stable copy of its exposed settings.
	 */
	@Test
	public void copiesExposedSettings()
	{
		Map<String, String> settings = new HashMap<>();
		settings.put("temperature", "0.2");
		ModelProfile profile = new ModelProfile("model-a", Optional.of("high"), settings);
		settings.put("temperature", "0.9");
		requireThat(profile.exposedSettings(), "exposedSettings").isEqualTo(Map.of("temperature", "0.2"));
	}

	/**
	 * An unspecified requested effort accepts the harness default while retaining its observed identity.
	 */
	@Test
	public void acceptsObservedHarnessDefault()
	{
		ModelProfile requested = new ModelProfile("model-a", Optional.empty(), Map.of());
		ModelProfile observed = new ModelProfile("model-a", Optional.of("high"),
			Map.of("providerMode", "standard"));
		requireThat(requested.acceptsObserved(observed), "defaultedEffort").isTrue();
		requireThat(observed.reasoningProfile(), "observedEffort").isEqualTo(Optional.of("high"));
	}

	/**
	 * An explicitly requested setting must match the native observation.
	 */
	@Test
	public void rejectsDifferentObservedSettings()
	{
		ModelProfile requested = new ModelProfile("model-a", Optional.of("medium"),
			Map.of("providerMode", "standard"));
		requireThat(requested.acceptsObserved(new ModelProfile("model-a", Optional.of("high"),
			Map.of("providerMode", "standard"))), "differentEffort").isFalse();
		requireThat(requested.acceptsObserved(new ModelProfile("model-a", Optional.of("medium"),
			Map.of("providerMode", "other"))), "differentSetting").isFalse();
		requireThat(requested.acceptsObserved(new ModelProfile("model-b", Optional.of("medium"),
			Map.of("providerMode", "standard"))), "differentModel").isFalse();
	}

	/**
	 * Verifies a blank model identity cannot be recorded.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankModelId()
	{
		new ModelProfile(" ", Optional.empty(), Map.of());
	}

	/**
	 * Verifies a present but blank reasoning profile cannot be recorded.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankReasoningProfile()
	{
		new ModelProfile("model-a", Optional.of(" "), Map.of());
	}

	/**
	 * Verifies settings with absent values cannot be recorded.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullSettingValue()
	{
		Map<String, String> settings = new HashMap<>();
		settings.put("temperature", null);
		new ModelProfile("model-a", Optional.empty(), settings);
	}
}

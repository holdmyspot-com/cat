package io.github.cowwoc.cat.common.optimizer.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Identifies the model and exposed settings used for a native run.
 *
 * @param modelId the nonblank model identity
 * @param reasoningProfile the optional nonblank reasoning profile
 * @param exposedSettings the model settings exposed by the harness
 */
public record ModelProfile(String modelId, Optional<String> reasoningProfile, Map<String, String> exposedSettings)
{
	/**
	 * Validates and copies the model identity supplied by a harness.
	 *
	 * @param modelId the nonblank model identity
	 * @param reasoningProfile the optional nonblank reasoning profile
	 * @param exposedSettings the model settings exposed by the harness
	 * @throws NullPointerException if any parameter or setting key or value is null
	 * @throws IllegalArgumentException if the model identity, reasoning profile, or setting name is blank
	 */
	public ModelProfile
	{
		Objects.requireNonNull(modelId, "modelId");
		Objects.requireNonNull(reasoningProfile, "reasoningProfile");
		Objects.requireNonNull(exposedSettings, "exposedSettings");
		if (modelId.isBlank())
			throw new IllegalArgumentException("modelId must not be blank");
		String selectedReasoning = reasoningProfile.orElse(null);
		if (selectedReasoning != null && selectedReasoning.isBlank())
			throw new IllegalArgumentException("reasoningProfile must not be blank when present");
		exposedSettings = Map.copyOf(exposedSettings);
		for (String settingName : exposedSettings.keySet())
		{
			if (settingName.isBlank())
				throw new IllegalArgumentException("exposedSettings names must not be blank");
		}
	}

	/**
	 * Checks whether the native profile satisfies the model and settings selected for a call.
	 * An omitted optional setting permits the harness default, whose effective value remains in the observation.
	 *
	 * @param observed effective native profile
	 * @return whether the observed profile satisfies this requested profile
	 */
	public boolean acceptsObserved(ModelProfile observed)
	{
		Objects.requireNonNull(observed, "observed");
		return modelId.equals(observed.modelId()) &&
			(reasoningProfile.isEmpty() || reasoningProfile.equals(observed.reasoningProfile())) &&
			observed.exposedSettings().entrySet().containsAll(exposedSettings.entrySet());
	}
}

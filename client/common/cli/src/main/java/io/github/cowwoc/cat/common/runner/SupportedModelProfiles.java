package io.github.cowwoc.cat.common.runner;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The published model profiles permitted at optimizer and evaluator boundaries. */
public final class SupportedModelProfiles
{
	private static final Map<ProfileRole, HarnessModelProfile> PROFILES = profiles();

	/** Prevents construction of the registry. */
	private SupportedModelProfiles()
	{
	}

	/**
	 * Returns the exact profile registered for a role.
	 *
	 * @param role the closed profile role
	 * @return the published profile
	 */
	public static HarnessModelProfile profile(ProfileRole role)
	{
		return PROFILES.get(Objects.requireNonNull(role, "role must not be null"));
	}

	/**
	 * Returns every published profile for a role in stable registry order.
	 *
	 * @param role the closed profile role
	 * @return the immutable supported profile list
	 */
	public static List<HarnessModelProfile> supportedProfiles(ProfileRole role)
	{
		Objects.requireNonNull(role, "role must not be null");
		if (role == ProfileRole.OPTIMIZER_EXECUTOR)
			return List.of(new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM),
				new HarnessModelProfile("haiku", ReasoningEffort.MEDIUM));
		return List.of(PROFILES.get(ProfileRole.SEMANTIC_EVALUATOR));
	}

	/**
	 * Validates that a caller supplied profile is one of the published combinations.
	 *
	 * @param role the closed profile role
	 * @param profile the requested profile
	 * @return the same validated profile
	 */
	public static HarnessModelProfile requireSupported(ProfileRole role, HarnessModelProfile profile)
	{
		Objects.requireNonNull(profile, "profile must not be null");
		boolean supported = role == ProfileRole.OPTIMIZER_EXECUTOR &&
			(profile.equals(new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM)) ||
				profile.equals(new HarnessModelProfile("haiku", ReasoningEffort.MEDIUM))) ||
			role == ProfileRole.SEMANTIC_EVALUATOR &&
			profile.equals(new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.HIGH));
		if (!supported)
			throw new IllegalArgumentException("Unsupported " + role + " model profile: " + profile.model() + "/" +
				profile.externalEffort());
		return profile;
	}

	/**
	 * Builds the immutable launch registry.
	 *
	 * @return the published profiles
	 */
	private static Map<ProfileRole, HarnessModelProfile> profiles()
	{
		Map<ProfileRole, HarnessModelProfile> profiles = new EnumMap<>(ProfileRole.class);
		profiles.put(ProfileRole.OPTIMIZER_EXECUTOR, new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM));
		profiles.put(ProfileRole.SEMANTIC_EVALUATOR, new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.HIGH));
		return Map.copyOf(profiles);
	}
}

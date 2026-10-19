package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every model and reasoning-effort combination that a harness offers the user, with the source of that list. CAT
 * uses this list, called the profile inventory, to derive trial profiles.
 * <p>
 * A model whose effort support is undocumented is listed once, with an empty effort, so that its sessions run under
 * the harness's default effort.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param source where the inventory came from, such as the command that listed it or the documentation it cites
 * @param profiles every profile, in the harness's order
 */
public record ProfileCatalog(String source, List<TrialProfile> profiles)
{
	/**
	 * Creates an inventory.
	 *
	 * @param source where the inventory came from
	 * @param profiles every profile, in the harness's order
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code source} is blank, {@code profiles} is empty, a profile has no model
	 *   or a profile is listed twice
	 */
	public ProfileCatalog
	{
		requireThat(source, "source").isNotBlank();
		requireThat(profiles, "profiles").isNotEmpty();
		profiles = List.copyOf(profiles);
		Set<TrialProfile> unique = new HashSet<>();
		for (TrialProfile profile : profiles)
		{
			requireThat(profile.model(), "profile.model()").isNotEmpty();
			if (!unique.add(profile))
				throw new IllegalArgumentException("The profile " + profile + " is listed twice");
		}
	}

	/**
	 * Indicates whether no inventory profile matches every model and effort selected by a binding.
	 *
	 * @param binding a profile whose empty dimensions are unbound
	 * @return {@code true} if no inventory profile matches every bound dimension
	 * @throws NullPointerException if {@code binding} is null
	 */
	public boolean isOutside(TrialProfile binding)
	{
		requireThat(binding, "binding").isNotNull();
		return profiles.stream().noneMatch(profile -> profile.matches(binding));
	}
}

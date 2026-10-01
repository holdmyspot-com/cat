package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;

/**
 * A warning to the user that a requirement needs an edit outside the campaign's edit authority.
 *
 * @param requirement the identifier of the requirement that needs the edit
 * @param path the path at which the edit is needed, which may be a link path
 * @param target the path that the edit would change after symbolic links are resolved, or {@code path} if the links
 *               form a cycle
 * @param limit the limit that forbids the edit
 * @param choices the choices the user has, in the order to present them
 */
public record AuthorityWarning(String requirement, Path path, Path target, Limit limit, List<Choice> choices)
{
	/**
	 * Creates a warning.
	 *
	 * @param requirement the identifier of the requirement that needs the edit
	 * @param path the path at which the edit is needed, which may be a link path
	 * @param target the path that the edit would change after symbolic links are resolved, or {@code path} if the
	 *               links form a cycle
	 * @param limit the limit that forbids the edit
	 * @param choices the choices the user has, in the order to present them
	 * @throws NullPointerException if any of the arguments are null, or if {@code choices} contains null
	 * @throws IllegalArgumentException if {@code requirement} is blank or {@code choices} is empty
	 */
	public AuthorityWarning
	{
		requireThat(requirement, "requirement").isNotBlank();
		requireThat(path, "path").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(limit, "limit").isNotNull();
		choices = List.copyOf(choices);
		requireThat(choices, "choices").isNotEmpty();
	}

	/**
	 * A way for the user to proceed after a warning.
	 */
	public enum Choice
	{
		/**
		 * Authorize content edits to the external target.
		 */
		AUTHORIZE_TARGET,
		/**
		 * Satisfy the requirement through another source the campaign is already authorized to edit.
		 */
		USE_AUTHORIZED_SOURCE,
		/**
		 * Stop the campaign.
		 */
		STOP_CAMPAIGN,
		/**
		 * Revise the campaign's contract.
		 */
		REVISE_CAMPAIGN
	}
}

package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ProfileBinding;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The available model and effort combinations (trial profiles), and the model or effort that each prompt file
 * selects (its binding), recorded when the deriver ran.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param inventory the harness's profile inventory and its source
 * @param bindings the profile that each file binds, keyed by the file's path as the fork names it: relative to the
 *   project, or absolute outside it. A file that binds nothing is absent.
 */
public record ProfileEvidence(ProfileCatalog inventory, Map<String, TrialProfile> bindings)
{
	/**
	 * The separator between a link's path and its resolved target in a requirement's source.
	 */
	private static final String LINK_SEPARATOR = "->";

	/**
	 * Creates the evidence.
	 *
	 * @param inventory the harness's profile inventory and its source
	 * @param bindings the profile that each file binds, keyed by the file's path as the fork names it
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a binding binds nothing
	 */
	public ProfileEvidence
	{
		requireThat(inventory, "inventory").isNotNull();
		requireThat(bindings, "bindings").isNotNull();
		for (Map.Entry<String, TrialProfile> entry : bindings.entrySet())
			requireThat(entry.getValue().isBound(), "bindings[" + entry.getKey() + "].isBound()").isTrue();
		bindings = Map.copyOf(bindings);
	}

	/**
	 * Returns the bindings of the files that requirements name as their source.
	 * <p>
	 * A source written as {@code link -> target} uses the target's recorded binding when available. Otherwise, it
	 * uses the binding recorded at the link path, where staging copied the target's content. If neither path has a
	 * recorded binding, the source yields none.
	 *
	 * @param requirements the requirements whose source files are searched, in result order
	 * @return one binding per distinct source that binds a profile, named as the requirements name it, in requirement
	 *   order
	 * @throws NullPointerException if {@code requirements} is null or contains null
	 */
	public List<ProfileBinding> bindingsOf(List<Requirement> requirements)
	{
		requireThat(requirements, "requirements").isNotNull();
		Set<String> sources = new LinkedHashSet<>();
		for (Requirement requirement : requirements)
			sources.add(requirement.source());
		List<ProfileBinding> found = new ArrayList<>();
		for (String source : sources)
		{
			String[] parts = source.split(LINK_SEPARATOR);
			for (int i = parts.length - 1; i >= 0; --i)
			{
				TrialProfile profile = bindings.get(normalize(parts[i]));
				if (profile != null)
				{
					found.add(new ProfileBinding(source, profile));
					break;
				}
			}
		}
		return found;
	}

	/**
	 * Normalizes a path that a requirement's source names.
	 *
	 * @param path the path
	 * @return the normalized path, or the stripped text if it is not a valid path
	 */
	private static String normalize(String path)
	{
		String stripped = path.strip();
		try
		{
			return Path.of(stripped).normalize().toString();
		}
		catch (InvalidPathException _)
		{
			return stripped;
		}
	}
}

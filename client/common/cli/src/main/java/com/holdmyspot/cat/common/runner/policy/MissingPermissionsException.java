package com.holdmyspot.cat.common.runner.policy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Signals that the runner refuses to start a session because the session needs writes or hosts that the sandbox of the
 * session that requested it does not allow. The session's commands never run.
 * <p>
 * The message of the exception names every missing write and host for a requesting session that is a run's session,
 * and says to name them with {@code --extra-permissions} on the runner launcher command that started that session.
 * {@link #messageFor(String, String)} instead tells the user which settings of a harness session to change.
 */
public final class MissingPermissionsException extends SessionRefusedException
{
	private static final long serialVersionUID = 1L;
	/**
	 * The writes that no folder the requesting session may write contains.
	 */
	private final transient List<Path> missingWrites;
	/**
	 * The writes inside a path that the requesting session may not write, each with that path.
	 */
	private final transient Map<Path, Path> deniedWrites;
	/**
	 * The hosts that the requesting session may not reach, in alphabetical order.
	 */
	private final transient Set<String> missingHosts;

	/**
	 * Creates a refusal.
	 *
	 * @param missingWrites the writes that no folder the requesting session may write contains, in the order that the
	 *                      session needs them
	 * @param deniedWrites  the writes inside a path that the requesting session may not write, each with that path
	 * @param missingHosts  the hosts that the requesting session may not reach
	 * @throws NullPointerException     if any of the arguments are null, or a collection contains null
	 * @throws IllegalArgumentException if all three arguments are empty
	 */
	public MissingPermissionsException(List<Path> missingWrites, Map<Path, Path> deniedWrites,
		Set<String> missingHosts)
	{
		super(message(missingWrites, deniedWrites, missingHosts));
		this.missingWrites = List.copyOf(missingWrites);
		this.deniedWrites = Collections.unmodifiableMap(new LinkedHashMap<>(deniedWrites));
		this.missingHosts = Set.copyOf(missingHosts);
	}

	/**
	 * Returns the message for a requesting session that is a run's session, whose sandbox CAT wrote from the options
	 * of the runner launcher command that started the run. The user changes it only through that command's
	 * {@code --extra-permissions}.
	 *
	 * @param missingWrites the writes that no folder the requesting session may write contains
	 * @param deniedWrites  the writes inside a path that the requesting session may not write, each with that path
	 * @param missingHosts  the hosts that the requesting session may not reach
	 * @return the message
	 * @throws NullPointerException     if any of the arguments are null, or a collection contains null
	 * @throws IllegalArgumentException if all three arguments are empty
	 */
	private static String message(List<Path> missingWrites, Map<Path, Path> deniedWrites, Set<String> missingHosts)
	{
		List<String> sentences = new ArrayList<>();
		sentences.add("The requested session needs to " + needs(missingWrites, deniedWrites, missingHosts) +
			", which the requesting session's sandbox does not allow.");
		if (!missingWrites.isEmpty() || !missingHosts.isEmpty())
		{
			String listed = "them";
			if (!deniedWrites.isEmpty())
				listed = "the writes and hosts not marked denied";
			sentences.add("List " + listed + " in the file that " + ExtraPermissions.OPTION + " names, on the " +
				"runner launcher command that started the requesting session, then run that command again.");
		}
		if (!deniedWrites.isEmpty())
		{
			List<String> denials = new ArrayList<>();
			for (Path denial : new LinkedHashSet<>(deniedWrites.values()))
				denials.add(denial.toString());
			sentences.add(ExtraPermissions.OPTION + " does not lift a denial: remove the denial of " +
				String.join(", ", denials) + " from the sandbox settings of the session that started the " +
				"requesting session's run.");
		}
		return String.join(" ", sentences);
	}

	/**
	 * Returns what the requested session needs, as "write A, B (denied by D) and reach h1, h2".
	 *
	 * @param missingWrites the writes that no folder the requesting session may write contains
	 * @param deniedWrites  the writes inside a path that the requesting session may not write, each with that path
	 * @param missingHosts  the hosts that the requesting session may not reach
	 * @return the description
	 * @throws NullPointerException     if any of the arguments are null, or a collection contains null
	 * @throws IllegalArgumentException if all three arguments are empty
	 */
	private static String needs(List<Path> missingWrites, Map<Path, Path> deniedWrites, Set<String> missingHosts)
	{
		requireThat(missingWrites, "missingWrites").isNotNull();
		requireThat(deniedWrites, "deniedWrites").isNotNull();
		requireThat(missingHosts, "missingHosts").isNotNull();
		List<String> writes = new ArrayList<>();
		for (Path write : missingWrites)
			writes.add(write.toString());
		for (Map.Entry<Path, Path> denied : deniedWrites.entrySet())
			writes.add(denied.getKey() + " (denied by " + denied.getValue() + ")");
		List<String> parts = new ArrayList<>();
		if (!writes.isEmpty())
			parts.add("write " + String.join(", ", writes));
		if (!missingHosts.isEmpty())
			parts.add("reach " + String.join(", ", new TreeSet<>(missingHosts)));
		requireThat(parts, "parts").isNotEmpty();
		return String.join(" and ", parts);
	}

	/**
	 * Returns the writes that the requesting session must be allowed to write.
	 *
	 * @return the writes that no folder it may write contains, in the order that the requested session needs them
	 */
	public List<Path> writes()
	{
		return missingWrites;
	}

	/**
	 * Returns the writes that the requesting session denies, with the path that denies each.
	 *
	 * @return the writes inside a path that it may not write; allowing a folder does not allow them
	 */
	public Map<Path, Path> deniedWrites()
	{
		return deniedWrites;
	}

	/**
	 * Returns the hosts that the requesting session must be allowed to reach.
	 *
	 * @return the hosts, in alphabetical order
	 */
	public Set<String> hosts()
	{
		return new TreeSet<>(missingHosts);
	}

	/**
	 * Returns the message that the launcher answers a request with.
	 *
	 * @param harnessName the display name of the requesting session's harness
	 * @param entries     the instruction that says which of the settings of the harness to add the {@link #writes()}
	 *                    and {@link #hosts()} to, or empty if there are none; a clause without a final period
	 * @return the reason, as sentences with a final period
	 * @throws NullPointerException if any of the arguments are null
	 */
	public String messageFor(String harnessName, String entries)
	{
		requireThat(harnessName, "harnessName").isNotNull();
		requireThat(entries, "entries").isNotNull();
		Set<Path> denials = new LinkedHashSet<>(deniedWrites.values());
		List<String> fixes = new ArrayList<>();
		if (!entries.isEmpty())
			fixes.add(entries);
		if (!denials.isEmpty())
		{
			List<String> names = new ArrayList<>();
			for (Path denial : denials)
				names.add(denial.toString());
			fixes.add("remove the denial of " + String.join(", ", names) + " from its settings");
		}
		return "CAT cannot start the session: this " + harnessName + " session's sandbox does not let its commands " +
			needs(missingWrites, deniedWrites, missingHosts) + ", which the requested session needs. To allow this, " +
			String.join(" and ", fixes) + ", then start a new " + harnessName + " session.";
	}
}

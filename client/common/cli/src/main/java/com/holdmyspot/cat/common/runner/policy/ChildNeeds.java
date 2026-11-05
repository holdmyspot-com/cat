package com.holdmyspot.cat.common.runner.policy;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The accesses that a child session's commands need, and the paths they must not read, before the parent's policy
 * bounds them.
 *
 * @param runFolder   the absolute folder that holds the child's run, which the launcher creates empty and owns
 * @param extraWrites the absolute paths outside the run folder that the commands need to write, such as dependency
 *                    caches
 * @param readDenials the absolute paths that the commands must not read, such as harness login files and the paths
 *                    that the request denies
 * @param hosts       the network hosts that the commands need to reach, as host names or literal IP addresses, in
 *                    lower case
 */
public record ChildNeeds(Path runFolder, List<Path> extraWrites, List<Path> readDenials, Set<String> hosts)
{
	/**
	 * Creates the needs of a child.
	 *
	 * @param runFolder   the absolute folder that holds the child's run, which the launcher creates empty and owns
	 * @param extraWrites the absolute paths outside the run folder that the commands need to write, such as dependency
	 *                    caches
	 * @param readDenials the absolute paths that the commands must not read, such as harness login files and the
	 *                    paths that the request denies
	 * @param hosts       the network hosts that the commands need to reach, as host names or literal IP addresses
	 * @throws NullPointerException     if any of the arguments are null, or a collection contains null
	 * @throws IllegalArgumentException if a path is not absolute, or a host is blank or is a pattern that starts with
	 *                                  {@code *}
	 */
	public ChildNeeds
	{
		requireThat(runFolder, "runFolder").isNotNull();
		if (!runFolder.isAbsolute())
			throw new IllegalArgumentException("runFolder must be absolute: " + runFolder);
		extraWrites = ParentPolicy.absolutePaths(extraWrites, "extraWrites");
		readDenials = ParentPolicy.absolutePaths(readDenials, "readDenials");
		requireThat(hosts, "hosts").isNotNull();
		Set<String> lowerCase = new LinkedHashSet<>();
		for (String host : hosts)
		{
			requireThat(host, "host").isNotBlank();
			if (host.strip().startsWith("*"))
				throw new IllegalArgumentException("hosts must name hosts, not patterns: " + host);
			lowerCase.add(host.strip().toLowerCase(Locale.ROOT));
		}
		hosts = Set.copyOf(lowerCase);
	}
}

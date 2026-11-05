package com.holdmyspot.cat.common.runner.policy;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The network hosts that a session's sandboxed commands may reach: none, every host, or the hosts that a list of
 * patterns names.
 * <p>
 * A pattern is either a host name, which matches that host only, or {@code *.} followed by a domain, which matches
 * every host below that domain but not the domain itself. Host names compare without regard to case.
 */
public sealed interface HostAccess
{
	/**
	 * The access of a session whose commands may reach no host.
	 */
	HostAccess NONE = new NoHosts();
	/**
	 * The access of a session whose commands may reach every host.
	 */
	HostAccess ALL = new AllHosts();

	/**
	 * Returns the access of a session whose commands may reach the hosts that some patterns name.
	 *
	 * @param patterns the patterns, in the form that {@link HostAccess} describes
	 * @return the access
	 * @throws NullPointerException     if {@code patterns} is null or contains null
	 * @throws IllegalArgumentException if a pattern is blank
	 */
	static HostAccess listed(Set<String> patterns)
	{
		return new ListedHosts(patterns);
	}

	/**
	 * Indicates whether the session's commands may reach a host.
	 *
	 * @param host a host name or a literal IP address
	 * @return {@code true} if the access lets the commands reach {@code host}
	 * @throws NullPointerException     if {@code host} is null
	 * @throws IllegalArgumentException if {@code host} is blank
	 */
	boolean allows(String host);

	/**
	 * Indicates whether the session's commands may reach every host that a pattern matches.
	 *
	 * @param pattern a pattern in the form that {@link HostAccess} describes
	 * @return {@code true} if the access lets the commands reach every host that {@code pattern} matches
	 * @throws NullPointerException     if {@code pattern} is null
	 * @throws IllegalArgumentException if {@code pattern} is blank
	 */
	boolean covers(String pattern);

	/**
	 * The access of a session whose commands may reach no host.
	 */
	record NoHosts() implements HostAccess
	{
		@Override
		public boolean allows(String host)
		{
			requireThat(host, "host").isNotBlank();
			return false;
		}

		@Override
		public boolean covers(String pattern)
		{
			requireThat(pattern, "pattern").isNotBlank();
			return false;
		}
	}

	/**
	 * The access of a session whose commands may reach every host.
	 */
	record AllHosts() implements HostAccess
	{
		@Override
		public boolean allows(String host)
		{
			requireThat(host, "host").isNotBlank();
			return true;
		}

		@Override
		public boolean covers(String pattern)
		{
			requireThat(pattern, "pattern").isNotBlank();
			return true;
		}
	}

	/**
	 * The access of a session whose commands may reach the hosts that some patterns name.
	 *
	 * @param patterns the patterns, in the form that {@link HostAccess} describes, in lower case
	 */
	record ListedHosts(Set<String> patterns) implements HostAccess
	{
		/**
		 * The prefix that makes a pattern match every host below the domain that follows it.
		 */
		private static final String SUBDOMAIN_PREFIX = "*.";

		/**
		 * Creates the access.
		 *
		 * @param patterns the patterns, in the form that {@link HostAccess} describes
		 * @throws NullPointerException     if {@code patterns} is null or contains null
		 * @throws IllegalArgumentException if a pattern is blank
		 */
		public ListedHosts
		{
			requireThat(patterns, "patterns").isNotNull();
			Set<String> lowerCase = new LinkedHashSet<>();
			for (String pattern : patterns)
			{
				requireThat(pattern, "pattern").isNotBlank();
				lowerCase.add(pattern.strip().toLowerCase(Locale.ROOT));
			}
			patterns = Set.copyOf(lowerCase);
		}

		@Override
		public boolean allows(String host)
		{
			requireThat(host, "host").isNotBlank();
			String name = host.strip().toLowerCase(Locale.ROOT);
			for (String pattern : patterns)
			{
				if (pattern.equals(name))
					return true;
				if (pattern.startsWith(SUBDOMAIN_PREFIX) && name.endsWith(pattern.substring(1)))
					return true;
			}
			return false;
		}

		@Override
		public boolean covers(String pattern)
		{
			requireThat(pattern, "pattern").isNotBlank();
			String normalized = pattern.strip().toLowerCase(Locale.ROOT);
			if (!normalized.startsWith(SUBDOMAIN_PREFIX))
				return allows(normalized);
			// "*.b.example.com" matches only hosts that "*.example.com" or "*.b.example.com" also match
			String domain = normalized.substring(SUBDOMAIN_PREFIX.length());
			for (String listed : patterns)
			{
				if (listed.equals(normalized))
					return true;
				if (listed.startsWith(SUBDOMAIN_PREFIX) && domain.endsWith(listed.substring(1)))
					return true;
			}
			return false;
		}
	}
}

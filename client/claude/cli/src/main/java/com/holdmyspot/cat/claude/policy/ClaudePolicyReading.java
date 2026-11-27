package com.holdmyspot.cat.claude.policy;

import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.nio.file.Path;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The sandbox policy that CAT read for a Claude Code session, with what it needs to read the policy again.
 *
 * @param policy             the policy
 * @param processStart       the {@link ProcessDescription#start() start} of the session's Claude Code process
 * @param sources            the settings files that existed when CAT read the policy
 * @param portLimitedDomains the allowed domains that the policy leaves out because they limit a host to one port, as
 *                           the settings spell them, such as {@code 127.0.0.1:2455}: a session that CAT bounds by the
 *                           policy reaches a host on every port
 */
public record ClaudePolicyReading(ParentPolicy policy, String processStart, Set<Path> sources,
	Set<String> portLimitedDomains)
{
	/**
	 * Creates a reading.
	 *
	 * @param policy             the policy
	 * @param processStart       the {@link ProcessDescription#start() start} of the session's Claude Code process
	 * @param sources            the settings files that existed when CAT read the policy
	 * @param portLimitedDomains the allowed domains that the policy leaves out because they limit a host to one port
	 * @throws NullPointerException if any of the arguments are null, or a set contains null
	 */
	public ClaudePolicyReading
	{
		requireThat(policy, "policy").isNotNull();
		requireThat(processStart, "processStart").isNotNull();
		sources = Set.copyOf(sources);
		portLimitedDomains = Set.copyOf(portLimitedDomains);
	}
}

package com.holdmyspot.cat.claude.policy;

import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The sandbox policy that CAT read for a Claude Code session, with what it needs to read the policy again.
 *
 * @param policy       the policy
 * @param processStart the instant that the session's Claude Code process started
 * @param sources      the settings files that existed when CAT read the policy
 */
public record ClaudePolicyReading(ParentPolicy policy, Instant processStart, Set<Path> sources)
{
	/**
	 * Creates a reading.
	 *
	 * @param policy       the policy
	 * @param processStart the instant that the session's Claude Code process started
	 * @param sources      the settings files that existed when CAT read the policy
	 * @throws NullPointerException if any of the arguments are null, or {@code sources} contains null
	 */
	public ClaudePolicyReading
	{
		requireThat(policy, "policy").isNotNull();
		requireThat(processStart, "processStart").isNotNull();
		sources = Set.copyOf(sources);
	}
}

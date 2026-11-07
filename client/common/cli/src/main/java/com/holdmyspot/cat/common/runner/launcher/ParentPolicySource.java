package com.holdmyspot.cat.common.runner.launcher;

import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the sandbox policy of the harness session that a session launcher serves, from that harness's own settings
 * and command line.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
public interface ParentPolicySource
{
	/**
	 * Returns the name of the session's harness as its user knows it, which refusals name.
	 *
	 * @return a product name such as "Claude Code"
	 */
	String harnessName();

	/**
	 * Returns the entries that the user adds to the harness's settings so that the session's sandbox lets its commands
	 * write some paths and reach some hosts, which a refusal quotes.
	 *
	 * @param writes the absolute paths that the commands may not write, or none
	 * @param hosts  the hosts that the commands may not reach, or none
	 * @return an instruction that starts with {@code add} and has no final period, which names each setting and
	 *         quotes each path and host that goes into it
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if both {@code writes} and {@code hosts} are empty
	 */
	String permissionEntries(List<Path> writes, Set<String> hosts);

	/**
	 * Reads the sandbox policy that the session gives the commands of one of its threads, as it is now.
	 *
	 * @param askingThread the identifier that the harness gives the thread that asks, as its request names it, or
	 *                     empty for the session's main thread. An identifier that names no thread of the session
	 *                     gives the main thread's policy, as does any identifier in a harness whose threads share
	 *                     the session's policy.
	 * @return the policy
	 * @throws NullPointerException if {@code askingThread} is null
	 * @throws IOException          if a source of the policy cannot be read
	 * @throws PolicyRefusal        if the policy cannot be established safely
	 */
	ParentPolicy read(Optional<String> askingThread) throws IOException, PolicyRefusal;
}

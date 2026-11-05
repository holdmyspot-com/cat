package com.holdmyspot.cat.common.runner.launcher;

import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;

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
	 * Reads the session's sandbox policy as it is now.
	 *
	 * @return the policy
	 * @throws IOException   if a source of the policy cannot be read
	 * @throws PolicyRefusal if the policy cannot be established safely
	 */
	ParentPolicy read() throws IOException, PolicyRefusal;
}

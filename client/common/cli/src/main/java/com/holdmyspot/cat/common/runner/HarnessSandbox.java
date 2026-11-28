package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Platform;

import java.util.Optional;

/**
 * Decides whether a coding harness can run a prompt optimizer's trials on a platform. Each harness module supplies
 * the decision for its own harness.
 */
@FunctionalInterface
public interface HarnessSandbox
{
	/**
	 * Returns why the harness cannot run a prompt optimizer's trials on a platform.
	 *
	 * @param platform the platform that the trials would run on
	 * @return the reason, which names what is missing and what to do instead, or empty if the harness can run the
	 *   trials on {@code platform}
	 * @throws NullPointerException if {@code platform} is null
	 */
	Optional<String> refusal(Platform platform);
}

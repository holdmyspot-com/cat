package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessSandbox;

import java.util.Optional;

/**
 * Decides on which platforms CAT starts a Codex session, as {@link HarnessSandbox} describes. Codex confines a
 * session in a sandbox on Linux and macOS. It has a sandbox on native Windows too, but CAT starts no Codex session
 * there until it has verified that this sandbox denies the session the reads that CAT denies, keeps its writes inside
 * the paths that CAT allows, and lets it reach no host other than its model's.
 */
public final class CodexSessionSandbox
{
	/**
	 * Returns why CAT refuses to start a Codex session on a platform.
	 *
	 * @param platform the platform that the session would run on
	 * @return the reason, or empty if CAT starts Codex sessions on {@code platform}
	 * @throws NullPointerException if {@code platform} is null
	 */
	public static Optional<String> refusal(Platform platform)
	{
		requireThat(platform, "platform").isNotNull();
		if (platform.isWindows())
		{
			return Optional.of(HarnessSandbox.nativeWindowsRefusal("Codex", "CAT has not yet verified that Codex's " +
				"sandbox on native Windows denies a session the reads that CAT denies, keeps its writes inside the paths " +
				"that CAT allows, and lets it reach no host other than its model's"));
		}
		return Optional.empty();
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexSessionSandbox()
	{
	}
}

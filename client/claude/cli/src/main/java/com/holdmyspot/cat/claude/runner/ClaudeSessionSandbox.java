package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessSandbox;

import java.util.Optional;

/**
 * Decides on which platforms CAT starts a Claude session, as {@link HarnessSandbox} describes. Claude Code confines a
 * session's commands in a sandbox on Linux and macOS, and has none on native Windows.
 */
public final class ClaudeSessionSandbox
{
	/**
	 * Returns why CAT refuses to start a Claude session on a platform.
	 *
	 * @param platform the platform that the session would run on
	 * @return the reason, or empty if CAT starts Claude sessions on {@code platform}
	 * @throws NullPointerException if {@code platform} is null
	 */
	public static Optional<String> refusal(Platform platform)
	{
		requireThat(platform, "platform").isNotNull();
		if (platform.isWindows())
		{
			return Optional.of(HarnessSandbox.nativeWindowsRefusal("Claude Code",
				"Claude Code has no sandbox on native Windows"));
		}
		return Optional.empty();
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeSessionSandbox()
	{
	}
}

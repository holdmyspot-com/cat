package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessSandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides where CAT starts a Claude session, as {@link HarnessSandbox} describes, and which sessions it starts without
 * a sandbox. Claude Code confines a session's commands in a sandbox on Linux and macOS, and has none on native Windows.
 * A session whose model is offered only {@link #READ_ONLY_TOOLS} has no command to confine, so it runs without a
 * sandbox, and the platform decision does not apply to it.
 */
public final class ClaudeSessionSandbox
{
	/**
	 * The tools that read and search files, and the only tools that a session that runs without a sandbox may be
	 * offered: none of them runs a command or writes a file, so Claude Code's permission rules, which govern them
	 * without a sandbox, are the whole of what keeps them out of the paths that the session must not read.
	 */
	public static final List<String> READ_ONLY_TOOLS = List.of("Read", "Grep", "Glob");

	/**
	 * Returns why CAT refuses to start a session that runs inside a sandbox on a platform.
	 *
	 * @param platform the platform that the session would run on
	 * @return the reason, or empty if CAT starts sandboxed Claude sessions on {@code platform}
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
	 * Returns why CAT refuses to start a session without a sandbox, given the tools that its model is offered.
	 *
	 * @param tools the only tools that the session's model is offered, or none if it is offered every tool
	 * @return the reason, which names each tool beyond {@link #READ_ONLY_TOOLS}, or empty if the session may run
	 *   without a sandbox
	 * @throws NullPointerException if {@code tools} is null
	 */
	public static Optional<String> unsandboxedRefusal(List<String> tools)
	{
		requireThat(tools, "tools").isNotNull();
		String offered;
		if (tools.isEmpty())
			offered = "every tool";
		else
		{
			List<String> beyond = new ArrayList<>();
			for (String tool : tools)
			{
				if (!READ_ONLY_TOOLS.contains(tool))
					beyond.add(tool);
			}
			if (beyond.isEmpty())
				return Optional.empty();
			offered = String.join(", ", beyond);
		}
		return Optional.of("CAT starts a Claude Code session without a sandbox only when its model is offered no tool " +
			"beyond " + String.join(", ", READ_ONLY_TOOLS) + ", but this session's model is offered " + offered +
			", so CAT does not start the session.");
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeSessionSandbox()
	{
	}
}

package com.holdmyspot.cat.claude.installer.test;

import com.holdmyspot.cat.common.installer.fakes.test.HarnessProgram;
import java.nio.file.Path;

/**
 * Creates the stateful Claude Code program answers used by harness tests.
 */
public final class ClaudeProgramFactory implements HarnessProgram.Factory
{
	/**
	 * Creates the factory.
	 */
	public ClaudeProgramFactory()
	{
	}

	@Override
	public String harness()
	{
		return "claude";
	}

	@Override
	public HarnessProgram.Answers answers(Path home)
	{
		return ClaudeProgram.answers(home);
	}
}

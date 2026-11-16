package com.holdmyspot.cat.codex.installer.test;

import com.holdmyspot.cat.common.installer.fakes.test.HarnessProgram;
import java.nio.file.Path;

/**
 * Creates the stateful Codex program answers used by harness tests.
 */
public final class CodexProgramFactory implements HarnessProgram.Factory
{
	/**
	 * Creates the factory.
	 */
	public CodexProgramFactory()
	{
	}

	@Override
	public String harness()
	{
		return "codex";
	}

	@Override
	public HarnessProgram.Answers answers(Path home)
	{
		return CodexProgram.answers(home);
	}
}

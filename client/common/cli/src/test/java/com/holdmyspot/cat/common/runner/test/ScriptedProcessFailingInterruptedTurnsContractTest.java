package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.contract.test.HarnessProcessContract;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess.InterruptedTurn;

/**
 * Runs {@link HarnessProcessContract} on {@link ScriptedProcess} when it fails a turn that the caller interrupts, as
 * Claude does.
 */
public final class ScriptedProcessFailingInterruptedTurnsContractTest extends ScriptedProcessContractTest
{
	/**
	 * Creates the test fixture.
	 */
	public ScriptedProcessFailingInterruptedTurnsContractTest()
	{
		super(InterruptedTurn.FAILS);
	}
}

package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.contract.test.HarnessProcessContract;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess.InterruptedTurn;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs {@link HarnessProcessContract} on {@link ScriptedProcess}, the double that tests use in place of a harness,
 * when it completes a turn that the caller interrupts, as Codex does.
 */
public class ScriptedProcessContractTest extends HarnessProcessContract
{
	private final InterruptedTurn interruptedTurn;

	/**
	 * Creates the test fixture.
	 */
	public ScriptedProcessContractTest()
	{
		this(InterruptedTurn.COMPLETES);
	}

	/**
	 * Creates the test fixture for a process that ends an interrupted turn a given way.
	 *
	 * @param interruptedTurn how the process ends a turn that the caller interrupts
	 */
	protected ScriptedProcessContractTest(InterruptedTurn interruptedTurn)
	{
		this.interruptedTurn = interruptedTurn;
	}

	@Override
	protected Harness start(List<Turn> script, Path directory)
	{
		List<String> received = new CopyOnWriteArrayList<>();
		ScriptedProcess process;
		Path transcript;
		if (directory == null)
		{
			process = new ScriptedProcess(script, received, interruptedTurn);
			transcript = null;
		}
		else
		{
			Path home = directory.resolve("home");
			transcript = home.resolve("session-1.jsonl");
			process = new ScriptedProcess(script, received, interruptedTurn, transcript,
				new HarnessSessionTranscripts(home, directory.resolve("copies"), name -> name.endsWith(".jsonl")));
		}
		return new Harness()
		{
			@Override
			public HarnessProcess process()
			{
				return process;
			}

			@Override
			public List<String> printed(int turn)
			{
				return script.get(turn).events();
			}

			@Override
			public List<String> received()
			{
				return List.copyOf(received);
			}

			@Override
			public Path transcript()
			{
				return transcript;
			}
		};
	}

	@Override
	protected boolean steers()
	{
		return true;
	}

	@Override
	protected boolean completesInterruptedTurn()
	{
		return interruptedTurn == InterruptedTurn.COMPLETES;
	}
}

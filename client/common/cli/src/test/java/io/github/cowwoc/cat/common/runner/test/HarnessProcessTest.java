package io.github.cowwoc.cat.common.runner.test;

import io.github.cowwoc.cat.common.runner.HarnessProcess;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import java.io.IOException;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests the repeated-request and closure behavior of {@link HarnessProcess}.
 */
public final class HarnessProcessTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessProcessTest()
	{
	}
	/**
	 * Verifies a live process accepts multiple turns before completion.
	 *
	 * @throws IOException if the fake process fails
	 */
	@Test
	public void acceptsMultipleTurnsBeforeClosing() throws IOException
	{
		try (FakeProcess process = new FakeProcess())
		{
			requireThat(process.sendTurn("first").standardOutput(), "firstOutput").isEqualTo("first");
			requireThat(process.sendTurn("second").standardOutput(), "secondOutput").isEqualTo("second");
			requireThat(process.isAlive(), "aliveBeforeClose").isTrue();
			process.finish();
			requireThat(process.isAlive(), "aliveAfterClose").isFalse();
		}
	}

	private static final class FakeProcess implements HarnessProcess
	{
		private boolean alive = true;

		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			assert request != null;
			if (!alive)
				throw new IllegalStateException("Process is closed");
			return new HarnessProcessResult(0, request, "");
		}

		@Override
		public boolean isAlive()
		{
			return alive;
		}

		@Override
		public void close()
		{
			alive = false;
		}

		/**
		 * Simulates graceful completion requested by the bridge client.
		 */
		private void finish()
		{
			alive = false;
		}
	}
}

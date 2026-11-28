package com.holdmyspot.cat.codex.optimizer.test;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.execution.contract.test.DriverReproductionContract;
import org.testng.annotations.Test;

import java.io.IOException;

/**
 * Exercises the shared output-limit reproduction scenario with Codex as the selected harness identity.
 */
public final class CodexReproductionTest
{
	/**
	 * Runs the shared reproduction scenario for Codex.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void reproducesWithOutputLimit() throws IOException
	{
		DriverReproductionContract.reproducesWithOutputLimit(Harness.CODEX);
	}
}

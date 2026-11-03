package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.runner.HarnessProcessLiveness;
import com.holdmyspot.cat.common.runner.HarnessRunnerMaintenance;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;

/**
 * Exposes {@link HarnessRunnerMaintenance} execution with a supplied process liveness service for tests.
 */
@FunctionalInterface
public interface HarnessRunnerMaintenanceAccess
{
	/**
	 * Executes one maintenance command for the selected harness, asking a liveness service whether each run's owner
	 * and harness processes are alive.
	 *
	 * @param scope           the process scope, whose lock coordinator and waiting the run manager uses
	 * @param args            the lifecycle command and its options
	 * @param harness         the non-blank harness identifier used to select owned runs
	 * @param temporaryRoot   where the runs are kept when the command line names no {@code --run-root}
	 * @param environment     the parent environment used to verify the dependency cache
	 * @param out             receives the verified cache path; other operations produce no output
	 * @param processLiveness the exact-process liveness service for the runs' owner and harness processes
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if the command is invalid, if {@code harness} is blank, or if the operation
	 *                                  acts on runs while the command line names no {@code --run-root} outside a
	 *                                  harness session
	 * @throws IOException              if run maintenance or dependency-cache verification fails
	 */
	void execute(JvmScope scope, String[] args, String harness, HarnessTemporaryRoot temporaryRoot,
		Map<String, String> environment, PrintStream out, HarnessProcessLiveness processLiveness) throws IOException;
}

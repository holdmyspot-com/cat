package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.execution.LeftoverRuns;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.runner.HarnessProcessLiveness;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes {@link LeftoverRuns} construction with a supplied owner liveness service for tests.
 */
@FunctionalInterface
public interface LeftoverRunsAccess
{
	/**
	 * Creates a finder that asks a liveness service whether each owner process is alive.
	 *
	 * @param sessions the session port that declares the run roots
	 * @param json     the JSON mapper that parses run manifests
	 * @param liveness answers whether a recorded owner process is alive
	 * @return the finder
	 * @throws NullPointerException if any of the arguments are null
	 */
	LeftoverRuns create(SessionPort sessions, JsonMapper json, HarnessProcessLiveness liveness);
}

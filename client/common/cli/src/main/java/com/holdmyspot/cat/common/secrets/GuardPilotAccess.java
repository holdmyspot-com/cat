package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.execution.GuardPilot;
import com.holdmyspot.cat.common.optimizer.execution.JudgeProtocol;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.WorkspacePort;

import java.nio.file.Path;

import tools.jackson.databind.json.JsonMapper;

/**
 * Exposes construction of a {@link GuardPilot} with an explicit project-directory remover.
 */
@FunctionalInterface
public interface GuardPilotAccess
{
	/**
	 * Creates a guard pilot whose passing trial project directories are removed by the supplied collaborator.
	 *
	 * @param sessions starts the trial and judge sessions
	 * @param workspace stages the control source that the trials work in
	 * @param target the normalized relative path of the target prompt
	 * @param judge writes the judge requests and reads their verdicts
	 * @param schema writes the definition of each prompt test
	 * @param json reads and writes the hook configurations
	 * @param ceiling the maximum number of sessions that run at the same time
	 * @param directoryRemoval deletes project directories of passing trials
	 * @return the guard pilot
	 * @throws NullPointerException if any required argument is null
	 * @throws IllegalArgumentException if {@code target} is absolute or {@code ceiling} is not positive
	 */
	GuardPilot create(SessionPort sessions, WorkspacePort workspace, Path target, JudgeProtocol judge,
		ProposalSchema schema, JsonMapper json, int ceiling, GuardPilot.DirectoryRemoval directoryRemoval);
}

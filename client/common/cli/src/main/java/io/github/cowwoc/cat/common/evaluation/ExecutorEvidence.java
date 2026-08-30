package io.github.cowwoc.cat.common.evaluation;

import java.nio.file.Path;
import tools.jackson.databind.node.ObjectNode;

/**
 * Retains one executor result until its independent validator records a terminal verdict.
 *
 * @param trial the claimed whole-prompt trial
 * @param caseId the stable testcase identifier
 * @param testCase the testcase contract
 * @param prompt the testcase prompt
 * @param retainedProject the executor's retained project
 * @param rawResult the executor's retained protocol output
 * @param checkResult the optional deterministic-check result
 * @param timing the measured executor and validator durations, when available
 * @param terminallyRecorded whether a deterministic check already recorded the testcase outcome
 */
public record ExecutorEvidence(int trial, String caseId, ObjectNode testCase, Path prompt, Path retainedProject,
	Path rawResult, Path checkResult, WorkerTiming timing, boolean terminallyRecorded)
{
}

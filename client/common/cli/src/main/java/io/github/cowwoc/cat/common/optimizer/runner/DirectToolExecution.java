package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.absolute;
import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.check;
import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.positive;

/**
 * Immutable DirectToolExecution observation at a native runner boundary.
 *
 * @param conversationId the recorded conversationId
 * @param eventOrdinal the recorded eventOrdinal
 * @param toolId the recorded toolId
 * @param executablePath the recorded executablePath
 * @param exposedVersion the recorded exposedVersion
 * @param invocationEvidence the recorded invocationEvidence
 */
public record DirectToolExecution(String conversationId,
	long eventOrdinal,
	String toolId,
	Path executablePath,
	Optional<String> exposedVersion,
	EvidenceRef invocationEvidence)
{
    /**
     * Validates the observed fields.
     */
    public DirectToolExecution
    {
		check(conversationId, "conversationId");
		positive(eventOrdinal);
		check(toolId, "toolId");
		absolute(executablePath);
		Objects.requireNonNull(exposedVersion, "exposedVersion");
		exposedVersion.ifPresent(value -> check(value, "exposedVersion"));
		Objects.requireNonNull(invocationEvidence, "invocationEvidence");
    }
}

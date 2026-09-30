package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.check;
import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.positive;
import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.relative;

/**
 * Records a native prompt-source path observation without claiming which bytes the host consumed.
 * Repeated events for the same path remain separate observations.
 *
 * @param conversationId the recorded conversationId
 * @param eventOrdinal the recorded eventOrdinal
 * @param sourceId the recorded sourceId
 * @param normalizedRelativePath the recorded normalizedRelativePath
 * @param origin the recorded origin
 * @param loadMode the recorded loadMode
 * @param parentPath the recorded parentPath
 * @param loadReason the recorded loadReason
 * @param triggerPath the recorded triggerPath
 * @param nativeEventEvidence the recorded nativeEventEvidence
 */
public record RuntimePromptSource(String conversationId,
	long eventOrdinal,
	String sourceId,
	Path normalizedRelativePath,
	PromptSourceOrigin origin,
	Optional<PromptLoadMode> loadMode,
	Optional<Path> parentPath,
	Optional<String> loadReason,
	Optional<Path> triggerPath,
	EvidenceRef nativeEventEvidence)
{
    /**
     * Validates the observed fields.
     */
    public RuntimePromptSource
    {
		check(conversationId, "conversationId");
		positive(eventOrdinal);
		check(sourceId, "sourceId");
		relative(normalizedRelativePath);
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(loadMode, "loadMode");
		Objects.requireNonNull(parentPath, "parentPath");
		parentPath.ifPresent(RunnerIdentity.ObservationRules::relative);
		Objects.requireNonNull(loadReason, "loadReason");
		loadReason.ifPresent(value -> check(value, "loadReason"));
		Objects.requireNonNull(triggerPath, "triggerPath");
		triggerPath.ifPresent(RunnerIdentity.ObservationRules::relative);
		Objects.requireNonNull(nativeEventEvidence, "nativeEventEvidence");
    }
}

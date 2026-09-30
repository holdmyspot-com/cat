package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Retains one native execution and its typed observations.
  *
 * @param harness the recorded harness
 * @param runId the recorded runId
 * @param executionProject the recorded executionProject
 * @param turnResults the recorded turnResults
 * @param nativeEventEvidence the recorded nativeEventEvidence
 * @param runnerIdentity the recorded runnerIdentity
 * @param observedModelProfile the recorded observedModelProfile
 * @param promptSources the recorded promptSources
 * @param directToolExecutions the recorded directToolExecutions
 * @param externalSourceExecutions the recorded externalSourceExecutions
 * @param coverage the recorded coverage
 */
public record NestedRunResult(String harness, String runId, Path executionProject, List<TurnResult> turnResults,
    List<NativeEventEvidence> nativeEventEvidence, RunnerIdentity runnerIdentity,
    ModelProfile observedModelProfile, List<RuntimePromptSource> promptSources,
    List<DirectToolExecution> directToolExecutions, List<ExternalSourceExecution> externalSourceExecutions,
    List<ObservationCoverage> coverage)
{
    /**
     * Validates event relationships and exact coverage of observation domains.
     */
    public NestedRunResult
    {
        RunnerIdentity.ObservationRules.check(harness, "harness");
        RunnerIdentity.ObservationRules.check(runId, "runId");
        RunnerIdentity.ObservationRules.absolute(executionProject);
        turnResults = List.copyOf(turnResults);
        nativeEventEvidence = List.copyOf(nativeEventEvidence);
        Objects.requireNonNull(runnerIdentity, "runnerIdentity");
        if (!harness.equals(runnerIdentity.harness()))
            throw new IllegalArgumentException("result harness differs from runner identity");
        Objects.requireNonNull(observedModelProfile, "observedModelProfile");
        promptSources = List.copyOf(promptSources);
        directToolExecutions = List.copyOf(directToolExecutions);
        externalSourceExecutions = List.copyOf(externalSourceExecutions);
        coverage = List.copyOf(coverage);
        if (turnResults.isEmpty() || nativeEventEvidence.isEmpty())
            throw new IllegalArgumentException("turns and native evidence must not be empty");
        String conversation = turnResults.getFirst().conversationId();
        Map<Long, EvidenceRef> events = new HashMap<>();
        long expectedEvent = 1;
        for (NativeEventEvidence event : nativeEventEvidence)
        {
            if (!event.conversationId().equals(conversation) || event.eventOrdinal() != expectedEvent)
                throw new IllegalArgumentException("native event ordinals must be contiguous in one conversation");
            events.put(event.eventOrdinal(), event.evidence());
            expectedEvent = expectedEvent + 1;
        }
        int expectedTurn = 1;
        for (TurnResult turn : turnResults)
        {
            if (turn.turnOrdinal() != expectedTurn || !turn.conversationId().equals(conversation))
                throw new IllegalArgumentException("turns must be ordered in one conversation");
            observed(events, turn.nativeTurnEvent().eventOrdinal(), turn.nativeTurnEvent().evidence());
            expectedTurn = expectedTurn + 1;
        }
        long previous = 0;
        for (RuntimePromptSource source : promptSources)
        {
            if (!source.conversationId().equals(conversation) || source.eventOrdinal() <= previous)
                throw new IllegalArgumentException("prompt observations must follow event order");
            observed(events, source.eventOrdinal(), source.nativeEventEvidence());
            previous = source.eventOrdinal();
        }
        previous = 0;
        for (DirectToolExecution tool : directToolExecutions)
        {
            if (!tool.conversationId().equals(conversation) || tool.eventOrdinal() <= previous)
                throw new IllegalArgumentException("tool observations must follow event order");
            observed(events, tool.eventOrdinal(), tool.invocationEvidence());
            previous = tool.eventOrdinal();
        }
        previous = 0;
        for (ExternalSourceExecution source : externalSourceExecutions)
        {
            if (!source.runId().equals(runId) || !source.conversationId().equals(conversation) ||
                source.eventOrdinal() <= previous)
                throw new IllegalArgumentException("external observations must follow event order");
            observed(events, source.eventOrdinal(), source.nativeEventEvidence());
            previous = source.eventOrdinal();
        }
        Set<ObservationDomain> domains = EnumSet.noneOf(ObservationDomain.class);
        for (ObservationCoverage item : coverage)
        {
            if (!domains.add(item.domain()))
                throw new IllegalArgumentException("duplicate coverage domain");
        }
        if (!domains.equals(EnumSet.allOf(ObservationDomain.class)))
            throw new IllegalArgumentException("coverage must include every observation domain");
        if (promptSources.isEmpty() && coverage.stream().anyMatch(item ->
            item.domain() == ObservationDomain.PROMPT_SOURCE &&
                item.status() == ObservationCoverageStatus.COMPLETE))
            throw new IllegalArgumentException("missing native path events require limited prompt-source coverage");
    }

    /**
     * observed at the native runner boundary.
     */
    private static void observed(Map<Long, EvidenceRef> events, long ordinal, EvidenceRef reference)
    {
        if (!reference.equals(events.get(ordinal)))
            throw new IllegalArgumentException("typed observation has no matching native event");
    }
}

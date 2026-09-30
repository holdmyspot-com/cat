package io.github.cowwoc.cat.common.optimizer.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests native-event linkage without inferring file bytes or load order from observed paths.
 */
public final class NestedRunResultTest
{
    /**
     * Creates a test instance.
     */
    public NestedRunResultTest()
    {
    }

    /**
     * Verifies repeated path events remain distinct observations.
     */
    @Test
    public void acceptsRepeatedObservedPath()
    {
        NestedRunResult result = result(events(), sources(), coverage());
        requireThat(result.promptSources().size(), "sourceEvents").isEqualTo(2);
        requireThat(result.promptSources().get(0).normalizedRelativePath(), "firstPath").
            isEqualTo(result.promptSources().get(1).normalizedRelativePath());
    }

    /**
     * Verifies a missing ordinal cannot leave a false complete event stream.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsNativeEventGap()
    {
        result(List.of(event(1), event(3)), sources(), coverage());
    }

    /**
     * Verifies duplicate native event ordinals cannot be counted twice.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsDuplicateNativeEvent()
    {
        result(List.of(event(1), event(1), event(3)), sources(), coverage());
    }

    /**
     * Verifies a re-ordered native event stream cannot be reported as one contiguous conversation.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsOutOfOrderNativeEvent()
    {
        result(List.of(event(1), event(3), event(2)), sources(), coverage());
    }

    /**
     * Verifies every observation domain has one explicit coverage state.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsMissingCoverageDomain()
    {
        result(events(), sources(), coverage().subList(0, 2));
    }

    /**
     * Verifies typed observations must point to the same retained native evidence.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsTypedEventMismatch()
    {
        RuntimePromptSource mismatched = new RuntimePromptSource("conversation", 1, "prompt",
            Path.of("prompt.md"), PromptSourceOrigin.TARGET, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), ref("events", "f"));
        result(events(), List.of(mismatched), coverage());
    }

    /**
     * Verifies a result cannot claim a different harness from its runner identity.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsRunnerHarnessMismatch()
    {
        NestedRunResult valid = result(events(), sources(), coverage());
        new NestedRunResult("different", valid.runId(), valid.executionProject(), valid.turnResults(),
            valid.nativeEventEvidence(), valid.runnerIdentity(), valid.observedModelProfile(),
            valid.promptSources(), valid.directToolExecutions(), valid.externalSourceExecutions(), valid.coverage());
    }

    /**
     * Verifies a runner with no native path event reports prompt-source coverage as limited.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsCompleteCoverageWithoutPathEvents()
    {
        result(events(), List.of(), coverage());
    }

    /**
     * Verifies optional load context is retained only when supplied with that native event.
     */
    @Test
    public void retainsObservedLoadContext()
    {
        RuntimePromptSource source = new RuntimePromptSource("conversation", 1, "prompt",
            Path.of("rules/child.md"), PromptSourceOrigin.PROJECT_RULE, Optional.of(PromptLoadMode.LAZY),
            Optional.of(Path.of("rules/parent.md")), Optional.of("conditional branch"),
            Optional.of(Path.of("rules/trigger.md")), event(1).evidence());
        NestedRunResult result = result(events(), List.of(source), coverage());
        requireThat(result.promptSources().getFirst().loadMode(), "loadMode").
            isEqualTo(Optional.of(PromptLoadMode.LAZY));
        requireThat(result.promptSources().getFirst().parentPath(), "parentPath").
            isEqualTo(Optional.of(Path.of("rules/parent.md")));
    }

    /**
     * Creates a native result fixture.
     *
     * @param nativeEvents the retained events
     * @param promptSources the typed path events
     * @param observations the coverage declarations
     * @return the resulting native observation
     */
    private static NestedRunResult result(List<NativeEventEvidence> nativeEvents,
        List<RuntimePromptSource> promptSources, List<ObservationCoverage> observations)
    {
        return new NestedRunResult("harness", "run", Path.of("copy").toAbsolutePath().normalize(),
            List.of(new TurnResult(1, "conversation", "response", event(3))), nativeEvents,
            new RunnerIdentity("harness", "version", "a".repeat(64), Optional.of("provider"), Map.of(), List.of()),
            new ModelProfile("model", Optional.empty(), Map.of()), promptSources, List.of(), List.of(), observations);
    }

    /**
     * Creates the retained native event sequence.
     *
     * @return three ordered native events
     */
    private static List<NativeEventEvidence> events()
    {
        return List.of(event(1), event(2), event(3));
    }

    /**
     * Creates one retained native event.
     *
     * @param ordinal the event ordinal
     * @return the native event
     */
    private static NativeEventEvidence event(int ordinal)
    {
        return new NativeEventEvidence("conversation", ordinal, ref("events", Integer.toString(ordinal)));
    }

    /**
     * Creates two observations of one path at different event ordinals.
     *
     * @return the path observations
     */
    private static List<RuntimePromptSource> sources()
    {
        return List.of(
            new RuntimePromptSource("conversation", 1, "prompt", Path.of("prompt.md"),
                PromptSourceOrigin.TARGET, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                event(1).evidence()),
            new RuntimePromptSource("conversation", 2, "prompt", Path.of("prompt.md"),
                PromptSourceOrigin.TARGET, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                event(2).evidence()));
    }

    /**
     * Creates complete coverage declarations for all domains.
     *
     * @return the coverage declarations
     */
    private static List<ObservationCoverage> coverage()
    {
        return List.of(
            new ObservationCoverage(ObservationDomain.PROMPT_SOURCE, ObservationCoverageStatus.COMPLETE,
                Optional.empty(), List.of()),
            new ObservationCoverage(ObservationDomain.DIRECT_TOOL, ObservationCoverageStatus.COMPLETE,
                Optional.empty(), List.of()),
            new ObservationCoverage(ObservationDomain.EXTERNAL_SOURCE, ObservationCoverageStatus.COMPLETE,
                Optional.empty(), List.of()));
    }

    /**
     * Creates a content address for one event.
     *
     * @param collection the evidence collection
     * @param symbol the repeated SHA digit
     * @return the event evidence reference
     */
    private static EvidenceRef ref(String collection, String symbol)
    {
        return new EvidenceRef(collection, symbol.repeat(64));
    }
}

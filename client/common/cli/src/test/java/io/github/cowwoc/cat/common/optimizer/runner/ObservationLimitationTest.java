package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests explicit limitations for unobserved measurement identity.
 */
public final class ObservationLimitationTest
{
    /**
     * Creates a test instance.
     */
    public ObservationLimitationTest()
    {
    }

    /**
     * Verifies an unknown provider identity requires a recorded limitation.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsUnknownProviderWithoutLimitation()
    {
        new RunnerIdentity("harness", "version", "a".repeat(64), Optional.empty(), Map.of(), List.of());
    }

    /**
     * Verifies an external source lacking a material response identifies that limitation.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void requiresExternalResponseLimitation()
    {
        new ExternalSourceExecution("source", "mcp", Optional.of("identity"), Optional.empty(),
            new EvidenceRef("events", "a".repeat(64)), "run", "conversation", 1,
            Instant.parse("2026-01-01T00:00:00Z"), Optional.empty());
    }

    /**
     * Verifies exposed harness configuration names are nonblank.
     */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsBlankHarnessSettingName()
    {
        new RunnerIdentity("harness", "version", "a".repeat(64), Optional.of("provider"),
            Map.of("", "value"), List.of());
    }
}

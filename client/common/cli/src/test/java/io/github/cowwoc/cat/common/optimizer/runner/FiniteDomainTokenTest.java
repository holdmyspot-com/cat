package io.github.cowwoc.cat.common.optimizer.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.CallKind;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests exact case-sensitive tokens at every finite native-SPI domain boundary.
 */
public final class FiniteDomainTokenTest
{
    /**
     * Creates a test instance.
     */
    public FiniteDomainTokenTest()
    {
    }

    /**
     * Verifies one exact public token for each finite domain.
     *
     * @param sample the accepted enum value
     */
    @Test(dataProvider = "accepted")
    public void acceptsExactToken(Enum<?> sample)
    {
        requireThat(parse(sample, sample.name()), "parsed").isEqualTo(sample);
    }

    /**
     * Verifies blank, padded, folded, aliased and unknown tokens are rejected.
     *
     * @param sample an accepted value from the domain
     * @param token an excluded token
     */
    @Test(dataProvider = "excluded", expectedExceptions = IllegalArgumentException.class)
    public void rejectsNonExactToken(Enum<?> sample, String token)
    {
        parse(sample, token);
    }

    /**
     * Verifies null is not a valid token for any finite domain.
     *
     * @param sample an accepted value from the domain
     */
    @Test(dataProvider = "accepted", expectedExceptions = NullPointerException.class)
    public void rejectsNullToken(Enum<?> sample)
    {
        parse(sample, null);
    }

    /**
     * Supplies one accepted value for each public finite domain.
     *
     * @return the accepted enum values
     */
    @DataProvider(name = "accepted")
    public Object[][] accepted()
    {
        return samples().stream().map(sample -> new Object[]{sample}).toArray(Object[][]::new);
    }

    /**
     * Supplies excluded tokens that differ in every relevant way from accepted tokens.
     *
     * @return the excluded tokens
     */
    @DataProvider(name = "excluded")
    public Object[][] excluded()
    {
        List<Object[]> cases = new ArrayList<>();
        for (Enum<?> sample : samples())
        {
            cases.add(new Object[]{sample, ""});
            cases.add(new Object[]{sample, " " + sample.name()});
            cases.add(new Object[]{sample, sample.name().toLowerCase(java.util.Locale.ROOT)});
            cases.add(new Object[]{sample, sample.name() + "_ALIAS"});
            cases.add(new Object[]{sample, "UNKNOWN_VALUE"});
        }
        return cases.toArray(Object[][]::new);
    }

    /**
     * Parses a raw token through its finite domain.
     *
     * @param sample one typed value from the domain
     * @param token the raw token
     * @return the parsed value
     */
    private static Enum<?> parse(Enum<?> sample, String token)
    {
        if (sample instanceof CallKind)
            return CallKind.valueOf(token);
        if (sample instanceof EvidencePurpose)
            return EvidencePurpose.valueOf(token);
        if (sample instanceof SessionMode)
            return SessionMode.valueOf(token);
        if (sample instanceof ProjectWorkspaceMode)
            return ProjectWorkspaceMode.valueOf(token);
        if (sample instanceof SterileWorkspaceKind)
            return SterileWorkspaceKind.valueOf(token);
        if (sample instanceof SterileWorkspaceInputRole)
            return SterileWorkspaceInputRole.valueOf(token);
        if (sample instanceof ObservationDomain)
            return ObservationDomain.valueOf(token);
        if (sample instanceof ObservationCoverageStatus)
            return ObservationCoverageStatus.valueOf(token);
        if (sample instanceof PromptSourceOrigin)
            return PromptSourceOrigin.valueOf(token);
        if (sample instanceof PromptLoadMode)
            return PromptLoadMode.valueOf(token);
        if (sample instanceof NestedRunFailureKind)
            return NestedRunFailureKind.valueOf(token);
        if (sample instanceof RetrySafety)
            return RetrySafety.valueOf(token);
        throw new IllegalArgumentException("unknown enum domain");
    }

    /**
     * Lists one example of each public finite domain.
     *
     * @return the domain examples
     */
    private static List<Enum<?>> samples()
    {
        return List.of(CallKind.CANDIDATE, EvidencePurpose.VALIDATION, SessionMode.SINGLE_TURN,
            ProjectWorkspaceMode.ACTIVE_WORKTREE, SterileWorkspaceKind.CONTRACT_DERIVATION,
            SterileWorkspaceInputRole.AUTHORITY, ObservationDomain.PROMPT_SOURCE,
            ObservationCoverageStatus.COMPLETE, PromptSourceOrigin.TARGET, PromptLoadMode.DIRECT,
            NestedRunFailureKind.START_FAILED, RetrySafety.PROVEN_OUTCOME_FREE);
    }
}

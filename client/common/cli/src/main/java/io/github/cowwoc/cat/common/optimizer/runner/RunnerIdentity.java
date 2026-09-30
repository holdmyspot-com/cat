package io.github.cowwoc.cat.common.optimizer.runner;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.check;
import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.hash;

/**
 * Immutable RunnerIdentity observation at a native runner boundary.
 *
 * @param harness the recorded harness
 * @param runnerVersion the recorded runnerVersion
 * @param runtimeManifestSha256 the recorded runtimeManifestSha256
 * @param providerIdentity the recorded providerIdentity
 * @param harnessConfiguration the recorded harnessConfiguration
 * @param limitations the recorded limitations
 */
public record RunnerIdentity(String harness,
	String runnerVersion,
	String runtimeManifestSha256,
	Optional<String> providerIdentity,
	Map<String, String> harnessConfiguration,
	List<String> limitations)
{
    /**
     * Validates the observed fields.
     */
    public RunnerIdentity
    {
		check(harness, "harness");
		check(runnerVersion, "runnerVersion");
		hash(runtimeManifestSha256);
		Objects.requireNonNull(providerIdentity, "providerIdentity");
		providerIdentity.ifPresent(value -> check(value, "providerIdentity"));
		harnessConfiguration = Map.copyOf(harnessConfiguration);
		for (String settingName : harnessConfiguration.keySet())
			check(settingName, "harness setting name");
		limitations = List.copyOf(limitations);
		for (String value : limitations) check(value, "limitation");
		if (providerIdentity.isEmpty() && limitations.isEmpty())
			throw new IllegalArgumentException("missing provider identity requires a limitation");
    }
    /**
     * Shared mechanical validation for native observations.
     */
    static final class ObservationRules
{
    /**
     * ObservationRules at the native runner boundary.
     */
    private ObservationRules()
    {
    }

    /**
     * check at the native runner boundary.
      *
      * @param value the value value
      * @param name the name value
     */
    static void check(String value, String name)
    {
        Objects.requireNonNull(value, name);
        if (value.isBlank())
            throw new IllegalArgumentException(name + " must not be blank");
    }

    /**
     * hash at the native runner boundary.
      *
      * @param value the value value
     */
    static void hash(String value)
    {
        Objects.requireNonNull(value, "hash");
        if (!value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("hash must be lowercase SHA-256");
    }

    /**
     * positive at the native runner boundary.
      *
      * @param value the value value
     */
    static void positive(long value)
    {
        if (value < 1)
            throw new IllegalArgumentException("event ordinal must be positive");
    }

    /**
     * relative at the native runner boundary.
      *
      * @param value the value value
     */
    static void relative(java.nio.file.Path value)
    {
        Objects.requireNonNull(value, "relative path");
        if (value.isAbsolute() || value.toString().isEmpty() || !value.equals(value.normalize()))
            throw new IllegalArgumentException("path must be normalized and relative");
    }

    /**
     * absolute at the native runner boundary.
      *
      * @param value the value value
     */
    static void absolute(java.nio.file.Path value)
    {
        Objects.requireNonNull(value, "absolute path");
        if (!value.isAbsolute() || !value.equals(value.normalize()))
            throw new IllegalArgumentException("path must be normalized and absolute");
    }
}
}

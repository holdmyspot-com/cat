package io.github.cowwoc.cat.distribution;

import io.github.cowwoc.cat.common.Platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines the closed edition, harness, and platform matrix for CAT's first release.
 */
public final class ReleaseManifest
{
	private static final List<ReleaseTarget> TARGETS = createTargets();

	/**
	 * Prevents construction of this static manifest owner.
	 */
	private ReleaseManifest()
	{
	}

	/**
	 * Returns every target across the two separately published editions.
	 *
	 * @return the sixteen immutable release targets
	 */
	public static List<ReleaseTarget> targets()
	{
		return TARGETS;
	}

	/**
	 * Returns every harness-platform target published in one edition's repository.
	 *
	 * @param edition the repository edition
	 * @return the eight immutable release targets
	 */
	public static List<ReleaseTarget> targets(Edition edition)
	{
		Objects.requireNonNull(edition, "edition must not be null");
		return TARGETS.stream().filter(target -> target.edition() == edition).toList();
	}

	/**
	 * Detects one exact supported target without substituting another platform.
	 *
	 * @param edition the installed edition
	 * @param harness the active harness
	 * @param operatingSystem the observed operating-system name
	 * @param architecture the observed hardware architecture
	 * @param wsl whether execution is inside Windows Subsystem for Linux
	 * @return the supported exact target
	 * @throws UnsupportedOperationException if the observed tuple is unsupported
	 */
	public static Detection detect(Edition edition, Harness harness, String operatingSystem, String architecture,
		boolean wsl)
	{
		Objects.requireNonNull(edition, "edition must not be null");
		Objects.requireNonNull(harness, "harness must not be null");
		String os = requireText(operatingSystem, "operatingSystem");
		String arch = requireText(architecture, "architecture");
		String normalizedOs = os.toLowerCase(Locale.ROOT);
		boolean windows = normalizedOs.startsWith("windows");
		if (windows && !wsl)
		{
			throw new UnsupportedOperationException("Native Windows is unsupported; install and run CAT inside WSL");
		}
		Platform platform;
		if (wsl)
		{
			if (!windows && !normalizedOs.equals("linux"))
				throw new UnsupportedOperationException("WSL was reported for a non-Windows environment: " + os);
			platform = Platform.detect("Linux", arch);
		}
		else
			platform = Platform.detect(os, arch);
		ReleaseTarget target = new ReleaseTarget(edition, harness, platform);
		if (!TARGETS.contains(target))
			throw new UnsupportedOperationException("Unsupported CAT release target: " + target.identifier());
		return new Detection(DetectionStatus.SUPPORTED, Optional.of(target), wsl);
	}

	/**
	 * Creates every first-release target in deterministic repository-directory order.
	 *
	 * @return the immutable target matrix
	 */
	private static List<ReleaseTarget> createTargets()
	{
		List<ReleaseTarget> result = new ArrayList<>();
		for (Edition edition : Edition.values())
		{
			for (Harness harness : Harness.values())
			{
				for (Platform platform : Platform.values())
					result.add(new ReleaseTarget(edition, harness, platform));
			}
		}
		result.sort(Comparator.comparing(ReleaseTarget::edition).thenComparing(ReleaseTarget::repositoryDirectory));
		return List.copyOf(result);
	}

	/**
	 * Validates open text.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireText(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Names the separately published CAT edition.
	 */
	public enum Edition
	{
		PERSONAL("personal"),
		COMMERCIAL("commercial");

		private final String externalValue;

		/**
		 * Creates one edition value.
		 *
		 * @param externalValue the manifest spelling
		 */
		Edition(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Returns the manifest spelling.
		 *
		 * @return the external value
		 */
		public String externalValue()
		{
			return externalValue;
		}
	}

	/**
	 * Names a supported agentic harness.
	 */
	public enum Harness
	{
		CLAUDE("claude"),
		CODEX("codex");

		private final String externalValue;

		/**
		 * Creates one harness value.
		 *
		 * @param externalValue the manifest spelling
		 */
		Harness(String externalValue)
		{
			this.externalValue = externalValue;
		}

		/**
		 * Returns the manifest spelling.
		 *
		 * @return the external value
		 */
		public String externalValue()
		{
			return externalValue;
		}

		/**
		 * Parses one exact untyped manifest value.
		 *
		 * @param value the manifest value
		 * @return the closed harness
		 */
		public static Harness fromExternalValue(String value)
		{
			Objects.requireNonNull(value, "value must not be null");
			for (Harness candidate : values())
			{
				if (candidate.externalValue.equals(value))
					return candidate;
			}
			throw new IllegalArgumentException("Unsupported CAT harness: " + value);
		}
	}

	/**
	 * Names the detection result.
	 */
	public enum DetectionStatus
	{
		SUPPORTED
	}

	/**
	 * Holds one exact release target.
	 *
	 * @param edition the separately published edition
	 * @param harness the harness
	 * @param platform the operating-system and architecture pair
	 */
	public record ReleaseTarget(Edition edition, Harness harness, Platform platform)
	{
		/**
		 * Validates one target.
		 *
		 * @param edition the separately published edition
		 * @param harness the harness
		 * @param platform the operating-system and architecture pair
		 */
		public ReleaseTarget
		{
			Objects.requireNonNull(edition, "edition must not be null");
			Objects.requireNonNull(harness, "harness must not be null");
			Objects.requireNonNull(platform, "platform must not be null");
		}

		/**
		 * Returns the canonical target identifier across both editions.
		 *
		 * @return the target identifier
		 */
		public String identifier()
		{
			return edition.externalValue + "/" + repositoryDirectory();
		}

		/**
		 * Returns the target directory relative to its edition's separate release repository.
		 *
		 * @return the repository-relative directory
		 */
		public String repositoryDirectory()
		{
			return harness.externalValue + "/" + repositoryPlatformDirectory(platform);
		}
	}

	/**
	 * Translates the runtime's historical archive identifier to the stable release-repository directory name.
	 *
	 * @param platform the target operating-system and architecture tuple
	 * @return the repository platform directory
	 */
	private static String repositoryPlatformDirectory(Platform platform)
	{
		return switch (platform)
		{
			case LINUX_X64 -> "linux-x86_64";
			case LINUX_ARM64 -> "linux-arm64";
			case MACOS_X64 -> "macos-x86_64";
			case MACOS_ARM64 -> "macos-arm64";
		};
	}

	/**
	 * Holds one successful local target detection.
	 *
	 * @param status the closed detection result
	 * @param target the exact supported target
	 * @param wsl whether the Linux target is running inside WSL
	 */
	public record Detection(DetectionStatus status, Optional<ReleaseTarget> target, boolean wsl)
	{
		/**
		 * Validates one detection.
		 *
		 * @param status the closed detection result
		 * @param target the exact supported target
		 * @param wsl whether the Linux target is running inside WSL
		 */
		public Detection
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(target, "target must not be null");
			if (status == DetectionStatus.SUPPORTED && target.isEmpty())
				throw new IllegalArgumentException("A supported detection requires an exact target");
		}
	}
}

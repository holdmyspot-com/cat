package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;

import java.util.Locale;

/**
 * A supported operating-system and CPU-architecture combination for CAT release archives.
 */
public enum Platform
{
	LINUX_X64("linux-x64"),
	LINUX_ARM64("linux-arm64"),
	MACOS_X64("macos-x64"),
	MACOS_ARM64("macos-arm64");

	/**
	 * Detects the current platform.
	 *
	 * @return the current platform
	 * @throws UnsupportedOperationException if the platform is unsupported
	 */
	public static Platform current()
	{
		return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
	}

	/**
	 * Detects a platform from operating-system properties.
	 *
	 * @param osName a non-blank operating-system name
	 * @param architecture a non-blank operating-system architecture
	 * @return the matching platform
	 * @throws NullPointerException if {@code osName} or {@code architecture} are null
	 * @throws IllegalArgumentException if {@code osName} or {@code architecture} are blank
	 * @throws UnsupportedOperationException if the platform is unsupported
	 */
	public static Platform detect(String osName, String architecture)
	{
		requireThat(osName, "osName").isNotBlank();
		requireThat(architecture, "architecture").isNotBlank();

		String normalizedOs = osName.toLowerCase(Locale.ROOT);
		boolean linux = normalizedOs.equals("linux");
		if (!linux && !normalizedOs.equals("mac os x") && !normalizedOs.equals("darwin"))
			throw new UnsupportedOperationException("Unsupported platform: os.name=" + osName + ", os.arch=" + architecture);

		String normalizedArchitecture = architecture.toLowerCase(Locale.ROOT);
		if (normalizedArchitecture.equals("amd64") || normalizedArchitecture.equals("x86_64"))
		{
			if (linux)
				return LINUX_X64;
			return MACOS_X64;
		}
		if (normalizedArchitecture.equals("aarch64") || normalizedArchitecture.equals("arm64"))
		{
			if (linux)
				return LINUX_ARM64;
			return MACOS_ARM64;
		}

		throw new UnsupportedOperationException("Unsupported platform: os.name=" + osName + ", os.arch=" + architecture);
	}

	private final String id;

	/**
	 * Creates a platform.
	 *
	 * @param id a non-blank identifier used in distribution filenames
	 */
	Platform(String id)
	{
		assert that(id, "id").isNotBlank().elseThrow();
		this.id = id;
	}

	/**
	 * Returns the identifier used in distribution filenames.
	 *
	 * @return the identifier
	 */
	public String id()
	{
		return id;
	}
}

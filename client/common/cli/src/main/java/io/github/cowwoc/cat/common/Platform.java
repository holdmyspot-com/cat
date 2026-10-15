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
	MACOS_ARM64("macos-arm64"),
	WINDOWS_X64("windows-x64"),
	WINDOWS_ARM64("windows-arm64");

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
		String normalizedArchitecture = architecture.toLowerCase(Locale.ROOT);
		Platform x64Platform = null;
		Platform arm64Platform = null;
		if (normalizedOs.equals("linux"))
		{
			x64Platform = LINUX_X64;
			arm64Platform = LINUX_ARM64;
		}
		else if (normalizedOs.equals("mac os x") || normalizedOs.equals("darwin"))
		{
			x64Platform = MACOS_X64;
			arm64Platform = MACOS_ARM64;
		}
		else if (normalizedOs.startsWith("windows"))
		{
			x64Platform = WINDOWS_X64;
			arm64Platform = WINDOWS_ARM64;
		}
		Platform platform = switch (normalizedArchitecture)
		{
			case "amd64", "x86_64" -> x64Platform;
			case "aarch64", "arm64" -> arm64Platform;
			default -> null;
		};
		if (platform != null)
			return platform;

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
	 * Returns whether the platform is Windows.
	 *
	 * @return {@code true} for Windows
	 */
	public boolean isWindows()
	{
		return this == WINDOWS_X64 || this == WINDOWS_ARM64;
	}

	/**
	 * Returns the environment variable that names the current user's home directory. Windows sets
	 * {@code USERPROFILE}, which Windows programs such as Codex and Claude Code read; only POSIX layers such as Git Bash
	 * and Cygwin set {@code HOME} there, so a program that a Windows program starts, such as a Codex hook, may lack it.
	 *
	 * @return {@code USERPROFILE} on Windows, otherwise {@code HOME}
	 */
	public String homeVariable()
	{
		if (isWindows())
			return "USERPROFILE";
		return "HOME";
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

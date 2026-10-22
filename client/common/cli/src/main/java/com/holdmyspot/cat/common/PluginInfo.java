package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Identifies a CAT plugin distribution for one supported AI coding harness and native platform.
 *
 * @param harness the supported harness identifier, such as {@code claude} or {@code codex}
 * @param version the CAT release version packaged in the distribution
 * @param platform the native platform targeted by the distribution
 */
public record PluginInfo(String harness, String version, Platform platform)
{
	/**
	 * Current CAT release version.
	 */
	public static final String CAT_VERSION = "1.0";
	/**
	 * The {@code repository} URL in CAT's plugin manifests, which identifies a plugin as CAT whatever name or
	 * marketplace the user installed it under.
	 */
	public static final String CAT_REPOSITORY = "https://github.com/holdmyspot-com/cat";

	/**
	 * Indicates whether a plugin manifest's {@code repository} is CAT's. The comparison ignores the letter case of the
	 * URL's scheme and host, and one trailing {@code /} and then one trailing {@code .git} of its path; every other
	 * part, including the path's letter case, must match.
	 *
	 * @param repository the manifest's {@code repository}, or {@code null} if it states none
	 * @return {@code true} if {@code repository} names {@link #CAT_REPOSITORY}; {@code false} if it is {@code null},
	 *   is not an absolute hierarchical URL with a host, or names another repository
	 */
	public static boolean isCatRepository(String repository)
	{
		if (repository == null)
			return false;
		Optional<String> comparable = comparableRepository(repository);
		return comparable.isPresent() && comparable.get().equals(comparableRepository(CAT_REPOSITORY).orElseThrow());
	}

	/**
	 * Returns the form of a repository URL that {@link #isCatRepository(String)} compares.
	 *
	 * @param repository the URL
	 * @return the URL with its scheme and host in lower case and one trailing {@code /} and then one trailing
	 *   {@code .git} removed from its path, or empty if it is not an absolute hierarchical URL with a host
	 */
	private static Optional<String> comparableRepository(String repository)
	{
		URI uri;
		try
		{
			uri = new URI(repository);
		}
		catch (URISyntaxException _)
		{
			// A value that is not a URL names no repository, so it cannot name CAT's
			return Optional.empty();
		}
		if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null)
			return Optional.empty();
		String path = uri.getRawPath();
		if (path.endsWith("/"))
			path = path.substring(0, path.length() - 1);
		if (path.endsWith(".git"))
			path = path.substring(0, path.length() - ".git".length());
		StringBuilder comparable = new StringBuilder(repository.length());
		comparable.append(uri.getScheme().toLowerCase(Locale.ROOT)).append("://");
		if (uri.getRawUserInfo() != null)
			comparable.append(uri.getRawUserInfo()).append('@');
		comparable.append(uri.getHost().toLowerCase(Locale.ROOT));
		if (uri.getPort() >= 0)
			comparable.append(':').append(uri.getPort());
		comparable.append(path);
		if (uri.getRawQuery() != null)
			comparable.append('?').append(uri.getRawQuery());
		if (uri.getRawFragment() != null)
			comparable.append('#').append(uri.getRawFragment());
		return Optional.of(comparable.toString());
	}

	/**
	 * Creates a CAT plugin distribution identity.
	 *
	 * @param harness the supported harness identifier
	 * @param version the CAT release version packaged in the distribution
	 * @param platform the native platform targeted by the distribution
	 * @throws NullPointerException if {@code harness}, {@code version}, or {@code platform} are null
	 * @throws IllegalArgumentException if {@code harness} or {@code version} are blank
	 */
	public PluginInfo
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(version, "version").isNotBlank();
		requireThat(platform, "platform").isNotNull();
	}

	/**
	 * Formats the harness identity for command-line output.
	 *
	 * @return the formatted identity
	 */
	public String toDisplayString()
	{
		return "harness=" + harness + "\nversion=" + version + "\nplatform=" + platform.id();
	}
}

package io.github.cowwoc.cat.distribution.license;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;

/**
 * A third-party component included in one CAT distribution and the license material that accompanies it.
 *
 * @param name the component's display name
 * @param origin the component's Maven coordinate and version, or the JAR that embeds it
 * @param licenseFile the canonical license-text filename under {@code licenses/}
 * @param jar the component JAR that supplies embedded notice material
 * @param noticeEntries the embedded JAR entries whose exact text belongs in {@code NOTICE.txt}
 */
public record ThirdPartyComponent(String name, String origin, String licenseFile, Path jar,
	List<String> noticeEntries)
{
	/**
	 * Validates and copies the component's legal-material description.
	 *
	 * @param name the component's display name
	 * @param origin the component's Maven coordinate and version, or the JAR that embeds it
	 * @param licenseFile the canonical license-text filename under {@code licenses/}
	 * @param jar the component JAR that supplies embedded notice material
	 * @param noticeEntries the embedded JAR entries whose exact text belongs in {@code NOTICE.txt}
	 * @throws NullPointerException if {@code name}, {@code origin}, {@code licenseFile}, {@code jar}, or
	 *         {@code noticeEntries} are null
	 * @throws IllegalArgumentException if {@code name}, {@code origin}, or {@code licenseFile} are blank
	 */
	public ThirdPartyComponent
	{
		requireThat(name, "name").isNotBlank();
		requireThat(origin, "origin").isNotBlank();
		requireThat(licenseFile, "licenseFile").isNotBlank();
		requireThat(jar, "jar").isNotNull();
		requireThat(noticeEntries, "noticeEntries").isNotNull();
		noticeEntries = List.copyOf(noticeEntries);
	}
}

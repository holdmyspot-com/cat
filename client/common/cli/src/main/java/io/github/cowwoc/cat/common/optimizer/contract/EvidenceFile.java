package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * One file in a control's project.
 *
 * @param path the normalized project-relative path, which uses {@code /} as the separator
 * @param content the file's text
 */
public record EvidenceFile(String path, String content)
{
	/**
	 * Creates a file.
	 *
	 * @param path the normalized project-relative path, which uses {@code /} as the separator
	 * @param content the file's text
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code path} is blank or is not a normalized project-relative path
	 */
	public EvidenceFile
	{
		requireThat(path, "path").isNotBlank();
		requireThat(content, "content").isNotNull();
		if (!ProjectPaths.isNormalized(path))
		{
			throw new IllegalArgumentException("Control file path '" + path + "' must be a normalized path that is " +
				"relative to the project and uses / as the separator");
		}
	}
}

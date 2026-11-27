package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * A folder that a harness's sandbox lets its commands write, and the setting that grants the write.
 *
 * @param path    the absolute folder, with everything below it
 * @param setting the grant as the user knows it, such as {@code sandbox.filesystem.allowWrite in <settings file>}
 */
public record WriteGrant(Path path, String setting)
{
	/**
	 * Creates a grant.
	 *
	 * @param path    the absolute folder, with everything below it
	 * @param setting the grant as the user knows it
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code path} is relative or {@code setting} is blank
	 */
	public WriteGrant
	{
		requireThat(path, "path").isAbsolute();
		requireThat(setting, "setting").isNotBlank();
	}
}

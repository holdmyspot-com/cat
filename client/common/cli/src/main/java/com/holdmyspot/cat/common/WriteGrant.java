package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * A folder that a harness's sandbox lets its commands write, or may let them write, and the setting that grants the
 * write.
 *
 * @param path     the absolute folder, with everything below it
 * @param setting  the grant as the user knows it, such as {@code sandbox.filesystem.allowWrite in <settings file>}
 * @param possible {@code true} if CAT cannot tell for certain that the sandbox applies the grant
 */
public record WriteGrant(Path path, String setting, boolean possible)
{
	/**
	 * Creates a grant.
	 *
	 * @param path     the absolute folder, with everything below it
	 * @param setting  the grant as the user knows it
	 * @param possible {@code true} if CAT cannot tell for certain that the sandbox applies the grant
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code path} is relative or {@code setting} is blank
	 */
	public WriteGrant
	{
		requireThat(path, "path").isAbsolute();
		requireThat(setting, "setting").isNotBlank();
	}

	/**
	 * Creates a grant that the sandbox applies for certain.
	 *
	 * @param path    the absolute folder, with everything below it
	 * @param setting the grant as the user knows it
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code path} is relative or {@code setting} is blank
	 */
	public WriteGrant(Path path, String setting)
	{
		this(path, setting, false);
	}
}

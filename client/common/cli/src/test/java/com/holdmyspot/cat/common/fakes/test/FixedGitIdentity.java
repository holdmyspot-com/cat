package com.holdmyspot.cat.common.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.launcher.client.GitIdentity;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A Git identity that answers with the same values for every project, so that a test never starts Git to read it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class FixedGitIdentity implements GitIdentity
{
	private final String name;
	private final String email;

	/**
	 * Creates an identity.
	 *
	 * @param name  the author name
	 * @param email the author email
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if any of the arguments are blank
	 */
	public FixedGitIdentity(String name, String email)
	{
		requireThat(name, "name").isNotBlank();
		requireThat(email, "email").isNotBlank();
		this.name = name;
		this.email = email;
	}

	@Override
	public Optional<String> name(Path project)
	{
		requireThat(project, "project").isNotNull();
		return Optional.of(name);
	}

	@Override
	public Optional<String> email(Path project)
	{
		requireThat(project, "project").isNotNull();
		return Optional.of(email);
	}
}

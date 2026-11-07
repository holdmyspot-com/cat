package com.holdmyspot.cat.common.runner.launcher.client.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.launcher.client.GitIdentity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Checks what a {@link GitIdentity} reports for a project whose Git configuration sets an author identity. The caller
 * supplies the project and the identity that it configures, and owns their cleanup.
 */
public final class GitIdentityContract
{
	/**
	 * The reader under contract.
	 */
	private final GitIdentity identity;

	/**
	 * Creates checks of one reader.
	 *
	 * @param identity the reader under contract
	 */
	public GitIdentityContract(GitIdentity identity)
	{
		this.identity = identity;
	}

	/**
	 * Verifies that the reader reports the name and email that the project's Git configuration sets.
	 *
	 * @param project the project directory
	 * @param name    the author name that the project's Git configuration sets
	 * @param email   the author email that the project's Git configuration sets
	 * @throws IOException if the reader cannot read the identity
	 */
	public void readsConfiguredIdentity(Path project, String name, String email) throws IOException
	{
		requireThat(identity.name(project), "name").isEqualTo(Optional.of(name));
		requireThat(identity.email(project), "email").isEqualTo(Optional.of(email));
	}
}

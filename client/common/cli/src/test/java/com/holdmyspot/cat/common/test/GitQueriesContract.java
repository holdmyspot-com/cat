package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.SessionIgnore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks that {@link SessionIgnore.GitQueries} answer what Git answers about a project in each situation that the
 * session-directory tests set up. The caller supplies the project directory and owns its cleanup.
 * <p>
 * The answers come from Git's documentation: {@code git check-ignore} exits with 0 when it ignores a path, 1 when it
 * does not and 128 on a fatal error, and {@code git rev-parse --is-inside-work-tree} prints {@code true} inside a work
 * tree and {@code false} elsewhere in a repository.
 */
public final class GitQueriesContract
{
	/**
	 * The path in the CAT session directories that {@link SessionIgnore#ensure(Path)} asks Git to check.
	 */
	public static final String PROBE = SessionDirectory.ROOT + "/probe";

	/**
	 * The questions under contract.
	 */
	private final Subject subject;

	/**
	 * Creates checks of one set of questions.
	 *
	 * @param subject the questions under contract
	 */
	public GitQueriesContract(Subject subject)
	{
		this.subject = subject;
	}

	/**
	 * Verifies that the questions answer, for a project in each situation, what Git answers there.
	 *
	 * @param project the project directory
	 * @throws IOException if a question cannot be answered
	 */
	public void answersAsGit(Path project) throws IOException
	{
		for (Situation situation : Situation.values())
		{
			SessionIgnore.GitQueries git = subject.queries(project, situation);

			requireThat(git.checkIgnore(project, PROBE), situation + ".checkIgnore").
				isEqualTo(situation.checkIgnoreStatus());
			requireThat(git.isInsideWorkTree(project), situation + ".isInsideWorkTree").
				isEqualTo(List.of(Boolean.toString(situation.insideWorkTree())));
		}
	}

	/**
	 * A project's situation in Git, with the answers that Git gives there.
	 */
	public enum Situation
	{
		/**
		 * The project is in a work tree whose Git ignores the CAT session directories.
		 */
		IGNORING(0, true),
		/**
		 * The project is in a work tree whose Git does not ignore the CAT session directories.
		 */
		NOT_IGNORING(1, true),
		/**
		 * The project is in a work tree where the check fails, as when {@code .cat} is a symbolic link.
		 */
		FAILING_CHECK(128, true),
		/**
		 * The project is the directory of a bare repository, where the check fails.
		 */
		BARE_REPOSITORY(128, false);

		private final int checkIgnoreStatus;
		private final boolean insideWorkTree;

		/**
		 * Creates a situation.
		 *
		 * @param checkIgnoreStatus the exit status of {@code git check-ignore --quiet --no-index} for {@link #PROBE}
		 * @param insideWorkTree    what {@code git rev-parse --is-inside-work-tree} prints
		 */
		Situation(int checkIgnoreStatus, boolean insideWorkTree)
		{
			this.checkIgnoreStatus = checkIgnoreStatus;
			this.insideWorkTree = insideWorkTree;
		}

		/**
		 * Returns the exit status of {@code git check-ignore --quiet --no-index} for {@link #PROBE}.
		 *
		 * @return the exit status
		 */
		public int checkIgnoreStatus()
		{
			return checkIgnoreStatus;
		}

		/**
		 * Returns what {@code git rev-parse --is-inside-work-tree} prints.
		 *
		 * @return {@code true} inside a work tree
		 */
		public boolean insideWorkTree()
		{
			return insideWorkTree;
		}
	}

	/**
	 * The questions under contract.
	 */
	@FunctionalInterface
	public interface Subject
	{
		/**
		 * Returns questions about a project in a situation.
		 *
		 * @param project   the project directory
		 * @param situation the project's situation in Git
		 * @return the questions
		 */
		SessionIgnore.GitQueries queries(Path project, Situation situation);
	}
}

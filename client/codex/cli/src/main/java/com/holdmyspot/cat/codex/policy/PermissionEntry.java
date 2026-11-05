package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * An entry of a Codex permissions profile's file system policy, with its path resolved.
 *
 * @param path   the absolute path that the entry governs, with everything below it
 * @param access what the session's commands may do with the path
 */
public record PermissionEntry(Path path, Access access)
{
	/**
	 * Creates an entry.
	 *
	 * @param path   the absolute path that the entry governs, with everything below it
	 * @param access what the session's commands may do with the path
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code path} is relative
	 */
	public PermissionEntry
	{
		requireThat(path, "path").isAbsolute();
		requireThat(access, "access").isNotNull();
	}

	/**
	 * What a session's commands may do with a path.
	 */
	public enum Access
	{
		/**
		 * The commands may read the path.
		 */
		READ,
		/**
		 * The commands may read and write the path.
		 */
		WRITE,
		/**
		 * The commands may neither read nor write the path.
		 */
		DENY;

		/**
		 * Returns the access that wins when two entries name the same path: {@code DENY} over {@code WRITE}, and
		 * {@code WRITE} over {@code READ}.
		 *
		 * @param first  an access
		 * @param second another access
		 * @return the access that wins
		 */
		public static Access winner(Access first, Access second)
		{
			if (first.ordinal() >= second.ordinal())
				return first;
			return second;
		}

		/**
		 * Returns the access that a Codex configuration or rollout names.
		 *
		 * @param name the name: {@code read}, {@code write}, {@code deny}, or {@code none} as an alias of
		 *             {@code deny}
		 * @return the access, or {@code null} if the name is none of them
		 */
		public static Access ofName(String name)
		{
			return switch (name)
			{
				case "read" -> READ;
				case "write" -> WRITE;
				case "deny", "none" -> DENY;
				default -> null;
			};
		}
	}
}

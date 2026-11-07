package com.holdmyspot.cat.common.installer.cli.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import java.nio.file.Path;
import java.util.List;

/**
 * The permission and sandbox entries that a report to a person lists, and the locations by which it names them.
 *
 * @param groups      the entries, by effect and setting
 * @param harnessHome the harness home that the entries' files lie in
 * @param userHome    the user's home directory that the installer used, which the report writes as {@code ~}
 * @param installer   the installer launcher that the entries name, which the report writes as {@code installer}
 * @param exact       {@code true} to list each entry as the configuration holds it, or {@code false} to list the
 *                    operations, paths and names that the entries name
 */
public record ReportedEntries(List<EntryGroup> groups, Path harnessHome, Path userHome, Path installer,
	boolean exact)
{
	/**
	 * Creates the entries of a report.
	 *
	 * @param groups      the entries, by effect and setting
	 * @param harnessHome the harness home that the entries' files lie in
	 * @param userHome    the user's home directory that the installer used, which the report writes as {@code ~}
	 * @param installer   the installer launcher that the entries name, which the report writes as {@code installer}
	 * @param exact       {@code true} to list each entry as the configuration holds it, or {@code false} to list the
	 *                    operations, paths and names that the entries name
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if any of the paths are relative
	 */
	public ReportedEntries
	{
		groups = List.copyOf(groups);
		requireThat(harnessHome, "harnessHome").isAbsolute();
		requireThat(userHome, "userHome").isAbsolute();
		requireThat(installer, "installer").isAbsolute();
	}
}

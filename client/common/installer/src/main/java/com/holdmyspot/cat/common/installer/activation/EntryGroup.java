package com.holdmyspot.cat.common.installer.activation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;

/**
 * Entries of one setting that install recorded in a harness's configuration, grouped by what they let the harness
 * do, as the installer's report to a person lists them.
 *
 * @param effect  what the entries let the harness do
 * @param file    the configuration file that holds the entries, relative to the harness home
 * @param setting the setting in {@code file} that holds the entries, or empty when CAT owns the whole file
 * @param items   what the entries name, of the kind that {@link EntryEffect#items()} states: a CAT command, such as
 *                {@code installer install} or the request server's file name, an absolute path, or a name; each item
 *                names one or more entries. The text form is kept because an item names a path only for some
 *                effects.
 * @param entries the entries as the configuration holds them
 */
public record EntryGroup(EntryEffect effect, Path file, String setting, List<String> items, List<String> entries)
{
	/**
	 * Creates a group.
	 *
	 * @param effect  what the entries let the harness do
	 * @param file    the configuration file that holds the entries, relative to the harness home
	 * @param setting the setting in {@code file} that holds the entries, or empty when CAT owns the whole file
	 * @param items   what the entries name, of the kind that {@link EntryEffect#items()} states
	 * @param entries the entries as the configuration holds them
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code file} is absolute
	 */
	public EntryGroup
	{
		requireThat(effect, "effect").isNotNull();
		requireThat(file, "file").isRelative();
		requireThat(setting, "setting").isNotNull();
		items = List.copyOf(items);
		entries = List.copyOf(entries);
	}
}

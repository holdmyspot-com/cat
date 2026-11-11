package com.holdmyspot.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.inventory.Authority;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Limit;
import com.holdmyspot.cat.common.optimizer.inventory.Operation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The files that a contract declares its target prompt file refers to in its text, with the edit authority that the
 * campaign has over each one.
 * <p>
 * Every trial receives a declared file with the content that the incumbent has, and no candidate changes it, so a
 * declared file that the campaign may edit contradicts its declaration and blocks confirmation, as do a file that the
 * fork does not hold and a link cycle.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ReferencedFiles
{
	/**
	 * The report of a contract that declares no file.
	 */
	public static final ReferencedFiles NONE = new ReferencedFiles(List.of(), List.of());
	private final List<Entry> entries;
	private final List<String> refusals;

	/**
	 * Creates a report.
	 *
	 * @param entries the declared files that trials receive without any candidate changing them, in contract order
	 * @param refusals the reasons why a declared file blocks confirmation
	 */
	private ReferencedFiles(List<Entry> entries, List<String> refusals)
	{
		this.entries = List.copyOf(entries);
		this.refusals = List.copyOf(refusals);
	}

	/**
	 * Reports the declared files of a contract.
	 *
	 * @param files the declared files, relative to the project
	 * @param inventory the fork's inventory
	 * @param authority the edit authority that decides each file's limit
	 * @return the report
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static ReferencedFiles of(List<Path> files, FileInventory inventory, Authority authority)
	{
		requireThat(files, "files").isNotNull();
		requireThat(inventory, "inventory").isNotNull();
		requireThat(authority, "authority").isNotNull();
		List<Entry> entries = new ArrayList<>();
		List<String> refusals = new ArrayList<>();
		for (Path file : files)
		{
			String name = "`" + PortablePaths.text(file) + "`";
			if (!inventory.holds(file))
			{
				refusals.add("The project no longer holds the referenced file " + name + ".");
				continue;
			}
			Optional<Path> target = inventory.resolve(file);
			if (target.isEmpty())
			{
				refusals.add("The symbolic links of the referenced file " + name + " form a cycle.");
				continue;
			}
			Optional<Limit> limit = authority.limitOf(Operation.EDIT_CONTENT, target.get());
			if (limit.isEmpty())
			{
				refusals.add("The referenced file " + name + " is a prompt source that the campaign optimizes, so " +
					"it is not a file that a candidate leaves unchanged.");
				continue;
			}
			entries.add(new Entry(file, target.get(), limit.get()));
		}
		if (refusals.isEmpty())
			return new ReferencedFiles(entries, refusals);
		List<String> reasons = new ArrayList<>(refusals);
		reasons.add("Run revise with feedback that corrects the referenced files.");
		return new ReferencedFiles(entries, List.of(String.join(" ", reasons)));
	}

	/**
	 * Returns the declared files that trials receive without any candidate changing them.
	 *
	 * @return the files, in contract order
	 */
	public List<Entry> entries()
	{
		return entries;
	}

	/**
	 * Returns the reasons why a declared file blocks confirmation.
	 *
	 * @return at most one reason, which lists every file; empty if no declared file blocks confirmation
	 */
	public List<String> refusals()
	{
		return refusals;
	}

	@Override
	public String toString()
	{
		return "ReferencedFiles" + entries;
	}

	/**
	 * One declared file.
	 *
	 * @param file the declared path, relative to the project, which may be a symbolic link
	 * @param target the file's path after symbolic links are resolved: relative to the project for a file inside it,
	 *   absolute for an external file
	 * @param limit why no candidate changes the file
	 */
	public record Entry(Path file, Path target, Limit limit)
	{
		/**
		 * Creates an entry.
		 *
		 * @param file the declared path
		 * @param target the file's resolved path
		 * @param limit why no candidate changes the file
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Entry
		{
			requireThat(file, "file").isNotNull();
			requireThat(target, "target").isNotNull();
			requireThat(limit, "limit").isNotNull();
		}
	}
}

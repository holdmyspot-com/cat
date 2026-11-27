package com.holdmyspot.cat.claude.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.installer.ClaudeWriteGrants;
import com.holdmyspot.cat.claude.installer.ClaudeWriteGrants.Launch;
import com.holdmyspot.cat.claude.installer.ClaudeWriteGrants.Session;
import com.holdmyspot.cat.common.SandboxWrites;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.WriteGrant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Verifies that {@link ClaudeWriteGrants} overestimates where Claude Code's sandbox lets commands write: it reports
 * every grant that it cannot rule out, marks the grants that it cannot be sure of as possible, applies only the
 * denials that it is sure of, and lists what it cannot read.
 */
public final class ClaudeWriteGrantsTest
{
	/**
	 * Creates the test.
	 */
	public ClaudeWriteGrantsTest()
	{
	}

	/**
	 * An exact {@code allowWrite} entry of the user's settings is a certain grant that names the setting and the file,
	 * and the project folder and Claude Code's built-in folders are certain grants too.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsExactEntriesAsCertainGrants() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true,\"filesystem\":" +
				"{\"allowWrite\":[" + Fixture.json(tools) + "]}}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known());

			WriteGrant grant = fixture.grantAt(writes, tools);
			requireThat(grant.possible(), "possible").isFalse();
			requireThat(grant.setting(), "setting").contains("sandbox.filesystem.allowWrite").
				contains(fixture.config.resolve("settings.json").toString());
			requireThat(fixture.grantAt(writes, fixture.project).possible(), "project").isFalse();
			requireThat(writes.problems(), "problems").isEmpty();
		}
	}

	/**
	 * A session whose settings never enable the sandbox has no grant to report.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsNothingWhenSandboxIsOff() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":false,\"filesystem\":" +
				"{\"allowWrite\":[" + Fixture.json(fixture.root.resolve("tools")) + "]}}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known());

			requireThat(writes.grants(), "grants").isEmpty();
		}
	}

	/**
	 * Settings that never mention the sandbox leave it off, so the grants that they give, and the project folder, are
	 * not reported, while what CAT cannot read still is.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsNothingWhenNoSandboxIsSet() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"filesystem\":" +
				"{\"allowWrite\":[" + Fixture.json(tools) + "]}},\"permissions\":{\"additionalDirectories\":[" +
				Fixture.json(tools) + "],\"allow\":[\"Edit(" + tools.toString().replace("\\", "\\\\") + "/**)\"]}}");
			Path broken = fixture.write(fixture.project.resolve(".claude/settings.json"), "{not json");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known());

			requireThat(writes.grants(), "grants").isEmpty();
			requireThat(String.join("\n", writes.problems()), "problems").contains(broken.toString());
		}
	}

	/**
	 * A session with no settings at all has no sandbox, so it has no grant, not even for its project folder.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsNothingWithoutSettings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known());

			requireThat(writes.grants(), "grants").isEmpty();
			requireThat(writes.problems(), "problems").isEmpty();
		}
	}

	/**
	 * Settings that disagree on whether the sandbox is on leave its state uncertain, so every grant that they give is
	 * possible.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksGrantsPossibleOnDisagreement() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true,\"filesystem\":" +
				"{\"allowWrite\":[" + Fixture.json(tools) + "]}}}");
			fixture.write(fixture.project.resolve(".claude/settings.json"), "{\"sandbox\":{\"enabled\":false}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known());

			requireThat(fixture.grantAt(writes, tools).possible(), "possible").isTrue();
		}
	}

	/**
	 * An entry that holds a pattern is a possible grant of the folder that its literal part names, because the sandbox
	 * may let commands write what the pattern matches.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksPatternEntryPossible() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true,\"filesystem\":" +
				"{\"allowWrite\":[" + Fixture.jsonPattern(tools, "b*") + "]}}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known());

			WriteGrant grant = fixture.grantAt(writes, tools);
			requireThat(grant.possible(), "possible").isTrue();
			requireThat(grant.setting(), "setting").contains("sandbox.filesystem.allowWrite");
		}
	}

	/**
	 * A relative entry that the release may resolve against the settings' root or against the project folder is a
	 * possible grant of both folders.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksAmbiguousRelativeEntryPossible() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true,\"filesystem\":" +
				"{\"allowWrite\":[\"shared\"]}}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known());

			requireThat(fixture.grantAt(writes, fixture.config.resolve("shared")).possible(), "fromSettingsRoot").
				isTrue();
			requireThat(fixture.grantAt(writes, fixture.project.resolve("shared")).possible(), "fromProject").isTrue();
		}
	}

	/**
	 * A permission rule of the form {@code Edit(path)} that allows a path grants it, and a path that the rule spells in
	 * a form that the release may read in two ways is possible at each reading.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsEditAllowRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true},\"permissions\":" +
				"{\"allow\":[\"Edit(" + Fixture.ruleSpelling(tools) + ")\",\"Edit(/elsewhere)\"]}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known());

			WriteGrant exact = fixture.grantAt(writes, tools);
			requireThat(exact.possible(), "exactPossible").isFalse();
			requireThat(exact.setting(), "setting").contains("permissions.allow");
			requireThat(fixture.grantAt(writes, fixture.config.resolve("elsewhere")).possible(), "ambiguous").isTrue();
			// A path that starts with one slash is absolute only where a slash alone names the root of a filesystem
			Path absolute = Path.of("/elsewhere");
			if (absolute.isAbsolute())
				requireThat(fixture.grantAt(writes, absolute).possible(), "absolute").isTrue();
		}
	}

	/**
	 * A grant whose value is a single string instead of a list is still a possible grant, and a value that is neither a
	 * list nor a string is listed as a problem that names the setting and the file.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void handlesValuesOfTheWrongType() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			Path file = fixture.config.resolve("settings.json");
			fixture.write(file, "{\"sandbox\":{\"enabled\":true,\"filesystem\":{\"allowWrite\":" + Fixture.json(tools) +
				"}},\"permissions\":{\"additionalDirectories\":{\"a\":1}}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known());

			requireThat(fixture.grantAt(writes, tools).possible(), "possible").isTrue();
			requireThat(writes.problems(), "problems").size().isEqualTo(1);
			requireThat(writes.problems().getFirst(), "problem").contains("permissions.additionalDirectories").
				contains(file.toString());
		}
	}

	/**
	 * An entry that names another user's home folder could name any folder below the folder that holds the home
	 * folders, so it is a possible grant of that folder.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksOtherUsersHomePossible() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true,\"filesystem\":" +
				"{\"allowWrite\":[\"~someone/tools\"]}}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known());

			requireThat(fixture.grantAt(writes, fixture.userHome.getParent()).possible(), "possible").isTrue();
		}
	}

	/**
	 * Project settings that the release ignores while a more trusted scope forbids unsandboxed commands may still apply
	 * in another release, so their grants are possible rather than left out.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksIgnorableProjectGrantsPossible() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			Path flag = fixture.write(fixture.root.resolve("flag.json"), "{\"sandbox\":{\"enabled\":true," +
				"\"allowUnsandboxedCommands\":false}}");
			fixture.write(fixture.project.resolve(".claude/settings.json"), "{\"sandbox\":{\"filesystem\":" +
				"{\"allowWrite\":[" + Fixture.json(tools) + "]}}}");
			Launch launch = new Launch(List.of(), Optional.of(flag.toString()), Optional.empty(), List.of(), false);

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known(launch));

			requireThat(fixture.grantAt(writes, tools).possible(), "possible").isTrue();
		}
	}

	/**
	 * A setting that lifts the sandbox's file restrictions lets commands write every folder.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsDisabledFilesystemAsEveryFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path file = fixture.config.resolve("settings.json");
			fixture.write(file, "{\"sandbox\":{\"enabled\":true,\"filesystem\":{\"disabled\":true}}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known());

			WriteGrant grant = fixture.grantAt(writes, fixture.root.getRoot());
			requireThat(grant.possible(), "possible").isFalse();
			requireThat(grant.setting(), "setting").contains("sandbox.filesystem.disabled").contains(file.toString());
		}
	}

	/**
	 * Project and local settings that the session may not load, because the command line is not known, give possible
	 * grants; when the command line is known and lists the setting sources, a scope that it leaves out gives none.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void appliesSettingSourcesWhenLaunchKnown() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true}}");
			fixture.write(fixture.project.resolve(".claude/settings.local.json"), "{\"sandbox\":{\"filesystem\":" +
				"{\"allowWrite\":[" + Fixture.json(tools) + "]}}}");

			SandboxWrites unknown = ClaudeWriteGrants.read(fixture.session(List.of(fixture.project), true,
				Optional.empty()));
			SandboxWrites known = ClaudeWriteGrants.read(fixture.known());
			SandboxWrites userOnly = ClaudeWriteGrants.read(fixture.known(new Launch(List.of(), Optional.empty(),
				Optional.of(Set.of("user")), List.of(), false)));

			requireThat(fixture.grantAt(unknown, tools).possible(), "unknown").isTrue();
			requireThat(fixture.grantAt(known, tools).possible(), "known").isFalse();
			requireThat(fixture.grants(userOnly, tools), "userOnly").isEmpty();
		}
	}

	/**
	 * A project that the reader cannot tell from other candidate folders gives possible grants only, and each
	 * candidate folder is a possible grant, because any of them may be the project folder.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksCandidateProjectsPossible() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			Path inner = Files.createDirectories(fixture.project.resolve("inner"));
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true}}");
			fixture.write(fixture.project.resolve(".claude/settings.json"), "{\"sandbox\":{\"filesystem\":" +
				"{\"allowWrite\":[" + Fixture.json(tools) + "]}}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.session(List.of(inner, fixture.project), false,
				Optional.empty()));

			requireThat(fixture.grantAt(writes, tools).possible(), "settings").isTrue();
			requireThat(fixture.grantAt(writes, inner).possible(), "inner").isTrue();
			requireThat(fixture.grantAt(writes, fixture.project).possible(), "project").isTrue();
		}
	}

	/**
	 * The folders of {@code --add-dir} are certain grants, and a relative one resolves against the project folder.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsAddedDirectories() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true}}");
			Launch launch = new Launch(List.of("extra", fixture.root.resolve("abs").toString()), Optional.empty(),
				Optional.empty(), List.of(), false);

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known(launch));

			WriteGrant relative = fixture.grantAt(writes, fixture.project.resolve("extra"));
			requireThat(relative.possible(), "relativePossible").isFalse();
			requireThat(relative.setting(), "setting").contains("--add-dir");
			requireThat(fixture.grantAt(writes, fixture.root.resolve("abs")).possible(), "absolute").isFalse();
		}
	}

	/**
	 * Managed settings are read: a managed grant is certain when one managed source exists, and possible when several
	 * exist, because Claude Code takes one of them and CAT cannot tell which.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsManagedSettings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			Path managed = Files.createDirectories(fixture.root.resolve("managed"));
			Path other = Files.createDirectories(fixture.root.resolve("other-managed"));
			Path dropIn = fixture.write(managed.resolve("managed-settings.d/10-sandbox.json"),
				"{\"sandbox\":{\"enabled\":true,\"filesystem\":{\"allowWrite\":[" + Fixture.json(tools) + "]}}}");

			SandboxWrites one = ClaudeWriteGrants.read(fixture.known(List.of(managed, other)));
			fixture.write(other.resolve("managed-settings.json"), "{\"sandbox\":{\"enabled\":true}}");
			SandboxWrites several = ClaudeWriteGrants.read(fixture.known(List.of(managed, other)));

			WriteGrant certain = fixture.grantAt(one, tools);
			requireThat(certain.possible(), "onePossible").isFalse();
			requireThat(certain.setting(), "setting").contains(dropIn.toString());
			requireThat(fixture.grantAt(several, tools).possible(), "severalPossible").isTrue();
		}
	}

	/**
	 * Managed settings that let only managed permission rules grant access make the grants of the other settings
	 * possible, because CAT does not know which of them the release applies.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void marksGrantsPossibleUnderManagedRules() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			Path managed = Files.createDirectories(fixture.root.resolve("managed"));
			fixture.write(managed.resolve("managed-settings.json"),
				"{\"sandbox\":{\"enabled\":true},\"allowManagedPermissionRulesOnly\":true}");
			fixture.write(fixture.config.resolve("settings.json"), "{\"permissions\":{\"additionalDirectories\":[" +
				Fixture.json(tools) + "]}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.known(List.of(managed)));

			requireThat(fixture.grantAt(writes, tools).possible(), "possible").isTrue();
		}
	}

	/**
	 * A settings file that cannot be parsed is listed as a problem that names the file, and the grants of the other
	 * files still count.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void listsUnreadableFilesAsProblems() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true,\"filesystem\":" +
				"{\"allowWrite\":[" + Fixture.json(tools) + "]}}}");
			Path broken = fixture.write(fixture.project.resolve(".claude/settings.json"), "{not json");
			Path unreadable = fixture.write(fixture.root.resolve("preferences.plist"), "<plist/>");
			Session session = new Session(fixture.userHome, fixture.config, List.of(fixture.project), true,
				Optional.empty(), List.of(), List.of(unreadable), Optional.of(Fixture.NO_OPTIONS));

			SandboxWrites writes = ClaudeWriteGrants.read(session);

			requireThat(fixture.grantAt(writes, tools), "grant").isNotNull();
			requireThat(String.join("\n", writes.problems()), "problems").contains(broken.toString()).
				contains(unreadable.toString());
		}
	}

	/**
	 * A denial that names exactly one folder counts, and a denial that holds a pattern, or that comes from settings
	 * that may not apply, does not, because CAT is not certain of it.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void appliesOnlyCertainDenials() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			Path viaRule = fixture.root.resolve("by-rule");
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true,\"filesystem\":" +
				"{\"denyWrite\":[" + Fixture.json(tools.resolve("private")) + "," + Fixture.jsonPattern(tools, "p*") +
				"]}},\"permissions\":{\"deny\":[\"Edit(" + Fixture.ruleSpelling(viaRule) + ")\"]}}");

			SandboxWrites known = ClaudeWriteGrants.read(fixture.known());
			SandboxWrites candidates = ClaudeWriteGrants.read(fixture.session(List.of(fixture.project), false,
				Optional.empty()));

			requireThat(known.denials(), "denials").contains(tools.resolve("private")).contains(viaRule).
				doesNotContain(tools);
			requireThat(known.denials().stream().anyMatch(denial -> denial.toString().contains("*")), "pattern").
				isFalse();
			requireThat(candidates.denials(), "candidateDenials").contains(tools.resolve("private"));
		}
	}

	/**
	 * A denial from a project's settings that CAT cannot tie to the session does not count.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void ignoresDenialsOfProjectsThatMayNotApply() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			Path tools = fixture.root.resolve("tools");
			fixture.write(fixture.config.resolve("settings.json"), "{\"sandbox\":{\"enabled\":true}}");
			fixture.write(fixture.project.resolve(".claude/settings.json"), "{\"sandbox\":{\"filesystem\":" +
				"{\"denyWrite\":[" + Fixture.json(tools) + "]}}}");

			SandboxWrites writes = ClaudeWriteGrants.read(fixture.session(List.of(fixture.project), false,
				Optional.empty()));

			requireThat(writes.denials(), "denials").doesNotContain(tools);
		}
	}

	/**
	 * Test data: a project, a configuration folder and a home folder in one temporary folder.
	 */
	private static final class Fixture
	{
		/**
		 * The command line options of a session that gives none.
		 */
		private static final Launch NO_OPTIONS = new Launch(List.of(), Optional.empty(), Optional.empty(), List.of(),
			false);
		private final Path root;
		private final Path userHome;
		private final Path config;
		private final Path project;

		/**
		 * Creates the folders.
		 *
		 * @param cleanup removes the temporary folder
		 * @throws IOException if the folders cannot be created
		 */
		private Fixture(TemporaryDirectories cleanup) throws IOException
		{
			this.root = cleanup.add(Files.createTempDirectory("claude-write-grants-")).toRealPath();
			this.userHome = Files.createDirectories(root.resolve("home/user"));
			this.config = Files.createDirectories(userHome.resolve(".claude"));
			this.project = Files.createDirectories(root.resolve("project"));
		}

		/**
		 * Writes a file, creating its folder.
		 *
		 * @param file    the file
		 * @param content the content
		 * @return the file
		 * @throws IOException if the file cannot be written
		 */
		private Path write(Path file, String content) throws IOException
		{
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);
			return file;
		}

		/**
		 * Returns a session whose project folder and command line are known.
		 *
		 * @return the session
		 */
		private Session known()
		{
			return session(List.of(project), true, Optional.of(NO_OPTIONS));
		}

		/**
		 * Returns a session whose project folder is known and that was started with options.
		 *
		 * @param launch the options
		 * @return the session
		 */
		private Session known(Launch launch)
		{
			return session(List.of(project), true, Optional.of(launch));
		}

		/**
		 * Returns a session whose project folder and command line are known, with managed settings folders.
		 *
		 * @param managedDirectories the folders that may hold managed settings
		 * @return the session
		 */
		private Session known(List<Path> managedDirectories)
		{
			return new Session(userHome, config, List.of(project), true, Optional.empty(), managedDirectories,
				List.of(), Optional.of(NO_OPTIONS));
		}

		/**
		 * Returns a session.
		 *
		 * @param projects     the project folders
		 * @param projectKnown whether the first of them is the session's project folder
		 * @param launch       the command line options
		 * @return the session
		 */
		private Session session(List<Path> projects, boolean projectKnown, Optional<Launch> launch)
		{
			return new Session(userHome, config, projects, projectKnown, Optional.empty(), List.of(), List.of(),
				launch);
		}

		/**
		 * Returns the grants of a folder.
		 *
		 * @param writes the reading
		 * @param folder the folder
		 * @return the grants of the folder
		 */
		private List<WriteGrant> grants(SandboxWrites writes, Path folder)
		{
			return writes.grants().stream().filter(grant -> grant.path().equals(folder)).toList();
		}

		/**
		 * Returns the grant of a folder, which must be unique after the possible and certain grants of one setting are
		 * merged: a certain grant wins.
		 *
		 * @param writes the reading
		 * @param folder the folder
		 * @return the grant
		 */
		private WriteGrant grantAt(SandboxWrites writes, Path folder)
		{
			List<WriteGrant> grants = grants(writes, folder);
			requireThat(grants, "grants").withContext(folder, "folder").withContext(writes, "writes").isNotEmpty();
			for (WriteGrant grant : grants)
			{
				if (!grant.possible())
					return grant;
			}
			return grants.getFirst();
		}

		/**
		 * Returns the spelling that a permission rule uses for an absolute path: two slashes before the path on Linux and
		 * macOS, and two slashes, the drive's letter and the rest of the path on Windows.
		 *
		 * @param path the path
		 * @return the spelling, which holds no character that a JSON string must escape
		 */
		private static String ruleSpelling(Path path)
		{
			String spelled = path.toString();
			if (!path.getFileSystem().getSeparator().equals("\\"))
				return "/" + spelled;
			return "//" + Character.toLowerCase(spelled.charAt(0)) + spelled.substring(2).replace('\\', '/');
		}

		/**
		 * Returns a pattern in a folder as a JSON string. The pattern is not a valid path on every platform, so it is
		 * joined to the folder as text.
		 *
		 * @param folder  the folder
		 * @param pattern the pattern
		 * @return the JSON string, with its quotes
		 */
		private static String jsonPattern(Path folder, String pattern)
		{
			String separator = folder.getFileSystem().getSeparator();
			return "\"" + (folder + separator + pattern).replace("\\", "\\\\") + "\"";
		}

		/**
		 * Returns a path as a JSON string.
		 *
		 * @param path the path
		 * @return the JSON string, with its quotes
		 */
		private static String json(Path path)
		{
			return "\"" + path.toString().replace("\\", "\\\\") + "\"";
		}
	}
}

package com.holdmyspot.cat.common.optimizer.adapter.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.adapter.ShellCommands;

import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link ShellCommands} against commands that trials ran, as Claude Code and Codex recorded them, and against
 * the shell forms that hide or start a program.
 */
public final class ShellCommandsTest
{
	/**
	 * Creates the test.
	 */
	public ShellCommandsTest()
	{
	}

	/**
	 * Returns commands with the programs that they run by name.
	 *
	 * @return each command and its programs
	 */
	@DataProvider
	public static Object[][] commands()
	{
		return new Object[][]
		{
			// Recorded by Claude Code 2.1.229 in docs/changelog/1.0/captures/languages/cpp/code/claude-cat.events.jsonl
			{"git status --short && git log --oneline -5", List.of("git")},
			{"pkg-config --list-all 2>/dev/null | grep -Ei 'gmp|boost|tommath|decimal' || true",
				List.of("pkg-config", "grep")},
			{"g++ -std=c++17 -Wall -Wextra -Werror \"$TMPDIR/CheckoutPriceCalculatorTest.cpp\" -o " +
				"\"$TMPDIR/checkout_test\" && \"$TMPDIR/checkout_test\"", List.of("g++")},
			{"cd \"$TMPDIR\" && apt-get download libboost-dev", List.of("apt-get")},
			{"/home/user/project/checkout_test", List.of()},
			// Recorded by codex-cli 0.147.0 in docs/changelog/1.0/captures/base-codex-live/events.jsonl
			{"/bin/bash -lc \"ls -la && rg --files -g '\"'!*node_modules*'\"' | sed -n '1,160p' && " +
				"find .. -name AGENTS.md -print\"", List.of("ls", "rg", "sed", "find")},
			{"/bin/bash -lc true", List.of()},
			// Shell syntax that names no program, or names one in another place
			{"FOO=bar LANG=C sort -u file", List.of("sort")},
			{"echo $(date +%s) `uname -r` $((1 + 2))", List.of("date", "uname")},
			{"diff <(jq . a.json) b.json", List.of("diff", "jq")},
			{"if test -f x; then make; else cmake .; fi", List.of("make", "cmake")},
			{"for f in *.txt; do wc -l \"$f\"; done", List.of("wc")},
			{"while read -r line; do printf '%s' \"$line\"; done < input.txt", List.of()},
			{"case \"$x\" in a|b) tar -xf a ;; *) unzip b ;; esac", List.of("tar", "unzip")},
			{"build() { gradle build; }; build", List.of("gradle")},
			{"command -v node && command python3 --version", List.of("python3")},
			{"exec 3>&1 && npm test 2>&1 >/dev/null", List.of("npm")},
			{"cat <<'EOF' > notes.txt\ncurl would not run here\nEOF\nwc -l notes.txt", List.of("cat", "wc")},
			{"bash -c 'node -e \"1\"' && sh", List.of("bash", "node", "sh")},
			{"\"$PYTHON\" script.py; ${CC:-cc} main.c; ./configure", List.of()},
			{"# python3 is not run\nmvn -q verify # nor is java", List.of("mvn")},
			{"git log --format='%H' \\\n  | head -1", List.of("git", "head")},
			{"pwsh -Command \"Get-ChildItem | Select-Object Name\"", List.of("pwsh", "Get-ChildItem", "Select-Object")},
			// A command that the prompt ships passes its script to the shell on its standard input
			{"/opt/cat/client/bin/run-part <<'EOF'\nrg -n needle src | sort\nEOF", List.of("rg", "sort")},
			{"xargs -0 rm < list && timeout 5 env make", List.of("xargs", "timeout")}
		};
	}

	/**
	 * Verifies that a command's programs are the words in command position that name a program, without builtins,
	 * keywords, paths or words that the shell computes.
	 *
	 * @param command  the command
	 * @param programs the programs that the command runs by name
	 */
	@Test(dataProvider = "commands")
	public void findsProgramsRunByName(String command, List<String> programs)
	{
		// Requirement: R-M8
		requireThat(ShellCommands.programs(command), "programs").isEqualTo(programs);
	}
}

package com.holdmyspot.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the programs that a shell command runs by name.
 * <p>
 * A program is a word in command position that names a program rather than a builtin, a keyword, a function that the
 * command defines, a path, or a word that the shell computes, such as {@code "$PYTHON"}. Commands in command and
 * process substitutions, in a shell's {@code -c} script, in a PowerShell {@code -Command} script and in a script that a
 * shell or {@code run-part} reads from a here-document count as well. A program that another program starts, such as
 * the command that {@code xargs} or {@code timeout} runs, does not.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class ShellCommands
{
	/**
	 * The builtins of POSIX shells and Bash, which run without a program.
	 */
	private static final Set<String> BUILTINS = Set.of(":", ".", "source", "alias", "bg", "bind", "break", "builtin",
		"caller", "cd", "command", "compgen", "complete", "continue", "declare", "dirs", "disown", "echo", "enable",
		"eval", "exec", "exit", "export", "false", "fc", "fg", "getopts", "hash", "help", "history", "jobs", "kill",
		"let", "local", "logout", "mapfile", "popd", "printf", "pushd", "pwd", "read", "readarray", "readonly",
		"return", "set", "shift", "shopt", "suspend", "test", "times", "trap", "true", "type", "typeset", "ulimit",
		"umask", "unalias", "unset", "wait", "[");
	/**
	 * The reserved words after which the next word is in command position.
	 */
	private static final Set<String> KEYWORDS = Set.of("if", "then", "else", "elif", "fi", "do", "done", "while",
		"until", "!", "{", "}", "esac");
	/**
	 * The shells that run a {@code -c} script or a script on their standard input.
	 */
	private static final Set<String> SHELLS = Set.of("bash", "sh", "zsh", "dash", "ksh");
	/**
	 * The PowerShell programs that run a {@code -Command} script.
	 */
	private static final Set<String> POWERSHELLS = Set.of("pwsh", "powershell");
	/**
	 * The command that runs the script on its standard input, as a shell does.
	 */
	private static final String RUN_PART = "run-part";
	/**
	 * The PowerShell keywords that can begin a statement.
	 */
	private static final Set<String> POWERSHELL_KEYWORDS = Set.of("if", "else", "elseif", "foreach", "for", "while",
		"do", "until", "switch", "function", "filter", "return", "try", "catch", "finally", "param", "begin",
		"process", "end", "throw", "exit", "break", "continue", "trap");

	/**
	 * Prevents instantiation.
	 */
	private ShellCommands()
	{
	}

	/**
	 * Returns the programs that a shell command runs by name.
	 *
	 * @param command the command
	 * @return the programs' names, each once, in the order in which the command first names them
	 * @throws NullPointerException if {@code command} is null
	 */
	public static List<String> programs(String command)
	{
		requireThat(command, "command").isNotNull();
		Set<String> programs = new LinkedHashSet<>();
		addPrograms(command, programs);
		return List.copyOf(programs);
	}

	/**
	 * Adds the programs that a shell script runs by name.
	 *
	 * @param script   the script
	 * @param programs receives the programs, in the order in which the script names them
	 */
	private static void addPrograms(String script, Set<String> programs)
	{
		new Reader(new Tokenizer(script).tokens(), programs).read();
	}

	/**
	 * Adds the programs that a PowerShell script runs by name. Each statement and each block begins with a command, so
	 * the first word of each part between separators, braces and parentheses names one unless it is a variable, a
	 * keyword, an option or a quoted string.
	 *
	 * @param script   the script
	 * @param programs receives the programs, in the order in which the script names them
	 */
	private static void addPowerShellPrograms(String script, Set<String> programs)
	{
		StringBuilder part = new StringBuilder();
		char quote = 0;
		for (int i = 0; i < script.length(); i += 1)
		{
			char c = script.charAt(i);
			if (quote != 0)
			{
				if (c == quote)
					quote = 0;
				part.append(c);
			}
			else if (c == '\'' || c == '"')
			{
				quote = c;
				part.append(c);
			}
			else if ("|;&{}()\n\r".indexOf(c) >= 0)
			{
				addPowerShellCommand(part.toString(), programs);
				part.setLength(0);
			}
			else
				part.append(c);
		}
		addPowerShellCommand(part.toString(), programs);
	}

	/**
	 * Adds the program that one part of a PowerShell script begins with.
	 *
	 * @param part     the part
	 * @param programs receives the program
	 */
	private static void addPowerShellCommand(String part, Set<String> programs)
	{
		String trimmed = part.strip();
		if (trimmed.isEmpty())
			return;
		String word = trimmed.split("\\s+", 2)[0];
		if (!word.matches("[A-Za-z_][\\w.+-]*") || POWERSHELL_KEYWORDS.contains(word.toLowerCase(Locale.ROOT)))
			return;
		programs.add(stripExtension(word));
	}

	/**
	 * Returns a program name without a Windows {@code .exe} extension.
	 *
	 * @param name the name
	 * @return the name without the extension
	 */
	private static String stripExtension(String name)
	{
		if (name.length() > 4 && name.toLowerCase(Locale.ROOT).endsWith(".exe"))
			return name.substring(0, name.length() - 4);
		return name;
	}

	/**
	 * Returns the last component of a program's path, without a Windows {@code .exe} extension.
	 *
	 * @param path the path
	 * @return the program's name
	 */
	private static String baseName(String path)
	{
		int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return stripExtension(path.substring(slash + 1));
	}

	/**
	 * Indicates whether a word names a program by its path rather than by a name that {@code PATH} finds.
	 *
	 * @param word the word
	 * @return {@code true} if the word contains a directory separator
	 */
	private static boolean isPath(String word)
	{
		return word.indexOf('/') >= 0 || word.indexOf('\\') >= 0;
	}

	/**
	 * A part of a shell command.
	 */
	private sealed interface Token
	{
	}

	/**
	 * A word.
	 *
	 * @param text          the word with its quoting removed
	 * @param dynamic       {@code true} if the shell computes part of the word, such as by a parameter expansion
	 * @param substitutions the commands of the command substitutions in the word, in order
	 */
	private record Word(String text, boolean dynamic, List<String> substitutions) implements Token
	{
	}

	/**
	 * A control operator, such as {@code &&}, {@code ;;} or a line break, or a parenthesis.
	 *
	 * @param text the operator
	 */
	private record Operator(String text) implements Token
	{
	}

	/**
	 * A redirection operator, after which a word names the redirection's target.
	 */
	private record Redirection() implements Token
	{
	}

	/**
	 * A process substitution.
	 *
	 * @param command the command that the substitution runs
	 */
	private record ProcessSubstitution(String command) implements Token
	{
	}

	/**
	 * A here-document, whose body the tokenizer reads after the line that names it.
	 */
	private static final class HereDocument implements Token
	{
		private String body = "";
	}

	/**
	 * Splits a shell command into tokens.
	 */
	private static final class Tokenizer
	{
		private final String text;
		private final List<Token> tokens = new ArrayList<>();
		private final List<HereDocument> pendingBodies = new ArrayList<>();
		private final List<String> pendingDelimiters = new ArrayList<>();
		private final List<Boolean> pendingTabs = new ArrayList<>();
		private int position;

		/**
		 * Creates a tokenizer.
		 *
		 * @param text the command
		 */
		Tokenizer(String text)
		{
			this.text = text;
		}

		/**
		 * Returns the command's tokens.
		 *
		 * @return the tokens, in order
		 */
		List<Token> tokens()
		{
			while (position < text.length())
				next();
			return tokens;
		}

		/**
		 * Reads the next token, or skips whitespace or a comment.
		 */
		private void next()
		{
			char c = text.charAt(position);
			if (c == ' ' || c == '\t' || c == '\r')
			{
				position += 1;
				return;
			}
			if (c == '\\' && position + 1 < text.length() && text.charAt(position + 1) == '\n')
			{
				position += 2;
				return;
			}
			if (c == '#')
			{
				while (position < text.length() && text.charAt(position) != '\n')
					position += 1;
				return;
			}
			if (c == '\n')
			{
				position += 1;
				tokens.add(new Operator("\n"));
				readBodies();
				return;
			}
			if (startsWith("(("))
			{
				// An arithmetic command runs no program
				position = closing(position + 1, '(', ')') + 1;
				if (position < text.length() && text.charAt(position) == ')')
					position += 1;
				return;
			}
			if (startsWith("<(") || startsWith(">("))
			{
				int end = closing(position + 1, '(', ')');
				tokens.add(new ProcessSubstitution(text.substring(position + 2, end)));
				position = end + 1;
				return;
			}
			for (String operator : List.of(";;&", ";;", ";&", "&&", "||", "|&", "|", ";", "&", "(", ")"))
			{
				if (startsWith(operator) && !startsWith("&>"))
				{
					position += operator.length();
					tokens.add(new Operator(operator));
					return;
				}
			}
			if (startsWith("<<") && !startsWith("<<<"))
			{
				readHereDocument();
				return;
			}
			for (String redirection : List.of("<<<", "&>>", "&>", ">>", ">&", ">|", "<&", "<>", "<", ">"))
			{
				if (startsWith(redirection))
				{
					position += redirection.length();
					tokens.add(new Redirection());
					return;
				}
			}
			readWord();
		}

		/**
		 * Indicates whether the text at the current position starts with a string.
		 *
		 * @param prefix the string
		 * @return {@code true} if it does
		 */
		private boolean startsWith(String prefix)
		{
			return text.startsWith(prefix, position);
		}

		/**
		 * Reads a here-document operator and its delimiter. Its body follows the end of the line.
		 */
		private void readHereDocument()
		{
			position += 2;
			boolean tabs = position < text.length() && text.charAt(position) == '-';
			if (tabs)
				position += 1;
			while (position < text.length() && (text.charAt(position) == ' ' || text.charAt(position) == '\t'))
				position += 1;
			int start = position;
			Word delimiter = word();
			HereDocument document = new HereDocument();
			tokens.add(document);
			if (position == start)
				return;
			pendingBodies.add(document);
			pendingDelimiters.add(delimiter.text());
			pendingTabs.add(tabs);
		}

		/**
		 * Reads the bodies of the here-documents that the line just ended named.
		 */
		private void readBodies()
		{
			for (int i = 0; i < pendingBodies.size(); i += 1)
			{
				StringBuilder body = new StringBuilder();
				while (position < text.length())
				{
					int end = text.indexOf('\n', position);
					if (end < 0)
						end = text.length();
					String line = text.substring(position, end);
					position = Math.min(end + 1, text.length());
					String compared = line;
					if (pendingTabs.get(i))
						compared = compared.replaceFirst("^\t+", "");
					if (compared.equals(pendingDelimiters.get(i)))
						break;
					body.append(line).append('\n');
				}
				pendingBodies.get(i).body = body.toString();
			}
			pendingBodies.clear();
			pendingDelimiters.clear();
			pendingTabs.clear();
		}

		/**
		 * Reads a word, or a file descriptor number that begins a redirection.
		 */
		private void readWord()
		{
			int start = position;
			Word word = word();
			if (position == start)
			{
				// A character that begins no token
				position += 1;
				return;
			}
			if (word.text().chars().allMatch(Character::isDigit) && !word.dynamic() && position < text.length() &&
				(text.charAt(position) == '<' || text.charAt(position) == '>'))
			{
				return;
			}
			tokens.add(word);
		}

		/**
		 * Reads the word at the current position.
		 *
		 * @return the word, which is empty if the current character ends words
		 */
		private Word word()
		{
			StringBuilder value = new StringBuilder();
			List<String> substitutions = new ArrayList<>();
			boolean dynamic = false;
			while (position < text.length())
			{
				char c = text.charAt(position);
				if (" \t\r\n;&|()<>".indexOf(c) >= 0)
					break;
				switch (c)
				{
					case '\'' ->
					{
						int end = text.indexOf('\'', position + 1);
						if (end < 0)
							end = text.length();
						value.append(text, position + 1, end);
						position = end + 1;
					}
					case '"' ->
					{
						position += 1;
						while (position < text.length() && text.charAt(position) != '"')
						{
							char inner = text.charAt(position);
							if (inner == '\\' && position + 1 < text.length())
							{
								value.append(text.charAt(position + 1));
								position += 2;
							}
							else if (inner == '$' || inner == '`')
							{
								dynamic = true;
								expansion(substitutions);
							}
							else
							{
								value.append(inner);
								position += 1;
							}
						}
						position += 1;
					}
					case '\\' ->
					{
						if (position + 1 < text.length() && text.charAt(position + 1) != '\n')
							value.append(text.charAt(position + 1));
						position += 2;
					}
					case '$', '`' ->
					{
						dynamic = true;
						expansion(substitutions);
					}
					case '*', '?' ->
					{
						dynamic = true;
						value.append(c);
						position += 1;
					}
					default ->
					{
						value.append(c);
						position += 1;
					}
				}
			}
			position = Math.min(position, text.length());
			return new Word(value.toString(), dynamic, substitutions);
		}

		/**
		 * Reads an expansion that starts with {@code $} or a backquote, recording the command of a command
		 * substitution.
		 *
		 * @param substitutions receives the command of a command substitution
		 */
		private void expansion(List<String> substitutions)
		{
			if (text.charAt(position) == '`')
			{
				int end = position + 1;
				while (end < text.length() && text.charAt(end) != '`')
				{
					if (text.charAt(end) == '\\')
						end += 1;
					end += 1;
				}
				end = Math.min(end, text.length());
				substitutions.add(text.substring(position + 1, end));
				position = end + 1;
				return;
			}
			if (startsWith("$(("))
			{
				position = closing(position + 2, '(', ')') + 1;
				if (position < text.length() && text.charAt(position) == ')')
					position += 1;
				return;
			}
			if (startsWith("$("))
			{
				int end = closing(position + 1, '(', ')');
				substitutions.add(text.substring(position + 2, end));
				position = end + 1;
				return;
			}
			if (startsWith("${"))
			{
				position = closing(position + 1, '{', '}') + 1;
				return;
			}
			position += 1;
			while (position < text.length() && (Character.isLetterOrDigit(text.charAt(position)) ||
				text.charAt(position) == '_'))
			{
				position += 1;
			}
			if (position < text.length() && "?$!#*@-".indexOf(text.charAt(position)) >= 0 &&
				text.charAt(position - 1) == '$')
			{
				position += 1;
			}
		}

		/**
		 * Finds the bracket that closes an opening bracket, skipping quoted text.
		 *
		 * @param open    the position of the opening bracket
		 * @param opening the opening bracket
		 * @param closing the closing bracket
		 * @return the position of the closing bracket, or the end of the text if none closes it
		 */
		private int closing(int open, char opening, char closing)
		{
			int depth = 0;
			int i = open;
			while (i < text.length())
			{
				char c = text.charAt(i);
				if (c == '\\')
					i += 1;
				else if (c == '\'')
				{
					int end = text.indexOf('\'', i + 1);
					if (end < 0)
						return text.length();
					i = end;
				}
				else if (c == '"')
				{
					i += 1;
					while (i < text.length() && text.charAt(i) != '"')
					{
						if (text.charAt(i) == '\\')
							i += 1;
						i += 1;
					}
				}
				else if (c == opening)
					depth += 1;
				else if (c == closing)
				{
					depth -= 1;
					if (depth == 0)
						return i;
				}
				i += 1;
			}
			return text.length();
		}
	}

	/**
	 * Finds the commands in a script's tokens.
	 */
	private static final class Reader
	{
		private final List<Token> tokens;
		private final Set<String> programs;
		private final Set<String> functions = new HashSet<>();
		/**
		 * For each {@code case} that the reader is in, whether it reads a pattern rather than a command.
		 */
		private final Deque<Boolean> cases = new ArrayDeque<>();
		private int index;

		/**
		 * Creates a reader.
		 *
		 * @param tokens   the script's tokens
		 * @param programs receives the programs, in the order in which the script names them
		 */
		Reader(List<Token> tokens, Set<String> programs)
		{
			this.tokens = tokens;
			this.programs = programs;
		}

		/**
		 * Reads the commands of the script.
		 */
		void read()
		{
			while (index < tokens.size())
				command();
		}

		/**
		 * Reads one simple command, or a token that separates commands.
		 */
		private void command()
		{
			Token token = tokens.get(index);
			if (!cases.isEmpty() && cases.peek())
			{
				pattern(token);
				return;
			}
			switch (token)
			{
				case Operator operator ->
				{
					index += 1;
					if (operator.text().startsWith(";;") || operator.text().equals(";&"))
						setPattern(true);
				}
				case Word word -> simpleCommand(word);
				default -> arguments(null);
			}
		}

		/**
		 * Reads a token of a {@code case} pattern.
		 *
		 * @param token the token
		 */
		private void pattern(Token token)
		{
			index += 1;
			if (token instanceof Word word && word.text().equals("esac") && !word.dynamic())
				cases.pop();
			else if (token instanceof Operator operator && operator.text().equals(")"))
				setPattern(false);
		}

		/**
		 * Sets whether the innermost {@code case} reads a pattern.
		 *
		 * @param pattern {@code true} if it reads a pattern
		 */
		private void setPattern(boolean pattern)
		{
			if (cases.isEmpty())
				return;
			cases.pop();
			cases.push(pattern);
		}

		/**
		 * Reads a simple command that begins with a word.
		 *
		 * @param first the command's first word
		 */
		private void simpleCommand(Word first)
		{
			Word word = first;
			while (true)
			{
				String name = word.text();
				addSubstitutions(word);
				index += 1;
				if (word.dynamic())
				{
					arguments(null);
					return;
				}
				if (isFunctionDefinition())
				{
					functions.add(name);
					index += 2;
					return;
				}
				if (KEYWORDS.contains(name))
				{
					if (name.equals("esac") && !cases.isEmpty())
						cases.pop();
					return;
				}
				switch (name)
				{
					case "for", "select" ->
					{
						skipHeader();
						return;
					}
					case "case" ->
					{
						skipCaseHeader();
						return;
					}
					case "function" ->
					{
						if (index < tokens.size() && tokens.get(index) instanceof Word function)
						{
							functions.add(function.text());
							index += 1;
						}
						return;
					}
					case "[[" ->
					{
						skipCondition();
						return;
					}
					default ->
					{
					}
				}
				if (isAssignment(name))
				{
					Word next = nextWord();
					if (next == null)
					{
						arguments(null);
						return;
					}
					word = next;
					continue;
				}
				if (name.equals("command") || name.equals("exec") || name.equals("time"))
				{
					Word next = wrapped(name);
					if (next == null)
					{
						arguments(null);
						return;
					}
					word = next;
					continue;
				}
				if (!BUILTINS.contains(name) && !name.isEmpty() && !name.startsWith("-") && !functions.contains(name))
				{
					if (!isPath(name))
						programs.add(stripExtension(name));
					arguments(baseName(name));
					return;
				}
				arguments(null);
				return;
			}
		}

		/**
		 * Indicates whether the tokens after a command's first word are {@code ()}, which make it a function's name.
		 *
		 * @return {@code true} if they are
		 */
		private boolean isFunctionDefinition()
		{
			return index + 1 < tokens.size() && tokens.get(index) instanceof Operator(String open) &&
				open.equals("(") && tokens.get(index + 1) instanceof Operator(String close) && close.equals(")");
		}

		/**
		 * Indicates whether a word assigns a variable.
		 *
		 * @param word the word
		 * @return {@code true} if it does
		 */
		private static boolean isAssignment(String word)
		{
			int equals = word.indexOf('=');
			return equals > 0 && word.substring(0, equals).matches("[A-Za-z_][A-Za-z0-9_]*(\\[[^]]*])?\\+?");
		}

		/**
		 * Returns the next token if it is a word, skipping a redirection and its target.
		 *
		 * @return the word, or {@code null} if the next token is not a word
		 */
		private Word nextWord()
		{
			while (index < tokens.size())
			{
				Token token = tokens.get(index);
				if (token instanceof Redirection)
				{
					index += 2;
					continue;
				}
				if (token instanceof HereDocument)
				{
					index += 1;
					continue;
				}
				if (token instanceof Word word)
					return word;
				return null;
			}
			return null;
		}

		/**
		 * Returns the word that a builtin or keyword that runs a command names as the command, skipping the builtin's
		 * options.
		 *
		 * @param name {@code command}, {@code exec} or {@code time}
		 * @return the word, or {@code null} if the builtin runs no command
		 */
		private Word wrapped(String name)
		{
			while (true)
			{
				Word next = nextWord();
				if (next == null)
					return null;
				String text = next.text();
				if (!text.startsWith("-") || next.dynamic() || text.equals("-"))
					return next;
				index += 1;
				if (name.equals("command") && (text.contains("v") || text.contains("V")))
				{
					arguments(null);
					return null;
				}
				if (name.equals("exec") && text.equals("-a"))
					index += 1;
			}
		}

		/**
		 * Reads the arguments of a command up to the operator that ends it, including the scripts that a shell or
		 * PowerShell runs.
		 *
		 * @param program the name of the program that runs, or {@code null} for a builtin or a computed word
		 */
		private void arguments(String program)
		{
			boolean shell = program != null && SHELLS.contains(program);
			boolean powerShell = program != null && POWERSHELLS.contains(program.toLowerCase(Locale.ROOT));
			boolean readsScript = shell || RUN_PART.equals(program);
			boolean script = false;
			List<String> powerShellScript = null;
			while (index < tokens.size())
			{
				Token token = tokens.get(index);
				switch (token)
				{
					case Operator _ ->
					{
						if (powerShellScript != null)
							addPowerShellPrograms(String.join(" ", powerShellScript), programs);
						return;
					}
					case Redirection _ ->
					{
						index += 1;
						if (index < tokens.size() && tokens.get(index) instanceof Word target)
						{
							addSubstitutions(target);
							index += 1;
						}
						continue;
					}
					case HereDocument document ->
					{
						if (readsScript)
							addPrograms(document.body, programs);
					}
					case ProcessSubstitution substitution -> addPrograms(substitution.command(), programs);
					case Word word ->
					{
						addSubstitutions(word);
						String text = word.text();
						if (powerShellScript != null)
							powerShellScript.add(text);
						else if (script)
						{
							addPrograms(text, programs);
							script = false;
						}
						else if (shell && text.matches("-[A-Za-z]*c[A-Za-z]*"))
							script = true;
						else if (powerShell && text.matches("(?i)-c(o(m(m(a(n(d)?)?)?)?)?)?"))
							powerShellScript = new ArrayList<>();
					}
				}
				index += 1;
			}
			if (powerShellScript != null)
				addPowerShellPrograms(String.join(" ", powerShellScript), programs);
		}

		/**
		 * Adds the programs of a word's command substitutions.
		 *
		 * @param word the word
		 */
		private void addSubstitutions(Word word)
		{
			for (String substitution : word.substitutions())
				addPrograms(substitution, programs);
		}

		/**
		 * Skips the header of a {@code for} or {@code select} loop, which names a variable and the words that it takes.
		 */
		private void skipHeader()
		{
			while (index < tokens.size())
			{
				Token token = tokens.get(index);
				if (token instanceof Operator)
					return;
				if (token instanceof Word word && word.text().equals("do") && !word.dynamic())
					return;
				index += 1;
			}
		}

		/**
		 * Skips the word that a {@code case} matches and its {@code in}, after which a pattern follows.
		 */
		private void skipCaseHeader()
		{
			while (index < tokens.size())
			{
				Token token = tokens.get(index);
				index += 1;
				if (token instanceof Word word && word.text().equals("in") && !word.dynamic())
				{
					cases.push(true);
					return;
				}
			}
		}

		/**
		 * Skips a {@code [[} condition up to its {@code ]]}, whose operators separate no commands.
		 */
		private void skipCondition()
		{
			while (index < tokens.size())
			{
				Token token = tokens.get(index);
				index += 1;
				if (token instanceof Word word)
				{
					addSubstitutions(word);
					if (word.text().equals("]]"))
						return;
				}
			}
		}
	}
}

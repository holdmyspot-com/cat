package io.github.cowwoc.cat.common.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of a Java source file that {@link TestabilityAuditTest} inspects.
 * <p>
 * The file's code is kept with every comment and every literal's content replaced by spaces, so that text in comments
 * and literals never matches a code pattern, while offsets and line breaks stay unchanged. The literals' contents are
 * kept separately.
 * <p>
 * Field declarations are found by tracking braces. A brace opens a type body when the code before it declares a class,
 * interface, enum or record. Anonymous class bodies are not type bodies, so their fields are not reported.
 *
 * @param code     the source with comments and literal contents replaced by spaces
 * @param literals the string and text block literals, in source order
 * @param fields   the field declarations in class, enum and record bodies, in source order
 */
record JavaSource(String code, List<Literal> literals, List<Field> fields)
{
	/**
	 * Matches a keyword that declares a class, interface or enum, unless it follows a dot as in {@code Foo.class}.
	 */
	private static final Pattern TYPE_KEYWORD = Pattern.compile("(?<![.\\w$])(class|interface|enum)(?![\\w$])");
	/**
	 * Matches a record declaration. {@code record} is a contextual keyword, so a name must follow it.
	 */
	private static final Pattern RECORD_KEYWORD = Pattern.compile("(?<![.\\w$])record\\s+[\\w$]+\\s*[<(]");
	/**
	 * Matches the start of an annotation.
	 */
	private static final Pattern ANNOTATION = Pattern.compile("@\\s*[\\w$.]+");
	/**
	 * Matches the {@code static} modifier.
	 */
	private static final Pattern STATIC = Pattern.compile("(?<![\\w$])static(?![\\w$])");
	/**
	 * Matches the {@code final} modifier.
	 */
	private static final Pattern FINAL = Pattern.compile("(?<![\\w$])final(?![\\w$])");

	/**
	 * Creates a source.
	 *
	 * @param code     the source with comments and literal contents replaced by spaces
	 * @param literals the string and text block literals, in source order
	 * @param fields   the field declarations in class, enum and record bodies, in source order
	 */
	JavaSource
	{
		requireThat(code, "code").isNotNull();
		literals = List.copyOf(literals);
		fields = List.copyOf(fields);
	}

	/**
	 * Parses a source file.
	 *
	 * @param text the file's contents
	 * @return the parsed source
	 */
	static JavaSource parse(String text)
	{
		List<Literal> literals = new ArrayList<>();
		String code = blankCommentsAndLiterals(text, literals);
		return new JavaSource(code, literals, findFields(code));
	}

	/**
	 * Returns the 1-based line number of an offset.
	 *
	 * @param offset an offset into the code
	 * @return the line that contains the offset
	 */
	int lineOf(int offset)
	{
		int line = 1;
		for (int i = 0; i < offset; ++i)
		{
			if (code.charAt(i) == '\n')
				++line;
		}
		return line;
	}

	/**
	 * Returns the source with comments and literal contents replaced by spaces.
	 *
	 * @param text     the source
	 * @param literals receives the string and text block literals
	 * @return the blanked source
	 */
	private static String blankCommentsAndLiterals(String text, List<Literal> literals)
	{
		StringBuilder code = new StringBuilder(text);
		int i = 0;
		while (i < text.length())
		{
			if (text.startsWith("//", i))
				i = blankUntil(code, text, i, text.indexOf('\n', i));
			else if (text.startsWith("/*", i))
				i = blankUntil(code, text, i, endOf(text, text.indexOf("*/", i + 2), 2));
			else if (text.startsWith("\"\"\"", i))
				i = readTextBlock(code, text, i, literals);
			else if (text.charAt(i) == '"' || text.charAt(i) == '\'')
				i = readQuoted(code, text, i, literals);
			else
				++i;
		}
		return code.toString();
	}

	/**
	 * Returns the end of a delimited region.
	 *
	 * @param text   the source
	 * @param start  the offset of the closing delimiter, or -1 if there is none
	 * @param length the length of the closing delimiter
	 * @return the offset after the closing delimiter, or the end of the source if there is none
	 */
	private static int endOf(String text, int start, int length)
	{
		if (start < 0)
			return text.length();
		return start + length;
	}

	/**
	 * Replaces a region with spaces, keeping line breaks.
	 *
	 * @param code  the blanked source
	 * @param text  the source
	 * @param start the first offset of the region
	 * @param end   the offset after the region, or -1 for the end of the source
	 * @return the offset after the region
	 */
	private static int blankUntil(StringBuilder code, String text, int start, int end)
	{
		int limit = end;
		if (limit < 0)
			limit = text.length();
		for (int i = start; i < limit; ++i)
		{
			if (text.charAt(i) != '\n')
				code.setCharAt(i, ' ');
		}
		return limit;
	}

	/**
	 * Reads a string or character literal and blanks its contents.
	 *
	 * @param code     the blanked source
	 * @param text     the source
	 * @param start    the offset of the opening quote
	 * @param literals receives a string literal
	 * @return the offset after the closing quote
	 */
	private static int readQuoted(StringBuilder code, String text, int start, List<Literal> literals)
	{
		char quote = text.charAt(start);
		StringBuilder value = new StringBuilder();
		int i = start + 1;
		while (i < text.length() && text.charAt(i) != quote && text.charAt(i) != '\n')
		{
			char next = text.charAt(i);
			if (next == '\\' && i + 1 < text.length())
			{
				++i;
				next = unescape(text.charAt(i));
			}
			value.append(next);
			++i;
		}
		blankUntil(code, text, start + 1, i);
		if (quote == '"')
			literals.add(new Literal(value.toString(), start));
		return i + 1;
	}

	/**
	 * Reads a text block and blanks its contents.
	 *
	 * @param code     the blanked source
	 * @param text     the source
	 * @param start    the offset of the opening delimiter
	 * @param literals receives the text block
	 * @return the offset after the closing delimiter
	 */
	private static int readTextBlock(StringBuilder code, String text, int start, List<Literal> literals)
	{
		int i = start + 3;
		while (i < text.length() && !text.startsWith("\"\"\"", i))
		{
			if (text.charAt(i) == '\\')
				++i;
			++i;
		}
		int end = Math.min(i, text.length());
		blankUntil(code, text, start + 3, end);
		literals.add(new Literal(text.substring(start + 3, end).strip(), start));
		return endOf(text, end, 3);
	}

	/**
	 * Returns the character that an escape sequence denotes.
	 *
	 * @param escaped the character after the backslash
	 * @return the denoted character
	 */
	private static char unescape(char escaped)
	{
		return switch (escaped)
		{
			case 'n' -> '\n';
			case 't' -> '\t';
			case 'r' -> '\r';
			default -> escaped;
		};
	}

	/**
	 * Returns the field declarations in class, enum and record bodies.
	 *
	 * @param code the blanked source
	 * @return the fields
	 */
	private static List<Field> findFields(String code)
	{
		List<Field> fields = new ArrayList<>();
		Deque<Frame> frames = new ArrayDeque<>();
		frames.push(new Frame(Kind.FILE, 0, false));
		int parentheses = 0;
		for (int i = 0; i < code.length(); ++i)
		{
			char next = code.charAt(i);
			Frame frame = frames.peek();
			switch (next)
			{
				case '(' -> ++parentheses;
				case ')' -> --parentheses;
				case '{' -> frames.push(open(frame, code, i, parentheses));
				case '}' ->
				{
					Frame closed = frames.pop();
					if (frames.isEmpty())
						return fields;
					if (!closed.continuesDeclaration())
						frames.peek().startDeclaration(i + 1);
				}
				case ';' ->
				{
					if (parentheses == frame.parentheses())
					{
						if (frame.kind() == Kind.CLASS && !frame.inEnumConstants())
							addField(code, frame.declarationStart(), i, frames.size() == 2, fields);
						frame.endEnumConstants();
						frame.startDeclaration(i + 1);
					}
				}
				default ->
				{
				}
			}
		}
		return fields;
	}

	/**
	 * Returns the frame that a brace opens.
	 *
	 * @param frame       the enclosing frame
	 * @param code        the blanked source
	 * @param brace       the offset of the brace
	 * @param parentheses the parenthesis depth at the brace
	 * @return the new frame
	 */
	private static Frame open(Frame frame, String code, int brace, int parentheses)
	{
		String declaration = withoutAnnotations(code.substring(frame.declarationStart(), brace));
		boolean isInitializer = frame.kind() != Kind.OTHER &&
			(parentheses > frame.parentheses() || hasInitializer(declaration));
		if (isInitializer)
			return new Frame(Kind.OTHER, parentheses, true);
		frame.startDeclaration(brace + 1);
		Matcher keyword = TYPE_KEYWORD.matcher(declaration);
		if (keyword.find())
		{
			if (keyword.group(1).equals("interface"))
				return new Frame(Kind.INTERFACE, parentheses, false);
			Frame type = new Frame(Kind.CLASS, parentheses, false);
			if (keyword.group(1).equals("enum"))
				type.startEnumConstants();
			type.startDeclaration(brace + 1);
			return type;
		}
		if (RECORD_KEYWORD.matcher(declaration).find())
		{
			Frame type = new Frame(Kind.CLASS, parentheses, false);
			type.startDeclaration(brace + 1);
			return type;
		}
		return new Frame(Kind.OTHER, parentheses, false);
	}

	/**
	 * Indicates whether a member declaration has an initializer.
	 *
	 * @param declaration a member declaration without annotations
	 * @return {@code true} if it contains {@code =} outside parentheses
	 */
	private static boolean hasInitializer(String declaration)
	{
		return initializerOffset(declaration) >= 0;
	}

	/**
	 * Returns the offset of a declaration's initializer.
	 *
	 * @param declaration a member declaration without annotations
	 * @return the offset of the first {@code =} outside parentheses that is not part of an operator, or -1 if there is
	 *   none
	 */
	private static int initializerOffset(String declaration)
	{
		int parentheses = 0;
		for (int i = 0; i < declaration.length(); ++i)
		{
			char next = declaration.charAt(i);
			if (next == '(')
				++parentheses;
			else if (next == ')')
				--parentheses;
			else if (next == '=' && parentheses == 0 && isAssignment(declaration, i))
				return i;
		}
		return -1;
	}

	/**
	 * Indicates whether an {@code =} is an assignment rather than part of a comparison or lambda.
	 *
	 * @param declaration a member declaration
	 * @param offset      the offset of the {@code =}
	 * @return {@code true} if it is an assignment
	 */
	private static boolean isAssignment(String declaration, int offset)
	{
		boolean isEquality = offset + 1 < declaration.length() && declaration.charAt(offset + 1) == '=';
		boolean isComparison = offset > 0 && "=!<>".indexOf(declaration.charAt(offset - 1)) >= 0;
		return !isEquality && !isComparison;
	}

	/**
	 * Returns a declaration with its annotations, including their arguments, replaced by spaces.
	 *
	 * @param declaration a declaration
	 * @return the declaration without annotations
	 */
	private static String withoutAnnotations(String declaration)
	{
		StringBuilder result = new StringBuilder(declaration);
		Matcher annotation = ANNOTATION.matcher(declaration);
		int searchFrom = 0;
		while (annotation.find(searchFrom))
		{
			int end = annotation.end();
			int argumentsStart = end;
			while (argumentsStart < declaration.length() && Character.isWhitespace(declaration.charAt(argumentsStart)))
				++argumentsStart;
			if (argumentsStart < declaration.length() && declaration.charAt(argumentsStart) == '(')
				end = matchingParenthesis(declaration, argumentsStart) + 1;
			for (int i = annotation.start(); i < end; ++i)
				result.setCharAt(i, ' ');
			searchFrom = end;
		}
		return result.toString();
	}

	/**
	 * Returns the offset of the parenthesis that closes an opening one.
	 *
	 * @param text  the text
	 * @param start the offset of the opening parenthesis
	 * @return the offset of the closing parenthesis, or the last offset if there is none
	 */
	private static int matchingParenthesis(String text, int start)
	{
		int depth = 0;
		for (int i = start; i < text.length(); ++i)
		{
			if (text.charAt(i) == '(')
				++depth;
			else if (text.charAt(i) == ')')
			{
				--depth;
				if (depth == 0)
					return i;
			}
		}
		return text.length() - 1;
	}

	/**
	 * Adds a member declaration that ends with a semicolon if it declares fields.
	 *
	 * @param code       the blanked source
	 * @param start      the offset where the declaration starts
	 * @param end        the offset of the semicolon
	 * @param isTopLevel {@code true} if the declaration is in a top-level type
	 * @param fields     receives the field
	 */
	private static void addField(String code, int start, int end, boolean isTopLevel, List<Field> fields)
	{
		String declaration = withoutAnnotations(code.substring(start, end));
		if (declaration.isBlank())
			return;
		int initializer = initializerOffset(declaration);
		String head = declaration;
		if (initializer >= 0)
			head = declaration.substring(0, initializer);
		// An abstract or native method has parameters before any initializer
		if (head.indexOf('(') >= 0)
			return;
		// Annotations were replaced by spaces, so the first remaining character starts the declaration
		int offset = start;
		while (Character.isWhitespace(declaration.charAt(offset - start)))
			++offset;
		fields.add(new Field(offset, head.strip().replaceAll("\\s+", " "), isTopLevel, STATIC.matcher(head).find(),
			FINAL.matcher(head).find()));
	}

	/**
	 * A string or text block literal.
	 *
	 * @param value  the literal's value
	 * @param offset the offset of its opening quote
	 */
	record Literal(String value, int offset)
	{
	}

	/**
	 * A field declaration in a class, enum or record body.
	 *
	 * @param offset      the offset where the declaration starts
	 * @param declaration the declaration's modifiers, type and names, without annotations or initializer
	 * @param isTopLevel  {@code true} if the field belongs to a top-level type rather than a nested or local one
	 * @param isStatic    {@code true} if the field is static
	 * @param isFinal     {@code true} if the field is final
	 */
	record Field(int offset, String declaration, boolean isTopLevel, boolean isStatic, boolean isFinal)
	{
	}

	/**
	 * The kind of code that a pair of braces encloses.
	 */
	private enum Kind
	{
		/**
		 * The top level of the file.
		 */
		FILE,
		/**
		 * A class, enum or record body, whose fields are reported.
		 */
		CLASS,
		/**
		 * An interface or annotation body, whose fields are implicitly static and final.
		 */
		INTERFACE,
		/**
		 * A method body, initializer, lambda body, array initializer or anonymous class body.
		 */
		OTHER
	}

	/**
	 * The state of one pair of braces while the parser is inside it.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe.
	 */
	private static final class Frame
	{
		private final Kind kind;
		private final int parentheses;
		private final boolean continuesDeclaration;
		private int declarationStart;
		private boolean inEnumConstants;

		/**
		 * Creates a frame.
		 *
		 * @param kind                 the kind of code that the braces enclose
		 * @param parentheses          the parenthesis depth at the opening brace
		 * @param continuesDeclaration {@code true} if the braces are part of the enclosing member's declaration, as an
		 *                             initializer is
		 */
		Frame(Kind kind, int parentheses, boolean continuesDeclaration)
		{
			this.kind = kind;
			this.parentheses = parentheses;
			this.continuesDeclaration = continuesDeclaration;
		}

		/**
		 * Returns the kind of code that the braces enclose.
		 *
		 * @return the kind
		 */
		Kind kind()
		{
			return kind;
		}

		/**
		 * Returns the parenthesis depth at the opening brace.
		 *
		 * @return the depth
		 */
		int parentheses()
		{
			return parentheses;
		}

		/**
		 * Indicates whether the braces are part of the enclosing member's declaration.
		 *
		 * @return {@code true} if they are
		 */
		boolean continuesDeclaration()
		{
			return continuesDeclaration;
		}

		/**
		 * Returns the offset where the current member declaration starts.
		 *
		 * @return the offset
		 */
		int declarationStart()
		{
			return declarationStart;
		}

		/**
		 * Starts a new member declaration.
		 *
		 * @param offset the offset where it starts
		 */
		void startDeclaration(int offset)
		{
			declarationStart = offset;
		}

		/**
		 * Indicates whether the parser is in an enum's constant list.
		 *
		 * @return {@code true} if it is
		 */
		boolean inEnumConstants()
		{
			return inEnumConstants;
		}

		/**
		 * Marks the start of an enum's constant list.
		 */
		void startEnumConstants()
		{
			inEnumConstants = true;
		}

		/**
		 * Marks the end of an enum's constant list.
		 */
		void endEnumConstants()
		{
			inEnumConstants = false;
		}
	}
}

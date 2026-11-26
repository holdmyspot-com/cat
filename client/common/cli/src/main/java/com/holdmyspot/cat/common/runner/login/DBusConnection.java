package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A minimal client of a D-Bus message bus over a socket in the file system, which authenticates as the process's user
 * and calls methods, for the Secret Service reads of {@link LinuxSecretService}.
 * <p>
 * It marshals and unmarshals the types {@code y}, {@code b}, {@code u}, {@code i}, {@code s}, {@code o}, {@code g},
 * {@code v}, arrays, structures and dictionary entries in little-endian order. A variant that it writes holds a string.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
final class DBusConnection implements AutoCloseable
{
	private static final byte METHOD_CALL = 1;
	private static final byte METHOD_RETURN = 2;
	private static final byte ERROR = 3;
	private static final int FIELD_PATH = 1;
	private static final int FIELD_INTERFACE = 2;
	private static final int FIELD_MEMBER = 3;
	private static final int FIELD_ERROR_NAME = 4;
	private static final int FIELD_REPLY_SERIAL = 5;
	private static final int FIELD_DESTINATION = 6;
	private static final int FIELD_SIGNATURE = 8;
	private static final int MAXIMUM_MESSAGE_BYTES = 1 << 24;

	private final SocketChannel channel;
	private final ScheduledExecutorService watchdog;
	private final ScheduledFuture<?> deadline;
	private int serial;

	/**
	 * Connects to a bus and authenticates as a user.
	 *
	 * @param socket  the bus's socket
	 * @param uid     the user's numeric identifier
	 * @param timeout how long the connection may stay open before the client closes it
	 * @throws IOException if the bus cannot be reached or refuses the user
	 */
	DBusConnection(Path socket, long uid, Duration timeout) throws IOException
	{
		requireThat(socket, "socket").isNotNull();
		requireThat(timeout, "timeout").isNotNull();
		this.channel = SocketChannel.open(StandardProtocolFamily.UNIX);
		this.watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
		this.deadline = watchdog.schedule(() ->
		{
			try
			{
				channel.close();
			}
			catch (IOException _)
			{
				// The channel ends either way
			}
		}, timeout.toMillis(), TimeUnit.MILLISECONDS);
		try
		{
			channel.connect(UnixDomainSocketAddress.of(socket));
			String identity = HexFormat.of().formatHex(Long.toString(uid).getBytes(US_ASCII));
			write(("\0AUTH EXTERNAL " + identity + "\r\n").getBytes(US_ASCII));
			String answer = readLine();
			if (!answer.startsWith("OK "))
				throw new IOException("The session bus refused the user's authentication");
			write("BEGIN\r\n".getBytes(US_ASCII));
			call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello", "", List.of());
		}
		catch (IOException | RuntimeException e)
		{
			close();
			throw e;
		}
	}

	/**
	 * Calls a method and waits for its reply.
	 *
	 * @param destination the bus name of the service
	 * @param path        the object's path
	 * @param iface       the method's interface
	 * @param member      the method's name
	 * @param signature   the signature of the arguments
	 * @param arguments   the arguments: a {@code String} for {@code s}, {@code o}, {@code g} and a variant, a
	 *                    {@code List} for an array, a {@code Object[]} for a structure or dictionary entry
	 * @return the reply's values, as the arguments are given, with {@code byte[]} for {@code ay} and {@code Long} for
	 *   numbers
	 * @throws IOException if the call fails or the reply is an error, whose name the message gives
	 */
	List<Object> call(String destination, String path, String iface, String member, String signature,
		List<Object> arguments) throws IOException
	{
		Writer body = new Writer();
		body.values(signature, arguments);
		++serial;
		int callSerial = serial;
		Writer message = new Writer();
		message.bytes.write('l');
		message.bytes.write(METHOD_CALL);
		message.bytes.write(0);
		message.bytes.write(1);
		message.uint32(body.bytes.size());
		message.uint32(callSerial);
		List<Object> fields = new ArrayList<>();
		fields.add(new Object[]{(long) FIELD_PATH, new Variant("o", path)});
		fields.add(new Object[]{(long) FIELD_INTERFACE, new Variant("s", iface)});
		fields.add(new Object[]{(long) FIELD_MEMBER, new Variant("s", member)});
		fields.add(new Object[]{(long) FIELD_DESTINATION, new Variant("s", destination)});
		if (!signature.isEmpty())
			fields.add(new Object[]{(long) FIELD_SIGNATURE, new Variant("g", signature)});
		message.value("a(yv)", fields);
		message.align(8);
		message.bytes.writeBytes(body.bytes.toByteArray());
		write(message.bytes.toByteArray());
		while (true)
		{
			Reply reply = readMessage();
			if (reply.replySerial != callSerial)
				continue;
			if (reply.type == ERROR)
				throw new IOException("The bus answered " + member + " with the error " + reply.errorName);
			if (reply.type != METHOD_RETURN)
				continue;
			return reply.values;
		}
	}

	/**
	 * Writes bytes to the bus.
	 *
	 * @param bytes the bytes
	 * @throws IOException if they cannot be written
	 */
	private void write(byte[] bytes) throws IOException
	{
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		while (buffer.hasRemaining())
			channel.write(buffer);
	}

	/**
	 * Reads a line of the authentication exchange.
	 *
	 * @return the line without its end
	 * @throws IOException if the bus ends the connection
	 */
	private String readLine() throws IOException
	{
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		ByteBuffer one = ByteBuffer.allocate(1);
		while (line.size() < 4096)
		{
			one.clear();
			if (channel.read(one) < 0)
				throw new IOException("The session bus ended the connection");
			byte b = one.get(0);
			if (b == '\n')
				return line.toString(US_ASCII).strip();
			line.write(b);
		}
		throw new IOException("The session bus sent an overlong line");
	}

	/**
	 * Reads exactly a number of bytes.
	 *
	 * @param count the number
	 * @return the bytes
	 * @throws IOException if the bus ends the connection first
	 */
	private ByteBuffer readFully(int count) throws IOException
	{
		ByteBuffer buffer = ByteBuffer.allocate(count);
		while (buffer.hasRemaining())
		{
			if (channel.read(buffer) < 0)
				throw new IOException("The session bus ended the connection");
		}
		return buffer.flip().order(ByteOrder.LITTLE_ENDIAN);
	}

	/**
	 * Reads a message.
	 *
	 * @return the message
	 * @throws IOException if the message cannot be read or parsed
	 */
	private Reply readMessage() throws IOException
	{
		ByteBuffer fixed = readFully(16);
		byte endian = fixed.get(0);
		if (endian == 'B')
			fixed.order(ByteOrder.BIG_ENDIAN);
		else if (endian != 'l')
			throw new IOException("The session bus sent a message in no known byte order");
		byte type = fixed.get(1);
		long bodyLength = Integer.toUnsignedLong(fixed.getInt(4));
		long fieldsLength = Integer.toUnsignedLong(fixed.getInt(12));
		long headerEnd = 16 + fieldsLength;
		long padded = (headerEnd + 7) & ~7L;
		if (padded + bodyLength > MAXIMUM_MESSAGE_BYTES)
			throw new IOException("The session bus sent an overlong message");
		ByteBuffer rest = readFully((int) (padded - 16 + bodyLength));
		ByteBuffer whole = ByteBuffer.allocate((int) (padded + bodyLength)).order(fixed.order());
		whole.put(fixed.rewind()).put(rest).flip();
		Reader header = new Reader(whole, 12);
		List<Object> fields = header.values("a(yv)");
		long replySerial = -1;
		String errorName = "";
		String signature = "";
		for (Object field : (List<?>) fields.getFirst())
		{
			Object[] entry = (Object[]) field;
			long code = (Long) entry[0];
			Object value = entry[1];
			if (code == FIELD_REPLY_SERIAL)
				replySerial = (Long) value;
			else if (code == FIELD_ERROR_NAME)
				errorName = String.valueOf(value);
			else if (code == FIELD_SIGNATURE)
				signature = String.valueOf(value);
		}
		List<Object> values = List.of();
		if (type == METHOD_RETURN && !signature.isEmpty())
			values = new Reader(whole, (int) padded).values(signature);
		return new Reply(type, replySerial, errorName, values);
	}

	@Override
	public void close() throws IOException
	{
		deadline.cancel(false);
		watchdog.shutdownNow();
		channel.close();
	}

	/**
	 * A message that the bus sent.
	 *
	 * @param type        the message's type
	 * @param replySerial the serial of the call that it answers, or {@code -1}
	 * @param errorName   the error's name, or empty
	 * @param values      the body's values
	 */
	private record Reply(byte type, long replySerial, String errorName, List<Object> values)
	{
	}

	/**
	 * A variant value that the client writes.
	 *
	 * @param signature the value's signature
	 * @param value     the value
	 */
	record Variant(String signature, Object value)
	{
	}

	/**
	 * Returns the end of the complete type that starts at an index of a signature.
	 *
	 * @param signature the signature
	 * @param start     the index of the type's first character
	 * @return the index after its last character
	 */
	private static int typeEnd(String signature, int start)
	{
		char c = signature.charAt(start);
		if (c == 'a')
			return typeEnd(signature, start + 1);
		if (c == '(' || c == '{')
		{
			char close;
			if (c == '(')
				close = ')';
			else
				close = '}';
			int index = start + 1;
			while (signature.charAt(index) != close)
				index = typeEnd(signature, index);
			return index + 1;
		}
		return start + 1;
	}

	/**
	 * Returns the alignment of a type.
	 *
	 * @param type the type's first character
	 * @return the alignment in bytes
	 */
	private static int alignment(char type)
	{
		return switch (type)
		{
			case 'y', 'g', 'v' -> 1;
			case 'n', 'q' -> 2;
			case 'b', 'i', 'u', 's', 'o', 'a', 'h' -> 4;
			default -> 8;
		};
	}

	/**
	 * Marshals values.
	 */
	private static final class Writer
	{
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		/**
		 * Pads to an alignment.
		 *
		 * @param boundary the alignment
		 */
		void align(int boundary)
		{
			while (bytes.size() % boundary != 0)
				bytes.write(0);
		}

		/**
		 * Writes an unsigned 32-bit number.
		 *
		 * @param value the number
		 */
		void uint32(long value)
		{
			align(4);
			bytes.write((int) (value & 0xFF));
			bytes.write((int) ((value >> 8) & 0xFF));
			bytes.write((int) ((value >> 16) & 0xFF));
			bytes.write((int) ((value >> 24) & 0xFF));
		}

		/**
		 * Writes values of a signature's complete types.
		 *
		 * @param signature the signature
		 * @param values    the values
		 */
		void values(String signature, List<Object> values)
		{
			int index = 0;
			int value = 0;
			while (index < signature.length())
			{
				int end = typeEnd(signature, index);
				value(signature.substring(index, end), values.get(value));
				++value;
				index = end;
			}
		}

		/**
		 * Writes a value of a complete type.
		 *
		 * @param type  the type
		 * @param value the value
		 */
		void value(String type, Object value)
		{
			char c = type.charAt(0);
			switch (c)
			{
				case 'y' -> bytes.write((int) ((Number) value).longValue());
				case 'b', 'u', 'i' -> uint32(((Number) value).longValue());
				case 's', 'o' ->
				{
					byte[] text = ((String) value).getBytes(UTF_8);
					uint32(text.length);
					bytes.writeBytes(text);
					bytes.write(0);
				}
				case 'g' ->
				{
					byte[] text = ((String) value).getBytes(UTF_8);
					bytes.write(text.length);
					bytes.writeBytes(text);
					bytes.write(0);
				}
				case 'v' ->
				{
					Variant variant;
					if (value instanceof Variant given)
						variant = given;
					else
						variant = new Variant("s", value);
					value("g", variant.signature());
					value(variant.signature(), variant.value());
				}
				case 'a' ->
				{
					String element = type.substring(1);
					uint32(0);
					int lengthAt = bytes.size() - 4;
					align(alignment(element.charAt(0)));
					int start = bytes.size();
					for (Object item : (List<?>) value)
						value(element, item);
					int length = bytes.size() - start;
					byte[] written = bytes.toByteArray();
					written[lengthAt] = (byte) length;
					written[lengthAt + 1] = (byte) (length >> 8);
					written[lengthAt + 2] = (byte) (length >> 16);
					written[lengthAt + 3] = (byte) (length >> 24);
					bytes.reset();
					bytes.writeBytes(written);
				}
				case '(', '{' ->
				{
					align(8);
					Object[] members = (Object[]) value;
					String inner = type.substring(1, type.length() - 1);
					int index = 0;
					int member = 0;
					while (index < inner.length())
					{
						int end = typeEnd(inner, index);
						value(inner.substring(index, end), members[member]);
						++member;
						index = end;
					}
				}
				default -> throw new IllegalArgumentException("The client does not write the type " + type);
			}
		}
	}

	/**
	 * Unmarshals values.
	 */
	private static final class Reader
	{
		private final ByteBuffer buffer;

		/**
		 * Creates a reader.
		 *
		 * @param buffer the whole message, whose positions alignment counts from
		 * @param start  the position of the first value
		 */
		Reader(ByteBuffer buffer, int start)
		{
			this.buffer = buffer.duplicate().order(buffer.order());
			this.buffer.position(start);
		}

		/**
		 * Skips to an alignment.
		 *
		 * @param boundary the alignment
		 * @throws IOException if the message ends first
		 */
		void align(int boundary) throws IOException
		{
			int position = (buffer.position() + boundary - 1) / boundary * boundary;
			if (position > buffer.limit())
				throw new IOException("The session bus sent a truncated message");
			buffer.position(position);
		}

		/**
		 * Reads values of a signature's complete types.
		 *
		 * @param signature the signature
		 * @return the values
		 * @throws IOException if the message cannot be parsed
		 */
		List<Object> values(String signature) throws IOException
		{
			List<Object> values = new ArrayList<>();
			int index = 0;
			while (index < signature.length())
			{
				int end = typeEnd(signature, index);
				values.add(value(signature.substring(index, end)));
				index = end;
			}
			return values;
		}

		/**
		 * Reads a value of a complete type.
		 *
		 * @param type the type
		 * @return the value
		 * @throws IOException if the message cannot be parsed
		 */
		Object value(String type) throws IOException
		{
			try
			{
				char c = type.charAt(0);
				return switch (c)
				{
					case 'y' -> (long) Byte.toUnsignedInt(buffer.get());
					case 'n', 'q' ->
					{
						align(2);
						yield (long) buffer.getShort();
					}
					case 'b', 'u', 'h' ->
					{
						align(4);
						yield Integer.toUnsignedLong(buffer.getInt());
					}
					case 'i' ->
					{
						align(4);
						yield (long) buffer.getInt();
					}
					case 'x', 't', 'd' ->
					{
						align(8);
						yield buffer.getLong();
					}
					case 's', 'o' ->
					{
						align(4);
						int length = buffer.getInt();
						byte[] text = new byte[length];
						buffer.get(text);
						buffer.get();
						yield new String(text, UTF_8);
					}
					case 'g' ->
					{
						int length = Byte.toUnsignedInt(buffer.get());
						byte[] text = new byte[length];
						buffer.get(text);
						buffer.get();
						yield new String(text, UTF_8);
					}
					case 'v' ->
					{
						String signature = (String) value("g");
						yield value(signature);
					}
					case 'a' ->
					{
						align(4);
						int length = buffer.getInt();
						String element = type.substring(1);
						align(alignment(element.charAt(0)));
						int end = buffer.position() + length;
						if (element.equals("y"))
						{
							byte[] array = new byte[length];
							buffer.get(array);
							yield array;
						}
						List<Object> items = new ArrayList<>();
						while (buffer.position() < end)
							items.add(value(element));
						yield items;
					}
					case '(', '{' ->
					{
						align(8);
						String inner = type.substring(1, type.length() - 1);
						yield values(inner).toArray();
					}
					default -> throw new IOException("The session bus sent a value of the unknown type " + type);
				};
			}
			catch (RuntimeException e)
			{
				throw new IOException("The session bus sent a malformed message", e);
			}
		}
	}
}

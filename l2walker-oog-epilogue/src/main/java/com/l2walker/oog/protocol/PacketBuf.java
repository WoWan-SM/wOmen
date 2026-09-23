package com.l2walker.oog.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Little-endian packet builder and reader for L2 login/game bodies. */
public final class PacketBuf {

	private PacketBuf() {
	}

	public static final class Writer {
		private final ByteBuffer buffer;

		public Writer(int capacity) {
			this.buffer = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
		}

		public Writer writeC(int value) {
			buffer.put((byte) value);
			return this;
		}

		public Writer writeH(int value) {
			buffer.putShort((short) value);
			return this;
		}

		public Writer writeD(int value) {
			buffer.putInt(value);
			return this;
		}

		public Writer writeB(byte[] value) {
			buffer.put(value);
			return this;
		}

		public Writer writeS(String value) {
			buffer.put(value.getBytes(StandardCharsets.UTF_16LE));
			buffer.putShort((short) 0);
			return this;
		}

		public byte[] toByteArray() {
			byte[] out = new byte[buffer.position()];
			buffer.flip();
			buffer.get(out);
			return out;
		}
	}

	public static final class Reader {
		private final ByteBuffer buffer;

		public Reader(byte[] data) {
			this.buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		}

		public int remaining() {
			return buffer.remaining();
		}

		public int readC() {
			return buffer.get() & 0xff;
		}

		public int readH() {
			return buffer.getShort() & 0xffff;
		}

		public int readD() {
			return buffer.getInt();
		}

		public byte[] readB(int length) {
			byte[] value = new byte[length];
			buffer.get(value);
			return value;
		}

		public String readS() {
			int start = buffer.position();
			while (buffer.remaining() >= 2) {
				int lo = buffer.get() & 0xff;
				int hi = buffer.get() & 0xff;
				if (lo == 0 && hi == 0) {
					int len = buffer.position() - start - 2;
					byte[] encoded = new byte[len];
					buffer.position(start);
					buffer.get(encoded);
					buffer.getShort();
					return new String(encoded, StandardCharsets.UTF_16LE);
				}
			}
			throw new IllegalArgumentException("unterminated L2 string");
		}
	}
}

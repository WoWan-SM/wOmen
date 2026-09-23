package com.l2walker.oog.crypt;

/** Little-endian helpers shared by login checksum, XOR pass, and game crypt. */
public final class LittleEndian {

	private LittleEndian() {
	}

	public static int readInt(byte[] raw, int offset) {
		return (raw[offset] & 0xff)
				| ((raw[offset + 1] & 0xff) << 8)
				| ((raw[offset + 2] & 0xff) << 16)
				| ((raw[offset + 3] & 0xff) << 24);
	}

	public static void writeInt(byte[] raw, int offset, int value) {
		raw[offset] = (byte) (value & 0xff);
		raw[offset + 1] = (byte) ((value >>> 8) & 0xff);
		raw[offset + 2] = (byte) ((value >>> 16) & 0xff);
		raw[offset + 3] = (byte) ((value >>> 24) & 0xff);
	}
}

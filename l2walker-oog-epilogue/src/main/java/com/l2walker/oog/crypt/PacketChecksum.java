package com.l2walker.oog.crypt;

/** XOR checksum used on Lineage 2 login packets after the initial Init frame. */
public final class PacketChecksum {

	private PacketChecksum() {
	}

	public static void append(byte[] raw, int offset, int size) {
		if (size < 4 || (size & 3) != 0) {
			throw new IllegalArgumentException("checksum size must be a positive multiple of 4");
		}
		int checksum = 0;
		int end = offset + size - 4;
		for (int i = offset; i < end; i += 4) {
			checksum ^= LittleEndian.readInt(raw, i);
		}
		LittleEndian.writeInt(raw, end, checksum);
	}

	public static boolean verify(byte[] raw, int offset, int size) {
		if (size < 8 || (size & 3) != 0) {
			return false;
		}
		int checksum = 0;
		int end = offset + size - 4;
		for (int i = offset; i < end; i += 4) {
			checksum ^= LittleEndian.readInt(raw, i);
		}
		return checksum == LittleEndian.readInt(raw, end);
	}
}

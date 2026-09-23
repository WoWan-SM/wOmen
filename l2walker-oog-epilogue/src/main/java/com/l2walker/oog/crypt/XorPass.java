package com.l2walker.oog.crypt;

/**
 * XOR-pass used only on the first login-server packet (Init), before static Blowfish.
 * Bytes {@code [0, 4)} stay plaintext; the running key is stored at {@code size - 8}.
 */
public final class XorPass {

	private XorPass() {
	}

	public static void encrypt(byte[] raw, int offset, int size, int key) {
		int pos = offset + 4;
		int stop = offset + size - 8;
		int ecx = key;
		while (pos < stop) {
			int edx = LittleEndian.readInt(raw, pos);
			ecx += edx;
			LittleEndian.writeInt(raw, pos, edx ^ ecx);
			pos += 4;
		}
		LittleEndian.writeInt(raw, stop, ecx);
	}

	public static void decrypt(byte[] raw, int offset, int size) {
		int pos = offset + size - 12;
		int stop = offset + 4;
		int ecx = LittleEndian.readInt(raw, offset + size - 8);
		while (pos >= stop) {
			int edx = LittleEndian.readInt(raw, pos) ^ ecx;
			ecx -= edx;
			LittleEndian.writeInt(raw, pos, edx);
			pos -= 4;
		}
	}
}

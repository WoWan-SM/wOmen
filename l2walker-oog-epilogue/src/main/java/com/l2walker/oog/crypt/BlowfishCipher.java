package com.l2walker.oog.crypt;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Lineage 2 login Blowfish: standard ECB Blowfish with little-endian 32-bit words.
 * Block bytes are word-swapped around the JCE cipher so the result matches the
 * L2J BlowfishEngine layout (the key schedule itself stays big-endian).
 */
public final class BlowfishCipher {

	private final SecretKeySpec key;

	private BlowfishCipher(byte[] keyBytes) {
		if (keyBytes.length < 4) {
			throw new IllegalArgumentException("Blowfish key must be at least 4 bytes");
		}
		this.key = new SecretKeySpec(keyBytes.clone(), "Blowfish");
	}

	public static BlowfishCipher forKey(byte[] keyBytes) {
		return new BlowfishCipher(keyBytes);
	}

	public void encryptInPlace(byte[] data) {
		process(data, Cipher.ENCRYPT_MODE);
	}

	public void decryptInPlace(byte[] data) {
		process(data, Cipher.DECRYPT_MODE);
	}

	private void process(byte[] data, int mode) {
		if ((data.length % 8) != 0) {
			throw new IllegalArgumentException("Blowfish input length must be a multiple of 8, got " + data.length);
		}
		try {
			Cipher cipher = Cipher.getInstance("Blowfish/ECB/NoPadding");
			cipher.init(mode, key);
			byte[] block = new byte[8];
			byte[] out = new byte[8];
			for (int offset = 0; offset < data.length; offset += 8) {
				swapWords(data, offset, block, 0);
				int written = cipher.update(block, 0, 8, out, 0);
				if (written != 8) {
					throw new IllegalStateException("Unexpected Blowfish block size " + written);
				}
				swapWords(out, 0, data, offset);
			}
		} catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException("Blowfish failed", ex);
		}
	}

	/** Reverse each 4-byte word so JCE sees the same integers as the L2 engine. */
	public static void swapWords(byte[] src, int srcOffset, byte[] dst, int dstOffset) {
		dst[dstOffset] = src[srcOffset + 3];
		dst[dstOffset + 1] = src[srcOffset + 2];
		dst[dstOffset + 2] = src[srcOffset + 1];
		dst[dstOffset + 3] = src[srcOffset];
		dst[dstOffset + 4] = src[srcOffset + 7];
		dst[dstOffset + 5] = src[srcOffset + 6];
		dst[dstOffset + 6] = src[srcOffset + 5];
		dst[dstOffset + 7] = src[srcOffset + 4];
	}
}

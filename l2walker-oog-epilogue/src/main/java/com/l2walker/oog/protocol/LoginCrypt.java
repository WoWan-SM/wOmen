package com.l2walker.oog.protocol;

import com.l2walker.oog.crypt.BlowfishCipher;
import com.l2walker.oog.crypt.PacketChecksum;
import com.l2walker.oog.crypt.XorPass;

/**
 * Login-server framing crypt used by L2J-style Gracia servers.
 * The first server packet is XOR-passed and encrypted with the static Blowfish key.
 * Later packets in both directions use the session key from Init plus an XOR checksum.
 */
public final class LoginCrypt {

	static final byte[] STATIC_BLOWFISH_KEY = {
			0x6b, 0x60, (byte) 0xcb, 0x5b,
			(byte) 0x82, (byte) 0xce, (byte) 0x90, (byte) 0xb1,
			(byte) 0xcc, 0x2b, 0x6c, 0x55,
			0x6c, 0x6c, 0x6c, 0x6c
	};

	private BlowfishCipher dynamic;

	public void setKey(byte[] key) {
		this.dynamic = BlowfishCipher.forKey(key);
	}

	public byte[] encryptInitial(byte[] payload, int xorKey) {
		int size = pad8(payload.length + 8);
		byte[] raw = copy(payload, size);
		XorPass.encrypt(raw, 0, size, xorKey);
		BlowfishCipher.forKey(STATIC_BLOWFISH_KEY).encryptInPlace(raw);
		return raw;
	}

	public static byte[] decryptInitial(byte[] raw) {
		byte[] plain = raw.clone();
		BlowfishCipher.forKey(STATIC_BLOWFISH_KEY).decryptInPlace(plain);
		XorPass.decrypt(plain, 0, plain.length);
		return plain;
	}

	public byte[] encrypt(byte[] payload) {
		requireDynamic();
		int size = pad8(payload.length + 4);
		byte[] raw = copy(payload, size);
		PacketChecksum.append(raw, 0, size);
		dynamic.encryptInPlace(raw);
		return raw;
	}

	public byte[] decrypt(byte[] raw) {
		requireDynamic();
		byte[] plain = raw.clone();
		dynamic.decryptInPlace(plain);
		if (!PacketChecksum.verify(plain, 0, plain.length)) {
			throw new IllegalArgumentException("login packet checksum mismatch");
		}
		return plain;
	}

	private void requireDynamic() {
		if (dynamic == null) {
			throw new IllegalStateException("session Blowfish key was not set");
		}
	}

	private static int pad8(int size) {
		int rem = size % 8;
		return rem == 0 ? size : size + (8 - rem);
	}

	private static byte[] copy(byte[] payload, int size) {
		byte[] raw = new byte[size];
		System.arraycopy(payload, 0, raw, 0, payload.length);
		return raw;
	}
}

package com.l2walker.oog.crypt;

/**
 * Game-server XOR cipher (Gracia Epilogue).
 * The server's first outgoing packet and the client's first incoming packet stay plaintext;
 * every later body is XOR'd and the key's second dword advances by the body length.
 */
public final class GameXorCrypt {

	public enum Role {
		CLIENT,
		SERVER
	}

	private final Role role;
	private final byte[] inKey = new byte[16];
	private final byte[] outKey = new byte[16];
	private boolean passThrough = true;
	private boolean keyed;

	public GameXorCrypt(Role role) {
		this.role = role;
	}

	public void setKey(byte[] key8) {
		if (key8.length < 8) {
			throw new IllegalArgumentException("game XOR key must be 8 bytes");
		}
		System.arraycopy(key8, 0, inKey, 0, 8);
		System.arraycopy(key8, 0, inKey, 8, 8);
		System.arraycopy(key8, 0, outKey, 0, 8);
		System.arraycopy(key8, 0, outKey, 8, 8);
		keyed = true;
	}

	public void encrypt(byte[] body) {
		if (role == Role.SERVER && passThrough) {
			passThrough = false;
			return;
		}
		requireKey();
		applyEncrypt(body, outKey);
	}

	public void decrypt(byte[] body) {
		if (role == Role.CLIENT && passThrough) {
			passThrough = false;
			return;
		}
		requireKey();
		applyDecrypt(body, inKey);
	}

	private void requireKey() {
		if (!keyed) {
			throw new IllegalStateException("game XOR key was not set");
		}
	}

	private static void applyEncrypt(byte[] body, byte[] key) {
		int temp = 0;
		for (int i = 0; i < body.length; i++) {
			int plain = body[i] & 0xff;
			temp = plain ^ (key[i & 15] & 0xff) ^ temp;
			body[i] = (byte) temp;
		}
		shift(key, body.length);
	}

	private static void applyDecrypt(byte[] body, byte[] key) {
		int temp = 0;
		for (int i = 0; i < body.length; i++) {
			int cipher = body[i] & 0xff;
			body[i] = (byte) (cipher ^ (key[i & 15] & 0xff) ^ temp);
			temp = cipher;
		}
		shift(key, body.length);
	}

	private static void shift(byte[] key, int size) {
		int old = (key[8] & 0xff)
				| ((key[9] & 0xff) << 8)
				| ((key[10] & 0xff) << 16)
				| ((key[11] & 0xff) << 24);
		old += size;
		key[8] = (byte) (old & 0xff);
		key[9] = (byte) ((old >>> 8) & 0xff);
		key[10] = (byte) ((old >>> 16) & 0xff);
		key[11] = (byte) ((old >>> 24) & 0xff);
	}
}

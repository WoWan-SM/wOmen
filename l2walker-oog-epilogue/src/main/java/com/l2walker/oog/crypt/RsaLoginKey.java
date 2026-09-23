package com.l2walker.oog.crypt;

import javax.crypto.Cipher;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;

/**
 * 1024-bit RSA used by the Gracia Epilogue login handshake.
 * The official client writes a raw 128-byte block: marker {@code 0x24} at {@code 0x5B},
 * account at {@code 0x5E} (14 bytes), password at {@code 0x6C} (16 bytes).
 */
public final class RsaLoginKey {

	private final KeyPair pair;
	private final byte[] modulus128;

	private RsaLoginKey(KeyPair pair, byte[] modulus128) {
		this.pair = pair;
		this.modulus128 = modulus128;
	}

	public static RsaLoginKey generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(1024);
			KeyPair pair = generator.generateKeyPair();
			BigInteger modulus = ((java.security.interfaces.RSAPublicKey) pair.getPublic()).getModulus();
			return new RsaLoginKey(pair, to128(modulus));
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("RSA key generation failed", ex);
		}
	}

	public byte[] modulus128() {
		return modulus128.clone();
	}

	public byte[] scrambledModulus() {
		return scramble(modulus128);
	}

	public PublicKey publicKey() {
		return pair.getPublic();
	}

	public PrivateKey privateKey() {
		return pair.getPrivate();
	}

	public static PublicKey publicKeyFromScrambled(byte[] scrambled) {
		try {
			byte[] modulus = unscramble(scrambled);
			RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, modulus), BigInteger.valueOf(65537));
			return KeyFactory.getInstance("RSA").generatePublic(spec);
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("RSA public key rebuild failed", ex);
		}
	}

	/** Plaintext block the client encrypts. {@code block[0x5B] == 0x24}. */
	public static byte[] credentialBlock(String account, String password) {
		byte[] block = new byte[128];
		block[0x5B] = 0x24;
		writeAscii(block, 0x5E, account, 14);
		writeAscii(block, 0x6C, password, 16);
		return block;
	}

	public static byte[] sealCredentials(PublicKey key, String account, String password) {
		byte[] block = credentialBlock(account, password);
		try {
			Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key);
			return leftPad(cipher.doFinal(block), 128);
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("RSA credential seal failed", ex);
		}
	}

	public String[] openCredentials(byte[] encrypted) {
		try {
			Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, privateKey());
			byte[] plain = leftPad(cipher.doFinal(encrypted), 128);
			return new String[] { readAscii(plain, 0x5E, 14), readAscii(plain, 0x6C, 16) };
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("RSA credential open failed", ex);
		}
	}

	public static byte[] scramble(byte[] modulus) {
		byte[] scrambled = copy128(modulus);
		swap4(scrambled, 0x00, 0x4d);
		xorRange(scrambled, 0x00, 0x40);
		xor4(scrambled, 0x0d, 0x34);
		xorRange(scrambled, 0x40, 0x00);
		return scrambled;
	}

	public static byte[] unscramble(byte[] scrambled) {
		byte[] modulus = copy128(scrambled);
		xorRange(modulus, 0x40, 0x00);
		xor4(modulus, 0x0d, 0x34);
		xorRange(modulus, 0x00, 0x40);
		swap4(modulus, 0x00, 0x4d);
		return modulus;
	}

	private static byte[] to128(BigInteger modulus) {
		byte[] raw = modulus.toByteArray();
		if (raw.length == 129 && raw[0] == 0) {
			return Arrays.copyOfRange(raw, 1, 129);
		}
		if (raw.length == 128) {
			return raw;
		}
		byte[] padded = new byte[128];
		int copy = Math.min(raw.length, 128);
		System.arraycopy(raw, raw.length - copy, padded, 128 - copy, copy);
		return padded;
	}

	private static byte[] copy128(byte[] value) {
		if (value.length != 128) {
			throw new IllegalArgumentException("RSA modulus must be 128 bytes, got " + value.length);
		}
		return value.clone();
	}

	private static void swap4(byte[] data, int left, int right) {
		for (int i = 0; i < 4; i++) {
			byte temp = data[left + i];
			data[left + i] = data[right + i];
			data[right + i] = temp;
		}
	}

	private static void xor4(byte[] data, int dest, int src) {
		for (int i = 0; i < 4; i++) {
			data[dest + i] ^= data[src + i];
		}
	}

	private static void xorRange(byte[] data, int dest, int src) {
		for (int i = 0; i < 0x40; i++) {
			data[dest + i] ^= data[src + i];
		}
	}

	private static void writeAscii(byte[] block, int offset, String value, int max) {
		byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		int length = Math.min(bytes.length, max);
		System.arraycopy(bytes, 0, block, offset, length);
	}

	private static String readAscii(byte[] block, int offset, int length) {
		int end = offset;
		int limit = offset + length;
		while (end < limit && block[end] != 0) {
			end++;
		}
		return new String(block, offset, end - offset, java.nio.charset.StandardCharsets.US_ASCII).trim();
	}

	private static byte[] leftPad(byte[] value, int size) {
		if (value.length == size) {
			return value;
		}
		if (value.length > size) {
			return Arrays.copyOfRange(value, value.length - size, value.length);
		}
		byte[] padded = new byte[size];
		System.arraycopy(value, 0, padded, size - value.length, value.length);
		return padded;
	}
}

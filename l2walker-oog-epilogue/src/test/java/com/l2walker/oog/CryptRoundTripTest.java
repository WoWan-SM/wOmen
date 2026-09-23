package com.l2walker.oog;

import com.l2walker.oog.crypt.BlowfishCipher;
import com.l2walker.oog.crypt.GameXorCrypt;
import com.l2walker.oog.crypt.PacketChecksum;
import com.l2walker.oog.crypt.RsaLoginKey;
import com.l2walker.oog.crypt.XorPass;
import com.l2walker.oog.protocol.LoginCrypt;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptRoundTripTest {

	@Test
	void blowfishMatchesLittleEndianWordSwap() throws Exception {
		byte[] key = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
		byte[] plain = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

		Cipher jce = Cipher.getInstance("Blowfish/ECB/NoPadding");
		jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "Blowfish"));
		byte[] swapped = new byte[plain.length];
		for (int offset = 0; offset < plain.length; offset += 8) {
			BlowfishCipher.swapWords(plain, offset, swapped, offset);
		}
		byte[] jceOut = jce.doFinal(swapped);
		byte[] expected = new byte[plain.length];
		for (int offset = 0; offset < plain.length; offset += 8) {
			BlowfishCipher.swapWords(jceOut, offset, expected, offset);
		}

		byte[] actual = plain.clone();
		BlowfishCipher cipher = BlowfishCipher.forKey(key);
		cipher.encryptInPlace(actual);
		assertArrayEquals(expected, actual);
		cipher.decryptInPlace(actual);
		assertArrayEquals(plain, actual);
	}

	@Test
	void xorPassAndChecksumRoundTrip() {
		byte[] raw = new byte[24];
		raw[0] = 0x00;
		raw[5] = 0x11;
		raw[9] = 0x22;
		XorPass.encrypt(raw, 0, raw.length, 0x12345678);
		XorPass.decrypt(raw, 0, raw.length);
		assertEquals(0x00, raw[0]);
		assertEquals(0x11, raw[5]);
		assertEquals(0x22, raw[9]);

		byte[] body = new byte[16];
		body[0] = 0x03;
		body[1] = 0x10;
		PacketChecksum.append(body, 0, body.length);
		assertTrue(PacketChecksum.verify(body, 0, body.length));
		body[1] ^= 0x01;
		assertFalse(PacketChecksum.verify(body, 0, body.length));
	}

	@Test
	void loginInitialPacketRoundTrip() {
		byte[] payload = new byte[170];
		payload[0] = 0x00;
		payload[10] = 0x5a;
		LoginCrypt crypt = new LoginCrypt();
		byte[] wire = crypt.encryptInitial(payload, 0x55aa55aa);
		byte[] plain = LoginCrypt.decryptInitial(wire);
		assertEquals(0x00, plain[0]);
		assertEquals(0x5a, plain[10]);
		assertEquals(payload.length, leadingMatch(payload, plain));
	}

	@Test
	void rsaScrambleAndCredentials() {
		byte[] modulus = new byte[128];
		for (int i = 0; i < modulus.length; i++) {
			modulus[i] = (byte) (i * 17 + 3);
		}
		assertArrayEquals(modulus, RsaLoginKey.unscramble(RsaLoginKey.scramble(modulus)));

		RsaLoginKey key = RsaLoginKey.generate();
		byte[] sealed = RsaLoginKey.sealCredentials(RsaLoginKey.publicKeyFromScrambled(key.scrambledModulus()), "epilogue", "secret");
		String[] opened = key.openCredentials(sealed);
		assertEquals("epilogue", opened[0]);
		assertEquals("secret", opened[1]);
	}

	@Test
	void gameXorIsDirectional() {
		byte[] key = {9, 8, 7, 6, 5, 4, 3, 2};
		byte[] protocol = {0x0e, 0x58, 0x00, 0x00, 0x00};
		byte[] auth = {0x2b, 0x61, 0x00, 0x00, 0x00};

		GameXorCrypt server = new GameXorCrypt(GameXorCrypt.Role.SERVER);
		GameXorCrypt client = new GameXorCrypt(GameXorCrypt.Role.CLIENT);
		server.setKey(key);
		byte[] keyPacket = {0x2e, 0x01, 9, 8, 7, 6, 5, 4, 3, 2};
		server.encrypt(keyPacket);
		client.decrypt(keyPacket);
		assertEquals(0x2e, keyPacket[0] & 0xff);
		client.setKey(key);

		byte[] outgoing = protocol.clone();
		client.encrypt(outgoing);
		assertFalse(Arrays.equals(protocol, outgoing));
		server.decrypt(outgoing);
		assertArrayEquals(protocol, outgoing);

		outgoing = auth.clone();
		client.encrypt(outgoing);
		server.decrypt(outgoing);
		assertArrayEquals(auth, outgoing);
	}

	private static int leadingMatch(byte[] left, byte[] right) {
		int n = Math.min(left.length, right.length);
		int i = 0;
		while (i < n && left[i] == right[i]) {
			i++;
		}
		return i;
	}
}

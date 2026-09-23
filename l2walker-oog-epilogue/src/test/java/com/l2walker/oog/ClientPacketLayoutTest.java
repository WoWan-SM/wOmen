package com.l2walker.oog;

import com.l2walker.oog.protocol.EpiloguePackets;
import com.l2walker.oog.protocol.LoginCrypt;
import com.l2walker.oog.protocol.PacketBuf;
import com.l2walker.oog.protocol.PlaySession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientPacketLayoutTest {

	@Test
	void clientSendLayoutsMatchPublishedFieldOrder() {
		byte[] guard = EpiloguePackets.authGameGuard(0x11223344);
		assertEquals(EpiloguePackets.AUTH_GAME_GUARD, guard[0] & 0xff);
		assertEquals(40, guard.length);
		assertEquals(0x11223344, readSecondDword(guard));

		byte[] auth = EpiloguePackets.requestAuthLogin(new byte[128], 7);
		assertEquals(1 + 128 + 4 + 16 + 11 + 16, auth.length);
		assertEquals(0x23, auth[1 + 128 + 4] & 0xff);

		byte[] serverList = EpiloguePackets.requestServerList(1, 2);
		assertEquals(1 + 8 + 7 + 16, serverList.length);
		assertEquals(0x04, serverList[9] & 0xff);

		byte[] play = EpiloguePackets.requestServerLogin(1, 2, 3);
		assertEquals(1 + 8 + 1 + 22, play.length);

		byte[] version = EpiloguePackets.protocolVersion(152);
		assertEquals(1 + 4 + EpiloguePackets.PROTOCOL_FIXED_BLOCK_LENGTH + 4, version.length);
		PacketBuf.Reader versionReader = new PacketBuf.Reader(version);
		assertEquals(0x0e, versionReader.readC());
		assertEquals(152, versionReader.readD());
		versionReader.readB(EpiloguePackets.PROTOCOL_FIXED_BLOCK_LENGTH);
		assertEquals(EpiloguePackets.EPILOGUE_CLIENT_TRAILER, versionReader.readD());

		byte[] gameAuth = EpiloguePackets.gameAuth("epilogue", new PlaySession("epilogue", 1, 2, 3, 4));
		PacketBuf.Reader authReader = new PacketBuf.Reader(gameAuth);
		assertEquals(0x2b, authReader.readC());
		assertEquals("epilogue", authReader.readS());
		assertEquals(4, authReader.readD());
		assertEquals(3, authReader.readD());
		assertEquals(1, authReader.readD());
		assertEquals(2, authReader.readD());
		assertEquals(0, authReader.readD());
	}

	@Test
	void initFrameIsTheSizeTheClientParserRequires() {
		byte[] modulus = new byte[128];
		byte[] blowfish = new byte[16];
		byte[] payload = EpiloguePackets.init(1, modulus, blowfish);
		byte[] body = new LoginCrypt().encryptInitial(payload, 0x55);
		assertEquals(184, body.length);
	}

	private static int readSecondDword(byte[] packet) {
		PacketBuf.Reader reader = new PacketBuf.Reader(packet);
		reader.readC();
		return reader.readD();
	}
}

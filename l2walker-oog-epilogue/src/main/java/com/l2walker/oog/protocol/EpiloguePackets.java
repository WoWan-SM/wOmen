package com.l2walker.oog.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Packet bodies shaped the way the Gracia Epilogue client emits and reads them.
 * No client binary is in this workspace. Field order follows public client code:
 * l2js-client outgoing/incoming login packets, the client Init parser in
 * L2Login_Init, and the Epilogue ProtocolVersion frame (version + 256-byte block + trailer).
 */
public final class EpiloguePackets {

	public static final int INIT = 0x00;
	public static final int LOGIN_FAIL = 0x01;
	public static final int REQUEST_SERVER_LOGIN = 0x02;
	public static final int LOGIN_OK = 0x03;
	public static final int SERVER_LIST = 0x04;
	public static final int REQUEST_SERVER_LIST = 0x05;
	public static final int AUTH_GAME_GUARD = 0x07;
	public static final int PLAY_OK = 0x07;
	public static final int GG_AUTH = 0x0b;

	public static final int CHAR_SELECTION_INFO = 0x09;
	public static final int PROTOCOL_VERSION = 0x0e;
	public static final int GAME_AUTH = 0x2b;
	public static final int KEY_PACKET = 0x2e;

	/** Client ProtocolVersion fixed block length: {@code z(0256fixed)}. */
	public static final int PROTOCOL_FIXED_BLOCK_LENGTH = 256;

	/**
	 * Trailer dword the Epilogue client writes at the end of ProtocolVersion
	 * (published for client protocols 146–148; same field for 152).
	 * On the wire, little-endian: {@code A6 23 F4 FE}.
	 */
	public static final int EPILOGUE_CLIENT_TRAILER = 0xFEF423A6;

	/** Client AuthGameGuard constants, also copied into RequestAuthLogin. */
	private static final byte[] CLIENT_GUARD_BLOCK = {
			0x23, 0x01, 0x00, 0x00,
			0x67, 0x45, 0x00, 0x00,
			(byte) 0xab, (byte) 0x89, 0x00, 0x00,
			(byte) 0xef, (byte) 0xcd, 0x00, 0x00
	};

	private static final byte[] CLIENT_AUTH_FOOTER = {
			0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
	};

	private EpiloguePackets() {
	}

	public static byte[] init(int sessionId, byte[] scrambledModulus, byte[] blowfishKey) {
		return new PacketBuf.Writer(256)
				.writeC(INIT)
				.writeD(sessionId)
				.writeD(0x0000c621)
				.writeB(scrambledModulus)
				.writeD(0x29DD954E)
				.writeD(0x77C39CFC)
				.writeD(0x97ADB620)
				.writeD(0x07BDE0F7)
				.writeB(blowfishKey)
				.writeC(0x00)
				.toByteArray();
	}

	public static InitFrame parseInit(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != INIT) {
			throw new IllegalArgumentException("expected Init opcode 0x00, got " + opcode);
		}
		int sessionId = reader.readD();
		reader.readD();
		byte[] scrambledModulus = reader.readB(128);
		reader.readD();
		reader.readD();
		reader.readD();
		reader.readD();
		byte[] blowfishKey = reader.readB(16);
		return new InitFrame(sessionId, scrambledModulus, blowfishKey);
	}

	/** Client {@code C/0x07}: session id, four guard dwords, 19 zero bytes. */
	public static byte[] authGameGuard(int sessionId) {
		return new PacketBuf.Writer(64)
				.writeC(AUTH_GAME_GUARD)
				.writeD(sessionId)
				.writeD(0x00000123)
				.writeD(0x00004567)
				.writeD(0x000089ab)
				.writeD(0x0000cdef)
				.writeB(new byte[19])
				.toByteArray();
	}

	public static int parseAuthGameGuard(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != AUTH_GAME_GUARD) {
			throw new IllegalArgumentException("expected AuthGameGuard 0x07, got " + opcode);
		}
		return reader.readD();
	}

	/** Client reads {@code S/0x0B}: response dword plus four zero dwords. */
	public static byte[] ggAuth(int sessionId) {
		return new PacketBuf.Writer(32)
				.writeC(GG_AUTH)
				.writeD(sessionId)
				.writeD(0)
				.writeD(0)
				.writeD(0)
				.writeD(0)
				.toByteArray();
	}

	public static int parseGgAuth(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != GG_AUTH) {
			throw new IllegalArgumentException("expected GGAuth 0x0b, got " + opcode);
		}
		return reader.readD();
	}

	/**
	 * Client {@code C/0x00}: RSA block, session id, 16-byte guard block,
	 * 11-byte footer, 16 zero bytes.
	 */
	public static byte[] requestAuthLogin(byte[] rsaBlock, int sessionId) {
		return new PacketBuf.Writer(256)
				.writeC(INIT)
				.writeB(rsaBlock)
				.writeD(sessionId)
				.writeB(CLIENT_GUARD_BLOCK)
				.writeB(CLIENT_AUTH_FOOTER)
				.writeB(new byte[16])
				.toByteArray();
	}

	public static AuthFrame parseAuthLogin(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != INIT) {
			throw new IllegalArgumentException("expected RequestAuthLogin opcode 0x00, got " + opcode);
		}
		return new AuthFrame(reader.readB(128), reader.readD());
	}

	public static byte[] loginFail(int reason) {
		return new PacketBuf.Writer(16).writeC(LOGIN_FAIL).writeD(reason).toByteArray();
	}

	public static byte[] loginOk(int loginOk1, int loginOk2) {
		return new PacketBuf.Writer(64)
				.writeC(LOGIN_OK)
				.writeD(loginOk1)
				.writeD(loginOk2)
				.writeD(0)
				.writeD(0)
				.writeD(0x000003ea)
				.writeD(0)
				.writeD(0)
				.writeD(0)
				.toByteArray();
	}

	public static SessionPair parseLoginOk(byte[] plain) throws java.io.IOException {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode == LOGIN_FAIL) {
			throw new LoginRejectedException(reader.readD());
		}
		if (opcode != LOGIN_OK) {
			throw new IllegalArgumentException("expected LoginOk, got opcode " + opcode);
		}
		return new SessionPair(reader.readD(), reader.readD());
	}

	/** Client {@code C/0x05}: keys, 7-byte tail starting with {@code 0x04}, then 16 zeros. */
	public static byte[] requestServerList(int loginOk1, int loginOk2) {
		return new PacketBuf.Writer(48)
				.writeC(REQUEST_SERVER_LIST)
				.writeD(loginOk1)
				.writeD(loginOk2)
				.writeB(new byte[] {0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00})
				.writeB(new byte[16])
				.toByteArray();
	}

	public static SessionPair parseRequestServerList(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != REQUEST_SERVER_LIST) {
			throw new IllegalArgumentException("expected RequestServerList, got " + opcode);
		}
		return new SessionPair(reader.readD(), reader.readD());
	}

	public static byte[] serverList(int serverId, String host, int port) {
		return new PacketBuf.Writer(64)
				.writeC(SERVER_LIST)
				.writeC(1)
				.writeC(serverId)
				.writeC(serverId)
				.writeD(ipv4Int(host))
				.writeD(port)
				.writeC(0)
				.writeC(0)
				.writeH(0)
				.writeH(1000)
				.writeC(1)
				.writeD(1)
				.writeC(0)
				.writeH(0)
				.writeC(0)
				.toByteArray();
	}

	public static List<ServerEntry> parseServerList(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != SERVER_LIST) {
			throw new IllegalArgumentException("expected ServerList, got " + opcode);
		}
		int count = reader.readC();
		reader.readC();
		List<ServerEntry> servers = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int id = reader.readC();
			String host = formatIpv4(reader.readD());
			int port = reader.readD();
			reader.readB(12);
			servers.add(new ServerEntry(id, host, port));
		}
		return servers;
	}

	/** Client {@code C/0x02}: keys, server id, 22 zero bytes. */
	public static byte[] requestServerLogin(int loginOk1, int loginOk2, int serverId) {
		return new PacketBuf.Writer(48)
				.writeC(REQUEST_SERVER_LOGIN)
				.writeD(loginOk1)
				.writeD(loginOk2)
				.writeC(serverId)
				.writeB(new byte[22])
				.toByteArray();
	}

	public static ServerLogin parseRequestServerLogin(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != REQUEST_SERVER_LOGIN) {
			throw new IllegalArgumentException("expected RequestServerLogin, got " + opcode);
		}
		return new ServerLogin(reader.readD(), reader.readD(), reader.readC());
	}

	public static byte[] playOk(int playOk1, int playOk2) {
		return new PacketBuf.Writer(16).writeC(PLAY_OK).writeD(playOk1).writeD(playOk2).toByteArray();
	}

	public static SessionPair parsePlayOk(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != PLAY_OK) {
			throw new IllegalArgumentException("expected PlayOk, got " + opcode);
		}
		return new SessionPair(reader.readD(), reader.readD());
	}

	public static byte[] keyPacket(byte[] key8) {
		return new PacketBuf.Writer(32)
				.writeC(KEY_PACKET)
				.writeC(0x01)
				.writeB(key8)
				.writeD(0x01)
				.writeD(0x01)
				.writeC(0x01)
				.writeD(0x00)
				.toByteArray();
	}

	public static byte[] parseKeyPacket(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != KEY_PACKET) {
			throw new IllegalArgumentException("expected KeyPacket 0x2e, got " + opcode);
		}
		int ok = reader.readC();
		if (ok != 0x01) {
			throw new IllegalArgumentException("game server rejected protocol, flag " + ok);
		}
		return reader.readB(8);
	}

	/**
	 * Client {@code C/0x0E}: version, 256-byte fixed block, trailer dword.
	 * The fixed-block bytes are not published for protocol 152; length matches the client.
	 */
	public static byte[] protocolVersion(int version) {
		return new PacketBuf.Writer(280)
				.writeC(PROTOCOL_VERSION)
				.writeD(version)
				.writeB(new byte[PROTOCOL_FIXED_BLOCK_LENGTH])
				.writeD(EPILOGUE_CLIENT_TRAILER)
				.toByteArray();
	}

	public static int parseProtocolVersion(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != PROTOCOL_VERSION) {
			throw new IllegalArgumentException("expected ProtocolVersion 0x0e, got " + opcode);
		}
		return reader.readD();
	}

	public static byte[] gameAuth(String account, PlaySession session) {
		return new PacketBuf.Writer(128)
				.writeC(GAME_AUTH)
				.writeS(account)
				.writeD(session.playOk2())
				.writeD(session.playOk1())
				.writeD(session.loginOk1())
				.writeD(session.loginOk2())
				.writeD(0)
				.toByteArray();
	}

	public static GameAuth parseGameAuth(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != GAME_AUTH) {
			throw new IllegalArgumentException("expected game AuthLogin 0x2b, got " + opcode);
		}
		String account = reader.readS();
		int playOk2 = reader.readD();
		int playOk1 = reader.readD();
		int loginOk1 = reader.readD();
		int loginOk2 = reader.readD();
		return new GameAuth(account, playOk1, playOk2, loginOk1, loginOk2);
	}

	/** Client CharSelectionInfo header: count, max slots, flag byte, then the name on a short packet. */
	public static byte[] charSelection(String characterName) {
		PacketBuf.Writer writer = new PacketBuf.Writer(64)
				.writeC(CHAR_SELECTION_INFO)
				.writeD(characterName == null ? 0 : 1)
				.writeD(7)
				.writeC(0);
		if (characterName != null) {
			writer.writeS(characterName);
		}
		return writer.toByteArray();
	}

	public static CharacterList parseCharSelection(byte[] plain) {
		PacketBuf.Reader reader = new PacketBuf.Reader(plain);
		int opcode = reader.readC();
		if (opcode != CHAR_SELECTION_INFO) {
			throw new IllegalArgumentException("expected CharSelectionInfo 0x09, got " + opcode);
		}
		int count = reader.readD();
		int max = reader.readD();
		List<String> names = new ArrayList<>();
		if (reader.remaining() > 0 && reader.remaining() < 80) {
			reader.readC();
			if (count > 0 && reader.remaining() >= 2) {
				names.add(reader.readS());
			}
		}
		return new CharacterList(count, max, names);
	}

	public static int ipv4Int(String host) {
		String[] parts = host.split("\\.");
		if (parts.length != 4) {
			throw new IllegalArgumentException("expected IPv4 host, got " + host);
		}
		int ip = 0;
		for (int i = 0; i < 4; i++) {
			ip |= (Integer.parseInt(parts[i]) & 0xff) << (8 * i);
		}
		return ip;
	}

	public static String formatIpv4(int ip) {
		return (ip & 0xff) + "." + ((ip >>> 8) & 0xff) + "." + ((ip >>> 16) & 0xff) + "." + ((ip >>> 24) & 0xff);
	}

	public record InitFrame(int sessionId, byte[] scrambledModulus, byte[] blowfishKey) {
	}

	public record AuthFrame(byte[] rsaBlock, int sessionId) {
	}

	public record SessionPair(int first, int second) {
	}

	public record ServerEntry(int id, String host, int port) {
	}

	public record ServerLogin(int loginOk1, int loginOk2, int serverId) {
	}

	public record GameAuth(String account, int playOk1, int playOk2, int loginOk1, int loginOk2) {
	}

	public record CharacterList(int count, int maxCharacters, List<String> names) {
	}
}

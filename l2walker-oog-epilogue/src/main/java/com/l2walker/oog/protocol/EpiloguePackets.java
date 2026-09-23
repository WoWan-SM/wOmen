package com.l2walker.oog.protocol;

import java.util.ArrayList;
import java.util.List;

/** Login and game packet bodies for the Gracia Epilogue (152) OOG handshake. */
public final class EpiloguePackets {

	public static final int INIT = 0x00;
	public static final int LOGIN_FAIL = 0x01;
	public static final int REQUEST_SERVER_LOGIN = 0x02;
	public static final int LOGIN_OK = 0x03;
	public static final int SERVER_LIST = 0x04;
	public static final int REQUEST_SERVER_LIST = 0x05;
	public static final int PLAY_OK = 0x07;

	public static final int CHAR_SELECTION_INFO = 0x09;
	public static final int PROTOCOL_VERSION = 0x0e;
	public static final int GAME_AUTH = 0x2b;
	public static final int KEY_PACKET = 0x2e;

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

	public static byte[] requestAuthLogin(byte[] rsaBlock, int sessionId) {
		return new PacketBuf.Writer(256)
				.writeC(INIT)
				.writeB(rsaBlock)
				.writeD(sessionId)
				.writeD(0)
				.writeD(0)
				.writeD(0)
				.writeD(0)
				.writeD(8)
				.writeH(0)
				.writeC(0)
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

	public static byte[] requestServerList(int loginOk1, int loginOk2) {
		return new PacketBuf.Writer(16).writeC(REQUEST_SERVER_LIST).writeD(loginOk1).writeD(loginOk2).toByteArray();
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
				.writeB(ipv4(host))
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
			String host = formatIpv4(reader.readB(4));
			int port = reader.readD();
			reader.readB(12);
			servers.add(new ServerEntry(id, host, port));
		}
		return servers;
	}

	public static byte[] requestServerLogin(int loginOk1, int loginOk2, int serverId) {
		return new PacketBuf.Writer(16)
				.writeC(REQUEST_SERVER_LOGIN)
				.writeD(loginOk1)
				.writeD(loginOk2)
				.writeC(serverId)
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

	public static byte[] protocolVersion(int version) {
		return new PacketBuf.Writer(8).writeC(PROTOCOL_VERSION).writeD(version).toByteArray();
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

	public static byte[] charSelection(String characterName) {
		PacketBuf.Writer writer = new PacketBuf.Writer(64)
				.writeC(CHAR_SELECTION_INFO)
				.writeD(characterName == null ? 0 : 1)
				.writeD(7);
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
		if (count > 0 && reader.remaining() > 0 && reader.remaining() < 64) {
			names.add(reader.readS());
		}
		return new CharacterList(count, max, names);
	}

	public static byte[] ipv4(String host) {
		String[] parts = host.split("\\.");
		if (parts.length != 4) {
			throw new IllegalArgumentException("expected IPv4 host, got " + host);
		}
		byte[] ip = new byte[4];
		for (int i = 0; i < 4; i++) {
			ip[i] = (byte) Integer.parseInt(parts[i]);
		}
		return ip;
	}

	public static String formatIpv4(byte[] ip) {
		return (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
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

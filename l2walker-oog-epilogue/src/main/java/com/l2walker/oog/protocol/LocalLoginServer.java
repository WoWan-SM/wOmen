package com.l2walker.oog.protocol;

import com.l2walker.oog.crypt.RsaLoginKey;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Loopback login server that speaks the same Gracia Epilogue handshake as {@link LoginClient152}.
 * It accepts a single connection and is meant for local verification, not for hosting a shard.
 */
public final class LocalLoginServer implements AutoCloseable {

	private final String account;
	private final String password;
	private final SessionTable sessions;
	private final RsaLoginKey rsa;
	private final byte[] blowfishKey = new byte[16];
	private final int sessionId;
	private final ServerSocket socket;
	private final Thread thread;
	private volatile String gameHost = "127.0.0.1";
	private volatile int gamePort = 7777;
	private volatile Exception failure;
	private volatile String acceptedAccount;

	public LocalLoginServer(String account, String password, SessionTable sessions) throws IOException {
		this.account = account;
		this.password = password;
		this.sessions = sessions;
		this.rsa = RsaLoginKey.generate();
		SecureRandom random = new SecureRandom();
		random.nextBytes(blowfishKey);
		this.sessionId = random.nextInt();
		this.socket = new ServerSocket();
		this.socket.bind(new InetSocketAddress("127.0.0.1", 0));
		this.thread = new Thread(this::serve, "local-login-152");
		this.thread.setDaemon(true);
	}

	public void start() {
		thread.start();
	}

	public int port() {
		return socket.getLocalPort();
	}

	public void setGameEndpoint(String host, int port) {
		this.gameHost = host;
		this.gamePort = port;
	}

	public String acceptedAccount() {
		return acceptedAccount;
	}

	public Exception failure() {
		return failure;
	}

	private void serve() {
		try (Socket client = socket.accept()) {
			client.setTcpNoDelay(true);
			client.setSoTimeout(5000);
			handle(client);
		} catch (IOException ex) {
			if (!socket.isClosed()) {
				failure = ex;
			}
		}
	}

	private void handle(Socket client) {
		try {
			InputStream in = client.getInputStream();
			OutputStream out = client.getOutputStream();
			LoginCrypt crypt = new LoginCrypt();
			crypt.setKey(blowfishKey);
			byte[] init = EpiloguePackets.init(sessionId, rsa.scrambledModulus(), blowfishKey);
			TcpFrames.send(out, crypt.encryptInitial(init, sessionId));

			int guardSession = EpiloguePackets.parseAuthGameGuard(crypt.decrypt(TcpFrames.recv(in)));
			if (guardSession != sessionId) {
				throw new IllegalStateException("AuthGameGuard session id mismatch");
			}
			TcpFrames.send(out, crypt.encrypt(EpiloguePackets.ggAuth(sessionId)));

			EpiloguePackets.AuthFrame auth = EpiloguePackets.parseAuthLogin(crypt.decrypt(TcpFrames.recv(in)));
			if (auth.sessionId() != sessionId) {
				throw new IllegalStateException("client echoed a different login session id");
			}
			String[] credentials = rsa.openCredentials(auth.rsaBlock());
			String accountName = credentials[0].toLowerCase(Locale.ROOT);
			if (!accountName.equals(account.toLowerCase(Locale.ROOT)) || !credentials[1].equals(password)) {
				TcpFrames.send(out, crypt.encrypt(EpiloguePackets.loginFail(0x02)));
				return;
			}

			SecureRandom random = new SecureRandom();
			PlaySession session = new PlaySession(accountName, random.nextInt(), random.nextInt(), random.nextInt(), random.nextInt());
			sessions.put(session);
			acceptedAccount = accountName;
			TcpFrames.send(out, crypt.encrypt(EpiloguePackets.loginOk(session.loginOk1(), session.loginOk2())));

			EpiloguePackets.SessionPair listRequest = EpiloguePackets.parseRequestServerList(crypt.decrypt(TcpFrames.recv(in)));
			if (listRequest.first() != session.loginOk1() || listRequest.second() != session.loginOk2()) {
				throw new IllegalStateException("RequestServerList session key mismatch");
			}
			TcpFrames.send(out, crypt.encrypt(EpiloguePackets.serverList(1, gameHost, gamePort)));

			EpiloguePackets.ServerLogin serverLogin = EpiloguePackets.parseRequestServerLogin(crypt.decrypt(TcpFrames.recv(in)));
			if (serverLogin.loginOk1() != session.loginOk1() || serverLogin.loginOk2() != session.loginOk2()) {
				throw new IllegalStateException("RequestServerLogin session key mismatch");
			}
			TcpFrames.send(out, crypt.encrypt(EpiloguePackets.playOk(session.playOk1(), session.playOk2())));
		} catch (Exception ex) {
			failure = ex;
		}
	}

	@Override
	public void close() throws IOException {
		socket.close();
		try {
			thread.join(2000);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}

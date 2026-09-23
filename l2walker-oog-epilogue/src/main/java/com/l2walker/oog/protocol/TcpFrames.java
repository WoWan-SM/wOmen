package com.l2walker.oog.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 2-byte little-endian length prefix, including the two length bytes. */
public final class TcpFrames {

	private TcpFrames() {
	}

	public static void send(OutputStream out, byte[] body) throws IOException {
		int length = body.length + 2;
		if (length > 65535) {
			throw new IOException("packet too large: " + length);
		}
		out.write(length & 0xff);
		out.write((length >> 8) & 0xff);
		out.write(body);
		out.flush();
	}

	public static byte[] recv(InputStream in) throws IOException {
		int lo = readByte(in);
		int hi = readByte(in);
		int length = lo | (hi << 8);
		if (length < 2) {
			throw new IOException("invalid packet length " + length);
		}
		byte[] body = new byte[length - 2];
		readFully(in, body);
		return body;
	}

	private static int readByte(InputStream in) throws IOException {
		int value = in.read();
		if (value < 0) {
			throw new EOFException("connection closed");
		}
		return value;
	}

	private static void readFully(InputStream in, byte[] body) throws IOException {
		int offset = 0;
		while (offset < body.length) {
			int read = in.read(body, offset, body.length - offset);
			if (read < 0) {
				throw new EOFException("connection closed");
			}
			offset += read;
		}
	}
}

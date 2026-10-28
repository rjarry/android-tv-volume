// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Robin Jarry

package cc.jarry.finevolume;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal adb client, enough to run one shell command against the local adbd.
 *
 * The daemon has to run as the shell uid, which an app cannot obtain by
 * itself. Talking adb to 127.0.0.1 is the only way for the app to start it
 * without a host machine. The first connection raises the usual on-screen
 * authorization prompt; accepting it stores this app's key in
 * /data/misc/adb/adb_keys, and every later connection is silent.
 */
final class AdbClient {
	private static final int A_CNXN = 0x4e584e43;
	private static final int A_AUTH = 0x48545541;
	private static final int A_OPEN = 0x4e45504f;
	private static final int A_OKAY = 0x59414b4f;
	private static final int A_CLSE = 0x45534c43;
	private static final int A_WRTE = 0x45545257;
	private static final int A_VERSION = 0x01000000;
	private static final int MAX_PAYLOAD = 256 * 1024;
	private static final int AUTH_TOKEN = 1;
	private static final int AUTH_SIGNATURE = 2;
	private static final int AUTH_RSAPUBLICKEY = 3;
	private static final int KEY_BITS = 2048;
	private static final int AUTHORIZE_TIMEOUT_MS = 180000;
	private static final int LOCAL_ID = 1;
	private static final String PRIVATE_KEY_FILE = "adb_key.pk8";
	private static final String PUBLIC_KEY_FILE = "adb_key.pub";

	// PKCS#1 v1.5 DigestInfo prefix for SHA-1. adbd verifies the auth token
	// with RSA_verify(NID_sha1, ...), which expects the token to be signed as
	// if it were a SHA-1 digest.
	private static final byte[] SHA1_DIGEST_INFO = {
		0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
		0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
	};

	/** Waiting for the user to accept the on-screen authorization prompt. */
	static final class AuthorizationPendingException extends IOException {
		AuthorizationPendingException(String message) {
			super(message);
		}
	}

	private AdbClient() {
	}

	private static final class Message {
		int command;
		int arg0;
		int arg1;
		byte[] data;
	}

	/** Runs a shell command and returns whatever it printed. */
	static String shell(Context context, String host, int port, int timeoutMs,
			String command) throws IOException, GeneralSecurityException {
		KeyPair keys = keyPair(context);
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), timeoutMs);
			socket.setSoTimeout(timeoutMs);
			socket.setTcpNoDelay(true);
			OutputStream out = socket.getOutputStream();
			DataInputStream in = new DataInputStream(socket.getInputStream());

			send(out, A_CNXN, A_VERSION, MAX_PAYLOAD,
					"host::features=shell_v2\0".getBytes(StandardCharsets.UTF_8));
			authenticate(socket, out, in, keys);
			return runCommand(out, in, command);
		}
	}

	private static void authenticate(Socket socket, OutputStream out, DataInputStream in,
			KeyPair keys) throws IOException, GeneralSecurityException {
		boolean signatureSent = false;
		boolean publicKeySent = false;
		while (true) {
			Message message;
			try {
				message = receive(in);
			} catch (IOException e) {
				if (publicKeySent) {
					throw new AuthorizationPendingException(
							"accept the debugging prompt on screen");
				}
				throw e;
			}
			if (message.command == A_CNXN) {
				return;
			}
			if (message.command != A_AUTH || message.arg0 != AUTH_TOKEN) {
				throw new IOException("unexpected command "
						+ Integer.toHexString(message.command) + " during auth");
			}
			if (!signatureSent) {
				send(out, A_AUTH, AUTH_SIGNATURE, 0,
						sign(keys.getPrivate(), message.data));
				signatureSent = true;
			} else if (!publicKeySent) {
				// The key is unknown to adbd. Offering it raises the
				// on-screen prompt; the reply only arrives once accepted, so
				// allow time for someone to pick up the remote.
				socket.setSoTimeout(AUTHORIZE_TIMEOUT_MS);
				send(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, encodedPublicKey(keys));
				publicKeySent = true;
			} else {
				throw new IOException("adb rejected the key");
			}
		}
	}

	private static String runCommand(OutputStream out, DataInputStream in, String command)
			throws IOException {
		send(out, A_OPEN, LOCAL_ID, 0,
				("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		int remoteId = 0;
		while (true) {
			Message message = receive(in);
			switch (message.command) {
				case A_OKAY:
					remoteId = message.arg0;
					break;
				case A_WRTE:
					output.write(message.data);
					send(out, A_OKAY, LOCAL_ID, message.arg0, null);
					break;
				case A_CLSE:
					if (remoteId == 0) {
						throw new IOException("adb refused the shell service");
					}
					send(out, A_CLSE, LOCAL_ID, remoteId, null);
					return output.toString("UTF-8");
				default:
					throw new IOException("unexpected command "
							+ Integer.toHexString(message.command));
			}
		}
	}

	private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data)
			throws IOException {
		ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
		header.putInt(command);
		header.putInt(arg0);
		header.putInt(arg1);
		header.putInt(data == null ? 0 : data.length);
		header.putInt(checksum(data));
		header.putInt(~command);
		out.write(header.array());
		if (data != null && data.length > 0) {
			out.write(data);
		}
		out.flush();
	}

	private static Message receive(DataInputStream in) throws IOException {
		byte[] header = new byte[24];
		in.readFully(header);
		ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		Message message = new Message();
		message.command = buffer.getInt();
		message.arg0 = buffer.getInt();
		message.arg1 = buffer.getInt();
		int length = buffer.getInt();
		if (length < 0 || length > MAX_PAYLOAD) {
			throw new IOException("bad payload length " + length);
		}
		message.data = new byte[length];
		if (length > 0) {
			in.readFully(message.data);
		}
		return message;
	}

	private static int checksum(byte[] data) {
		int sum = 0;
		if (data != null) {
			for (byte b : data) {
				sum += b & 0xff;
			}
		}
		return sum;
	}

	private static byte[] sign(PrivateKey key, byte[] token)
			throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		cipher.init(Cipher.ENCRYPT_MODE, key);
		cipher.update(SHA1_DIGEST_INFO);
		return cipher.doFinal(token);
	}

	/**
	 * adb does not exchange public keys in any standard encoding. It sends
	 * base64 of the Montgomery form that its own verifier expects: modulus
	 * word count, -1/n[0] mod 2^32, the modulus, R^2 mod n, and the exponent,
	 * all as little-endian 32 bit words.
	 */
	private static byte[] encodedPublicKey(KeyPair keys) {
		RSAPublicKey key = (RSAPublicKey) keys.getPublic();
		BigInteger modulus = key.getModulus();
		int words = KEY_BITS / 32;
		BigInteger radix = BigInteger.ONE.shiftLeft(32);

		ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4)
				.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(words);
		buffer.putInt(radix.subtract(modulus.mod(radix).modInverse(radix)).intValue());
		putWords(buffer, modulus, words, radix);
		putWords(buffer, BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(modulus), words, radix);
		buffer.putInt(key.getPublicExponent().intValue());

		String encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
				+ " finevolume@android\0";
		return encoded.getBytes(StandardCharsets.UTF_8);
	}

	private static void putWords(ByteBuffer buffer, BigInteger value, int words,
			BigInteger radix) {
		BigInteger remaining = value;
		for (int i = 0; i < words; i++) {
			buffer.putInt(remaining.mod(radix).intValue());
			remaining = remaining.shiftRight(32);
		}
	}

	private static synchronized KeyPair keyPair(Context context)
			throws GeneralSecurityException, IOException {
		File privateFile = new File(context.getFilesDir(), PRIVATE_KEY_FILE);
		File publicFile = new File(context.getFilesDir(), PUBLIC_KEY_FILE);
		KeyFactory factory = KeyFactory.getInstance("RSA");
		if (privateFile.exists() && publicFile.exists()) {
			return new KeyPair(
					factory.generatePublic(new X509EncodedKeySpec(read(publicFile))),
					factory.generatePrivate(new PKCS8EncodedKeySpec(read(privateFile))));
		}
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(KEY_BITS);
		KeyPair keys = generator.generateKeyPair();
		write(privateFile, keys.getPrivate().getEncoded());
		write(publicFile, keys.getPublic().getEncoded());
		return keys;
	}

	private static byte[] read(File file) throws IOException {
		byte[] data = new byte[(int) file.length()];
		try (InputStream in = new FileInputStream(file)) {
			new DataInputStream(in).readFully(data);
		}
		return data;
	}

	private static void write(File file, byte[] data) throws IOException {
		try (OutputStream out = new FileOutputStream(file)) {
			out.write(data);
		}
		file.setReadable(false, false);
		file.setReadable(true, true);
	}
}

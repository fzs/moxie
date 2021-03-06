/*
 * Copyright 2002-2005 The Apache Software Foundation.
 * Copyright 2012 James Moger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.moxie.proxy.connection;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.moxie.Constants;
import org.moxie.proxy.LuceneExecutor;
import org.moxie.proxy.ProxyConfig;

/**
 * Handle a connection from a maven.
 *
 * @author digulla
 *
 */
public class ProxyRequestHandler extends Thread {
	public static final Logger log = Logger.getLogger(ProxyRequestHandler.class.getSimpleName());

	private final ProxyConfig config;
	private final LuceneExecutor lucene;
	private Socket clientSocket;

	private enum HttpMethod {
		GET, HEAD;

		static HttpMethod fromString(String val) {
			for (HttpMethod method : values()) {
				if (val.equals(method.name())) {
					return method;
				}
			}
			return null;
		}
	}

	public ProxyRequestHandler(ProxyConfig config, LuceneExecutor lucene, Socket clientSocket) {
		this.config = config;
		this.lucene = lucene;
		this.clientSocket = clientSocket;
	}

	@Override
	public void run() {
		if (clientSocket == null)
			throw new RuntimeException("Connection is already closed");

		try {
			log.fine("Got connection from " + clientSocket.getInetAddress());

			String line;
			boolean keepAlive = false;
			do {
				HttpMethod method = null;
				String downloadURL = null;
				StringBuilder fullRequest = new StringBuilder(1024);
				while ((line = readLine()) != null) {
					if (line.length() == 0)
						break;

					// log.debug ("Got: "+line);
					fullRequest.append(line);
					fullRequest.append('\n');

					if ("proxy-connection: keep-alive".equals(line.toLowerCase()))
						keepAlive = true;

					// parse HTTP method
					int spc = line.indexOf(' ');
					if (spc > -1) {
						HttpMethod m = HttpMethod.fromString(line.substring(0, spc).trim());
						if (m != null) {
							int pos = line.lastIndexOf(' ');
							line = line.substring(m.name().length(), pos);
							downloadURL = line;
							method = m;
						}
					}
				}

				if (downloadURL == null) {
					if (line == null)
						break;

					log.severe("Found no URL to download in request:\n" + fullRequest.toString());
				} else {
					log.info("Got request for " + method + " " + downloadURL);
					handle(method, downloadURL);
				}
			} while (line != null && keepAlive);

			log.fine("Terminating connection with " + clientSocket.getInetAddress());
		} catch (Exception e) {
			log.log(Level.SEVERE, "Conversation with client aborted", e);
		} finally {
			close();
		}
	}

	public void close() {
		try {
			if (out != null)
				out.close();
		} catch (Exception e) {
			log.log(Level.SEVERE, "Exception while closing the outputstream", e);
		}
		out = null;

		try {
			if (in != null)
				in.close();
		} catch (Exception e) {
			log.log(Level.SEVERE, "Exception while closing the inputstream", e);
		}
		in = null;

		try {
			if (clientSocket != null)
				clientSocket.close();
		} catch (Exception e) {
			log.log(Level.SEVERE, "Exception while closing the socket", e);
		}
		clientSocket = null;
	}

	private void handle(HttpMethod method, String downloadURL) throws IOException {
		URL url = new URL(downloadURL);
		url = config.getRedirect(url);

		if (!"http".equals(url.getProtocol()))
			throw new IOException("Can only handle HTTP requests, got " + downloadURL);

		File f = config.getRemoteArtifact(url);
		if (f == null) {
			throw new IOException("Unregistered remote repository, got " + downloadURL);
		}
		String name = f.getName();
		String path = f.getPath().replace('\\', '/');

		// ensure we have the requested artifact or the latest version
		// of the requested artifact
		if (name.contains("-SNAPSHOT")
				|| name.contains("maven-metadata")
				|| path.contains("/.m2e/")
				|| path.contains("/.meta/")
				|| path.contains("/.nexus/")
				|| !f.exists()) {
			ProxyDownload d = new ProxyDownload(config, url, f);
			try {
				d.download();

				// index this artifact's pom
				if (name.toLowerCase().endsWith(Constants.POM)) {
					lucene.index(f);
				}
			} catch (DownloadFailed e) {
				log.severe(e.getMessage());
				if (!f.exists()) {
					// return failure
					println(e.getStatusLine());
					println();
					getOut().flush();
					return;
				} else {
					log.fine("Serving from local cache " + f.getAbsolutePath());
				}
			}
		} else {
			log.fine("Serving from local cache " + f.getAbsolutePath());
		}

		// now that we have the artifact, handle the client's request
		switch (method) {
		case HEAD:
			handleHEAD(f);
			break;
		case GET:
			handleGET(f);
			break;
		default:
			log.warning("Unimplemented HTTP method " + method);
			break;
		}
	}

	/**
	 * Set the http headers for the request.
	 *
	 * @param file
	 * @throws IOException
	 */
	private void setHeaders(File file) throws IOException {
		println("HTTP/1.1 200 OK");
		println("Server: moxieproxy/" + org.moxie.proxy.Constants.getVersion());

		print("Date: ");
		println(INTERNET_FORMAT.format(new Date(System.currentTimeMillis())));

		print("Last-modified: ");
		println(INTERNET_FORMAT.format(new Date(file.lastModified())));

		print("Content-length: ");
		println(String.valueOf(file.length()));

		print("Content-type: ");
		String ext = file.getName().substring(file.getName().lastIndexOf('.') + 1).toLowerCase();
		String type = CONTENT_TYPES.get(ext);
		if (type == null) {
			log.warning("Unknown extension " + ext + ". Using content type text/plain.");
			type = "text/plain";
		}
		println(type);
		println();
	}

	/**
	 * HEAD requests are used to determine the current status of a resource.
	 *
	 * @param file
	 * @throws IOException
	 */
	protected void handleHEAD(File file) throws IOException {
		// set the http headers for the file
		setHeaders(file);
		out.flush();
	}

	/**
	 * GET requests are used to retrieve a resource.
	 *
	 * @param file
	 * @throws IOException
	 */
	protected void handleGET(File file) throws IOException {
		// set the http headers for the file
		setHeaders(file);

		// load the file for streaming back to the client
		InputStream data = new BufferedInputStream(new FileInputStream(file));
		copy(data, out);
		data.close();
	}

	void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024 * 100];
		int len;

		while ((len = in.read(buffer)) != -1) {
			out.write(buffer, 0, len);
		}
		out.flush();
	}


	public final static HashMap<String, String> CONTENT_TYPES = new HashMap<String, String>();
	static {
		CONTENT_TYPES.put("xml", "text/xml");
		CONTENT_TYPES.put("pom", "text/xml");

		CONTENT_TYPES.put("jar", "application/java-archive");
		CONTENT_TYPES.put("war", "application/java-archive");
		CONTENT_TYPES.put("ear", "application/java-archive");

		CONTENT_TYPES.put("zip", "application/zip");
		CONTENT_TYPES.put("tar", "application/x-tar");
		CONTENT_TYPES.put("tgz", "application/gzip");
		CONTENT_TYPES.put("gz", "application/gzip");

		CONTENT_TYPES.put("txt", "text/plain");
		CONTENT_TYPES.put("md5", "text/plain");
		CONTENT_TYPES.put("sha1", "text/plain");
		CONTENT_TYPES.put("asc", "text/plain");

	}

	private final static SimpleDateFormat INTERNET_FORMAT = new SimpleDateFormat(
			"EEE, d MMM yyyy HH:mm:ss zzz");
	private byte[] NEW_LINE = new byte[] { '\r', '\n' };

	private void println(String string) throws IOException {
		print(string);
		println();
	}

	private void println() throws IOException {
		getOut().write(NEW_LINE);
	}

	private void print(String string) throws IOException {
		getOut().write(string.getBytes("ISO-8859-1"));
	}

	private OutputStream out;

	protected OutputStream getOut() throws IOException {
		if (out == null)
			out = new BufferedOutputStream(clientSocket.getOutputStream());

		return out;
	}

	private BufferedInputStream in;

	private String readLine() throws IOException {
		if (in == null)
			in = new BufferedInputStream(clientSocket.getInputStream());

		StringBuilder buffer = new StringBuilder(256);
		int c;

		try {
			while ((c = in.read()) != -1) {
				if (c == '\r')
					continue;

				if (c == '\n')
					break;

				buffer.append((char) c);
			}
		} catch (SocketException e) {
			if ("connection reset".equals(e.getMessage().toLowerCase()))
				return null;

			throw e;
		}

		if (c == -1)
			return null;

		if (buffer.length() == 0)
			return "";

		return buffer.toString();
	}
}

package org.apache.catalina.connector.comet;


import org.apache.catalina.*;
import org.apache.catalina.util.*;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.TreeMap;


/**
 * 全新的长连接CometProcessor，processor处理完事情后会自己回收。
 *
 * @author lishuang
 * @version 1.0  2014/08/21
 */

final class CometProcessor implements Lifecycle, Runnable {

	private static final String SERVER_INFO = ServerInfo.getServerInfo() + " (Comet/1.0 Connector)";



	// ----------------------------------------------------- Instance Variables


	//是否有一个socket可用。
	private boolean available = false;

	private CometConnector connector = null;

	private int debug = 0;

	//id值，用于区分而已。
	private int id = 0;

	private LifecycleSupport lifecycle = new LifecycleSupport(this);


	/**
	 * The match string for identifying a session ID parameter.
	 */
	private static final String match =";" + Globals.SESSION_PARAMETER_NAME + "=";


	/**
	 * The match string for identifying a session ID parameter.
	 */
	private static final char[] SESSION_ID = match.toCharArray();


	/**
	 * The string parser we will use for parsing request lines.
	 */
	private StringParser parser = new StringParser();


	/**
	 * The proxy server name for our Connector.
	 */
	private String proxyName = null;


	/**
	 * The proxy server port for our Connector.
	 */
	private int proxyPort = 0;


	//将要传递给Container的request.
	private HttpRequestImpl request = null;


	//将要传递给Container的response
	private HttpResponseImpl response = null;


	/**
	 * The actual server port for our Connector.
	 */
	private int serverPort = 0;


	//处理多语言的工具。
	protected StringManager sm = StringManager.getManager(Constants.Package);


	//灵魂人物！！！
	private Socket socket = null;

	//是否已经启动或者停止。
	private boolean started = false;
	private boolean stopped = false;

	//该类的线程。
	private Thread thread = null;
	//线程名字
	private String threadName = null;
	//线程锁
	private final Object threadSync = new Object();
	//Http中的keepAlive标识。
	private boolean keepAlive = false;
	//是否是http1.1协议
	private boolean http11 = true;


	/**
	 * True if the client has asked to recieve a request acknoledgement. If so
	 * the server will send a preliminary 100 Continue response just after it
	 * has successfully parsed the request headers, and before starting
	 * reading the request entity body.
	 */
	private boolean sendAck = false;


	/**
	 * Ack string when pipelining HTTP requests.
	 */
	private static final byte[] ack ="HTTP/1.1 100 Continue\r\n\r\n".getBytes();


	/**
	 * CRLF.
	 */
	private static final byte[] CRLF = "\r\n".getBytes();

	/**
	 * Request line buffer.
	 */
	private HttpRequestLine requestLine = new HttpRequestLine();


	/**
	 * Processor state
	 */
	private int status = Constants.PROCESSOR_IDLE;


	//用一个CometConnector来创建一个Processor
	public CometProcessor(CometConnector connector, int id) {

		this.connector = connector;
		this.debug = connector.getDebug();
		this.id = id;
		this.proxyName = connector.getProxyName();
		this.proxyPort = connector.getProxyPort();
		this.request = (HttpRequestImpl) connector.createRequest();
		this.response = (HttpResponseImpl) connector.createResponse();
		this.serverPort = connector.getPort();
		this.threadName = this.getClass().getSimpleName()+"[" + connector.getPort() + "][" + id + "]";

	}

	public String toString() {

		return threadName;

	}


	/**
	 * 灵魂方法！！！
	 * Process an incoming TCP/IP connection on the specified socket.  Any
	 * exception that occurs during processing must be logged and swallowed.
	 * <b>NOTE</b>:  This method is called from our Connector's thread.  We
	 * must assign it to our own thread so that multiple simultaneous
	 * requests can be handled.
	 *
	 * @param socket TCP socket to process
	 */
	synchronized void assign(Socket socket) {

		// 如果当前尚且有一个socket没有被占用，那自己就先排队等会儿了。
		while (available) {
			try {
				wait();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}


		this.socket = socket;
		available = true;

		notifyAll();

	}


	// -------------------------------------------------------- Private Methods


	/**
	 * Await a newly assigned Socket from our Connector, or <code>null</code>
	 * if we are supposed to shut down.
	 */
	private synchronized Socket await() {

		// Wait for the Connector to provide a new Socket
		while (!available) {
			try {
				wait();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		// Notify the Connector that we have received this Socket
		Socket socket = this.socket;
		available = false;
		notifyAll();


		return socket;

	}


	/**
	 * Log a message on the Logger associated with our Container (if any)
	 *
	 * @param message Message to be logged
	 */
	private void log(String message) {

		Logger logger = connector.getContainer().getLogger();
		if (logger != null)
			logger.log(threadName + " " + message);

	}


	/**
	 * Log a message on the Logger associated with our Container (if any)
	 *
	 * @param message   Message to be logged
	 * @param throwable Associated exception
	 */
	private void log(String message, Throwable throwable) {

		Logger logger = connector.getContainer().getLogger();
		if (logger != null)
			logger.log(threadName + " " + message, throwable);

	}


	/**
	 * Parse the value of an <code>Accept-Language</code> header, and add
	 * the corresponding Locales to the current request.
	 *
	 * @param value The value of the <code>Accept-Language</code> header.
	 */
	private void parseAcceptLanguage(String value) {

		// Store the accumulated languages that have been requested in
		// a local collection, sorted by the quality value (so we can
		// add Locales in descending order).  The values will be ArrayLists
		// containing the corresponding Locales to be added
		TreeMap locales = new TreeMap();

		// Preprocess the value to remove all whitespace
		int white = value.indexOf(' ');
		if (white < 0)
			white = value.indexOf('\t');
		if (white >= 0) {
			StringBuffer sb = new StringBuffer();
			int len = value.length();
			for (int i = 0; i < len; i++) {
				char ch = value.charAt(i);
				if ((ch != ' ') && (ch != '\t'))
					sb.append(ch);
			}
			value = sb.toString();
		}

		// Process each comma-delimited language specification
		parser.setString(value);        // ASSERT: parser is available to us
		int length = parser.getLength();
		while (true) {

			// Extract the next comma-delimited entry
			int start = parser.getIndex();
			if (start >= length)
				break;
			int end = parser.findChar(',');
			String entry = parser.extract(start, end).trim();
			parser.advance();   // For the following entry

			// Extract the quality factor for this entry
			double quality = 1.0;
			int semi = entry.indexOf(";q=");
			if (semi >= 0) {
				try {
					quality = Double.parseDouble(entry.substring(semi + 3));
				} catch (NumberFormatException e) {
					quality = 0.0;
				}
				entry = entry.substring(0, semi);
			}

			// Skip entries we are not going to keep track of
			if (quality < 0.00005)
				continue;       // Zero (or effectively zero) quality factors
			if ("*".equals(entry))
				continue;       // FIXME - "*" entries are not handled

			// Extract the language and country for this entry
			String language = null;
			String country = null;
			String variant = null;
			int dash = entry.indexOf('-');
			if (dash < 0) {
				language = entry;
				country = "";
				variant = "";
			} else {
				language = entry.substring(0, dash);
				country = entry.substring(dash + 1);
				int vDash = country.indexOf('-');
				if (vDash > 0) {
					String cTemp = country.substring(0, vDash);
					variant = country.substring(vDash + 1);
					country = cTemp;
				} else {
					variant = "";
				}
			}

			// Add a new Locale to the list of Locales for this quality level
			Locale locale = new Locale(language, country, variant);
			Double key = new Double(-quality);  // Reverse the order
			ArrayList values = (ArrayList) locales.get(key);
			if (values == null) {
				values = new ArrayList();
				locales.put(key, values);
			}
			values.add(locale);

		}

		// Process the quality values in highest->lowest order (due to
		// negating the Double value when creating the key)
		Iterator keys = locales.keySet().iterator();
		while (keys.hasNext()) {
			Double key = (Double) keys.next();
			ArrayList list = (ArrayList) locales.get(key);
			Iterator values = list.iterator();
			while (values.hasNext()) {
				Locale locale = (Locale) values.next();
				if (debug >= 1)
					log(" Adding locale '" + locale + "'");
				request.addLocale(locale);
			}
		}

	}


	/**
	 * Parse and record the connection parameters related to this request.
	 *
	 * @param socket The socket on which we are connected
	 * @throws java.io.IOException      if an input/output error occurs
	 * @throws javax.servlet.ServletException if a parsing error occurs
	 */
	private void parseConnection(Socket socket)
			throws IOException, ServletException {

		((HttpRequestImpl) request).setInet(socket.getInetAddress());
		if (proxyPort != 0)
			request.setServerPort(proxyPort);
		else
			request.setServerPort(serverPort);
		request.setSocket(socket);

	}


	/**
	 * Parse the incoming HTTP request headers, and set the appropriate
	 * request headers.
	 *
	 * @param input The input stream connected to our socket
	 * @throws java.io.IOException      if an input/output error occurs
	 * @throws javax.servlet.ServletException if a parsing error occurs
	 */
	private void parseHeaders(SocketInputStream input)
			throws IOException, ServletException {

		while (true) {

			HttpHeader header = request.allocateHeader();

			// Read the next header
			input.readHeader(header);
			if (header.nameEnd == 0) {
				if (header.valueEnd == 0) {
					return;
				} else {
					throw new ServletException
							(sm.getString("cometProcessor.parseHeaders.colon"));
				}
			}

			String value = new String(header.value, 0, header.valueEnd);
			if (debug >= 1)
				log(" Header " + new String(header.name, 0, header.nameEnd)
						+ " = " + value);

			// Set the corresponding request headers
			if (header.equals(DefaultHeaders.AUTHORIZATION_NAME)) {
				request.setAuthorization(value);
			} else if (header.equals(DefaultHeaders.ACCEPT_LANGUAGE_NAME)) {
				parseAcceptLanguage(value);
			} else if (header.equals(DefaultHeaders.COOKIE_NAME)) {
				Cookie cookies[] = RequestUtil.parseCookieHeader(value);
				for (int i = 0; i < cookies.length; i++) {
					if (cookies[i].getName().equals
							(Globals.SESSION_COOKIE_NAME)) {
						// Override anything requested in the URL
						if (!request.isRequestedSessionIdFromCookie()) {
							// Accept only the first session id cookie
							request.setRequestedSessionId
									(cookies[i].getValue());
							request.setRequestedSessionCookie(true);
							request.setRequestedSessionURL(false);
							if (debug >= 1)
								log(" Requested cookie session id is " +
										((HttpServletRequest) request.getRequest())
												.getRequestedSessionId());
						}
					}
					if (debug >= 1)
						log(" Adding cookie " + cookies[i].getName() + "=" +
								cookies[i].getValue());
					request.addCookie(cookies[i]);
				}
			} else if (header.equals(DefaultHeaders.CONTENT_LENGTH_NAME)) {
				int n = -1;
				try {
					n = Integer.parseInt(value);
				} catch (Exception e) {
					throw new ServletException
							(sm.getString
									("cometProcessor.parseHeaders.contentLength"));
				}
				request.setContentLength(n);
			} else if (header.equals(DefaultHeaders.CONTENT_TYPE_NAME)) {
				request.setContentType(value);
			} else if (header.equals(DefaultHeaders.HOST_NAME)) {
				int n = value.indexOf(':');
				if (n < 0) {
					if (connector.getScheme().equals("http")) {
						request.setServerPort(80);
					} else if (connector.getScheme().equals("https")) {
						request.setServerPort(443);
					}
					if (proxyName != null)
						request.setServerName(proxyName);
					else
						request.setServerName(value);
				} else {
					if (proxyName != null)
						request.setServerName(proxyName);
					else
						request.setServerName(value.substring(0, n).trim());
					if (proxyPort != 0)
						request.setServerPort(proxyPort);
					else {
						int port = 80;
						try {
							port =
									Integer.parseInt(value.substring(n + 1).trim());
						} catch (Exception e) {
							throw new ServletException
									(sm.getString
											("cometProcessor.parseHeaders.portNumber"));
						}
						request.setServerPort(port);
					}
				}
			} else if (header.equals(DefaultHeaders.CONNECTION_NAME)) {
				if (header.valueEquals
						(DefaultHeaders.CONNECTION_CLOSE_VALUE)) {
					keepAlive = false;
					response.setHeader("Connection", "close");
				}
				//request.setConnection(header);
	            /*
                  if ("keep-alive".equalsIgnoreCase(value)) {
                  keepAlive = true;
                  }
                */
			} else if (header.equals(DefaultHeaders.EXPECT_NAME)) {
				if (header.valueEquals(DefaultHeaders.EXPECT_100_VALUE))
					sendAck = true;
				else
					throw new ServletException
							(sm.getString
									("cometProcessor.parseHeaders.unknownExpectation"));
			} else if (header.equals(DefaultHeaders.TRANSFER_ENCODING_NAME)) {
				//request.setTransferEncoding(header);
			}

			request.nextHeader();

		}

	}


	/**
	 * Parse the incoming HTTP request and set the corresponding HTTP request
	 * properties.
	 *
	 * @param input  The input stream attached to our socket
	 * @param output The output stream of the socket
	 * @throws java.io.IOException      if an input/output error occurs
	 * @throws javax.servlet.ServletException if a parsing error occurs
	 */
	private void parseRequest(SocketInputStream input, OutputStream output)
			throws IOException, ServletException {

		// Parse the incoming request line
		input.readRequestLine(requestLine);

		// When the previous method returns, we're actually processing a
		// request
		status = Constants.PROCESSOR_ACTIVE;

		String method =
				new String(requestLine.method, 0, requestLine.methodEnd);
		String uri = null;
		String protocol = new String(requestLine.protocol, 0,
				requestLine.protocolEnd);

		//System.out.println(" Method:" + method + "_ Uri:" + uri
		//                   + "_ Protocol:" + protocol);

		if (protocol.length() == 0)
			protocol = "HTTP/0.9";

		// Now check if the connection should be kept alive after parsing the
		// request.
		if (protocol.equals("HTTP/1.1")) {
			http11 = true;
			sendAck = false;
		} else {
			http11 = false;
			sendAck = false;
			// For HTTP/1.0, connection are not persistent by default,
			// unless specified with a Connection: Keep-Alive header.
			keepAlive = false;
		}

		// Validate the incoming request line
		if (method.length() < 1) {
			throw new ServletException
					(sm.getString("cometProcessor.parseRequest.method"));
		} else if (requestLine.uriEnd < 1) {
			throw new ServletException
					(sm.getString("cometProcessor.parseRequest.uri"));
		}

		// Parse any query parameters out of the request URI
		int question = requestLine.indexOf("?");
		if (question >= 0) {
			request.setQueryString
					(new String(requestLine.uri, question + 1,
							requestLine.uriEnd - question - 1));
			if (debug >= 1)
				log(" Query string is " +
						((HttpServletRequest) request.getRequest())
								.getQueryString());
			uri = new String(requestLine.uri, 0, question);
		} else {
			request.setQueryString(null);
			uri = new String(requestLine.uri, 0, requestLine.uriEnd);
		}

		// Checking for an absolute URI (with the HTTP protocol)
		if (!uri.startsWith("/")) {
			int pos = uri.indexOf("://");
			// Parsing out protocol and host name
			if (pos != -1) {
				pos = uri.indexOf('/', pos + 3);
				if (pos == -1) {
					uri = "";
				} else {
					uri = uri.substring(pos);
				}
			}
		}

		// Parse any requested session ID out of the request URI
		int semicolon = uri.indexOf(match);
		if (semicolon >= 0) {
			String rest = uri.substring(semicolon + match.length());
			int semicolon2 = rest.indexOf(';');
			if (semicolon2 >= 0) {
				request.setRequestedSessionId(rest.substring(0, semicolon2));
				rest = rest.substring(semicolon2);
			} else {
				request.setRequestedSessionId(rest);
				rest = "";
			}
			request.setRequestedSessionURL(true);
			uri = uri.substring(0, semicolon) + rest;
			if (debug >= 1)
				log(" Requested URL session id is " +
						((HttpServletRequest) request.getRequest())
								.getRequestedSessionId());
		} else {
			request.setRequestedSessionId(null);
			request.setRequestedSessionURL(false);
		}

		// Normalize URI (using String operations at the moment)
		String normalizedUri = normalize(uri);
		if (debug >= 1)
			log("Normalized: '" + uri + "' to '" + normalizedUri + "'");

		// Set the corresponding request properties
		((HttpRequest) request).setMethod(method);
		request.setProtocol(protocol);
		if (normalizedUri != null) {
			((HttpRequest) request).setRequestURI(normalizedUri);
		} else {
			((HttpRequest) request).setRequestURI(uri);
		}
		request.setSecure(connector.getSecure());
		request.setScheme(connector.getScheme());

		if (normalizedUri == null) {
			log(" Invalid request URI: '" + uri + "'");
			throw new ServletException("Invalid URI: " + uri + "'");
		}

		if (debug >= 1)
			log(" Request is '" + method + "' for '" + uri +
					"' with protocol '" + protocol + "'");

	}


	/**
	 * Return a context-relative path, beginning with a "/", that represents
	 * the canonical version of the specified path after ".." and "." elements
	 * are resolved out.  If the specified path attempts to go outside the
	 * boundaries of the current context (i.e. too many ".." path elements
	 * are present), return <code>null</code> instead.
	 *
	 * @param path Path to be normalized
	 */
	protected String normalize(String path) {

		if (path == null)
			return null;

		// Create a place for the normalized path
		String normalized = path;

		// Normalize "/%7E" and "/%7e" at the beginning to "/~"
		if (normalized.startsWith("/%7E") ||
				normalized.startsWith("/%7e"))
			normalized = "/~" + normalized.substring(4);

		// Prevent encoding '%', '/', '.' and '\', which are special reserved
		// characters
		if ((normalized.indexOf("%25") >= 0)
				|| (normalized.indexOf("%2F") >= 0)
				|| (normalized.indexOf("%2E") >= 0)
				|| (normalized.indexOf("%5C") >= 0)
				|| (normalized.indexOf("%2f") >= 0)
				|| (normalized.indexOf("%2e") >= 0)
				|| (normalized.indexOf("%5c") >= 0)) {
			return null;
		}

		if (normalized.equals("/."))
			return "/";

		// Normalize the slashes and add leading slash if necessary
		if (normalized.indexOf('\\') >= 0)
			normalized = normalized.replace('\\', '/');
		if (!normalized.startsWith("/"))
			normalized = "/" + normalized;

		// Resolve occurrences of "//" in the normalized path
		while (true) {
			int index = normalized.indexOf("//");
			if (index < 0)
				break;
			normalized = normalized.substring(0, index) +
					normalized.substring(index + 1);
		}

		// Resolve occurrences of "/./" in the normalized path
		while (true) {
			int index = normalized.indexOf("/./");
			if (index < 0)
				break;
			normalized = normalized.substring(0, index) +
					normalized.substring(index + 2);
		}

		// Resolve occurrences of "/../" in the normalized path
		while (true) {
			int index = normalized.indexOf("/../");
			if (index < 0)
				break;
			if (index == 0)
				return (null);  // Trying to go outside our context
			int index2 = normalized.lastIndexOf('/', index - 1);
			normalized = normalized.substring(0, index2) +
					normalized.substring(index + 3);
		}

		// Declare occurrences of "/..." (three or more dots) to be invalid
		// (on some Windows platforms this walks the directory tree!!!)
		if (normalized.indexOf("/...") >= 0)
			return (null);

		// Return the normalized path that we have completed
		return (normalized);

	}


	/**
	 * Send a confirmation that a request has been processed when pipelining.
	 * HTTP/1.1 100 Continue is sent back to the client.
	 *
	 * @param output Socket output stream
	 */
	private void ackRequest(OutputStream output)
			throws IOException {
		if (sendAck)
			output.write(ack);
	}


	/**
	 * 处理socket，任何异常要么吞掉，要么处理掉。
	 *
	 * @param socket 远道而来的socket
	 */
	private void process(Socket socket) {
		boolean ok = true;
		boolean finishResponse = true;
		SocketInputStream input = null;
		OutputStream output = null;


		//测试代码
		try {
			InputStream inputStream=socket.getInputStream();
			BufferedReader is = new BufferedReader(new InputStreamReader(inputStream));
			//由Socket对象得到输入流，并构造相应的BufferedReader对象
			PrintWriter os = new PrintWriter(socket.getOutputStream());
			//由Socket对象得到输出流，并构造PrintWriter对象
			//BufferedReader sin = new BufferedReader(new InputStreamReader(System.in));
			//由系统标准输入设备构造BufferedReader对象
			String line = null;
			String res=null;
			//在标准输出上打印从客户端读入的字符串
			//line = sin.readLine();
			//从标准输入读入一字符串
			do {
				line = is.readLine();
				System.out.println("Client:" + line);


				//如果该字符串为 "bye"，则停止循环
				res = "<h1>Good</h1>";
				os.println(res);
				//向客户端输出该字符串
				os.flush();

				if (line.equals("")) break;

			} while (true);
			os.close(); //关闭Socket输出流
			is.close(); //关闭Socket输入流
			socket.close();

		}catch (IOException e){
			e.printStackTrace();
		}



		/*
		// Construct and initialize the objects we will need
		try {
			input = new SocketInputStream(socket.getInputStream(),connector.getBufferSize());
		} catch (Exception e) {
			log("process.create", e);
			ok = false;
		}

		keepAlive = true;

		while (!stopped && ok && keepAlive) {

			finishResponse = true;

			try {
				request.setStream(input);
				request.setResponse(response);
				output = socket.getOutputStream();
				response.setStream(output);
				response.setRequest(request);
				((HttpServletResponse) response.getResponse()).setHeader("Server", SERVER_INFO);

			} catch (Exception e) {
				log("process.create", e);
				ok = false;
			}


			// Parse the incoming request
			try {
				if (ok) {

					parseConnection(socket);
					parseRequest(input, output);
					if (!request.getRequest().getProtocol().startsWith("HTTP/0")){
						parseHeaders(input);
					}
					if (http11) {
						// Sending a request acknowledge back to the client if
						// requested.
						ackRequest(output);
						// If the protocol is HTTP/1.1, chunking is allowed.
						if (connector.isChunkingAllowed()){
							response.setAllowChunking(true);
						}
					}

				}
			} catch (EOFException e) {
				// It's very likely to be a socket disconnect on either the
				// client or the server
				ok = false;
				finishResponse = false;
			} catch (ServletException e) {
				ok = false;
				try {
					((HttpServletResponse) response.getResponse())
							.sendError(HttpServletResponse.SC_BAD_REQUEST);
				} catch (Exception f) {
					e.printStackTrace();
				}
			} catch (InterruptedIOException e) {
				if (debug > 1) {
					try {
						log("process.parse", e);
						((HttpServletResponse) response.getResponse())
								.sendError(HttpServletResponse.SC_BAD_REQUEST);
					} catch (Exception f) {
						e.printStackTrace();
					}
				}
				ok = false;
			} catch (Exception e) {
				try {
					log("process.parse", e);
					((HttpServletResponse) response.getResponse()).sendError
							(HttpServletResponse.SC_BAD_REQUEST);
				} catch (Exception f) {
					e.printStackTrace();
				}
				ok = false;
			}

			// Ask our Container to process this request
			try {
				((HttpServletResponse) response).setHeader("Date", FastHttpDateFormat.getCurrentDate());
				if (ok) {

					//这里把请求交给容器去处理了。
					connector.getContainer().invoke(request, response);
				}
			} catch (ServletException e) {
				log("process.invoke", e);
				try {
					((HttpServletResponse) response.getResponse()).sendError
							(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				} catch (Exception f) {
					e.printStackTrace();
				}
				ok = false;
			} catch (InterruptedIOException e) {
				ok = false;
			} catch (Throwable e) {
				log("process.invoke", e);
				try {
					((HttpServletResponse) response.getResponse()).sendError
							(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				} catch (Exception f) {
					e.printStackTrace();
				}
				ok = false;
			}

			// Finish up the handling of the request
			if (finishResponse) {
				try {
					response.finishResponse();
				} catch (IOException e) {
					ok = false;
				} catch (Throwable e) {
					log("process.invoke", e);
					ok = false;
				}
				try {
					request.finishRequest();
				} catch (IOException e) {
					ok = false;
				} catch (Throwable e) {
					log("process.invoke", e);
					ok = false;
				}
				try {
					if (output != null)
						output.flush();
				} catch (IOException e) {
					ok = false;
				}
			}

			// We have to check if the connection closure has been requested
			// by the application or the response stream (in case of HTTP/1.0
			// and keep-alive).
			if ("close".equals(response.getHeader("Connection"))) {
				keepAlive = false;
			}

			// End of request processing
			status = Constants.PROCESSOR_IDLE;

			// Recycling the request and the response objects
			request.recycle();
			response.recycle();

		}

		try {
			shutdownInput(input);
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Throwable e) {
			log("process.invoke", e);
		}
		socket = null;
		*/


	}


	protected void shutdownInput(InputStream input) {
		try {
			int available = input.available();
			// 跳过所有没有读完的数据。
			if (available > 0) {
				long res = input.skip(available);
				System.out.println("skip "+res);
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}


	// ---------------------------------------------- Background Thread Methods


	/**
	 * The background thread that listens for incoming TCP/IP connections and
	 * hands them off to an appropriate processor.
	 */
	public void run() {

		// Process requests until we receive a shutdown signal
		while (!stopped) {

			// 等待分配过来的socket
			Socket socket = await();
			if (socket == null){
				continue;
			}

			//开始处理这个socket请求。
			process(socket);

			//干完活了就回收自己。
			connector.recycle(this);

		}

		// Tell threadStop() we have shut ourselves down successfully
		synchronized (threadSync) {
			threadSync.notifyAll();
		}

	}


	//启动线程
	private void threadStart() {

		log(sm.getString("cometProcessor.starting"));
		thread = new Thread(this, threadName);
		thread.setDaemon(true);
		thread.start();
	}


	//停止线程
	private void threadStop() {

		log(sm.getString("cometProcessor.stopping"));

		stopped = true;
		assign(null);

		if (status != Constants.PROCESSOR_IDLE) {
			// Only wait if the processor is actually processing a command
			synchronized (threadSync) {
				try {
					threadSync.wait(5000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		thread = null;

	}


	/* ------------------------Lifecycle接口方法--------------------------------*/
	public void addLifecycleListener(LifecycleListener listener) {
		lifecycle.addLifecycleListener(listener);
	}
	public LifecycleListener[] findLifecycleListeners() {
		return lifecycle.findLifecycleListeners();
	}
	public void removeLifecycleListener(LifecycleListener listener) {
		lifecycle.removeLifecycleListener(listener);
	}
	public void start() throws LifecycleException {
		if (started){
			throw new LifecycleException(sm.getString("cometProcessor.alreadyStarted"));
		}
		lifecycle.fireLifecycleEvent(START_EVENT, null);
		started = true;

		threadStart();

	}
	public void stop() throws LifecycleException {

		if (!started){
			throw new LifecycleException(sm.getString("cometProcessor.notStarted"));
		}

		lifecycle.fireLifecycleEvent(STOP_EVENT, null);
		started = false;

		threadStop();

	}
	/* ------------------------Lifecycle接口方法--------end------------------------*/

}

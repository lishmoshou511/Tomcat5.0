package org.apache.catalina.connector.comet;


import lombok.Getter;
import lombok.Setter;
import org.apache.catalina.*;
import org.apache.catalina.net.DefaultServerSocketFactory;
import org.apache.catalina.net.ServerSocketFactory;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.catalina.util.StringManager;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Stack;
import java.util.Vector;


/**
 * 用于长连接的Connector,全新改装的机器。
 *
 * @author lishuang 2014/08/21
 */
public final class CometConnector implements Connector, Lifecycle, Runnable {
	@Getter
	@Setter
	private Service service = null;

	/**
	 * The accept count for this Connector.
	 */
	@Getter
	@Setter
	private int acceptCount = 10;

	/**
	 * The IP address on which to bind, if any.  If <code>null</code>, all
	 * addresses on the server will be bound.
	 */
	@Getter
	@Setter
	private String address = null;

	/**
	 * The input buffer size we should create on input streams.
	 */
	@Getter
	@Setter
	private int bufferSize = 2048;

	/**
	 * The Container used for processing requests received by this Connector.
	 */
	@Getter
	@Setter
	protected Container container = null;

	/**
	 * The set of processors that have ever been created.
	 */
	private Vector<CometProcessor> created = new Vector<>();


	/**
	 * The current number of processors that have been created.
	 */
	@Getter
	private int curProcessors = 0;

	/**
	 * The debugging detail level for this component.
	 */
	@Getter
	@Setter
	private int debug = 0;

	/**
	 * The "enable DNS lookups" flag for this Connector.
	 */
	@Setter
	private boolean enableLookups = false;

	public boolean getEnableLookups() {
		return enableLookups;
	}

	/**
	 * The server socket factory for this component.
	 */
	@Setter
	private ServerSocketFactory factory = null;

	public ServerSocketFactory getFactory() {

		if (this.factory == null) {
			synchronized (this) {
				this.factory = new DefaultServerSocketFactory();
			}
		}
		return (this.factory);

	}

	/**
	 * Descriptive information about this Connector implementation.
	 */
	private static final String info = "org.apache.catalina.connector.http.HttpConnector/1.0";

	public String getInfo() {
		return info;
	}


	/**
	 * The lifecycle event support for this component.
	 */
	protected LifecycleSupport lifecycle = new LifecycleSupport(this);


	/**
	 * The minimum number of processors to start at initialization time.
	 */
	@Getter
	@Setter
	protected int minProcessors = 5;


	/**
	 * The maximum number of processors allowed, or <0 for unlimited.
	 */
	@Getter
	@Setter
	private int maxProcessors = 20;


	/**
	 * Timeout value on the incoming connection.
	 * Note : a value of 0 means no timeout.
	 */
	@Getter
	@Setter
	private int connectionTimeout = Constants.DEFAULT_CONNECTION_TIMEOUT;


	/**
	 * The port number on which we listen for HTTP requests.
	 */
	@Getter
	@Setter
	private int port = 8080;


	/**
	 * The set of processors that have been created but are not currently
	 * being used to process a request.
	 */
	private final Stack<CometProcessor> processors = new Stack<>();


	/**
	 * The server name to which we should pretend requests to this Connector
	 * were directed.  This is useful when operating Tomcat behind a proxy
	 * server, so that redirects get constructed accurately.  If not specified,
	 * the server name included in the <code>Host</code> header is used.
	 */
	@Getter
	@Setter
	private String proxyName = null;


	/**
	 * The server port to which we should pretent requests to this Connector
	 * were directed.  This is useful when operating Tomcat behind a proxy
	 * server, so that redirects get constructed accurately.  If not specified,
	 * the port number specified by the <code>port</code> property is used.
	 */
	@Getter
	@Setter
	private int proxyPort = 0;


	/**
	 * The redirect port for non-SSL to SSL redirects.
	 */
	@Getter
	@Setter
	private int redirectPort = 443;


	/**
	 * The request scheme that will be set on all requests received
	 * through this connector.
	 */
	@Getter
	@Setter
	private String scheme = "http";


	/**
	 * The secure connection flag that will be set on all requests received
	 * through this connector.
	 */
	@Setter
	private boolean secure = false;

	public boolean getSecure() {
		return secure;
	}


	/**
	 * The server socket through which we listen for incoming TCP connections.
	 */
	private ServerSocket serverSocket = null;


	/**
	 * The string manager for this package.
	 */
	private StringManager sm = StringManager.getManager(Constants.Package);


	/**
	 * Has this component been initialized yet?
	 */
	private boolean initialized = false;


	/**
	 * Has this component been started yet?
	 */
	private boolean started = false;


	/**
	 * The shutdown signal to our background thread
	 */
	private boolean stopped = false;


	/**
	 * The background thread.
	 */
	private Thread thread = null;


	/**
	 * The name to register for the background thread.
	 */
	private String threadName = null;


	/**
	 * The thread synchronization object.
	 */
	private final Object threadSync = new Object();


	/**
	 * Is chunking allowed ?
	 */
	@Setter
	private boolean allowChunking = true;

	public boolean getAllowChunking() {

		return allowChunking;

	}

	public boolean isChunkingAllowed() {

		return allowChunking;
	}


	/**
	 * Use TCP no delay ?
	 */
	@Setter
	private boolean tcpNoDelay = true;

	public boolean getTcpNoDelay() {

		return (this.tcpNoDelay);

	}

	/**
	 * Is this connector available for processing requests?
	 */
	public boolean isAvailable() {

		return (started);

	}


	/**
	 * Create (or allocate) and return a Request object suitable for
	 * specifying the contents of a Request to the responsible Container.
	 */
	public Request createRequest() {
		HttpRequestImpl request = new HttpRequestImpl();
		request.setConnector(this);
		return (request);

	}


	/**
	 * Create (or allocate) and return a Response object suitable for
	 * receiving the contents of a Response from the responsible Container.
	 */
	public Response createResponse() {
		HttpResponseImpl response = new HttpResponseImpl();
		response.setConnector(this);
		return (response);

	}


	/**
	 * Log a message on the Logger associated with our Container (if any).
	 *
	 * @param message Message to be logged
	 */
	private void log(String message) {
		Logger logger = container.getLogger();
		String localName = threadName;

		if (localName == null) {
			localName = "CometConnector";
		}

		if (logger != null) {
			logger.log(localName + " " + message);
		} else {
			System.out.println(localName + " " + message);
		}

	}


	/**
	 * Log a message on the Logger associated with our Container (if any).
	 *
	 * @param message   Message to be logged
	 * @param throwable Associated exception
	 */
	private void log(String message, Throwable throwable) {

		Logger logger = container.getLogger();
		String localName = threadName;
		if (localName == null)
			localName = "HttpConnector";
		if (logger != null)
			logger.log(localName + " " + message, throwable);
		else {
			System.out.println(localName + " " + message);
			throwable.printStackTrace(System.out);
		}

	}




	/*********************************processor栈相关*****start*********************************************/
	private CometProcessor newProcessor() {

		System.out.println("新建processor");
		CometProcessor processor = new CometProcessor(this, curProcessors++);

		try {
			processor.start();
		} catch (LifecycleException e) {
			log("newProcessor", e);
			return (null);
		}

		created.addElement(processor);

		return processor;

	}

	private CometProcessor createProcessor() {

		synchronized (processors) {
			if (processors.size() > 0) {
				System.out.println("重用之前的processor");
				return processors.pop();
			}

			if ((maxProcessors > 0) && (curProcessors < maxProcessors)) {
				return newProcessor();
			} else {
				if (maxProcessors < 0) {
					return newProcessor();
				} else {
					return null;
				}
			}
		}
	}


	public void recycle(CometProcessor processor) {
		processors.push(processor);
	}

	/*********************************processor栈相关*****end*********************************************/
	/**
	 * Open and return the server socket for this Connector.  If an IP
	 * address has been specified, the socket will be opened only on that
	 * address; otherwise it will be opened on all addresses.
	 *
	 * @throws java.io.IOException                     input/output or network error
	 * @throws java.security.KeyStoreException         error instantiating the
	 *                                                 KeyStore from file (SSL only)
	 * @throws java.security.NoSuchAlgorithmException  KeyStore algorithm unsupported
	 *                                                 by current provider (SSL only)
	 * @throws java.security.cert.CertificateException general certificate error (SSL only)
	 * @throws java.security.UnrecoverableKeyException internal KeyStore problem with
	 *                                                 the certificate (SSL only)
	 * @throws java.security.KeyManagementException    problem in the key management
	 *                                                 layer (SSL only)
	 */
	private ServerSocket open() throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException, UnrecoverableKeyException, KeyManagementException {

		//获取一个Socket工厂，其实就相当于直接创建ServerSocket，没什么特别的。
		ServerSocketFactory factory = getFactory();

		//对于所有IP均能连接的ServerSocket，一般也是这个方法。
		if (address == null) {
			log(sm.getString("cometConnector.allAddresses"));
			try {
				//这里其实和new ServerSocket(port, acceptCount)一样
				return (factory.createSocket(port, acceptCount));
			} catch (BindException be) {
				throw new BindException(be.getMessage() + ":" + port);
			}
		} else {
			//对于指定了IP地址的，则创建指定的地址，创建不成功就继续返回所有地址的。
			try {
				InetAddress is = InetAddress.getByName(address);
				log(sm.getString("cometConnector.anAddress", address));
				try {
					//这里其实和new ServerSocket(port, acceptCount, is)一样
					return (factory.createSocket(port, acceptCount, is));
				} catch (BindException be) {
					throw new BindException(be.getMessage() + ":" + address +
							":" + port);
				}
			} catch (Exception e) {
				log(sm.getString("cometConnector.noAddress", address));
				try {
					return (factory.createSocket(port, acceptCount));
				} catch (BindException be) {
					throw new BindException(be.getMessage() + ":" + port);
				}
			}
		}


	}


	//线程入口方法
	public void run() {
		//直到接到停止命令，否则一直运行下去。
		while (!stopped) {

			Socket socket = null;
			try {
				//等待下一个来连接。
				socket = serverSocket.accept();

				//设置读超时，一般我们都不需要超时的，毕竟长连接嘛。
				if (connectionTimeout > 0){
					socket.setSoTimeout(connectionTimeout);
				}

				socket.setTcpNoDelay(tcpNoDelay);

			} catch (AccessControlException ace) {
				log("socket accept security exception", ace);
				continue;
			} catch (IOException e) {

				try {
					synchronized (threadSync) {
						if (started && !stopped){
							log("accept error: ", e);
						}

						if (!stopped) {

							serverSocket.close();

							serverSocket = open();
						}
					}

				} catch (IOException ioe) {
					log("socket reopen, io problem: ", ioe);
					break;
				} catch (KeyStoreException kse) {
					log("socket reopen, keystore problem: ", kse);
					break;
				} catch (NoSuchAlgorithmException nsae) {
					log("socket reopen, keystore algorithm problem: ", nsae);
					break;
				} catch (CertificateException ce) {
					log("socket reopen, certificate problem: ", ce);
					break;
				} catch (UnrecoverableKeyException uke) {
					log("socket reopen, unrecoverable key: ", uke);
					break;
				} catch (KeyManagementException kme) {
					log("socket reopen, key management problem: ", kme);
					break;
				}

				continue;
			}

			//从池子中取出一个processor.
			CometProcessor processor = createProcessor();

			//如果取出是null，就什么也不做，直接关闭socket。一般都不可能进入到这个方法中。
			if (processor == null) {
				try {
					log(sm.getString("cometConnector.noProcessor"));
					socket.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				continue;
			}

			//把socket交给processor去处理了。
			processor.assign(socket);

			// processor处理完事情后会自己回收。

		}

		synchronized (threadSync) {
			threadSync.notifyAll();
		}

	}


	/**
	 * 启动CometConnector的后台进程。这个也是连接器主要工作的进程
	 */
	private void threadStart() {

		log(sm.getString("cometConnector.starting"));

		threadName = "CometConnector[" + port + "]";

		thread = new Thread(this, threadName);
		thread.setDaemon(true);
		thread.start();

	}


	/**
	 * 停止CometConnector的后台线程
	 */
	private void threadStop() {

		log(sm.getString("cometConnector.stopping"));

		stopped = true;
		try {
			threadSync.wait(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
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


	/**
	 * 初始化方法中主要就是创建了一个ServerSocket,也没有干其他事儿。
	 */
	public void initialize() throws LifecycleException {

		if (initialized) {
			throw new LifecycleException(sm.getString("cometConnector.alreadyInitialized"));
		}

		this.initialized = true;
		Exception eRethrow = null;

		// Establish a server socket on the specified port
		try {
			serverSocket = open();
		} catch (IOException ioe) {
			log("cometConnector, io problem: ", ioe);
			eRethrow = ioe;
		} catch (KeyStoreException kse) {
			log("cometConnector, keystore problem: ", kse);
			eRethrow = kse;
		} catch (NoSuchAlgorithmException nsae) {
			log("cometConnector, keystore algorithm problem: ", nsae);
			eRethrow = nsae;
		} catch (CertificateException ce) {
			log("cometConnector, certificate problem: ", ce);
			eRethrow = ce;
		} catch (UnrecoverableKeyException uke) {
			log("cometConnector, unrecoverable key: ", uke);
			eRethrow = uke;
		} catch (KeyManagementException kme) {
			log("cometConnector, key management problem: ", kme);
			eRethrow = kme;
		}

		if (eRethrow != null)
			throw new LifecycleException(threadName + ".open", eRethrow);

	}


	//开始运行连接器。主要打开了自己的线程，创建了一堆processor.并且在创建processor的时候会自动启动processor.
	public void start() throws LifecycleException {

		if (started) {
			throw new LifecycleException(sm.getString("cometConnector.alreadyStarted"));
		}

		lifecycle.fireLifecycleEvent(START_EVENT, null);
		started = true;

		// Start our background thread
		threadStart();

		// 创建processors池。
		while (curProcessors < minProcessors) {
			if ((maxProcessors > 0) && (curProcessors >= maxProcessors)){
				break;
			}
			CometProcessor processor = newProcessor();
			recycle(processor);
		}

	}


	//停止CometConnector.
	public void stop() throws LifecycleException {

		// Validate and update our current state
		if (!started){
			throw new LifecycleException(sm.getString("cometConnector.notStarted"));
		}

		lifecycle.fireLifecycleEvent(STOP_EVENT, null);
		started = false;

		//关闭全部的processor.
		for(CometProcessor processor:created){
			try {
				processor.stop();
			} catch (LifecycleException e) {
				log("HttpConnector.stop", e);
			}
		}
		synchronized (threadSync) {
			//关闭正在使用的serverSocket.
			if (serverSocket != null) {
				try {
					serverSocket.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			// 停止掉CometConnector线程。
			threadStop();
		}
		serverSocket = null;

	}


	/* ------------------------Lifecycle接口方法--------end------------------------*/

}

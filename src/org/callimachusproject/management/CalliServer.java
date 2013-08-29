package org.callimachusproject.management;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.mail.MessagingException;

import org.apache.http.HttpHost;
import org.apache.http.client.utils.URIUtils;
import org.callimachusproject.Version;
import org.callimachusproject.client.HttpClientFactory;
import org.callimachusproject.io.FileUtil;
import org.callimachusproject.logging.LoggingProperties;
import org.callimachusproject.repository.CalliRepository;
import org.callimachusproject.repository.DatasourceManager;
import org.callimachusproject.server.WebServer;
import org.callimachusproject.setup.SetupRealm;
import org.callimachusproject.setup.SetupTool;
import org.callimachusproject.util.CallimachusConf;
import org.callimachusproject.util.MailProperties;
import org.callimachusproject.util.SystemProperties;
import org.openrdf.OpenRDFException;
import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.model.util.GraphUtil;
import org.openrdf.model.util.GraphUtilException;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.query.BindingSet;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.config.RepositoryConfig;
import org.openrdf.repository.config.RepositoryConfigException;
import org.openrdf.repository.config.RepositoryConfigSchema;
import org.openrdf.repository.config.RepositoryImplConfig;
import org.openrdf.repository.manager.LocalRepositoryManager;
import org.openrdf.repository.object.ObjectConnection;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.Rio;
import org.openrdf.rio.helpers.StatementCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CalliServer implements CalliServerMXBean {
	private static final String CHANGES_PATH = "../changes/";
	private static final String SCHEMA_GRAPH = "types/SchemaGraph";
	private static final String ORIGIN = "http://callimachusproject.org/rdf/2009/framework#Origin";
	private static final String PREFIX = "PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>\n"
			+ "PREFIX calli:<http://callimachusproject.org/rdf/2009/framework#>\n";
	private static final String SEL_AUTH_MANAGER_LABEL = PREFIX
			+ "SELECT ?manager ?label { ?manager a calli:AuthenticationManager; rdfs:label ?label }";
	private static final ThreadFactory THREADFACTORY = new ThreadFactory() {
		public Thread newThread(Runnable r) {
			String name = CalliServer.class.getSimpleName() + "-"
					+ Integer.toHexString(r.hashCode());
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		}
	};

	public static interface ServerListener {
		void repositoryInitialized(String repositoryID, CalliRepository repository);

		void repositoryShutDown(String repositoryID);

		void webServiceStarted(WebServer server);

		void webServiceStopping(WebServer server);
	}

	private final Logger logger = LoggerFactory.getLogger(CalliServer.class);
	private final ExecutorService executor = Executors
			.newSingleThreadScheduledExecutor(THREADFACTORY);
	private final CallimachusConf conf;
	private final ServerListener listener;
	private final File serverCacheDir;
	private volatile int starting;
	private volatile boolean running;
	private volatile boolean stopping;
	private int processing;
	private Exception exception;
	WebServer server;
	private final LocalRepositoryManager manager;
	private final Map<String, CalliRepository> repositories = new LinkedHashMap<String, CalliRepository>();

	public CalliServer(CallimachusConf conf, LocalRepositoryManager manager,
			ServerListener listener) throws OpenRDFException, IOException {
		this.conf = conf;
		this.listener = listener;
		this.manager = manager;
		String tmpDirStr = System.getProperty("java.io.tmpdir");
		if (tmpDirStr == null) {
			tmpDirStr = "tmp";
		}
		File tmpDir = new File(tmpDirStr);
		if (!tmpDir.exists()) {
			tmpDir.mkdirs();
		}
		File cacheDir = File.createTempFile("cache", "", tmpDir);
		cacheDir.delete();
		cacheDir.mkdirs();
		FileUtil.deleteOnExit(cacheDir);
		File in = new File(cacheDir, "client");
		HttpClientFactory.setCacheDirectory(in);
		serverCacheDir = new File(cacheDir, "server");
	}

	public String toString() {
		return manager.getBaseDir().toString();
	}

	public boolean isRunning() {
		return running;
	}

	public synchronized void init() throws OpenRDFException, IOException {
		if (isWebServiceRunning()) {
			stopWebServiceNow();
		}
		try {
			if (isThereAnOriginSetup()) {
				server = createServer();
			} else {
				logger.warn("No Web origin is setup on this server");
			}
		} catch (IOException e) {
			logger.error(e.toString(), e);
		} catch (OpenRDFException e) {
			logger.error(e.toString(), e);
		} catch (GeneralSecurityException e) {
			logger.error(e.toString(), e);
		} finally {
			if (server == null) {
				shutDownRepositories();
			}
		}
	}

	public synchronized void start() throws IOException, OpenRDFException {
		running = true;
		notifyAll();
		if (server != null) {
			try {
				logger.info("Callimachus is binding to {}", toString());
				for (SetupRealm origin : getRealms()) {
					HttpHost host = URIUtils.extractHost(java.net.URI.create(origin.getRealm()));
					HttpClientFactory.getInstance().setProxy(host, server);
				}
				for (CalliRepository repository : repositories.values()) {
					repository.setCompileRepository(true);
				}
				server.start();
				System.gc();
				Thread.yield();
				long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
				logger.info("Callimachus started after {} seconds", uptime / 1000.0);
				if (listener != null) {
					listener.webServiceStarted(server);
				}
			} catch (IOException e) {
				logger.error(e.toString(), e);
			} catch (OpenRDFException e) {
				logger.error(e.toString(), e);
			}
		}
	}

	public synchronized void stop() throws IOException, OpenRDFException {
		running = false;
		if (isWebServiceRunning()) {
			stopWebServiceNow();
		}
		notifyAll();
	}

	public synchronized void destroy() throws IOException, OpenRDFException {
		stop();
		shutDownRepositories();
		manager.shutDown();
		notifyAll();
	}

	@Override
	public String getServerName() throws IOException {
		String name = conf.getServerName();
		if (name == null || name.length() == 0)
			return Version.getInstance().getVersion();
		return name;
	}

	@Override
	public synchronized void setServerName(String name) throws IOException {
		if (name == null || name.length() == 0 || name.equals(Version.getInstance().getVersion())) {
			conf.setServerName(null);
		} else {
			conf.setServerName(name);
		}
		if (server != null) {
			server.setName(getServerName());
		}
	}

	public String getPorts() throws IOException {
		int[] ports = getPortArray();
		StringBuilder sb = new StringBuilder();
		for (int i=0; i<ports.length; i++) {
			sb.append(ports[i]);
			if (i <ports.length - 1) {
				sb.append(' ');
			}
		}
		return sb.toString();
	}

	public synchronized void setPorts(String portStr) throws IOException {
		int[] ports = new int[0];
		if (portStr != null && portStr.trim().length() > 0) {
			String[] values = portStr.trim().split("\\s+");
			ports = new int[values.length];
			for (int i = 0; i < values.length; i++) {
				ports[i] = Integer.parseInt(values[i]);
			}
		}
		conf.setPorts(ports);
		if (server != null) {
			server.listen(getPortArray(), getSSLPortArray());
		}
	}

	public String getSSLPorts() throws IOException {
		int[] ports = getSSLPortArray();
		StringBuilder sb = new StringBuilder();
		for (int i=0; i<ports.length; i++) {
			sb.append(ports[i]);
			if (i <ports.length - 1) {
				sb.append(' ');
			}
		}
		return sb.toString();
	}

	public synchronized void setSSLPorts(String portStr) throws IOException {
		int[] ports = new int[0];
		if (portStr != null && portStr.trim().length() > 0) {
			String[] values = portStr.trim().split("\\s+");
			ports = new int[values.length];
			for (int i = 0; i < values.length; i++) {
				ports[i] = Integer.parseInt(values[i]);
			}
		}
		conf.setSslPorts(ports);
		if (server != null) {
			server.listen(getPortArray(), getSSLPortArray());
		}
	}

	public boolean isStartingInProgress() {
		return starting > 0;
	}

	public boolean isStoppingInProgress() {
		return stopping;
	}

	public synchronized boolean isWebServiceRunning() {
		return server != null && server.isRunning();
	}

	public synchronized void startWebService() throws Exception {
		if (isWebServiceRunning())
			return;
		final int start = ++starting;
		submit(new Callable<Void>() {
			public Void call() throws Exception {
				startWebServiceNow(start);
				return null;
			}
		});
	}

	public void restartWebService() throws Exception {
		final int start = ++starting;
		submit(new Callable<Void>() {
			public Void call() throws Exception {
				stopWebServiceNow();
				startWebServiceNow(start);
				return null;
			}
		});
	}

	public void stopWebService() throws Exception {
		if (stopping || !isWebServiceRunning())
			return;
		submit(new Callable<Void>() {
			public Void call() throws Exception {
				stopWebServiceNow();
				return null;
			}
		});
	}

	@Override
	public Map<String, String> getMailProperties() throws IOException {
		return MailProperties.getInstance().getMailProperties();
	}

	@Override
	public void setMailProperties(Map<String, String> lines)
			throws IOException, MessagingException {
		MailProperties.getInstance().setMailProperties(lines);
	}

	@Override
	public Map<String, String> getLoggingProperties() throws IOException {
		return LoggingProperties.getInstance().getLoggingProperties();
	}

	@Override
	public void setLoggingProperties(Map<String, String> lines)
			throws IOException, MessagingException {
		LoggingProperties.getInstance().setLoggingProperties(lines);
	}

	public synchronized void checkForErrors() throws Exception {
		try {
			if (exception != null)
				throw exception;
		} finally {
			exception = null;
		}
	}

	public synchronized boolean isSetupInProgress() {
		return processing > 0;
	}

	public synchronized String[] getWebappOrigins() throws IOException {
		return conf.getWebappOrigins();
	}

	@Override
	public synchronized void setWebappOrigins(String[] origins)
			throws Exception {
		Map<String, String> previously = conf.getOriginRepositoryIDs();
		final Map<String, String> newOrigins = new LinkedHashMap<String, String>();
		for (String origin : origins) {
			if (!previously.containsKey(origin)) {
				newOrigins.put(origin, getRepositoryId(origin));
			}
		}
		final Map<String, String> oldOrigins = new LinkedHashMap<String, String>();
		oldOrigins.putAll(previously);
		oldOrigins.keySet().removeAll(Arrays.asList(origins));
		final Map<String, String> subsequently = new LinkedHashMap<String, String>(previously);
		subsequently.keySet().removeAll(oldOrigins.keySet());
		conf.setOriginRepositoryIDs(subsequently);
		submit(new Callable<Void>() {
			public Void call() throws Exception {
				for (Map.Entry<String, String> e : oldOrigins.entrySet()) {
					String id = e.getValue();
					if (!subsequently.values().contains(id)
							&& manager.hasRepositoryConfig(id)) {
						String prefix = id + "/";
						TreeSet<String> subset = new TreeSet<String>();
						for (String other : manager.getRepositoryIDs()) {
							if (other.startsWith(prefix)) {
								subset.add(other);
							}
						}
						while (!subset.isEmpty()) {
							manager.removeRepository(subset.pollLast());
						}
						manager.removeRepository(id);
					}
				}
				for (Map.Entry<String, String> e : newOrigins.entrySet()) {
					String origin = e.getKey();
					String id = e.getValue();
					CalliRepository repository = getSetupRepository(id, origin);
					try {
						SetupTool tool = new SetupTool(id, repository, conf);
						tool.setupWebappOrigin(origin);
					} finally {
						refreshRepository(id);
					}
					serveRealm(origin, id);
				}
				return null;
			}
		});
	}

	public SetupRealm[] getRealms() throws IOException, OpenRDFException {
		List<SetupRealm> list = new ArrayList<SetupRealm>();
		Set<String> webapps = new LinkedHashSet<String>(Arrays.asList(getWebappOrigins()));
		Map<String, String> map = conf.getOriginRepositoryIDs();
		for (String repositoryID : new LinkedHashSet<String>(map.values())) {
			if (!manager.hasRepositoryConfig(repositoryID))
				continue;
			CalliRepository repository = getRepository(repositoryID);
			SetupTool tool = new SetupTool(repositoryID, repository, conf);
			SetupRealm[] origins = tool.getRealms();
			for (SetupRealm origin : origins) {
				webapps.remove(origin.getWebappOrigin());
			}
			list.addAll(Arrays.asList(origins));
		}
		for (String webappOrigin : webapps) {
			// this webapp is not yet setup in the store
			String id = map.get(webappOrigin);
			list.add(new SetupRealm(webappOrigin, id));
		}
		return list.toArray(new SetupRealm[list.size()]);
	}

	public Map<String, String> getAuthenticationManagers()
			throws OpenRDFException, IOException {
		Map<String, String> managers = new LinkedHashMap<String, String>();
		Map<String, String> map = conf.getOriginRepositoryIDs();
		for (String repositoryID : new LinkedHashSet<String>(map.values())) {
			if (!manager.hasRepositoryConfig(repositoryID))
				continue;
			CalliRepository repository = getRepository(repositoryID);
			ObjectConnection con = repository.getConnection();
			try {
				TupleQueryResult results = con.prepareTupleQuery(
						QueryLanguage.SPARQL, SEL_AUTH_MANAGER_LABEL)
						.evaluate();
				try {
					while (results.hasNext()) {
						BindingSet result = results.next();
						String manager = result.getValue("manager")
								.stringValue();
						String label = result.getValue("label").stringValue();
						managers.put(manager, label);
					}
				} finally {
					results.close();
				}
			} finally {
				con.close();
			}
		}
		return managers;
	}

	public void setupRealm(final String realm, final String webappOrigin)
			throws Exception {
		submit(new Callable<Void>() {
			public Void call() throws Exception {
				SetupTool tool = getSetupTool(webappOrigin);
				tool.setupRealm(realm, webappOrigin);
				serveRealm(realm, tool.getRepositoryID());
				return null;
			}
		});
	}

	public void replaceDatasourceConfig(final String uri, final String config) throws Exception {
		submit(new Callable<Void>() {
			public Void call() throws Exception {
				String webappOrigin = getWebappOrigin(uri);
				SetupTool tool = getSetupTool(webappOrigin);
				CalliRepository repository = tool.getRepository();
				ValueFactory vf = repository.getValueFactory();
				DatasourceManager manager = repository.getDatasourceManager();
				RepositoryConfig rc = getRepositoryConfig(config, uri);
				RepositoryImplConfig rci = rc.getRepositoryImplConfig();
				manager.replaceDatasourceConfig(vf.createURI(uri), rci);
				return null;
			}
		});
	}

	public void createResource(final String rdf, final String systemId, final String type) throws Exception {
		submit(new Callable<Void>() {
			public Void call() throws Exception {
				String webappOrigin = getWebappOrigin(systemId);
				SetupTool tool = getSetupTool(webappOrigin);
				tool.createResource(rdf, systemId, type, webappOrigin);
				return null;
			}
		});
	}

	public 

	void addAuthentication(final String realm, final String authenticationManager) throws Exception {
		submit(new Callable<Void>() {
			public Void call() throws Exception {
				SetupTool tool = getSetupTool(getWebappOrigin(realm));
				tool.addAuthentication(realm, authenticationManager);
				getRepository(tool.getRepositoryID()).resetCache();
				return null;
			}
		});
	}

	public void removeAuthentication(final String realm, final String authenticationManager) throws Exception {
		submit(new Callable<Void>() {
			public Void call() throws Exception {
				SetupTool tool = getSetupTool(getWebappOrigin(realm));
				tool.removeAuthentication(realm, authenticationManager);
				getRepository(tool.getRepositoryID()).resetCache();
				return null;
			}
		});
	}

	public String[] getDigestEmailAddresses(String webappOrigin)
			throws OpenRDFException, IOException {
		return getSetupTool(webappOrigin).getDigestEmailAddresses(webappOrigin);
	}

	public void inviteAdminUser(final String email, final String subject,
			final String body, final String webappOrigin) throws Exception {
		submit(new Callable<Void>() {
			public Void call() throws Exception {
				SetupTool tool = getSetupTool(webappOrigin);
				tool.inviteAdminUser(email, subject, body, webappOrigin);
				getRepository(tool.getRepositoryID()).resetCache();
				return null;
			}
		});
	}

	public boolean registerDigestUser(String email, String password,
			String webappOrigin) throws OpenRDFException, IOException {
		return getSetupTool(webappOrigin).registerDigestUser(email,
				password, webappOrigin);
	}

	String getWebappOrigin(final String uri) throws IOException,
			OpenRDFException {
		for (SetupRealm realm : getRealms()) {
			if (uri.startsWith(realm.getRealm())) {
				return realm.getWebappOrigin();
			}
		}
		throw new IllegalArgumentException("Unknown location: " + uri);
	}

	synchronized void saveError(Exception exc) {
		exception = exc;
	}

	synchronized void begin() {
		processing++;
	}

	synchronized void end() {
		processing--;
		notifyAll();
	}

	protected void submit(final Callable<Void> task)
			throws Exception {
		checkForErrors();
		executor.submit(new Runnable() {
			public void run() {
				begin();
				try {
					task.call();
				} catch (Exception exc) {
					saveError(exc);
				} finally {
					end();
				}
			}
		});
		Thread.yield();
	}

	SetupTool getSetupTool(String webappOrigin) throws OpenRDFException, IOException {
		String repositoryID = conf.getOriginRepositoryIDs().get(webappOrigin);
		if (repositoryID == null)
			throw new IllegalArgumentException("Unknown Callimachus origin: " + webappOrigin);
		CalliRepository repository = getRepository(repositoryID);
		return new SetupTool(repositoryID, repository, conf);
	}

	synchronized void startWebServiceNow(int start) {
		if (start != starting)
			return;
		try {
			if (isWebServiceRunning()) {
				stopWebServiceNow();
			} else {
				shutDownRepositories();
			}
			try {
				if (getPortArray().length == 0 && getSSLPortArray().length == 0)
					throw new IllegalStateException("No TCP port defined for server");
				if (!isThereAnOriginSetup())
					throw new IllegalStateException("Repository origin is not setup");
				if (server == null) {
					server = createServer();
				}
			} finally {
				if (server == null) {
					shutDownRepositories();
				}
			}
			server.start();
			if (listener != null) {
				listener.webServiceStarted(server);
			}
		} catch (IOException e) {
			logger.error(e.toString(), e);
		} catch (OpenRDFException e) {
			logger.error(e.toString(), e);
		} catch (GeneralSecurityException e) {
			logger.error(e.toString(), e);
		} finally {
			starting = 0;
			notifyAll();
		}
	}

	synchronized boolean stopWebServiceNow() throws OpenRDFException {
		stopping = true;
		try {
			if (server == null) {
				shutDownRepositories();
				return false;
			} else {
				if (listener != null) {
					listener.webServiceStopping(server);
				}
				server.stop();
				HttpClientFactory.getInstance().removeProxy(server);
				shutDownRepositories();
				server.destroy();
				return true;
			}
		} catch (IOException e) {
			logger.error(e.toString(), e);
			return false;
		} finally {
			stopping = false;
			notifyAll();
			server = null;
			shutDownRepositories();
		}
	}

	private synchronized boolean isThereAnOriginSetup() throws RepositoryException,
			RepositoryConfigException, IOException {
		Map<String, String> map = conf.getOriginRepositoryIDs();
		for (String repositoryID : new LinkedHashSet<String>(map.values())) {
			if (!manager.hasRepositoryConfig(repositoryID))
				continue;
			Repository repository = manager.getRepository(repositoryID);
			RepositoryConnection conn = repository.getConnection();
			try {
				ValueFactory vf = conn.getValueFactory();
				URI Origin = vf.createURI(ORIGIN);
				for (String origin : map.keySet()) {
					if (!repositoryID.equals(map.get(origin)))
						continue;
					URI subj = vf.createURI(origin + "/");
					if (conn.hasStatement(subj, RDF.TYPE, Origin, false))
						return true; // at least one origin is setup
				}
				return getPortArray().length > 0
						|| getSSLPortArray().length > 0;
			} finally {
				conn.close();
			}
		}
		return false;
	}

	private synchronized WebServer createServer() throws OpenRDFException,
			IOException, NoSuchAlgorithmException {
		WebServer server = new WebServer(serverCacheDir);
		for (SetupRealm so : getRealms()) {
			String origin = so.getOrigin();
			server.addOrigin(origin, getRepository(so.getRepositoryID()));
			HttpHost host = URIUtils.extractHost(java.net.URI.create(so.getRealm()));
			HttpClientFactory.getInstance().setProxy(host, server);
		}
		server.setName(getServerName());
		server.listen(getPortArray(), getSSLPortArray());
		return server;
	}

	synchronized CalliRepository getRepository(String repositoryID)
			throws IOException, OpenRDFException {
		Map<String, String> map = conf.getOriginRepositoryIDs();
		List<String> origins = new ArrayList<String>(map.size());
		for (Map.Entry<String, String> e : map.entrySet()) {
			if (repositoryID.equals(e.getValue())) {
				origins.add(e.getKey());
			}
		}
		CalliRepository repository = repositories.get(repositoryID);
		if (repository != null && repository.isInitialized())
			return repository;
		Repository repo = manager.getRepository(repositoryID);
		File dataDir = manager.getRepositoryDir(repositoryID);
		if (repo == null)
			throw new IllegalArgumentException("Unknown repositoryID: " + repositoryID);
		repository = new CalliRepository(repo, dataDir, new DatasourceManager(manager, repositoryID) {
			protected CalliRepository createCalliRepository(URI uri,
					Repository repository, File dataDir) throws OpenRDFException,
					IOException {
				CalliRepository calli = new CalliRepository(repository, dataDir, null);
				if (listener != null) {
					listener.repositoryInitialized(getRepositoryId(uri), calli);
				}
				return calli;
			}

			@Override
			public synchronized void shutDownDatasource(URI uri)
					throws OpenRDFException {
				super.shutDownDatasource(uri);
				if (listener != null) {
					listener.repositoryShutDown(getRepositoryId(uri));
				}
			}
		});
		if (!origins.isEmpty()) {
			String changes = repository.getCallimachusUrl(origins.get(0), CHANGES_PATH);
			if (changes != null) {
				repository.setChangeFolder(changes);
			}
		}
		for (String origin : origins) {
			String schema = repository.getCallimachusUrl(origin, SCHEMA_GRAPH);
			if (schema != null) {
				repository.addSchemaGraphType(schema);
			}
		}
		repository.setCompileRepository(true);
		if (listener != null && repositories.containsKey(repositoryID)) {
			listener.repositoryShutDown(repositoryID);
		}
		repositories.put(repositoryID, repository);
		if (listener != null) {
			listener.repositoryInitialized(repositoryID, repository);
		}
		return repository;
	}

	synchronized CalliRepository getSetupRepository(String repositoryID,
			String title) throws OpenRDFException, IOException {
		refreshRepository(repositoryID);
		Repository repo = getOrCreateRepsitory(repositoryID, title);
		File dataDir = manager.getRepositoryDir(repositoryID);
		DatasourceManager datasources = new DatasourceManager(manager,
				repositoryID);
		CalliRepository repository = new CalliRepository(repo, dataDir,
				datasources);
		repositories.put(repositoryID, repository);
		return repository;
	}

	synchronized void refreshRepository(String repositoryID) throws OpenRDFException {
		CalliRepository repository = repositories.get(repositoryID);
		if (repository != null) {
			repository.getDatasourceManager().shutDown();
			if (repository.isInitialized()) {
				repository.shutDown();
			}
		}
		repositories.remove(repositoryID);
	}

	private String getRepositoryId(final String webappOrigin) throws IOException {
		String id = conf.getOriginRepositoryIDs().get(webappOrigin);
		if (id != null)
			return id;
		java.net.URI uri = java.net.URI.create(webappOrigin + "/");
		String auth = uri.getAuthority().toLowerCase();
		return auth.replaceAll("[^a-zA-Z0-9\\.-]", "_");
	}

	private Repository getOrCreateRepsitory(String id, String title)
			throws OpenRDFException, IOException {
		Repository repo = manager.getRepository(id);
		if (repo == null) {
			manager.addRepositoryConfig(createRepositoryConfig(id, title));
			repo = manager.getRepository(id);
		}
		return repo;
	}

	private RepositoryConfig createRepositoryConfig(String id, String title)
			throws IOException, MalformedURLException, RDFParseException,
			RDFHandlerException, GraphUtilException, RepositoryConfigException {
		File repositoryConfig = SystemProperties.getRepositoryConfigFile();
		String configString = readContent(repositoryConfig.toURI().toURL());
		String systemId = repositoryConfig.toURI().toASCIIString();
		RepositoryConfig config = getRepositoryConfig(configString, systemId);
		RepositoryImplConfig impl = config.getRepositoryImplConfig();
		config = new RepositoryConfig(id, title, impl);
		config.validate();
		return config;
	}

	private String readContent(URL config) throws IOException {
		InputStream in = config.openStream();
		try {
			return new Scanner(in).useDelimiter("\\Z").next();
		} finally {
			in.close();
		}
	}

	private RepositoryConfig getRepositoryConfig(String configString, String base)
			throws IOException, RDFParseException, RDFHandlerException,
			GraphUtilException, RepositoryConfigException {
		Graph graph = parseTurtleGraph(configString, base);
		Resource node = GraphUtil.getUniqueSubject(graph, RDF.TYPE,
				RepositoryConfigSchema.REPOSITORY);
		return RepositoryConfig.create(graph, node);
	}

	private Graph parseTurtleGraph(String configString, String base) throws IOException,
			RDFParseException, RDFHandlerException {
		Graph graph = new LinkedHashModel();
		RDFParser rdfParser = Rio.createParser(RDFFormat.TURTLE);
		rdfParser.setRDFHandler(new StatementCollector(graph));
		rdfParser.parse(new StringReader(configString), base);
		return graph;
	}

	private synchronized void shutDownRepositories() throws OpenRDFException {
		for (Map.Entry<String, CalliRepository> e : repositories.entrySet()) {
			e.getValue().getDatasourceManager().shutDown();
			e.getValue().shutDown();
			if (listener != null) {
				listener.repositoryShutDown(e.getKey());
			}
		}
		repositories.clear();
		if (!manager.getInitializedRepositoryIDs().isEmpty()) {
			manager.refresh();
		}
	}

	private int[] getPortArray() throws IOException {
		return conf.getPorts();
	}

	private int[] getSSLPortArray() throws IOException {
		return conf.getSslPorts();
	}

	synchronized void serveRealm(final String realm, final String repositoryID)
			throws IOException, OpenRDFException {
		if (isWebServiceRunning()) {
			java.net.URI uri = java.net.URI.create(realm);
			String origin = uri.getScheme() + "://" + uri.getAuthority();
			server.addOrigin(origin, getRepository(repositoryID));
			HttpHost host = URIUtils.extractHost(uri);
			HttpClientFactory.getInstance().setProxy(host, server);
		}
	}

}

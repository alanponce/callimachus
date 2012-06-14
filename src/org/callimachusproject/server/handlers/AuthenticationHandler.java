/*
 * Copyright 2010, Zepheira LLC Some rights reserved.
 * Copyright (c) 2011 Talis Inc., Some rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution. 
 * - Neither the name of the openrdf.org nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 */
package org.callimachusproject.server.handlers;

import static org.openrdf.sail.auditing.vocabulary.Audit.CURRENT_TRX;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.apache.http.Header;
import org.apache.http.HttpMessage;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.util.EntityUtils;
import org.callimachusproject.annotations.realm;
import org.callimachusproject.concepts.Realm;
import org.callimachusproject.server.concepts.Transaction;
import org.callimachusproject.server.model.Handler;
import org.callimachusproject.server.model.ResourceOperation;
import org.callimachusproject.server.model.Response;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.object.ObjectConnection;
import org.openrdf.repository.object.ObjectFactory;
import org.openrdf.repository.object.RDFObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures the request is authentic if protected.
 * 
 * @author James Leigh
 * 
 */
public class AuthenticationHandler implements Handler {
	private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
	private static final String REQUEST_METHOD = "Access-Control-Request-Method";
	private static final String ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
	private static final String EXPOSE_HEADERS = "Access-Control-Expose-Headers";
	private static final String[] PUBLIC_HEADERS = { "Content-Type",
			"Content-Length", "Content-Encoding", "Date", "Server" };
	private static final Set<String> PRIVATE_HEADERS = new HashSet<String>(
			Arrays.asList("set-cookie", "set-cookie2"));
	public static final String PATTERN_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";
	public static final TimeZone GMT = TimeZone.getTimeZone("GMT");
	private static final BasicStatusLine _403 = new BasicStatusLine(
			new ProtocolVersion("HTTP", 1, 1), 403, "Forbidden");
	private final Logger logger = LoggerFactory
			.getLogger(AuthenticationHandler.class);
	private final DateFormat dateformat;
	private final Handler delegate;

	public AuthenticationHandler(Handler delegate) {
		this.delegate = delegate;
		this.dateformat = new SimpleDateFormat(PATTERN_RFC1123, Locale.US);
		this.dateformat.setTimeZone(GMT);
	}

	public Response verify(ResourceOperation request) throws Exception {
		if (request.isAuthenticating()) {
			HttpResponse unauthorized = authorize(request);
			if (unauthorized != null) {
				return new Response().unauthorized(unauthorized,
						request.getObjectConnection());
			}
		}
		return allow(request, delegate.verify(request));
	}

	public Response handle(ResourceOperation request) throws Exception {
		return allow(request, delegate.handle(request));
	}

	private <R extends HttpMessage> R allow(ResourceOperation request, R rb)
			throws QueryEvaluationException, RepositoryException, IOException {
		if (rb == null)
			return null;
		if (!rb.containsHeader(ALLOW_ORIGIN)) {
			String origins = allowOrigin(request);
			if (origins != null) {
				rb.setHeader(ALLOW_ORIGIN, origins);
			}
		}
		if (!rb.containsHeader(ALLOW_CREDENTIALS)) {
			String origin = request.getVaryHeader("Origin");
			if (origin != null) {
				if (withAgentCredentials(request, origin)) {
					rb.setHeader(ALLOW_CREDENTIALS, "true");
				} else {
					rb.setHeader(ALLOW_CREDENTIALS, "false");
				}
			}
		}
		HttpMessage msg = authenticationInfo(request);
		if (msg != null) {
			for (Header hd : msg.getAllHeaders()) {
				if (!rb.containsHeader(hd.getName())) {
					rb.setHeader(hd);
				}
			}
		}
		if (!rb.containsHeader(EXPOSE_HEADERS)) {
			String exposed = exposeHeaders(rb);
			if (exposed != null) {
				rb.setHeader(EXPOSE_HEADERS, exposed);
			}
		}
		return rb;
	}

	private boolean withAgentCredentials(ResourceOperation request,
			String origin) throws QueryEvaluationException, RepositoryException {
		for (Realm realm : request.getRealms()) {
			if (realm.withAgentCredentials(origin)) {
				return true;
			}
		}
		if ("OPTIONS".equals(request.getMethod())) {
			String m = request.getVaryHeader(REQUEST_METHOD);
			RDFObject target = request.getRequestedResource();
			for (Method method : request.findMethodHandlers(m)) {
				if (method.isAnnotationPresent(realm.class)) {
					if (withAgenCredentials(request, method, target)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private boolean withAgenCredentials(ResourceOperation request,
			Method method, RDFObject target) throws QueryEvaluationException,
			RepositoryException {
		String origin = request.getVaryHeader("Origin");
		String[] values = method.getAnnotation(realm.class).value();
		for (Realm realm : request.getRealms(values)) {
			if (realm.withAgentCredentials(origin)) {
				return true;
			}
		}
		return false;
	}

	private HttpResponse authorize(ResourceOperation request)
			throws QueryEvaluationException, RepositoryException, IOException {
		String m = request.getMethod();
		RDFObject target = request.getRequestedResource();
		String or = request.getVaryHeader("Origin");
		Map<String, String[]> map = getAuthorizationMap(request);
		// loop through first to see if further authorisation is needed
		List<Realm> realms = request.getRealms();
		List<String> credentials = new ArrayList<String>(realms.size());
		for (int i = 0, n = realms.size(); i < n; i++) {
			Realm realm = realms.get(i);
			try {
				Collection<String> allowed = realm.allowOrigin();
				if (or != null && !isOriginAllowed(allowed, or))
					continue;
				String cred = realm.authenticateRequest(m, target, map);
				credentials.add(cred);
				if (cred != null
						&& realm.authorizeCredential(cred, m, target, map)) {
					if (!request.isSafe()) {
						ObjectConnection con = request.getObjectConnection();
						ObjectFactory of = con.getObjectFactory();
						Transaction trans = of.createObject(CURRENT_TRX,
								Transaction.class);
						trans.setAuditContributor(of.createObject(cred));
					}
					request.setRealm(realm);
					request.setCredential(cred);
					return null; // this request is good
				}
			} catch (AbstractMethodError ame) {
				logger.error(ame.toString() + " in " + realm, ame);
			}
		}
		HttpResponse unauth = null;
		boolean noRealm = true;
		boolean wrongOrigin = true;
		for (int i = 0, n = realms.size(); i < n; i++) {
			Realm realm = realms.get(i);
			noRealm = false;
			try {
				Collection<String> allowed = realm.allowOrigin();
				if (or != null && !isOriginAllowed(allowed, or)) {
					try {
						unauth = choose(unauth, realm.forbidden(m, target, map));
					} catch (Exception exc) {
						logger.error(exc.toString(), exc);
					}
					continue;
				}
				wrongOrigin = false;
				Object cred = credentials.get(i);
				try {
					if (cred == null) {
						unauth = choose(unauth,
								realm.unauthorized(m, target, map));
					} else {
						unauth = choose(unauth, realm.forbidden(m, target, map));
					}
				} catch (Exception exc) {
					logger.error(exc.toString(), exc);
				}
			} catch (AbstractMethodError ame) {
				logger.error(ame.toString() + " in " + realm, ame);
			}
		}
		if (noRealm) {
			logger.info("No active realm for {}", request);
		} else if (wrongOrigin) {
			logger.info("Origin {} not allowed for {}", or, request);
		}
		if (unauth != null)
			return allow(request, unauth);
		StringEntity body = new StringEntity("Forbidden", "UTF-8");
		body.setContentType("text/plain");
		HttpResponse resp = new BasicHttpResponse(_403);
		resp.setHeader("Content-Type", "text/plain;charset=UTF-8");
		resp.setEntity(body);
		return allow(request, resp);
	}

	private HttpMessage authenticationInfo(ResourceOperation request)
			throws IOException, QueryEvaluationException, RepositoryException {
		String m = request.getMethod();
		RDFObject target = request.getRequestedResource();
		Map<String, String[]> map = getAuthorizationMap(request);
		Realm realm = request.getRealm();
		if (realm == null)
			return null;
		return realm.authenticationInfo(m, target, map);
	}

	private HttpResponse choose(HttpResponse unauthorized, HttpResponse auth)
			throws IOException {
		if (unauthorized == null)
			return auth;
		if (auth == null)
			return unauthorized;
		int code = unauthorized.getStatusLine().getStatusCode();
		if (auth.getStatusLine().getStatusCode() < code) {
			EntityUtils.consume(unauthorized.getEntity());
			return auth;
		} else {
			EntityUtils.consume(auth.getEntity());
			return unauthorized;
		}
	}

	private Map<String, String[]> getAuthorizationMap(ResourceOperation request)
			throws IOException {
		long now = request.getReceivedOn();
		Map<String, String[]> map = new HashMap<String, String[]>();
		map.put("request-target", new String[] { request.getRequestTarget() });
		map.put("request-scheme", new String[] { request.getScheme() });
		map.put("date", new String[] { this.dateformat.format(new Date(now)) });
		Header[] au = request.getHeaders("Authorization");
		if (au != null && au.length > 0) {
			map.put("authorization", toStringArray(au));
		}
		Header[] co = request.getHeaders("Cookie");
		if (co != null && co.length > 0) {
			map.put("cookie", toStringArray(co));
		}
		Header[] ho = request.getHeaders("Host");
		if (ho != null && ho.length > 0) {
			map.put("host", toStringArray(ho));
		}
		String via = getRequestSource(request);
		map.put("via", via.split("\\s*,\\s*"));
		return map;
	}

	private String[] toStringArray(Header[] au) {
		String[] result = new String[au.length];
		for (int i = 0; i < result.length; i++) {
			result[i] = au[i].getValue();
		}
		return result;
	}

	private String getRequestSource(ResourceOperation request) {
		StringBuilder via = new StringBuilder();
		for (String hd : request.getVaryHeaders("Via", "X-Forwarded-For")) {
			if (via.length() > 0) {
				via.append(",");
			}
			via.append(hd);
		}
		InetAddress remoteAddr = request.getRemoteAddr();
		if (via.length() > 0) {
			via.append(",");
		}
		via.append("1.1 " + remoteAddr.getCanonicalHostName());
		return via.toString();
	}

	private boolean isOriginAllowed(Collection<String> allowed, String o) {
		if (allowed == null)
			return false;
		for (String ao : allowed) {
			if (ao.equals("*") || o.startsWith(ao) || ao.startsWith(o)
					&& ao.charAt(o.length()) == '/')
				return true;
		}
		return false;
	}

	private String allowOrigin(ResourceOperation request)
			throws QueryEvaluationException, RepositoryException {
		Set<String> set = new LinkedHashSet<String>();
		if ("OPTIONS".equals(request.getMethod())) {
			String m = request.getVaryHeader(REQUEST_METHOD);
			for (Method method : request.findMethodHandlers(m)) {
				if (method.isAnnotationPresent(realm.class)) {
					String[] values = method.getAnnotation(realm.class).value();
					for (Realm realm : request.getRealms(values)) {
						set.addAll(realm.allowOrigin());
					}
				}
			}
		}
		List<Realm> realms = request.getRealms();
		for (Realm realm : realms) {
			set.addAll(realm.allowOrigin());
		}
		if (set.isEmpty() && realms.isEmpty())
			return "*";
		if (set.isEmpty())
			return null;
		StringBuilder sb = new StringBuilder();
		for (String s : set) {
			if (sb.length() > 0) {
				sb.append(" ");
			}
			sb.append(s);
		}
		return sb.toString();
	}

	private String exposeHeaders(HttpMessage rb) {
		Map<String,String> map = new LinkedHashMap<String,String>();
		for (Header hd : rb.getAllHeaders()) {
			String lc = hd.getName().toLowerCase();
			if (!PRIVATE_HEADERS.contains(lc)) {
				map.put(lc, hd.getName());
			}
		}
		for (String name : PUBLIC_HEADERS) {
			String lc = name.toLowerCase();
			if (!PRIVATE_HEADERS.contains(lc)) {
				map.put(lc, name);
			}
		}
		StringBuilder sb = new StringBuilder();
		for (String name : map.values()) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(name);
		}
		return sb.toString();
	}

}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.felix.http.itest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.osgi.framework.Constants.SERVICE_RANKING;
import static org.osgi.service.http.whiteboard.HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME;
import static org.osgi.service.http.whiteboard.HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH;
import static org.osgi.service.http.whiteboard.HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT;
import static org.osgi.service.http.whiteboard.HttpWhiteboardConstants.HTTP_WHITEBOARD_LISTENER;
import static org.osgi.service.http.whiteboard.HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME;
import static org.osgi.service.http.whiteboard.HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.json.JsonObject;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.johnzon.core.JsonProviderImpl;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerMethod;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.http.context.ServletContextHelper;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerMethod.class)
public class SessionHandlingTest extends BaseIntegrationTest
{
    private List<ServiceRegistration<?>> registrations = new ArrayList<ServiceRegistration<?>>();

    private CountDownLatch initLatch;
    private CountDownLatch destroyLatch;

    private void setupLatches(int count)
    {
        initLatch = new CountDownLatch(count);
        destroyLatch = new CountDownLatch(count);
    }

    private void setupServlet(final String name, String[] path, int rank, final String context) throws Exception
    {
        Dictionary<String, Object> servletProps = new Hashtable<String, Object>();
        servletProps.put(HTTP_WHITEBOARD_SERVLET_NAME, name);
        servletProps.put(HTTP_WHITEBOARD_SERVLET_PATTERN, path);
        servletProps.put(SERVICE_RANKING, rank);
        if (context != null)
        {
            servletProps.put(HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HTTP_WHITEBOARD_CONTEXT_NAME + "=" + context + ")");
        }

        Servlet sessionServlet = new TestServlet(initLatch, destroyLatch)
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException
            {
                final boolean create = req.getParameter("create") != null;
                if ( create )
                {
                    req.getSession();
                }
                final boolean destroy = req.getParameter("destroy") != null;
                if ( destroy )
                {
                    req.getSession().invalidate();
                }
                final HttpSession s = req.getSession(false);
                if ( s != null )
                {
                    s.setAttribute("value", context);
                }

                final PrintWriter pw = resp.getWriter();
                pw.println("{");
                if ( s == null )
                {
                    pw.println(" \"session\" : false");
                }
                else
                {
                    pw.println(" \"session\" : true,");
                    pw.println(" \"sessionId\" : \"" + s.getId() + "\",");
                    pw.println(" \"value\" : \"" + s.getAttribute("value") + "\"");
                }
                pw.println("}");
            }
        };

        registrations.add(m_context.registerService(Servlet.class.getName(), sessionServlet, servletProps));
    }

    private void setupContext(String name, String path) throws InterruptedException
    {
        Dictionary<String, ?> properties = createDictionary(
                HTTP_WHITEBOARD_CONTEXT_NAME, name,
                HTTP_WHITEBOARD_CONTEXT_PATH, path);

        ServletContextHelper servletContextHelper = new ServletContextHelper(m_context.getBundle()){
            // test helper
        };
        registrations.add(m_context.registerService(ServletContextHelper.class.getName(), servletContextHelper, properties));

        Thread.sleep(500);
    }

    @After
    public void unregisterServices() throws InterruptedException
    {
        for (ServiceRegistration<?> serviceRegistration : registrations)
        {
            serviceRegistration.unregister();
        }

        assertTrue(destroyLatch.await(5, TimeUnit.SECONDS));

        Thread.sleep(500);
    }

    private JsonObject getJSONResponse(final CloseableHttpClient client, final String path) throws IOException
    {
        final HttpGet httpGet = new HttpGet(createURL(path).toExternalForm().toString());
        CloseableHttpResponse response1 = client.execute(httpGet);

        try {
            HttpEntity entity1 = response1.getEntity();
            final String content = EntityUtils.toString(entity1);

            return new JsonProviderImpl().createReader(new StringReader(content)).readObject();
        } finally {
            response1.close();
        }

    }
    
    @Test
    public void testSessionAttributes() throws Exception
    {
        setupContext("test1", "/");
        setupContext("test2", "/");

        setupLatches(2);

        setupServlet("foo", new String[] { "/foo" }, 1, "test1");
        setupServlet("bar", new String[] { "/bar" }, 2, "test2" );

        assertTrue(initLatch.await(5, TimeUnit.SECONDS));

        RequestConfig globalConfig = RequestConfig.custom()
                .setCookieSpec(CookieSpecs.BEST_MATCH)
                .build();
        final CloseableHttpClient httpclient = HttpClients.custom().setDefaultRequestConfig(globalConfig)
                .setDefaultCookieStore(new BasicCookieStore())
                .build();

        JsonObject json;

        // session should not be available
        // check for foo servlet
        json = getJSONResponse(httpclient, "/foo");
        assertFalse(json.getBoolean("session"));

        // check for bar servlet
        json = getJSONResponse(httpclient, "/bar");
        assertFalse(json.getBoolean("session"));

        // create session for  context of servlet foo
        // check session and session attribute
        json = getJSONResponse(httpclient, "/foo?create=true");
        assertTrue(json.getBoolean("session"));
        assertEquals("test1", json.getString("value"));
        final String sessionId1 = json.getString("sessionId");
        assertNotNull(sessionId1);

        // check session for servlet bar (= no session)
        json = getJSONResponse(httpclient, "/bar");
        assertFalse(json.getBoolean("session"));
        // another request to servlet foo, still the same
        json = getJSONResponse(httpclient, "/foo");
        assertTrue(json.getBoolean("session"));
        assertEquals("test1", json.getString("value"));
        assertEquals(sessionId1, json.getString("sessionId"));

        // create session for second context
        json = getJSONResponse(httpclient, "/bar?create=true");
        assertTrue(json.getBoolean("session"));
        assertEquals("test2", json.getString("value"));
        final String sessionId2 = json.getString("sessionId");
        assertNotNull(sessionId2);
        assertFalse(sessionId1.equals(sessionId2));

        // and context foo is untouched
        json = getJSONResponse(httpclient, "/foo");
        assertTrue(json.getBoolean("session"));
        assertEquals("test1", json.getString("value"));
        assertEquals(sessionId1, json.getString("sessionId"));

        // invalidate session for foo context
        json = getJSONResponse(httpclient, "/foo?destroy=true");
        assertFalse(json.getBoolean("session"));
        // bar should be untouched
        json = getJSONResponse(httpclient, "/bar");
        assertTrue(json.getBoolean("session"));
        assertEquals("test2", json.getString("value"));
        assertEquals(sessionId2, json.getString("sessionId"));
    }
    
    static class TestListener implements HttpSessionListener {
	
		List<HttpSession> sessions = new CopyOnWriteArrayList<HttpSession>();
		
		@Override
		public void sessionCreated(HttpSessionEvent e) {
			sessions.add(e.getSession());
		}
	
		@Override
		public void sessionDestroyed(HttpSessionEvent e) {
			sessions.remove(e.getSession());
		}
		
		public List<Object> getSessionAttribute(String key) {
			List<Object> values = new ArrayList<Object>();
			for(HttpSession session : sessions) {
				values.add(session.getAttribute(key));
			}
			return values;
		}
	}

	private TestListener setupListener(final String context) throws Exception
    {
        Dictionary<String, Object> listenerProps = new Hashtable<String, Object>();
        listenerProps.put(HTTP_WHITEBOARD_LISTENER, Boolean.TRUE.toString());
        if (context != null)
        {
            listenerProps.put(HTTP_WHITEBOARD_CONTEXT_SELECT, "(" + HTTP_WHITEBOARD_CONTEXT_NAME + "=" + context + ")");
        }

        TestListener listener = new TestListener();
        registrations.add(m_context.registerService(HttpSessionListener.class.getName(), listener, listenerProps));
        
        return listener;
    }

    @Test
    public void testSessionListner() throws Exception
    {
    	setupContext("test1", "/");
    	setupContext("test2", "/");
    	
    	TestListener listener1 = setupListener("test1");
    	TestListener listener2 = setupListener("test2");
    	
    	setupLatches(2);
    	
    	setupServlet("foo", new String[] { "/foo" }, 1, "test1");
    	setupServlet("bar", new String[] { "/bar" }, 2, "test2" );
    	
    	assertTrue(initLatch.await(5, TimeUnit.SECONDS));
    	
    	RequestConfig globalConfig = RequestConfig.custom()
    			.setCookieSpec(CookieSpecs.BEST_MATCH)
    			.build();
    	final CloseableHttpClient httpclient = HttpClients.custom().setDefaultRequestConfig(globalConfig)
    			.setDefaultCookieStore(new BasicCookieStore())
    			.build();
    	
    	JsonObject json;
    	
    	// session should not be available
    	// check for foo servlet and listener1
    	json = getJSONResponse(httpclient, "/foo");
    	assertFalse(json.getBoolean("session"));
    	assertTrue(listener1.getSessionAttribute("value").isEmpty());
    	
    	// check for bar servlet
    	json = getJSONResponse(httpclient, "/bar");
    	assertFalse(json.getBoolean("session"));
    	assertTrue(listener2.getSessionAttribute("value").isEmpty());
    	
    	// create session for  context of servlet foo
    	// check session and session attribute
    	json = getJSONResponse(httpclient, "/foo?create=true");
    	assertTrue(json.getBoolean("session"));
    	assertEquals("test1", json.getString("value"));
    	final String sessionId1 = json.getString("sessionId");
    	assertNotNull(sessionId1);
    	assertEquals(Collections.singletonList("test1"),
    			listener1.getSessionAttribute("value"));
    	
    	// check session for servlet bar (= no session)
    	json = getJSONResponse(httpclient, "/bar");
    	assertFalse(json.getBoolean("session"));
    	assertTrue(listener2.getSessionAttribute("value").isEmpty());
    	// another request to servlet foo, still the same
    	json = getJSONResponse(httpclient, "/foo");
    	assertTrue(json.getBoolean("session"));
    	assertEquals("test1", json.getString("value"));
    	assertEquals(sessionId1, json.getString("sessionId"));
    	assertEquals(Collections.singletonList("test1"),
    			listener1.getSessionAttribute("value"));
    	
    	// create session for second context
    	json = getJSONResponse(httpclient, "/bar?create=true");
    	assertTrue(json.getBoolean("session"));
    	assertEquals("test2", json.getString("value"));
    	final String sessionId2 = json.getString("sessionId");
    	assertNotNull(sessionId2);
    	assertFalse(sessionId1.equals(sessionId2));
    	assertEquals(Collections.singletonList("test2"),
    			listener2.getSessionAttribute("value"));
    	
    	// and context foo is untouched
    	json = getJSONResponse(httpclient, "/foo");
    	assertTrue(json.getBoolean("session"));
    	assertEquals("test1", json.getString("value"));
    	assertEquals(sessionId1, json.getString("sessionId"));
    	assertEquals(Collections.singletonList("test1"),
    			listener1.getSessionAttribute("value"));
    	
    	// invalidate session for foo context
    	json = getJSONResponse(httpclient, "/foo?destroy=true");
    	assertFalse(json.getBoolean("session"));
    	assertTrue(listener1.getSessionAttribute("value").isEmpty());
    	// bar should be untouched
    	json = getJSONResponse(httpclient, "/bar");
    	assertTrue(json.getBoolean("session"));
    	assertEquals("test2", json.getString("value"));
    	assertEquals(sessionId2, json.getString("sessionId"));
    	assertEquals(Collections.singletonList("test2"),
    			listener2.getSessionAttribute("value"));
    }
}

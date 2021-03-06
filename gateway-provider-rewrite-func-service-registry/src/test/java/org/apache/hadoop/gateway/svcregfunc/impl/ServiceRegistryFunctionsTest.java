/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.gateway.svcregfunc.impl;

import org.apache.hadoop.gateway.filter.AbstractGatewayFilter;
import org.apache.hadoop.gateway.filter.rewrite.api.UrlRewriteServletContextListener;
import org.apache.hadoop.gateway.filter.rewrite.api.UrlRewriteServletFilter;
import org.apache.hadoop.gateway.services.GatewayServices;
import org.apache.hadoop.gateway.services.registry.ServiceRegistry;
import org.apache.hadoop.gateway.util.urltemplate.Parser;
import org.apache.hadoop.test.TestUtils;
import org.apache.hadoop.test.log.NoOpLogger;
import org.apache.hadoop.test.mock.MockInteraction;
import org.apache.hadoop.test.mock.MockServlet;
import org.apache.http.auth.BasicUserPrincipal;
import org.easymock.EasyMock;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.servlet.ServletTester;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.hamcrest.core.Is;
import org.junit.Test;

import javax.security.auth.Subject;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;


public class ServiceRegistryFunctionsTest {

  private ServletTester server;
  private HttpTester.Request request;
  private HttpTester.Response response;
  private ArrayQueue<MockInteraction> interactions;
  private MockInteraction interaction;

  private static URL getTestResource( String name ) {
    name = ServiceRegistryFunctionsTest.class.getName().replaceAll( "\\.", "/" ) + "/" + name;
    URL url = ClassLoader.getSystemResource( name );
    return url;
  }

  public void setUp( String username, Map<String,String> initParams ) throws Exception {
    ServiceRegistry mockServiceRegistry = EasyMock.createNiceMock( ServiceRegistry.class );
    EasyMock.expect( mockServiceRegistry.lookupServiceURL( "test-cluster", "NAMENODE" ) ).andReturn( "test-nn-scheme://test-nn-host:411" ).anyTimes();
    EasyMock.expect( mockServiceRegistry.lookupServiceURL( "test-cluster", "JOBTRACKER" ) ).andReturn( "test-jt-scheme://test-jt-host:511" ).anyTimes();

    GatewayServices mockGatewayServices = EasyMock.createNiceMock( GatewayServices.class );
    EasyMock.expect( mockGatewayServices.getService(GatewayServices.SERVICE_REGISTRY_SERVICE) ).andReturn( mockServiceRegistry ).anyTimes();

    EasyMock.replay( mockServiceRegistry, mockGatewayServices );

    String descriptorUrl = getTestResource( "rewrite.xml" ).toExternalForm();

    Log.setLog( new NoOpLogger() );

    server = new ServletTester();
    server.setContextPath( "/" );
    server.getContext().addEventListener( new UrlRewriteServletContextListener() );
    server.getContext().setInitParameter(
        UrlRewriteServletContextListener.DESCRIPTOR_LOCATION_INIT_PARAM_NAME, descriptorUrl );
    server.getContext().setAttribute( GatewayServices.GATEWAY_CLUSTER_ATTRIBUTE, "test-cluster" );
    server.getContext().setAttribute( GatewayServices.GATEWAY_SERVICES_ATTRIBUTE, mockGatewayServices );

    FilterHolder setupFilter = server.addFilter( SetupFilter.class, "/*", EnumSet.of( DispatcherType.REQUEST ) );
    setupFilter.setFilter( new SetupFilter( username ) );
    FilterHolder rewriteFilter = server.addFilter( UrlRewriteServletFilter.class, "/*", EnumSet.of( DispatcherType.REQUEST ) );
    if( initParams != null ) {
      for( Map.Entry<String,String> entry : initParams.entrySet() ) {
        rewriteFilter.setInitParameter( entry.getKey(), entry.getValue() );
      }
    }
    rewriteFilter.setFilter( new UrlRewriteServletFilter() );

    interactions = new ArrayQueue<MockInteraction>();

    ServletHolder servlet = server.addServlet( MockServlet.class, "/" );
    servlet.setServlet( new MockServlet( "mock-servlet", interactions ) );

    server.start();

    interaction = new MockInteraction();
    request = HttpTester.newRequest();
    response = null;
  }

  @Test
  public void testServiceRegistryFunctionsOnXmlRequestBody() throws Exception {
    Map<String,String> initParams = new HashMap<String,String>();
    initParams.put( "request.body", "oozie-conf" );
    setUp( "test-user", initParams );

    String input = TestUtils.getResourceString( ServiceRegistryFunctionsTest.class, "test-input-body.xml", "UTF-8" );
    String expect = TestUtils.getResourceString( ServiceRegistryFunctionsTest.class, "test-expect-body.xml", "UTF-8" );

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://test-host:42/test-path" )
        .contentType( "text/xml" )
        .characterEncoding( "UTF-8" )
        .content( expect, Charset.forName( "UTF-8" ) );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "test-host:42" );
    request.setHeader( "Content-Type", "text/xml; charset=UTF-8" );
    request.setContent( input );

    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), Is.is( 200 ) );
  }

  @Test
  public void testServiceRegistryFunctionsOnJsonRequestBody() throws Exception {
    Map<String,String> initParams = new HashMap<String,String>();
    initParams.put( "request.body", "oozie-conf" );
    setUp( "test-user", initParams );

    String input = TestUtils.getResourceString( ServiceRegistryFunctionsTest.class, "test-input-body.json", "UTF-8" );
    String expect = TestUtils.getResourceString( ServiceRegistryFunctionsTest.class, "test-expect-body.json", "UTF-8" );

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://test-host:42/test-path" )
        .contentType( "application/json" )
        .characterEncoding( "UTF-8" )
        .content( expect, Charset.forName( "UTF-8" ) );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "test-host:42" );
    request.setHeader( "Content-Type", "application/json; charset=UTF-8" );
    request.setContent( input );

    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), Is.is( 200 ) );
  }

  private static class SetupFilter implements Filter {
    private Subject subject;

    public SetupFilter( String userName ) {
      subject = new Subject();
      subject.getPrincipals().add( new BasicUserPrincipal( userName ) );
    }

    @Override
    public void init( FilterConfig filterConfig ) throws ServletException {
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain ) throws IOException, ServletException {
      HttpServletRequest httpRequest = ((HttpServletRequest)request);
      StringBuffer sourceUrl = httpRequest.getRequestURL();
      String queryString = httpRequest.getQueryString();
      if( queryString != null ) {
        sourceUrl.append( "?" );
        sourceUrl.append( queryString );
      }
      try {
        request.setAttribute(
            AbstractGatewayFilter.SOURCE_REQUEST_URL_ATTRIBUTE_NAME,
            Parser.parseLiteral( sourceUrl.toString() ) );
      } catch( URISyntaxException e ) {
        throw new ServletException( e );
      }
      try {
        Subject.doAs( subject, new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws Exception {
            chain.doFilter( request, response );
            return null;
          }
        } );
      } catch( PrivilegedActionException e ) {
        throw new ServletException( e );
      }
    }

    @Override
    public void destroy() {
    }
  }

}

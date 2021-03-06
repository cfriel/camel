/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.netty.http.handlers;

import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.util.Iterator;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.component.netty.NettyConsumer;
import org.apache.camel.component.netty.NettyHelper;
import org.apache.camel.component.netty.handlers.ServerChannelHandler;
import org.apache.camel.component.netty.http.HttpPrincipal;
import org.apache.camel.component.netty.http.NettyHttpConsumer;
import org.apache.camel.component.netty.http.NettyHttpSecurityConfiguration;
import org.apache.camel.component.netty.http.SecurityAuthenticator;
import org.apache.camel.util.CamelLogger;
import org.apache.camel.util.ObjectHelper;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.base64.Base64;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.jboss.netty.handler.codec.http.HttpHeaders.is100ContinueExpected;
import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Netty HTTP {@link ServerChannelHandler} that handles the incoming HTTP requests and routes
 * the received message in Camel.
 */
public class HttpServerChannelHandler extends ServerChannelHandler {

    // use NettyHttpConsumer as logger to make it easier to read the logs as this is part of the consumer
    private static final transient Logger LOG = LoggerFactory.getLogger(NettyHttpConsumer.class);
    private final NettyHttpConsumer consumer;
    private HttpRequest request;

    public HttpServerChannelHandler(NettyHttpConsumer consumer) {
        super(consumer);
        this.consumer = consumer;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent messageEvent) throws Exception {
        // store request, as this channel handler is created per pipeline
        request = (HttpRequest) messageEvent.getMessage();

        LOG.debug("Message received: {}", request);

        if (is100ContinueExpected(request)) {
            // send back http 100 response to continue
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, CONTINUE);
            messageEvent.getChannel().write(response);
            return;
        }

        if (consumer.isSuspended()) {
            // are we suspended?
            LOG.debug("Consumer suspended, cannot service request {}", request);
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, SERVICE_UNAVAILABLE);
            response.setChunked(false);
            response.setHeader(Exchange.CONTENT_TYPE, "text/plain");
            response.setHeader(Exchange.CONTENT_LENGTH, 0);
            response.setContent(ChannelBuffers.copiedBuffer(new byte[]{}));
            messageEvent.getChannel().write(response);
            return;
        }
        if (consumer.getEndpoint().getHttpMethodRestrict() != null
                && !consumer.getEndpoint().getHttpMethodRestrict().contains(request.getMethod().getName())) {
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, METHOD_NOT_ALLOWED);
            response.setChunked(false);
            response.setHeader(Exchange.CONTENT_TYPE, "text/plain");
            response.setHeader(Exchange.CONTENT_LENGTH, 0);
            response.setContent(ChannelBuffers.copiedBuffer(new byte[]{}));
            messageEvent.getChannel().write(response);
            return;
        }
        if ("TRACE".equals(request.getMethod().getName()) && !consumer.getEndpoint().isTraceEnabled()) {
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, METHOD_NOT_ALLOWED);
            response.setChunked(false);
            response.setHeader(Exchange.CONTENT_TYPE, "text/plain");
            response.setHeader(Exchange.CONTENT_LENGTH, 0);
            response.setContent(ChannelBuffers.copiedBuffer(new byte[]{}));
            messageEvent.getChannel().write(response);
            return;
        }
        // must include HOST header as required by HTTP 1.1
        if (!request.getHeaderNames().contains(HttpHeaders.Names.HOST)) {
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, BAD_REQUEST);
            response.setChunked(false);
            response.setHeader(Exchange.CONTENT_TYPE, "text/plain");
            response.setHeader(Exchange.CONTENT_LENGTH, 0);
            response.setContent(ChannelBuffers.copiedBuffer(new byte[]{}));
            messageEvent.getChannel().write(response);
            return;
        }

        // is basic auth configured
        NettyHttpSecurityConfiguration security = consumer.getEndpoint().getSecurityConfiguration();
        if (security != null && security.isAuthenticate() && "Basic".equalsIgnoreCase(security.getConstraint())) {
            String url = request.getUri();

            // drop parameters from url
            if (url.contains("?")) {
                url = ObjectHelper.before(url, "?");
            }

            // we need the relative path without the hostname and port
            URI uri = new URI(request.getUri());
            String target = uri.getPath();

            // strip the starting endpoint path so the target is relative to the endpoint uri
            String path = consumer.getConfiguration().getPath();
            if (path != null && target.startsWith(path)) {
                target = target.substring(path.length());
            }

            // is it a restricted resource?
            String roles;
            if (security.getSecurityConstraint() != null) {
                // if restricted returns null, then the resource is not restricted and we should not authenticate the user
                roles = security.getSecurityConstraint().restricted(target);
            } else {
                // assume any roles is valid if no security constraint has been configured
                roles = "*";
            }
            if (roles != null) {
                // basic auth subject
                HttpPrincipal principal = extractBasicAuthSubject(request);

                // authenticate principal and check if the user is in role
                Subject subject = null;
                boolean inRole = true;
                if (principal != null) {
                    subject = authenticate(security.getSecurityAuthenticator(), security.getLoginDeniedLoggingLevel(), principal);
                    if (subject != null) {
                        String userRoles = security.getSecurityAuthenticator().getUserRoles(subject);
                        inRole = matchesRoles(roles, userRoles);
                    }
                }

                if (principal == null || subject == null || !inRole) {
                    if (principal == null) {
                        LOG.debug("Http Basic Auth required for resource: {}", url);
                    } else if (subject == null) {
                        LOG.debug("Http Basic Auth not authorized for username: {}", principal.getUsername());
                    } else {
                        LOG.debug("Http Basic Auth not in role for username: {}", principal.getUsername());
                    }
                    // restricted resource, so send back 401 to require valid username/password
                    HttpResponse response = new DefaultHttpResponse(HTTP_1_1, UNAUTHORIZED);
                    response.setHeader("WWW-Authenticate", "Basic realm=\"" + security.getRealm() + "\"");
                    response.setHeader(Exchange.CONTENT_TYPE, "text/plain");
                    response.setHeader(Exchange.CONTENT_LENGTH, 0);
                    response.setContent(ChannelBuffers.copiedBuffer(new byte[]{}));
                    messageEvent.getChannel().write(response);
                    return;
                } else {
                    LOG.debug("Http Basic Auth authorized for username: {}", principal.getUsername());
                }
            }
        }

        // let Camel process this message
        super.messageReceived(ctx, messageEvent);
    }

    protected boolean matchesRoles(String roles, String userRoles) {
        // matches if no role restrictions or any role is accepted
        if (roles.equals("*")) {
            return true;
        }

        // see if any of the user roles is contained in the roles list
        Iterator it = ObjectHelper.createIterator(userRoles);
        while (it.hasNext()) {
            String userRole = it.next().toString();
            if (roles.contains(userRole)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Extracts the username and password details from the HTTP basic header Authorization.
     * <p/>
     * This requires that the <tt>Authorization</tt> HTTP header is provided, and its using Basic.
     * Currently Digest is <b>not</b> supported.
     *
     * @return {@link HttpPrincipal} with username and password details, or <tt>null</tt> if not possible to extract
     */
    protected static HttpPrincipal extractBasicAuthSubject(HttpRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null) {
            String constraint = ObjectHelper.before(auth, " ");
            if (constraint != null) {
                if ("Basic".equalsIgnoreCase(constraint.trim())) {
                    String decoded = ObjectHelper.after(auth, " ");
                    // the decoded part is base64 encoded, so we need to decode that
                    ChannelBuffer buf = ChannelBuffers.copiedBuffer(decoded.getBytes());
                    ChannelBuffer out = Base64.decode(buf);
                    String userAndPw = out.toString(Charset.defaultCharset());
                    String username = ObjectHelper.before(userAndPw, ":");
                    String password = ObjectHelper.after(userAndPw, ":");
                    HttpPrincipal principal = new HttpPrincipal(username, password);

                    LOG.debug("Extracted Basic Auth principal from HTTP header: {}", principal);
                    return principal;
                }
            }
        }
        return null;
    }

    /**
     * Authenticates the http basic auth subject.
     *
     * @param authenticator      the authenticator
     * @param principal          the principal
     * @return <tt>true</tt> if username and password is valid, <tt>false</tt> if not
     */
    protected Subject authenticate(SecurityAuthenticator authenticator, LoggingLevel deniedLoggingLevel, HttpPrincipal principal) {
        try {
            return authenticator.login(principal);
        } catch (LoginException e) {
            CamelLogger logger = new CamelLogger(LOG, deniedLoggingLevel);
            logger.log("Cannot login " + principal.getName() + " due " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    protected void beforeProcess(Exchange exchange, MessageEvent messageEvent) {
        if (consumer.getConfiguration().isBridgeEndpoint()) {
            exchange.setProperty(Exchange.SKIP_GZIP_ENCODING, Boolean.TRUE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent exceptionEvent) throws Exception {
        // only close if we are still allowed to run
        if (consumer.isRunAllowed()) {

            if (exceptionEvent.getCause() instanceof ClosedChannelException) {
                LOG.debug("Channel already closed. Ignoring this exception.");
            } else {
                LOG.warn("Closing channel as an exception was thrown from Netty", exceptionEvent.getCause());
                // close channel in case an exception was thrown
                NettyHelper.close(exceptionEvent.getChannel());
            }
        }
    }

    @Override
    protected ChannelFutureListener createResponseFutureListener(NettyConsumer consumer, Exchange exchange, SocketAddress remoteAddress) {
        // make sure to close channel if not keep-alive
        if (request != null && isKeepAlive(request)) {
            LOG.trace("Request has Connection: keep-alive so Channel is not being closed");
            return null;
        } else {
            LOG.trace("Request is not Connection: close so Channel is being closed");
            return ChannelFutureListener.CLOSE;
        }
    }

    @Override
    protected Object getResponseBody(Exchange exchange) throws Exception {
        // use the binding
        if (exchange.hasOut()) {
            return consumer.getEndpoint().getNettyHttpBinding().toNettyResponse(exchange.getOut(), consumer.getConfiguration());
        } else {
            return consumer.getEndpoint().getNettyHttpBinding().toNettyResponse(exchange.getIn(), consumer.getConfiguration());
        }
    }
}

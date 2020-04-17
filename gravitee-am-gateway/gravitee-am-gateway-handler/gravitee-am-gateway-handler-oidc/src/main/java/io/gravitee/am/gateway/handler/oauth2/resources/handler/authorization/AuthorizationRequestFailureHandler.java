/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.am.gateway.handler.oauth2.resources.handler.authorization;

import io.gravitee.am.common.exception.oauth2.OAuth2Exception;
import io.gravitee.am.common.oauth2.Parameters;
import io.gravitee.am.common.web.UriBuilder;
import io.gravitee.am.gateway.handler.common.jwt.JWTService;
import io.gravitee.am.gateway.handler.common.vertx.utils.UriBuilderRequest;
import io.gravitee.am.gateway.handler.oauth2.exception.JWTOAuth2Exception;
import io.gravitee.am.gateway.handler.oauth2.exception.RedirectMismatchException;
import io.gravitee.am.gateway.handler.oauth2.resources.request.AuthorizationRequestFactory;
import io.gravitee.am.gateway.handler.oauth2.service.request.AuthorizationRequest;
import io.gravitee.am.gateway.handler.oauth2.service.utils.OAuth2Constants;
import io.gravitee.am.gateway.handler.oidc.service.discovery.OpenIDDiscoveryService;
import io.gravitee.am.gateway.handler.oidc.service.jwe.JWEService;
import io.gravitee.am.model.Domain;
import io.gravitee.am.model.oidc.Client;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpStatusCode;
import io.vertx.core.Handler;
import io.vertx.ext.web.handler.impl.HttpStatusException;
import io.vertx.reactivex.core.http.HttpServerResponse;
import io.vertx.reactivex.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.gravitee.am.service.utils.ResponseTypeUtils.isHybridFlow;
import static io.gravitee.am.service.utils.ResponseTypeUtils.isImplicitFlow;

/**
 * If the request fails due to a missing, invalid, or mismatching redirection URI, or if the client identifier is missing or invalid,
 * the authorization server SHOULD inform the resource owner of the error and MUST NOT automatically redirect the user-agent to the
 * invalid redirection URI.
 *
 * If the resource owner denies the access request or if the request fails for reasons other than a missing or invalid redirection URI,
 * the authorization server informs the client by adding the following parameters to the fragment component of the redirection URI using the
 * "application/x-www-form-urlencoded" format
 *
 * See <a href="https://tools.ietf.org/html/rfc6749#section-4.2.2.1">4.2.2.1. Error Response</a>
 *
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class AuthorizationRequestFailureHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(AuthorizationRequestFailureHandler.class);
    private static final String CLIENT_CONTEXT_KEY = "client";
    private static final String USER_CONSENT_COMPLETED_CONTEXT_KEY = "userConsentCompleted";
    private static final String REQUESTED_CONSENT_CONTEXT_KEY = "requestedConsent";
    private final AuthorizationRequestFactory authorizationRequestFactory = new AuthorizationRequestFactory();
    private String defaultErrorPagePath;

    private JWTService jwtService;
    private JWEService jweService;
    private OpenIDDiscoveryService openIDDiscoveryService;
    private Domain domain;

    public AuthorizationRequestFailureHandler(final Domain domain,  final OpenIDDiscoveryService openIDDiscoveryService,
                                        final JWTService jwtService, final JWEService jweService) {
        this.domain = domain;
        this.openIDDiscoveryService = openIDDiscoveryService;
        this.jwtService = jwtService;
        this.jweService = jweService;

        defaultErrorPagePath = "/" + domain.getPath() + "/oauth/error";
    }

    @Override
    public void handle(RoutingContext routingContext) {
        if (routingContext.failed()) {
            try {
                AuthorizationRequest request = resolveInitialAuthorizeRequest(routingContext);
                String defaultProxiedOAuthErrorPage = UriBuilderRequest.resolveProxyRequest(routingContext.request(),  defaultErrorPagePath, null);
                Throwable throwable = routingContext.failure();
                if (throwable instanceof OAuth2Exception) {
                    OAuth2Exception oAuth2Exception = (OAuth2Exception) throwable;
                    String clientId = request.getClientId();
                    Client client = routingContext.get(CLIENT_CONTEXT_KEY);
                    // no client available or missing redirect_uri, go to default error page
                    if (clientId == null || client == null || request.getRedirectUri() == null) {
                        request.setRedirectUri(defaultProxiedOAuthErrorPage);
                    }
                    // user set a wrong redirect_uri, go to default error page
                    if (oAuth2Exception instanceof RedirectMismatchException) {
                        request.setRedirectUri(defaultProxiedOAuthErrorPage);
                    }
                    // Manage exception
                    sendError(routingContext, request, oAuth2Exception, client);
                } else if (throwable instanceof HttpStatusException) {
                    // in case of http status exception, go to the default error page
                    request.setRedirectUri(defaultProxiedOAuthErrorPage);
                    HttpStatusException httpStatusException = (HttpStatusException) throwable;
                    doRedirect(routingContext.response(), buildRedirectUri(httpStatusException.getMessage(), httpStatusException.getPayload(), request));
                } else {
                    logger.error("An exception occurs while handling authorization request", throwable);
                    if (routingContext.statusCode() != -1) {
                        routingContext
                                .response()
                                .setStatusCode(routingContext.statusCode())
                                .end();
                    } else {
                        routingContext
                                .response()
                                .setStatusCode(HttpStatusCode.INTERNAL_SERVER_ERROR_500)
                                .end();
                    }
                }
            } catch (Exception e) {
                logger.error("Unable to handle authorization error response", e);
                doRedirect(routingContext.response(),  defaultErrorPagePath);
            } finally {
                // clean session
                cleanSession(routingContext);
            }
        }
    }

    private void sendError(RoutingContext context, AuthorizationRequest request, OAuth2Exception oAuth2Exception, Client client) {
        try {
            // Response Mode is not supplied by the client, process the response as usual
            if (client == null || request.getResponseMode() == null || !request.getResponseMode().endsWith("jwt")) {
                // redirect user
                doRedirect(context.response(), buildRedirectUri(oAuth2Exception.getOAuth2ErrorCode(), oAuth2Exception.getMessage(), request));
            } else {
                JWTOAuth2Exception jwtException = new JWTOAuth2Exception(oAuth2Exception,
                        request.getState());
                jwtException.setIss(openIDDiscoveryService.getIssuer(UriBuilderRequest.extractBasePath(context)));
                jwtException.setAud(client.getClientId());

                // There is nothing about expiration. We admit to use the one settled for IdToken validity
                jwtException.setExp(Instant.now().plusSeconds(client.getIdTokenValiditySeconds()).getEpochSecond());

                //Sign if needed, else return unsigned JWT
                jwtService.encodeAuthorization(jwtException.build(), client)
                        //Encrypt if needed, else return JWT
                        .flatMap(authorization -> jweService.encryptAuthorization(authorization, client))
                        .subscribe(jwt -> doRedirect(context.response(),
                                jwtException.buildRedirectUri(request.getRedirectUri(),
                                        request.getResponseType(), request.getResponseMode(), jwt)),
                                throwable2 -> sendError(context, request, oAuth2Exception, client));
            }
        } catch (Exception e) {
            logger.error("Unable to handle authorization error response", e);
            doRedirect(context.response(), "/" + domain.getPath() + "/oauth/error");
        }
    }

    private String buildRedirectUri(String error, String errorDescription, AuthorizationRequest authorizationRequest) throws URISyntaxException {
        // prepare query
        Map<String, String> query = new LinkedHashMap<>();
        // put client_id parameter for the default error page for branding/custom html purpose
        if (isDefaultErrorPage(authorizationRequest.getRedirectUri())) {
            query.computeIfAbsent(Parameters.CLIENT_ID, val -> authorizationRequest.getClientId());
        }

        query.computeIfAbsent("error", val -> error);
        query.computeIfAbsent("error_description", val -> errorDescription);
        query.computeIfAbsent(Parameters.STATE, val -> authorizationRequest.getState());

        boolean fragment = !isDefaultErrorPage(authorizationRequest.getRedirectUri()) &&
                (isImplicitFlow(authorizationRequest.getResponseType()) || isHybridFlow(authorizationRequest.getResponseType()));
        return append(authorizationRequest.getRedirectUri(), query, fragment);
    }

    private boolean isDefaultErrorPage(String redirectUri) {
        return redirectUri.contains(defaultErrorPagePath);
    }

    private String append(String base, Map<String, String> query, boolean fragment) throws URISyntaxException {
        // prepare final redirect uri
        UriBuilder template = UriBuilder.newInstance();

        // get URI from the redirect_uri parameter
        UriBuilder builder = UriBuilder.fromURIString(base);
        URI redirectUri = builder.build();

        // router final redirect uri
        template.scheme(redirectUri.getScheme())
                .host(redirectUri.getHost())
                .port(redirectUri.getPort())
                .userInfo(redirectUri.getUserInfo())
                .path(redirectUri.getPath());

        // append error parameters in "application/x-www-form-urlencoded" format
        if (fragment) {
            query.forEach((k, v) -> template.addFragmentParameter(k, UriBuilder.encodeURIComponent(v)));
        } else {
            query.forEach((k, v) -> template.addParameter(k, UriBuilder.encodeURIComponent(v)));
        }
        return template.build().toString();
    }

    private AuthorizationRequest resolveInitialAuthorizeRequest(RoutingContext routingContext) {
        AuthorizationRequest authorizationRequest = routingContext.session().get(OAuth2Constants.AUTHORIZATION_REQUEST);
        // we have the authorization request in session if we come from the approval user page
        if (authorizationRequest != null) {
            return authorizationRequest;
        }

        // if none, we have the required request parameters to re-create the authorize request
        return authorizationRequestFactory.create(routingContext.request());
    }

    private void cleanSession(RoutingContext context) {
        context.session().remove(OAuth2Constants.AUTHORIZATION_REQUEST);
        context.session().remove(USER_CONSENT_COMPLETED_CONTEXT_KEY);
        context.session().remove(REQUESTED_CONSENT_CONTEXT_KEY);
    }

    private void doRedirect(HttpServerResponse response, String url) {
        response.putHeader(HttpHeaders.LOCATION, url).setStatusCode(302).end();
    }
}

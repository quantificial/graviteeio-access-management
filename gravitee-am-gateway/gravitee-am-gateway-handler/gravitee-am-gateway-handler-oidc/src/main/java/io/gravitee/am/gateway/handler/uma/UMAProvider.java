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
package io.gravitee.am.gateway.handler.uma;

import io.gravitee.am.common.oidc.Scope;
import io.gravitee.am.gateway.handler.api.ProtocolProvider;
import io.gravitee.am.gateway.handler.common.vertx.web.auth.handler.OAuth2AuthHandler;
import io.gravitee.am.gateway.handler.common.vertx.web.auth.provider.OAuth2AuthProvider;
import io.gravitee.am.gateway.handler.uma.resources.endpoint.ProviderConfigurationEndpoint;
import io.gravitee.am.gateway.handler.uma.resources.endpoint.ResourceSetRegistrationEndpoint;
import io.gravitee.am.gateway.handler.uma.resources.handler.MethodNotSupportedHandler;
import io.gravitee.am.gateway.handler.uma.resources.handler.ResourceSetRegistrationAccessHandler;
import io.gravitee.am.gateway.handler.uma.resources.handler.UmaExceptionHandler;
import io.gravitee.am.gateway.handler.uma.service.discovery.UMADiscoveryService;
import io.gravitee.am.model.Domain;
import io.gravitee.am.service.ResourceSetService;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.http.MediaType;
import io.gravitee.common.service.AbstractService;
import io.vertx.core.Handler;
import io.vertx.ext.web.handler.impl.HttpStatusException;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.handler.CorsHandler;
import org.apache.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;

import static io.gravitee.am.gateway.handler.uma.constants.UMAConstants.*;

/**
 * @author Alexandre FARIA (contact at alexandrefaria.net)
 * @author GraviteeSource Team
 */
public class UMAProvider extends AbstractService<ProtocolProvider> implements ProtocolProvider{

    @Autowired
    private Vertx vertx;

    @Autowired
    private Router router;

    @Autowired
    private CorsHandler corsHandler;

    @Autowired
    private Domain domain;

    @Autowired
    private OAuth2AuthProvider oAuth2AuthProvider;

    @Autowired
    private UMADiscoveryService discoveryService;

    @Autowired
    private ResourceSetService resourceSetService;

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        if(domain.getUma()!=null && domain.getUma().isEnabled()) {
            // Init web router
            initRouter();
        }
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
    }

    @Override
    public String path() {
        return UMA_PATH;
    }

    private void initRouter() {
        final Router umaRouter = Router.router(vertx);

        // UMA Provider configuration information endpoint
        Handler<RoutingContext> umaProviderConfigurationEndpoint = new ProviderConfigurationEndpoint();
        ((ProviderConfigurationEndpoint) umaProviderConfigurationEndpoint).setDiscoveryService(discoveryService);
        umaRouter.route(WELL_KNOWN_PATH).handler(corsHandler);
        umaRouter
                .get(WELL_KNOWN_PATH)
                .handler(umaProviderConfigurationEndpoint);

        // Resource Set Registration Auth handler
        OAuth2AuthHandler resourceSetRegistrationAuthHandler = OAuth2AuthHandler.create(oAuth2AuthProvider, Scope.UMA.getKey());
        resourceSetRegistrationAuthHandler.extractToken(true);
        resourceSetRegistrationAuthHandler.extractClient(true);
        resourceSetRegistrationAuthHandler.forceEndUserToken(true);//It must be a resource owner

        // Resource Set Registration Access Handler
        ResourceSetRegistrationAccessHandler resourceSetRegistrationAccessHandler = new ResourceSetRegistrationAccessHandler(domain, resourceSetRegistrationAuthHandler);

        // Resource Set Registration endpoint
        ResourceSetRegistrationEndpoint resourceSetRegistrationEndpoint = new ResourceSetRegistrationEndpoint(domain, resourceSetService);
        umaRouter.route(RESOURCE_REGISTRATION_PATH).handler(corsHandler);

        umaRouter
                .get(RESOURCE_REGISTRATION_PATH)
                .handler(resourceSetRegistrationAccessHandler)
                .handler(resourceSetRegistrationEndpoint);
        umaRouter
                .post(RESOURCE_REGISTRATION_PATH)
                .consumes(MediaType.APPLICATION_JSON)
                .handler(resourceSetRegistrationAccessHandler)
                .handler(resourceSetRegistrationEndpoint::create);
        umaRouter
                .get(RESOURCE_REGISTRATION_PATH+"/:"+RESOURCE_ID)
                .handler(resourceSetRegistrationAccessHandler)
                .handler(resourceSetRegistrationEndpoint::get);
        umaRouter
                .put(RESOURCE_REGISTRATION_PATH+"/:"+RESOURCE_ID)
                .consumes(MediaType.APPLICATION_JSON)
                .handler(resourceSetRegistrationAccessHandler)
                .handler(resourceSetRegistrationEndpoint::update);
        umaRouter
                .delete(RESOURCE_REGISTRATION_PATH+"/:"+RESOURCE_ID)
                .handler(resourceSetRegistrationAccessHandler)
                .handler(resourceSetRegistrationEndpoint::delete);

        // Not supported method for others method
        MethodNotSupportedHandler notSupportedFallbackHandler = new MethodNotSupportedHandler();
        umaRouter.route(RESOURCE_REGISTRATION_PATH).handler(notSupportedFallbackHandler);
        umaRouter.route(RESOURCE_REGISTRATION_PATH+"/:"+RESOURCE_ID).handler(notSupportedFallbackHandler);

        //TODO:remove once done.
        final Handler<RoutingContext> notImplementedHandler = new Handler<RoutingContext>() {
            @Override
            public void handle(RoutingContext context) {
                context.response().setStatusCode(501).end("Not Implemented Yet");
            }
        };

        umaRouter
                .get(CLAIMS_INTERACTION_PATH)
                .handler(notImplementedHandler);

        umaRouter
                .get(PERMISSION_PATH)
                .handler(notImplementedHandler);

        // error handler
        errorHandler(umaRouter);

        router.mountSubRouter(path(), umaRouter);
    }

    private void errorHandler(Router router) {
        router.route().failureHandler(new UmaExceptionHandler());
    }
}

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
package io.gravitee.am.management.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.am.management.service.NewsletterService;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class NewsletterServiceImpl implements NewsletterService, InitializingBean, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewsletterServiceImpl.class);
    private static final String NEWSLETTER_URI = "https://download.gravitee.io/newsletter";
    private ExecutorService executorService;

    @Autowired
    private ObjectMapper mapper;

    @Override
    public void subscribe(Object user) {
        executorService.execute(() -> {
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                String requestBody = mapper.writeValueAsString(user);
                HttpEntity requestEntity = new StringEntity(requestBody, "application/json", "UTF-8");
                HttpPost httpPost = new HttpPost(NEWSLETTER_URI);
                httpPost.setEntity(requestEntity);
                httpClient.execute(httpPost);
            } catch (final Exception e) {
                LOGGER.error("Error while subscribing to newsletter", e);
            }
        });
    }

    @Override
    public void afterPropertiesSet() {
        executorService = Executors.newCachedThreadPool();
    }

    @Override
    public void destroy() {
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}

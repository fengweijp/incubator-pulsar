/**
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
package org.apache.pulsar.broker.authorization;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.pulsar.zookeeper.ZooKeeperCache.cacheTimeOutInSec;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.authentication.AuthenticationDataSource;
import org.apache.pulsar.broker.cache.ConfigurationCacheService;
import org.apache.pulsar.common.naming.DestinationName;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.util.FutureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

/**
 * Authorization service that manages pluggable authorization provider and authorize requests accordingly.
 * 
 */
public class AuthorizationService {
    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private AuthorizationProvider provider;
    private final ServiceConfiguration conf;

    public AuthorizationService(ServiceConfiguration conf, ConfigurationCacheService configCache)
            throws PulsarServerException {

        this.conf = conf;
        if (this.conf.isAuthorizationEnabled()) {
            try {
                final String providerClassname = conf.getAuthorizationProvider();
                if(StringUtils.isNotBlank(providerClassname)) {
                    provider = (AuthorizationProvider) Class.forName(providerClassname).newInstance();
                    provider.initialize(conf, configCache);
                    log.info("{} has been loaded.", providerClassname); 
                } else {
                    throw new PulsarServerException("No authorization providers are present.");
                }
            } catch (PulsarServerException e) {
                throw e;
            }catch (Throwable e) {
                throw new PulsarServerException("Failed to load an authorization provider.", e);
            }
        } else {
            log.info("Authorization is disabled");
        }
    }

    /**
     * 
     * Grant authorization-action permission on a namespace to the given client
     * 
     * @param namespace
     * @param actions
     * @param role
     * @param authDataJson
     *            additional authdata in json for targeted authorization provider
     * @return
     * @throws IllegalArgumentException
     *             when namespace not found
     * @throws IllegalStateException
     *             when failed to grant permission
     */
    public CompletableFuture<Void> grantPermissionAsync(NamespaceName namespace, Set<AuthAction> actions, String role,
            String authDataJson) {

        if (provider != null) {
            return provider.grantPermissionAsync(namespace, actions, role, authDataJson);
        }
        return FutureUtil.failedFuture(new IllegalStateException("No authorization provider configured"));
    }

    /**
     * Grant authorization-action permission on a topic to the given client
     * 
     * @param topicname
     * @param role
     * @param authDataJson
     *            additional authdata in json for targeted authorization provider
     * @return IllegalArgumentException when namespace not found
     * @throws IllegalStateException
     *             when failed to grant permission
     */
    public CompletableFuture<Void> grantPermissionAsync(DestinationName topicname, Set<AuthAction> actions, String role,
            String authDataJson) {

        if (provider != null) {
            return provider.grantPermissionAsync(topicname, actions, role, authDataJson);
        }
        return FutureUtil
                .failedFuture(new IllegalStateException("No authorization provider configured"));

    }

    /**
     * Check if the specified role has permission to send messages to the specified fully qualified destination name.
     *
     * @param destination
     *            the fully qualified destination name associated with the destination.
     * @param role
     *            the app id used to send messages to the destination.
     */
    public CompletableFuture<Boolean> canProduceAsync(DestinationName destination, String role,
            AuthenticationDataSource authenticationData) {

        if (!this.conf.isAuthorizationEnabled()) {
            return CompletableFuture.completedFuture(true);
        }

        if (provider != null) {
            return provider.canProduceAsync(destination, role, authenticationData);
        }
        return FutureUtil
                .failedFuture(new IllegalStateException("No authorization provider configured"));
    }

    /**
     * Check if the specified role has permission to receive messages from the specified fully qualified destination
     * name.
     *
     * @param destination
     *            the fully qualified destination name associated with the destination.
     * @param role
     *            the app id used to receive messages from the destination.
     * @param subscription
     *            the subscription name defined by the client
     */
    public CompletableFuture<Boolean> canConsumeAsync(DestinationName destination, String role,
            AuthenticationDataSource authenticationData, String subscription) {
        if (!this.conf.isAuthorizationEnabled()) {
            return CompletableFuture.completedFuture(true);
        }
        if (provider != null) {
            return provider.canConsumeAsync(destination, role, authenticationData, subscription);
        }
        return FutureUtil
                .failedFuture(new IllegalStateException("No authorization provider configured"));
    }

    public boolean canProduce(DestinationName destination, String role, AuthenticationDataSource authenticationData) throws Exception {
        try {
            return canProduceAsync(destination, role, authenticationData).get(cacheTimeOutInSec,
                    SECONDS);
        } catch (InterruptedException e) {
            log.warn("Time-out {} sec while checking authorization on {} ", cacheTimeOutInSec, destination);
            throw e;
        } catch (Exception e) {
            log.warn("Producer-client  with Role - {} failed to get permissions for destination - {}. {}", role,
                    destination, e.getMessage());
            throw e;
        }
    }

    public boolean canConsume(DestinationName destination, String role, AuthenticationDataSource authenticationData,
            String subscription) throws Exception {
        try {
            return canConsumeAsync(destination, role, authenticationData, subscription)
                    .get(cacheTimeOutInSec, SECONDS);
        } catch (InterruptedException e) {
            log.warn("Time-out {} sec while checking authorization on {} ", cacheTimeOutInSec, destination);
            throw e;
        } catch (Exception e) {
            log.warn("Consumer-client  with Role - {} failed to get permissions for destination - {}. {}", role,
                    destination, e.getMessage());
            throw e;
        }
    }

    /**
     * Check whether the specified role can perform a lookup for the specified destination.
     *
     * For that the caller needs to have producer or consumer permission.
     *
     * @param destination
     * @param role
     * @return
     * @throws Exception
     */
    public boolean canLookup(DestinationName destination, String role, AuthenticationDataSource authenticationData) throws Exception {
        return canProduce(destination, role, authenticationData)
                || canConsume(destination, role, authenticationData, null);
    }

    /**
     * Check whether the specified role can perform a lookup for the specified destination.
     *
     * For that the caller needs to have producer or consumer permission.
     *
     * @param destination
     * @param role
     * @return
     * @throws Exception
     */
    public CompletableFuture<Boolean> canLookupAsync(DestinationName destination, String role,
            AuthenticationDataSource authenticationData) {
        CompletableFuture<Boolean> finalResult = new CompletableFuture<Boolean>();
        canProduceAsync(destination, role, authenticationData)
                .whenComplete((produceAuthorized, ex) -> {
                    if (ex == null) {
                        if (produceAuthorized) {
                            finalResult.complete(produceAuthorized);
                            return;
                        }
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug(
                                    "Destination [{}] Role [{}] exception occured while trying to check Produce permissions. {}",
                                    destination.toString(), role, ex.getMessage());
                        }
                    }
                    canConsumeAsync(destination, role, null, null)
                            .whenComplete((consumeAuthorized, e) -> {
                                if (e == null) {
                                    if (consumeAuthorized) {
                                        finalResult.complete(consumeAuthorized);
                                        return;
                                    }
                                } else {
                                    if (log.isDebugEnabled()) {
                                        log.debug(
                                                "Destination [{}] Role [{}] exception occured while trying to check Consume permissions. {}",
                                                destination.toString(), role, e.getMessage());

                                    }
                                    finalResult.completeExceptionally(e);
                                    return;
                                }
                                finalResult.complete(false);
                            });
                });
        return finalResult;
    }

}

/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.configuration;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.caching.BuildCacheServiceFactory;
import org.gradle.caching.local.LocalBuildCache;

/**
 * Configuration for the build cache for an entire Gradle build.
 *
 * <p>It consists of a {@code local} and a {@code remote} part that can be configured separately. When both are configured,
 * the first enabled is used.</p>
 *
 * <p>The local part is pre-configured to be a {@link LocalBuildCache}. The remote part can be configured by specifying
 * the type of cache service to use. Remote cache services can be registered via the {@link BuildCacheServiceFactory} SPI.</p>
 *
 * <p>Gradle ships with a built-in remote cache backend implementation that works via HTTP and can be configured as follows in {@code settings.gradle}:</p>
 *
 * <pre>
 *     buildCache {
 *         remote(HttpBuildCache) {
 *             url = "http://localhost:8123/gradle-cache/"
 *         }
 *     }
 * </pre>
 *
 * @since 3.5
 */
@Incubating
public interface BuildCacheConfiguration {

    /**
     * Registers an instance of a {@link BuildCacheServiceFactory} that can create a
     * {@link org.gradle.caching.BuildCacheService} given a {@link BuildCache} configuration.
     *
     * A factory must be registered before a {@code BuildCacheService} instance can be created.
     *
     * @param buildCacheServiceFactory instance to register
     */
    void registerBuildCacheServiceFactory(BuildCacheServiceFactory buildCacheServiceFactory);

    /**
     * Returns the local cache configuration.
     */
    LocalBuildCache getLocal();

    /**
     * Executes the given action against the local configuration.
     *
     * @param configuration the action to execute against the local cache configuration.
     */
    void local(Action<? super LocalBuildCache> configuration);

    /**
     * Returns the remote cache configuration.
     */
    BuildCache getRemote();

    /**
     * Configures a remote cache with the given type.
     *
     * @param type the type of remote cache to configure.
     *
     * @throws IllegalArgumentException If a different remote cache has already been configured.
     */
    <T extends BuildCache> T remote(Class<T> type);

    /**
     * Configures a remote cache with the given type.
     *
     * @param type the type of remote cache to configure.
     * @param configuration the configuration to execute against the remote cache.
     *
     * @throws IllegalArgumentException If a different remote cache has already been configured.
     */
    <T extends BuildCache> T remote(Class<T> type, Action<? super BuildCache> configuration);

    /**
     * Executes the given action against the currently configured remote cache.
     *
     * @param configuration the action to execute against the currently configured remote cache.
     *
     * @throws IllegalStateException If no remote cache has been assigned yet
     */
    void remote(Action<? super BuildCache> configuration);
}
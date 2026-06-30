/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.openshift.apiclient

import io.kubernetes.client.openapi.ApiClient
import java.util.concurrent.TimeUnit

/**
 * Interface for building OpenShift API clients.
 */
interface OpenShiftClientBuilder {
    fun build(): ApiClient
    fun readTimeout(timeout: Long, unit: TimeUnit): OpenShiftClientBuilder
}

/**
 * Base class for building OpenShift API clients.
 * Provides shared read timeout handling via the [applyReadTimeout] helper.
 */
abstract class BaseClientBuilder : OpenShiftClientBuilder {
    private var readTimeoutSeconds: Long = 0

    override fun readTimeout(timeout: Long, unit: TimeUnit): OpenShiftClientBuilder {
        this.readTimeoutSeconds = unit.toSeconds(timeout)
        return this
    }

    protected fun applyReadTimeout(client: ApiClient): ApiClient {
        if (readTimeoutSeconds > 0) {
            client.httpClient = client.httpClient.newBuilder()
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .build()
        }
        return client
    }
}

/*
 * Copyright (c) 2025-2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.auth.sandbox

import com.redhat.devtools.gateway.auth.code.AuthTokenKind
import com.redhat.devtools.gateway.auth.code.SSOToken
import com.redhat.devtools.gateway.auth.code.TokenModel
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Secret
import io.kubernetes.client.openapi.models.V1ServiceAccount
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.credentials.AccessTokenAuthentication
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class SandboxClusterAuthProvider(
    private val sandboxApi: SandboxApi = SandboxApi(
        SandboxDefaults.SANDBOX_API_BASE_URL,
        SandboxDefaults.SANDBOX_API_TIMEOUT_MS
    )
) {
    suspend fun authenticate(ssoToken: SSOToken): TokenModel {
        val signup = sandboxApi.getSignUpStatus(ssoToken.idToken)
            ?: error("Sandbox not available")

        if (!signup.status.ready) error("Sandbox not ready")

        val username = signup.compliantUsername ?: signup.username
        val namespace = "$username-dev"

        val client: ApiClient = ClientBuilder.standard()
            .setBasePath(signup.proxyUrl!!)
            .setAuthentication(AccessTokenAuthentication(ssoToken.idToken))
            .build()
            .also { it.httpClient = it.httpClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }

        val coreV1Api = CoreV1Api(client)
        val pipelineSA = ensurePipelineServiceAccount(coreV1Api, namespace)
        val pipelineSecret = ensurePipelineTokenSecret(coreV1Api, namespace, pipelineSA)
        val pipelineToken = extractToken(pipelineSecret)

        return TokenModel(
            accessToken = pipelineToken,
            expiresAt = null, // non-expiring pipeline token
            accountLabel = ssoToken.accountLabel,
            kind = AuthTokenKind.PIPELINE,
            clusterApiUrl = signup.apiEndpoint,
            namespace = namespace,
            serviceAccount = "pipeline"
        )
    }

    private suspend fun ensurePipelineServiceAccount(api: CoreV1Api, namespace: String): V1ServiceAccount = withContext(Dispatchers.IO) {
        val saList = api.listNamespacedServiceAccount(namespace).execute()
            ?: error("Failed to list ServiceAccounts")

        saList.items.firstOrNull { it.metadata?.name == "pipeline" }
            ?: api.createNamespacedServiceAccount(
                namespace,
                V1ServiceAccount().metadata(V1ObjectMeta().name("pipeline"))
            ).execute() ?: error("Failed to create pipeline ServiceAccount")
    }

    private suspend fun ensurePipelineTokenSecret(api: CoreV1Api, namespace: String, sa: V1ServiceAccount): V1Secret = withContext(Dispatchers.IO) {
        val secretName = "pipeline-secret-${sa.metadata?.name}"
        val secretList = api.listNamespacedSecret(namespace).execute()
            ?: error("Failed to list Secrets")

        secretList.items.firstOrNull { it.metadata?.name == secretName && it.data?.containsKey("token") == true }
            ?.let { return@withContext it }

        val secret = V1Secret().metadata(
            V1ObjectMeta()
                .name(secretName)
                .putAnnotationsItem("kubernetes.io/service-account.name", sa.metadata!!.name)
                .putAnnotationsItem("kubernetes.io/service-account.uid", sa.metadata!!.uid)
        ).type("kubernetes.io/service-account-token")

        api.createNamespacedSecret(namespace, secret).execute()

        if (requestSecret(secretName, namespace, api)) {
            return@withContext secret
        }

        error("Pipeline token secret not populated")
    }

    private suspend fun requestSecret(secretName: String, namespace: String, api: CoreV1Api): Boolean {
        repeat(30) {
            val secret = api.readNamespacedSecret(secretName, namespace).execute()
            if (secret.data?.containsKey("token") == true) {
                return true
            }
            delay(1000.milliseconds)
        }
        return false
    }

    private fun extractToken(secret: V1Secret): String {
        val tokenBytes = secret.data?.get("token") ?: error("Token missing in secret")
        return String(tokenBytes, Charsets.UTF_8)
    }
}

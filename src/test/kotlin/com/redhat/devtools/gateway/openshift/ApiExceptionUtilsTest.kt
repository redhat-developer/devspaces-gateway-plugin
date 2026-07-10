/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.redhat.devtools.gateway.openshift

import com.redhat.devtools.gateway.devworkspace.WorkspaceAccessDeniedException
import com.redhat.devtools.gateway.devworkspace.WorkspaceNotFoundException
import io.kubernetes.client.openapi.ApiException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiExceptionUtilsTest {

  private val namespace = "user-dev"
  private val workspaceName = "my-workspace"
  private val clusterUrl = "https://api.example.com:6443"

  @Test
  fun `isNotFound returns true when status body contains 404`() {
    val exception = apiException(
      code = 0,
      body = statusBody(
        code = 404,
        reason = "NotFound",
        message = "not found",
      ),
    )

    assertThat(exception.isNotFound()).isTrue()
  }

  @Test
  fun `isDevWorkspaceResourceMissing detects workspace name in status message`() {
    val exception = apiException(
      code = 404,
      body = statusBody(
        code = 404,
        reason = "NotFound",
        message = "devworkspaces.workspace.devfile.io \"$workspaceName\" not found",
        detailsName = workspaceName,
        detailsKind = "devworkspaces",
      ),
    )

    assertThat(exception.isDevWorkspaceResourceMissing(workspaceName)).isTrue()
    assertThat(exception.isDevWorkspaceCrdMissing()).isFalse()
  }

  @Test
  fun `isDevWorkspaceCrdMissing detects missing CRD`() {
    val exception = apiException(
      code = 404,
      body = statusBody(
        code = 404,
        reason = "NotFound",
        message = "the server could not find the requested resource (get devworkspaces.workspace.devfile.io $workspaceName)",
        detailsKind = "devworkspaces",
      ),
    )

    assertThat(exception.isDevWorkspaceCrdMissing()).isTrue()
    assertThat(exception.isDevWorkspaceResourceMissing(workspaceName)).isFalse()
  }

  @Test
  fun `isDevWorkspaceAccessDenied detects forbidden workspace access`() {
    val exception = apiException(
      code = 403,
      body = statusBody(
        code = 403,
        reason = "Forbidden",
        message = "devworkspaces.workspace.devfile.io is forbidden: User cannot get resource",
      ),
    )

    assertThat(exception.isDevWorkspaceAccessDenied(namespace)).isTrue()
  }

  @Test
  fun `toWorkspaceException maps workspace missing`() {
    val exception = apiException(
      code = 404,
      body = statusBody(
        code = 404,
        reason = "NotFound",
        message = "devworkspaces.workspace.devfile.io \"$workspaceName\" not found",
        detailsName = workspaceName,
        detailsKind = "devworkspaces",
      ),
    )

    val mapped = exception.toWorkspaceException(namespace, workspaceName, clusterUrl)

    assertThat(mapped).isInstanceOf(WorkspaceNotFoundException::class.java)
    val workspaceNotFound = mapped as WorkspaceNotFoundException
    assertThat(workspaceNotFound.namespace).isEqualTo(namespace)
    assertThat(workspaceNotFound.name).isEqualTo(workspaceName)
    assertThat(workspaceNotFound.clusterUrl).isEqualTo(clusterUrl)
  }

  @Test
  fun `toWorkspaceException maps CRD missing to workspace not found`() {
    val exception = apiException(
      code = 404,
      body = statusBody(
        code = 404,
        reason = "NotFound",
        message = "the server could not find the requested resource (get devworkspaces.workspace.devfile.io $workspaceName)",
        detailsKind = "devworkspaces",
      ),
    )

    val mapped = exception.toWorkspaceException(namespace, workspaceName, clusterUrl)

    assertThat(mapped).isInstanceOf(WorkspaceNotFoundException::class.java)
  }

  @Test
  fun `toWorkspaceException maps access denied`() {
    val exception = apiException(
      code = 403,
      body = statusBody(
        code = 403,
        reason = "Forbidden",
        message = "devworkspaces.workspace.devfile.io is forbidden",
      ),
    )

    val mapped = exception.toWorkspaceException(namespace, workspaceName, clusterUrl)

    assertThat(mapped).isInstanceOf(WorkspaceAccessDeniedException::class.java)
  }

  private fun apiException(code: Int, body: String): ApiException =
    ApiException(code, "error", emptyMap(), body)

  private fun statusBody(
    code: Int,
    reason: String,
    message: String,
    detailsName: String? = null,
    detailsKind: String? = null,
  ): String {
    fun escape(value: String): String =
      value.replace("\\", "\\\\").replace("\"", "\\\"")

    val details = when {
      detailsName != null || detailsKind != null -> {
        val fields = buildList {
          detailsKind?.let { add("\"kind\":\"${escape(it)}\"") }
          detailsName?.let { add("\"name\":\"${escape(it)}\"") }
        }
        ""","details":{${fields.joinToString(",")}}"""
      }
      else -> ""
    }
    return """
      {
        "kind": "Status",
        "apiVersion": "v1",
        "status": "Failure",
        "message": "${escape(message)}",
        "reason": "${escape(reason)}",
        "code": $code
        $details
      }
    """.trimIndent()
  }
}

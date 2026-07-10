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
package com.redhat.devtools.gateway

import com.redhat.devtools.gateway.devworkspace.WorkspaceAccessDeniedException
import com.redhat.devtools.gateway.devworkspace.WorkspaceException
import com.redhat.devtools.gateway.devworkspace.WorkspaceNotFoundException
import com.redhat.devtools.gateway.openshift.getStatus
import com.redhat.devtools.gateway.openshift.isUnauthorized
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.ApiException

object DevSpacesConnectionProviderErrors {

    fun showDialog(e: Exception, ctx: DevSpacesContext, indicator: ProgressCountdown) {
        when (e) {
            is WorkspaceException -> {
                indicator.text = "Connection failed"
                handleWorkspaceException(e, ctx)
            }
            is ApiException -> {
                indicator.text = "Connection failed"
                handleApiException(e, ctx)
            }
            else -> {
                if (e.isCancellationException()
                    || indicator.isCanceled) {
                    indicator.text2 = "Cancelled..."
                } else {
                    Dialogs.error(
                        e.message ?: "Could not connect to workspace.",
                        "Connection Error"
                    )
                }
            }
        }
    }

    private fun handleWorkspaceException(err: WorkspaceException, ctx: DevSpacesContext) {
        when (err) {
            is WorkspaceAccessDeniedException -> showAuthOrAccessRequiredDialog(
                err.name,
                err.namespace,
                err.clusterUrl,
                ctx.cluster?.name
            )
            is WorkspaceNotFoundException -> showWorkspaceNotFoundDialog(
                err.name,
                err.namespace,
                err.clusterUrl,
                ctx.cluster?.name
            )
        }
    }

    private fun showWorkspaceNotFoundDialog(workspaceName: String, namespaceName: String, clusterUrl: String, clusterName: String?) {
        Dialogs.error(
            """
            Workspace “$workspaceName” does not exist in namespace “$namespaceName” on cluster ${formatClusterLabel(clusterName, clusterUrl)}.

            It may be the wrong cluster or the workspace may not exist.

            Re-open the connection link and select the correct cluster.
            """.trimIndent(),
            "Workspace Not Found"
        )
    }

    private fun showAuthOrAccessRequiredDialog(workspaceName: String, namespaceName: String, clusterUrl: String?, clusterName: String?) {
        Dialogs.error(
            """
            Lack of permissions for workspace “$workspaceName” in namespace "$namespaceName" on cluster ${formatClusterLabel(clusterName, clusterUrl)}. 

            It may be the wrong cluster, your session is expired or you lack permissions.
            
            Verify the selected cluster and token, then authenticate again.
            """.trimIndent(),
            "Workspace Access Denied"
        )
    }

    private fun showGenericAuthErrorDialog(clusterName: String?, clusterUrl: String?) {
        Dialogs.error(
            """
            Authentication failed on cluster ${formatClusterLabel(clusterName, clusterUrl)}.

            Your session may have expired or your credentials are invalid.

            Verify the selected cluster and token, then authenticate again.
            """.trimIndent(),
            "Authentication Failed"
        )
    }

    private fun handleApiException(e: ApiException, ctx: DevSpacesContext) {
        if (e.isUnauthorized()) {
            if (ctx.hasWorkspace()) {
                showAuthOrAccessRequiredDialog(
                    ctx.devWorkspace.name,
                    ctx.devWorkspace.namespace,
                    ctx.cluster?.url,
                    ctx.cluster?.name
                )
            } else {
                showGenericAuthErrorDialog(ctx.cluster?.name, ctx.cluster?.url)
            }
        } else {
            Dialogs.error(
                e.getStatus()?.message ?: "Could not connect to workspace.",
                "Connection Error"
            )
        }
    }

    private fun formatClusterLabel(clusterName: String?, clusterUrl: String?): String =
        clusterName?.let { "“$it” ($clusterUrl)" } ?: clusterUrl ?: ""

}
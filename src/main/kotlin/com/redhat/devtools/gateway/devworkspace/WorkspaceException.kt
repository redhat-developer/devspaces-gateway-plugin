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
package com.redhat.devtools.gateway.devworkspace

/**
 * Base class for DevWorkspace fetch/access failures on the currently selected cluster.
 */
sealed class WorkspaceException(
    val clusterUrl: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Thrown when a DevWorkspace cannot be reached on the selected cluster (missing resource or CRD).
 */
class WorkspaceNotFoundException(
    val namespace: String,
    val name: String,
    clusterUrl: String,
    cause: Throwable? = null,
) : WorkspaceException(
    clusterUrl,
    "Workspace \"$name\" was not found in namespace \"$namespace\" on cluster $clusterUrl",
    cause
)

/**
 * Thrown when the user cannot access the requested DevWorkspace on the selected cluster.
 */
class WorkspaceAccessDeniedException(
    val namespace: String,
    val name: String,
    clusterUrl: String,
    cause: Throwable? = null,
) : WorkspaceException(
    clusterUrl,
    "Access to workspace \"$name\" in namespace \"$namespace\" was denied on cluster $clusterUrl",
    cause
)

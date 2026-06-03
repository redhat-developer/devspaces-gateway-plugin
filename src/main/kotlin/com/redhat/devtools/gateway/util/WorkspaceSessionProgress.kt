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
package com.redhat.devtools.gateway.util

/**
 * Shared progress step labels for workspace session recovery.
 *
 * Used by both recovery paths:
 * - **Pod-roll reconnect** ([com.redhat.devtools.gateway.ThinClientReconnect]): [CLOSING_IDE],
 *   [WAITING_FOR_IDE], [CONNECTING_TO_IDE]
 * - **Annotated restart** ([com.redhat.devtools.gateway.devworkspace.DevWorkspaceRestart]): all
 *   constants including [STOPPING_WORKSPACE], [WAITING_PODS_TERMINATE], [STARTING_WORKSPACE]
 * - **Delayed port-forward recovery** ([ForwardRecoveryProgress] via [PortForwardPodResolver]):
 *   [WAITING_FOR_POD]
 */
internal object WorkspaceSessionProgress {
    const val WAITING_FOR_POD: String = "Waiting for workspace pod..."
    const val CLOSING_IDE: String = "Closing IDE connection..."
    const val WAITING_FOR_IDE: String = "Waiting for IDE to be ready..."
    const val CONNECTING_TO_IDE: String = "Connecting to IDE..."
    const val STOPPING_WORKSPACE: String = "Stopping workspace..."
    const val WAITING_PODS_TERMINATE: String = "Waiting for pods to terminate..."
    const val STARTING_WORKSPACE: String = "Starting workspace..."
}

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
package com.redhat.devtools.gateway.openshift

/**
 * Event types of the Kubernetes watch protocol. The java client exposes no constants for them —
 * `Watch.Response.type` is a raw string — so this enum is the single place that knows the wire
 * strings, shared by every watch in the plugin.
 */
internal enum class WatchEventType(private val wireName: String) {
    ADDED("ADDED"),
    MODIFIED("MODIFIED"),
    DELETED("DELETED"),
    ERROR("ERROR");

    companion object {

        /**
         * The event type that the API server sent, or `null` for a type that no watch reacts to
         * (e.g. BOOKMARK).
         */
        fun fromWireName(wireName: String?): WatchEventType? =
            entries.firstOrNull { it.wireName == wireName }
    }
}

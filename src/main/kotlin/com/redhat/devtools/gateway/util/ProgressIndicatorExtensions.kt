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

import com.intellij.openapi.progress.ProgressIndicator

/**
 * Shows a determinate progress step. Does nothing when there is no indicator.
 */
fun ProgressIndicator?.showProgress(text: String, fraction: Double) {
    this ?: return
    isIndeterminate = false
    this.fraction = fraction
    this.text = text
}

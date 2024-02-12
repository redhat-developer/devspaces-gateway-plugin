/*
 * Copyright (c) 2024 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.github.devspaces.gateway.openshift

import io.kubernetes.client.Exec
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.util.Streams
import java.io.ByteArrayOutputStream


// Sample:
// https://github.com/kubernetes-client/java/blob/master/examples/examples-release-19/src/main/java/io/kubernetes/client/examples/ExecExample.java
class Exec(private val client: ApiClient) {
    fun run(pod: V1Pod, command: Array<String>, container: String): String {
        val output = ByteArrayOutputStream()

        Configuration.setDefaultApiClient(client)
        val process = Exec().exec(pod, command, container, false, false)

        val copyOutputThread =
            Thread {
                Streams.copy(process.inputStream, output)
            }
        copyOutputThread.start()

        process.waitFor()
        copyOutputThread.join()
        process.destroy()

        return output.toString()
    }
}
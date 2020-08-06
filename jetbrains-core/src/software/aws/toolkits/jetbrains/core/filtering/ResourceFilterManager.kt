// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.filtering

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project

class ResourceFilterManager : Disposable {
    val filters = mutableMapOf<String, MutableList<String>>(
        "SoftwareType" to mutableListOf("Infrastructure", "Long-Running Server-Side Software")
    )

    fun getActiveFilters(): MutableMap<String, MutableList<String>> = filters

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): ResourceFilterManager = ServiceManager.getService(project, ResourceFilterManager::class.java)
    }
}
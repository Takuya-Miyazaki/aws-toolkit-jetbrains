// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.go

import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoTokenType
import com.goide.psi.impl.GoLightType
import com.goide.psi.impl.GoPsiUtil
import com.goide.stubs.index.GoFunctionIndex
import com.goide.stubs.index.GoIdFilter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.elementType
import com.jetbrains.python.PyTokenTypes
import software.aws.toolkits.jetbrains.services.lambda.LambdaHandlerResolver

class GoLambdaHandlerResolver : LambdaHandlerResolver {
    override fun version(): Int = 1

    override fun findPsiElements(project: Project, handler: String, searchScope: GlobalSearchScope): Array<NavigatablePsiElement> =
        // GoFunctionDeclarationImpl is a NavigatablePsiElement
        GoFunctionIndex.find(handler, project, searchScope, GoIdFilter.getFilesFilter(searchScope)).filterIsInstance<NavigatablePsiElement>().toTypedArray()

    override fun determineHandler(element: PsiElement): String? {
        // Go PSI is different, go function declarations are not leaf's like in some other
        // languages, they are CompositeElements
        val parent = element.parent
        if(parent !is GoFunctionDeclaration) {
            return null
        }

        // make sure it's a top level function
        if(!GoPsiUtil.isTopLevelDeclaration(parent)) {
            return null
        }

        if (!parent.isValidHandlerIdentifier()) {
            return null
        }

        return parent.name
    }

    override fun determineHandlers(element: PsiElement, file: VirtualFile): Set<String> = determineHandler(element)?.let { setOf(it) }.orEmpty()

    // see https://docs.aws.amazon.com/lambda/latest/dg/golang-handler.html for what is valid
    private fun GoFunctionDeclaration.isValidHandlerIdentifier(): Boolean {
        val params = signature?.parameters?.parameterDeclarationList ?: listOf()
        // 0, 1 or 2 parameters
        if(params.size > 2) {
            return false
        }
        // if 2 parameters, first must be context.Context
        if (params.size == 2) {
            params.first()
        }
        // 0, 1, or 2 returned values. 0 is a bit strange for PSI, we can only check by text
        if (signature?.resultType?.text != GoLightType.LightVoidType.TYPE_TEXT) {
            // 1
            when (signature?.resultType) {
                //is
            }
            // 2

        }
        return true
    }
}

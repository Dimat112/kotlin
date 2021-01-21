/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.KotlinBundle
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.intentions.getCallableDescriptor
import org.jetbrains.kotlin.idea.project.platform
import org.jetbrains.kotlin.idea.references.KtSimpleNameReference
import org.jetbrains.kotlin.idea.references.readWriteAccess
import org.jetbrains.kotlin.platform.konan.isNative
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.source.getPsi
import com.intellij.psi.PsiReference as PsiReference

class MutationOfVariableWithSharedImmutableAnnotationInNativeInspection: AbstractKotlinInspection() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) =
        propertyVisitor(fun(property) {
            if (!property.platform.isNative()) return
            if (!(property.annotationEntries.map { it.shortName.toString() }.contains("SharedImmutable"))) return

            val callableDescriptor = property.delegateExpressionOrInitializer?.getCallableDescriptor()
            val psi = callableDescriptor?.source?.getPsi() ?: return

            var classProperties = listOf<KtProperty>()
            var valueParameters = listOf<KtParameter>()

            when (psi) {
                is KtClass ->
                    classProperties = psi.getProperties()
                is KtPrimaryConstructor -> {
                    valueParameters = psi.valueParameters
                    classProperties = (psi.parent as? KtClass)?.getProperties() ?: emptyList()
                }
            }

            fun searchWriteReferences(element: PsiElement): List<PsiReference> {
                return ReferencesSearch.search(element, element.useScope)
                    .filter {
                        (it as? KtSimpleNameReference)
                            ?.element
                            ?.readWriteAccess(true)
                            ?.isWrite == true
                    }
            }

            fun registerProblem(element: PsiElement) {
                holder.registerProblem(
                    element,
                    KotlinBundle.message("inspection.native.invalid.mutation.display.name"),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                )
            }
            
            valueParameters.forEach {
                if (!it.isMutable) return@forEach
                searchWriteReferences(it).forEach {
                   registerProblem(it.element)
                }
            }

            classProperties?.forEach {
                if (!it.isVar) return@forEach
                searchWriteReferences(it).forEach {
                    registerProblem(it.element)
                }
            }
        })
}
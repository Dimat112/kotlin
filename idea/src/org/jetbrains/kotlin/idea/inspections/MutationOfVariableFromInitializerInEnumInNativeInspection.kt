/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.project.platform
import org.jetbrains.kotlin.idea.references.KtSimpleNameReference
import org.jetbrains.kotlin.idea.references.readWriteAccess
import org.jetbrains.kotlin.platform.konan.isNative
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

class MutationOfVariableFromInitializerInEnumInNativeInspection: AbstractKotlinInspection() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) =
        parameterVisitor(fun(parameter) {
            if (!parameter.platform.isNative()) return
            if (!(parameter.containingClassOrObject as KtClass).isEnum()) return
            if (!parameter.isMutable) return

            val references = ReferencesSearch.search(parameter, parameter.useScope).filter {
                (it as? KtSimpleNameReference)
                    ?.element
                    ?.readWriteAccess(useResolveForReadWrite = true)
                    ?.isWrite == true
            }

            references.forEach {
                holder.registerProblem(
                    it.element,
                    KotlinBundle.message("inspection.native.invalid.mutation.display.name"),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                )
            }
        })
}
package ru.bekmnsrw.compose.performance.analyzer.recomposition

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
import ru.bekmnsrw.compose.performance.analyzer.recomposition.ScreenStateTransferAnalyzer.collectStateUsages
import ru.bekmnsrw.compose.performance.analyzer.recomposition.ScreenStateTransferAnalyzer.containsStateAsParam
import ru.bekmnsrw.compose.performance.analyzer.recomposition.ScreenStateTransferAnalyzer.containsStateProperty
import ru.bekmnsrw.compose.performance.analyzer.recomposition.ScreenStateTransferAnalyzer.isState
import ru.bekmnsrw.compose.performance.analyzer.recomposition.model.ComposableNode
import ru.bekmnsrw.compose.performance.analyzer.recomposition.model.ComposableParameter
import ru.bekmnsrw.compose.performance.analyzer.recomposition.model.Stability.*
import ru.bekmnsrw.compose.performance.analyzer.utils.Constants.COMPOSABLE_SHORT_NAME

/**
 * @author bekmnsrw
 */
internal object AbstractSyntaxTreeBuilder {

    fun buildAST(file: KtFile): List<ComposableNode> {
        val composableFunctions = mutableListOf<ComposableNode>()

        PsiTreeUtil.findChildrenOfType(
            /* element = */ file,
            /* aClass = */ KtNamedFunction::class.java,
        ).forEach { function ->
            if (function.isComposable()) {
                composableFunctions.add(traverseComposable(function))
            }
        }

        return composableFunctions
    }

    private fun traverseComposable(function: KtNamedFunction): ComposableNode {
        val name = function.name.orEmpty()
        val body = function.bodyExpression
        val params = function.valueParameters.map { valueParam ->
            ComposableParameter(
                ktParameter = valueParam,
                stability = Unknown,
                isState = valueParam.isState(),
                stateUsages = function.collectStateUsages(),
            )
        }

        val nestedComposables = mutableListOf<ComposableNode>()

        PsiTreeUtil.findChildrenOfType(
            /* element = */ function.bodyBlockExpression,
            /* aClass = */ KtCallExpression::class.java,
        )
            .forEach { callExpression ->
                val callee = callExpression.calleeExpression

                if (callee is KtNameReferenceExpression) {
                    val resolvedFunction = callee.resolve() as? KtNamedFunction

                    if (resolvedFunction?.isComposable() == true) {
                        val argumentMap = matchArgumentsWithParameters(callExpression, resolvedFunction)
                        val nestedNode = traverseComposable(resolvedFunction).copy(
                            parameters = resolvedFunction.valueParameters.map { param ->
                                ComposableParameter(
                                    ktParameter = param,
                                    passedValue = argumentMap[param.nameAsName?.asString()],
                                    stability = Unknown,
                                    isState = param.isState(),
                                    stateUsages = function.collectStateUsages(),
                                )
                            }
                        )
                        nestedComposables.add(nestedNode)
                    }
                }
            }

        return ComposableNode(
            name = name,
            parameters = params,
            nestedNodes = nestedComposables,
            isRoot = !params.containsStateAsParam() && body.containsStateProperty(),
        )
    }

    private fun matchArgumentsWithParameters(
        callExpression: KtCallExpression,
        resolvedFunction: KtNamedFunction,
    ): Map<String, String?> {
        val argumentMap = mutableMapOf<String, String?>()

        val arguments = callExpression.valueArguments

        resolvedFunction.valueParameters.forEachIndexed { index, param ->
            val argument = arguments.getOrNull(index)
            val passedValue = argument?.getArgumentExpression()?.text
            argumentMap[param.nameAsName?.asString().orEmpty()] = passedValue
        }

        return argumentMap
    }

    private fun KtNamedFunction.isComposable(): Boolean {
        return annotationEntries.any { annotation ->
            annotation.shortName?.asString() == COMPOSABLE_SHORT_NAME
        }
    }
}

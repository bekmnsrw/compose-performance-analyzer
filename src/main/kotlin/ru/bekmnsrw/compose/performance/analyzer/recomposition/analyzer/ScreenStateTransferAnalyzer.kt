package ru.bekmnsrw.compose.performance.analyzer.recomposition.analyzer

import org.jetbrains.kotlin.psi.*
import ru.bekmnsrw.compose.performance.analyzer.recomposition.model.ComposableNode
import ru.bekmnsrw.compose.performance.analyzer.recomposition.model.ComposableParameter
import ru.bekmnsrw.compose.performance.analyzer.utils.Constants.STATE

/**
 * @author bekmnsrw
 */
internal object ScreenStateTransferAnalyzer {

    fun checkScreenStateTransfer(composableNodes: List<ComposableNode>): List<String> {
        val stateTransferPaths = mutableListOf<String>()

        composableNodes.forEach { rootNode ->
            if (rootNode.isRoot) {
                checkChildNodeScreenStateTransfer(rootNode, "", stateTransferPaths)
            }
        }

        return stateTransferPaths
    }

    fun KtNamedFunction.collectStateUsages(): List<String> {
        val stateUsages = mutableListOf<String>()

        this.bodyExpression?.accept(object : KtTreeVisitorVoid() {
            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                super.visitDotQualifiedExpression(expression)

                if (expression.receiverExpression.text.contains(STATE)) {
                    val propertyOrMethodName = expression.selectorExpression?.text
                    if (propertyOrMethodName != null) {
                        stateUsages.add(propertyOrMethodName)
                    }
                }
            }
        })

        return stateUsages
    }

    fun KtParameter.isState(): Boolean {
        return typeReference?.text?.contains(
            other = STATE,
            ignoreCase = true,
        ) ?: false
    }

    fun List<ComposableParameter>.containsStateAsParam(): Boolean {
        return this.any { param -> param.ktParameter.isState() }
    }

    fun KtExpression?.containsStateProperty(): Boolean {
        var containsState = false

        this?.accept(object : KtTreeVisitorVoid() {
            override fun visitProperty(property: KtProperty) {
                if (property.name.orEmpty().contains(STATE, ignoreCase = true)) {
                    containsState = true
                }
            }
        })

        return containsState
    }

    private fun checkChildNodeScreenStateTransfer(
        node: ComposableNode,
        currentPath: String,
        stateTransferPaths: MutableList<String>
    ) {
        val stateParam = node.parameters.find { it.isState && it.passedValue != null }
        val newPath = if (stateParam != null) "$currentPath${node.name} -> " else currentPath

        if (stateParam != null && node.nestedNodes.isEmpty()) {
            stateTransferPaths.add(newPath.dropLast(4))
        }

        node.nestedNodes.forEach { nestedNode ->
            checkChildNodeScreenStateTransfer(nestedNode, newPath, stateTransferPaths)
        }
    }
}
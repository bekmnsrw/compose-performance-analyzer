package ru.bekmnsrw.compose.performance.analyzer.recomposition.analyzer

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.builtins.isKFunctionType
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.isEnumClass
import org.jetbrains.kotlin.idea.base.utils.fqname.fqName
import org.jetbrains.kotlin.idea.inspections.collections.isCollection
import org.jetbrains.kotlin.idea.search.usagesSearch.descriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.checker.SimpleClassicTypeSystemContext.isPrimitiveType
import org.jetbrains.kotlin.types.isNullable
import org.jetbrains.kotlin.types.typeUtil.isEnum
import org.jetbrains.kotlin.types.typeUtil.makeNotNullable
import org.jetbrains.kotlinx.serialization.compiler.resolve.toClassDescriptor
import ru.bekmnsrw.compose.performance.analyzer.recomposition.model.ComposableNode
import ru.bekmnsrw.compose.performance.analyzer.recomposition.model.Stability
import ru.bekmnsrw.compose.performance.analyzer.recomposition.model.Stability.*
import ru.bekmnsrw.compose.performance.analyzer.utils.Constants.COMPOSABLE_FUNCTION
import ru.bekmnsrw.compose.performance.analyzer.utils.Constants.IMMUTABLE_FQ_NAME
import ru.bekmnsrw.compose.performance.analyzer.utils.Constants.STABLE_COLLECTIONS
import ru.bekmnsrw.compose.performance.analyzer.utils.Constants.STABLE_FQ_NAME

/**
* @author bekmnsrw
*/
internal object StabilityAnalyzer {

    fun checkParamsStability(composables: List<ComposableNode>): List<ComposableNode> {
        return composables.map { composable ->
            val updatedParams = composable.parameters.map { parameter ->
                parameter.ktParameter.descriptor?.type?.let { returnType ->
                    parameter.copy(stability = stabilityOf(returnType))
                } ?: parameter
            }
            composable.copy(parameters = updatedParams)
        }
    }

    private fun stabilityOf(kotlinType: KotlinType): Stability = when {
        KotlinBuiltIns.isPrimitiveType(kotlinType) ||
                kotlinType.isSyntheticComposableFunction() ||
                kotlinType.isEnum() ||
                KotlinBuiltIns.isString(kotlinType) -> Stable

        kotlinType.isFunctionType ||
                kotlinType.isKFunctionType -> checkLambdaStability(kotlinType)

        kotlinType.isNullable() -> stabilityOf(kotlinType.makeNotNullable())

        kotlinType.isCollection() -> checkCollectionStability(kotlinType)

        kotlinType.constructor.declarationDescriptor is ClassDescriptor -> checkClassStability(kotlinType)

        else -> Unknown
    }

    private fun stabilityOf(declaration: ClassDescriptor): Stability {
        if (declaration.kind.isEnumClass || declaration.defaultType.isPrimitiveType()) return Stable

        val unstableProperties = mutableListOf<String>()

        for (member in declaration.unsubstitutedMemberScope.getDescriptorsFiltered()) {
            if (member is PropertyDescriptor) {
                if (!member.kind.isReal || member.getter?.isDefault == false && !member.isVar && !member.isDelegated) continue

                val propertyStability = when {
                    member.type.isCollection() -> checkCollectionStability(member.type)
                    member.isVar && !member.isDelegated -> Unstable("`${declaration.name}` contains non-delegated var `${member.name}`")
                    else -> stabilityOf(member.type)
                }

                if (propertyStability is Unstable) {
                    unstableProperties.add("`${member.name}: ${member.type}` is unstable: ${propertyStability.reason}")
                }
            }
        }

        return if (unstableProperties.isEmpty()) Stable else Unstable(unstableProperties.joinToString("; "))
    }

    private fun checkCollectionStability(kotlinType: KotlinType): Stability {
        val isStableCollection = kotlinType.fqName.toString() in STABLE_COLLECTIONS
        val unstableArgs = checkArgumentStability(kotlinType.arguments)

        return when {
            isStableCollection && unstableArgs.isEmpty() -> Stable
            !isStableCollection && unstableArgs.isNotEmpty() -> Unstable("`${kotlinType.fqName}` is unstable and has unstable type parameter: $unstableArgs")
            !isStableCollection -> Unstable("`${kotlinType.fqName}` is unstable")
            unstableArgs.isNotEmpty() -> Unstable(unstableArgs)
            else -> Unknown
        }
    }

    private fun checkClassStability(kotlinType: KotlinType): Stability {
        return with(kotlinType.toClassDescriptor) {
            if (this != null) {
                val annotationFqNames = listOf(STABLE_FQ_NAME, IMMUTABLE_FQ_NAME)
                if (annotations.hasAnyAnnotation(annotationFqNames)) Stable else stabilityOf(this)
            } else {
                Unknown
            }
        }
    }

    private fun checkArgumentStability(arguments: List<TypeProjection>): String {
        var unstableArgs = ""

        arguments.forEach { argument ->
            val stability = stabilityOf(argument.type)
            if (stability is Unstable) {
                unstableArgs += "`${argument.type}` is unstable: ${stability.reason}"
            }
        }

        return unstableArgs
    }

    private fun checkLambdaStability(kotlinType: KotlinType): Stability {
        val unstableArgs = checkArgumentStability(kotlinType.arguments)
        return if (unstableArgs.isEmpty()) Stable else Unstable(unstableArgs)
    }

    private fun Annotations.hasAnyAnnotation(annotationFqNames: List<FqName>): Boolean {
        annotationFqNames.forEach { annotationFqName ->
            if (this.hasAnnotation(annotationFqName)) {
                return true
            }
        }

        return false
    }

    private fun KotlinType.isSyntheticComposableFunction() = fqName?.asString()
        .orEmpty()
        .startsWith(COMPOSABLE_FUNCTION)
}
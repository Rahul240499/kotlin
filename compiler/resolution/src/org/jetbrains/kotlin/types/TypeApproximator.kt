/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.types

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.resolve.calls.NewCommonSuperTypeCalculator
import org.jetbrains.kotlin.resolve.calls.components.ClassicTypeSystemContextForCS
import org.jetbrains.kotlin.resolve.calls.inference.model.TypeVariableTypeConstructor
import org.jetbrains.kotlin.resolve.constants.IntegerLiteralTypeConstructor
import org.jetbrains.kotlin.types.TypeApproximatorConfiguration.IntersectionStrategy.*
import org.jetbrains.kotlin.types.checker.NewCapturedType
import org.jetbrains.kotlin.types.checker.NewCapturedTypeConstructor
import org.jetbrains.kotlin.types.model.*
import org.jetbrains.kotlin.types.model.CaptureStatus.*
import org.jetbrains.kotlin.types.typeUtil.builtIns


open class TypeApproximatorConfiguration {
    enum class IntersectionStrategy {
        ALLOWED,
        TO_FIRST,
        TO_COMMON_SUPERTYPE
    }

    open val flexible get() = false // simple flexible types (FlexibleTypeImpl)
    open val dynamic get() = false // DynamicType
    open val rawType get() = false // RawTypeImpl
    open val errorType get() = false
    open val integerLiteralType: Boolean = false // IntegerLiteralTypeConstructor
    open val definitelyNotNullType get() = true
    open val intersection: IntersectionStrategy = TO_COMMON_SUPERTYPE

    open val typeVariable: (TypeVariableTypeConstructor) -> Boolean = { false }
    open val capturedType: (NewCapturedType) -> Boolean = { false } // true means that this type we can leave as is

    abstract class AllFlexibleSameValue : TypeApproximatorConfiguration() {
        abstract val allFlexible: Boolean

        override val flexible get() = allFlexible
        override val dynamic get() = allFlexible
        override val rawType get() = allFlexible
    }

    object LocalDeclaration : AllFlexibleSameValue() {
        override val allFlexible get() = true
        override val intersection get() = ALLOWED
        override val errorType get() = true
        override val integerLiteralType: Boolean get() = true
    }

    object PublicDeclaration : AllFlexibleSameValue() {
        override val allFlexible get() = true
        override val errorType get() = true
        override val definitelyNotNullType get() = false
        override val integerLiteralType: Boolean get() = true
    }

    abstract class AbstractCapturedTypesApproximation(val approximatedCapturedStatus: CaptureStatus) :
        TypeApproximatorConfiguration.AllFlexibleSameValue() {
        override val allFlexible get() = true
        override val errorType get() = true

        // i.e. will be approximated only approximatedCapturedStatus captured types
        override val capturedType get() = { it: NewCapturedType -> it.captureStatus != approximatedCapturedStatus }
        override val intersection get() = IntersectionStrategy.ALLOWED
        override val typeVariable: (TypeVariableTypeConstructor) -> Boolean get() = { true }
    }

    object IncorporationConfiguration : TypeApproximatorConfiguration.AbstractCapturedTypesApproximation(FOR_INCORPORATION)
    object SubtypeCapturedTypesApproximation : TypeApproximatorConfiguration.AbstractCapturedTypesApproximation(FOR_SUBTYPING)
    object CapturedAndIntegerLiteralsTypesApproximation : TypeApproximatorConfiguration.AbstractCapturedTypesApproximation(FROM_EXPRESSION) {
        override val integerLiteralType: Boolean get() = true
    }
}


class TypeApproximator(builtIns: KotlinBuiltIns) : AbstractTypeApproximator(ClassicTypeSystemContextForCS(builtIns)) {
    fun approximateDeclarationType(baseType: KotlinType, local: Boolean, languageVersionSettings: LanguageVersionSettings): UnwrappedType {
        if (!languageVersionSettings.supportsFeature(LanguageFeature.NewInference)) return baseType.unwrap()

        val configuration = if (local) TypeApproximatorConfiguration.LocalDeclaration else TypeApproximatorConfiguration.PublicDeclaration
        return approximateToSuperType(baseType.unwrap(), configuration) ?: baseType.unwrap()
    }

    // null means that this input type is the result, i.e. input type not contains not-allowed kind of types
    // type <: resultType
    fun approximateToSuperType(type: UnwrappedType, conf: TypeApproximatorConfiguration): UnwrappedType? =
        super.approximateToSuperType(type, conf) as UnwrappedType?

    // resultType <: type
    fun approximateToSubType(type: UnwrappedType, conf: TypeApproximatorConfiguration): UnwrappedType? =
        super.approximateToSubType(type, conf) as UnwrappedType?
}

abstract class AbstractTypeApproximator(val ctx: TypeSystemInferenceExtensionContext) : TypeSystemInferenceExtensionContext by ctx {


    private val referenceApproximateToSuperType = this::approximateSimpleToSuperType
    private val referenceApproximateToSubType = this::approximateSimpleToSubType



    // null means that this input type is the result, i.e. input type not contains not-allowed kind of types
    // type <: resultType
    fun approximateToSuperType(type: KotlinTypeMarker, conf: TypeApproximatorConfiguration): KotlinTypeMarker? =
        approximateToSuperType(type, conf, -type.typeDepth())

    // resultType <: type
    fun approximateToSubType(type: KotlinTypeMarker, conf: TypeApproximatorConfiguration): KotlinTypeMarker? =
        approximateToSubType(type, conf, -type.typeDepth())

    private fun approximateToSuperType(type: KotlinTypeMarker, conf: TypeApproximatorConfiguration, depth: Int): KotlinTypeMarker? {
        if (type is TypeUtils.SpecialType) return null
        return approximateTo(
            prepareType(type), conf, { upperBound() },
            referenceApproximateToSuperType, depth
        )
    }

    private fun approximateToSubType(type: KotlinTypeMarker, conf: TypeApproximatorConfiguration, depth: Int): KotlinTypeMarker? {
        if (type is TypeUtils.SpecialType) return null
        return approximateTo(
            prepareType(type), conf, { lowerBound() },
            referenceApproximateToSubType, depth
        )
    }

    // comments for case bound = upperBound, approximateTo = toSuperType
    private fun approximateTo(
        type: KotlinTypeMarker,
        conf: TypeApproximatorConfiguration,
        bound: FlexibleTypeMarker.() -> SimpleTypeMarker,
        approximateTo: (SimpleTypeMarker, TypeApproximatorConfiguration, depth: Int) -> KotlinTypeMarker?,
        depth: Int
    ): KotlinTypeMarker? {
        when (type) {
            is SimpleTypeMarker -> return approximateTo(type, conf, depth)
            is FlexibleTypeMarker -> {
                if (type.isDynamic()) {
                    return if (conf.dynamic) null else type.bound()
                } else if (type.asRawType() != null) {
                    return if (conf.rawType) null else type.bound()
                }

                // TODO: Restore check
//                // TODO: currently we can lose information about enhancement, should be fixed later
//                assert(type is FlexibleTypeImpl || type is FlexibleTypeWithEnhancement) {
//                    "Unexpected subclass of FlexibleType: ${type::class.java.canonicalName}, type = $type"
//                }

                if (conf.flexible) {
                    /**
                     * Let inputType = L_1..U_1; resultType = L_2..U_2
                     * We should create resultType such as inputType <: resultType.
                     * It means that if A <: inputType, then A <: U_1. And, because inputType <: resultType,
                     * A <: resultType => A <: U_2. I.e. for every type A such A <: U_1, A <: U_2 => U_1 <: U_2.
                     *
                     * Similar for L_1 <: L_2: Let B : resultType <: B. L_2 <: B and L_1 <: B.
                     * I.e. for every type B such as L_2 <: B, L_1 <: B. For example B = L_2.
                     */

                    val lowerResult = approximateTo(type.lowerBound(), conf, depth)
                    val upperResult = approximateTo(type.upperBound(), conf, depth)
                    if (lowerResult == null && upperResult == null) return null

                    /**
                     * If C <: L..U then C <: L.
                     * inputType.lower <: lowerResult => inputType.lower <: lowerResult?.lowerIfFlexible()
                     * i.e. this type is correct. We use this type, because this type more flexible.
                     *
                     * If U_1 <: U_2.lower .. U_2.upper, then we know only that U_1 <: U_2.upper.
                     */
                    return createFlexibleType(
                        lowerResult?.lowerBoundIfFlexible() ?: type.lowerBound(),
                        upperResult?.upperBoundIfFlexible() ?: type.upperBound()
                    )
                } else {
                    return type.bound().let { approximateTo(it, conf, depth) ?: it }
                }
            }
            else -> error("sealed")
        }
    }

    private fun approximateIntersectionType(
        type: SimpleTypeMarker,
        conf: TypeApproximatorConfiguration,
        toSuper: Boolean,
        depth: Int
    ): KotlinTypeMarker? {
        val typeConstructor = type.typeConstructor()
        assert(typeConstructor.isIntersection()) {
            "Should be intersection type: $type, typeConstructor class: ${typeConstructor::class.java.canonicalName}"
        }
        assert(typeConstructor.supertypes().isNotEmpty()) {
            "Supertypes for intersection type should not be empty: $type"
        }

        var thereIsApproximation = false
        val newTypes = typeConstructor.supertypes().map {
            val newType = if (toSuper) approximateToSuperType(it, conf, depth) else approximateToSubType(it, conf, depth)
            if (newType != null) {
                thereIsApproximation = true
                newType
            } else it
        }

        /**
         * For case ALLOWED:
         * A <: A', B <: B' => A & B <: A' & B'
         *
         * For other case -- it's impossible to find some type except Nothing as subType for intersection type.
         */
        val baseResult = when (conf.intersection) {
            ALLOWED -> if (!thereIsApproximation) return null else intersectTypes(newTypes)
            TO_FIRST -> if (toSuper) newTypes.first() else return type.defaultResult(toSuper = false)
            // commonSupertypeCalculator should handle flexible types correctly
            TO_COMMON_SUPERTYPE -> {
                if (!toSuper) return type.defaultResult(toSuper = false)
                val resultType = with(NewCommonSuperTypeCalculator) { commonSuperType(newTypes) }
                approximateToSuperType(resultType, conf) ?: resultType
            }
        }

        return if (type.isMarkedNullable()) baseResult.withNullability(true) else baseResult
    }

    private fun approximateCapturedType(
        type: NewCapturedType,
        conf: TypeApproximatorConfiguration,
        toSuper: Boolean,
        depth: Int
    ): KotlinTypeMarker? {
        val supertypes = type.typeConstructor().supertypes()
        val baseSuperType = when (supertypes.size) {
            0 -> type.builtIns.nullableAnyType // Let C = in Int, then superType for C and C? is Any?
            1 -> supertypes.single()

            // Consider the following example:
            // A.getA()::class.java, where `getA()` returns some class from Java
            // From `::class` we are getting type KClass<Cap<out A!>>, where Cap<out A!> have two supertypes:
            // - Any (from declared upper bound of type parameter for KClass)
            // - (A..A?) -- from A!, projection type of captured type

            // Now, after approximation we were getting type `KClass<out A>`, because { Any & (A..A?) } = A,
            // but in old inference type was equal to `KClass<out A!>`.

            // Important note that from the point of type system first type is more specific:
            // Here, approximation of KClass<Cap<out A!>> is a type KClass<T> such that KClass<Cap<out A!>> <: KClass<out T> =>
            // So, the the more specific type for T would be "some non-null (because of declared upper bound type) subtype of A", which is `out A`

            // But for now, to reduce differences in behaviour of old and new inference, we'll approximate such types to `KClass<out A!>`

            // Once NI will be more stabilized, we'll use more specific type

            else -> type.typeConstructorProjection().getType()//.unwrap()
        }
        val baseSubType = type.lowerType ?: type.builtIns.nothingType

        if (conf.capturedType(type)) {
            /**
             * Here everything is ok if bounds for this captured type should not be approximated.
             * But. If such bounds contains some unauthorized types, then we cannot leave this captured type "as is".
             * And we cannot create new capture type, because meaning of new captured type is not clear.
             * So, we will just approximate such types
             *
             * todo handle flexible types
             */
            if (approximateToSuperType(baseSuperType, conf, depth) == null && approximateToSubType(baseSubType, conf, depth) == null) {
                return null
            }
        }
        val baseResult = if (toSuper) approximateToSuperType(baseSuperType, conf, depth) ?: baseSuperType else approximateToSubType(
            baseSubType,
            conf,
            depth
        ) ?: baseSubType

        // C = in Int, Int <: C => Int? <: C?
        // C = out Number, C <: Number => C? <: Number?
        return if (type.isMarkedNullable) baseResult.withNullability(true) else baseResult
    }

    private fun approximateSimpleToSuperType(type: SimpleTypeMarker, conf: TypeApproximatorConfiguration, depth: Int) =
        approximateTo(type, conf, toSuper = true, depth = depth)

    private fun approximateSimpleToSubType(type: SimpleTypeMarker, conf: TypeApproximatorConfiguration, depth: Int) =
        approximateTo(type, conf, toSuper = false, depth = depth)

    private fun approximateTo(
        type: SimpleTypeMarker,
        conf: TypeApproximatorConfiguration,
        toSuper: Boolean,
        depth: Int
    ): KotlinTypeMarker? {
        if (type.isError()) {
            // todo -- fix builtIns. Now builtIns here is DefaultBuiltIns
            return if (conf.errorType) null else type.defaultResult(toSuper)
        }

        if (depth > 3) return type.defaultResult(toSuper)

        if (type.argumentsCount() != 0) {
            return approximateParametrizedType(type, conf, toSuper, depth + 1)
        }

        val definitelyNotNullType = type.asDefinitelyNotNullType()
        if (definitelyNotNullType != null) {
            return approximateDefinitelyNotNullType(definitelyNotNullType, conf, toSuper, depth)
        }

        val typeConstructor = type.typeConstructor()

        if (typeConstructor is NewCapturedTypeConstructor) {
            assert(type is NewCapturedType) {
                // KT-16147
                "Type is inconsistent -- somewhere we create type with typeConstructor = $typeConstructor " +
                        "and class: ${type::class.java.canonicalName}. type.toString() = $type"
            }
            return approximateCapturedType(type as NewCapturedType, conf, toSuper, depth)
        }

        if (typeConstructor.isIntersection()) {
            return approximateIntersectionType(type, conf, toSuper, depth)
        }

        if (typeConstructor is TypeVariableTypeConstructor) {
            return if (conf.typeVariable(typeConstructor)) null else type.defaultResult(toSuper)
        }

        if (typeConstructor.isIntegerLiteralTypeConstructor()) {
            return if (conf.integerLiteralType)
                typeConstructor.getApproximatedIntegerLiteralType().withNullability(type.isMarkedNullable())
            else
                null
        }

        return null // simple classifier type
    }

    private fun approximateDefinitelyNotNullType(
        type: DefinitelyNotNullTypeMarker,
        conf: TypeApproximatorConfiguration,
        toSuper: Boolean,
        depth: Int
    ): KotlinTypeMarker? {
        val approximatedOriginalType = approximateTo(type.original(), conf, toSuper, depth)
        return if (conf.definitelyNotNullType) {
            approximatedOriginalType?.makeDefinitelyNotNullOrNotNull()
        } else {
            if (toSuper)
                (approximatedOriginalType ?: type.original()).withNullability(false)
            else
                type.defaultResult(toSuper)
        }
    }

    private fun isApproximateDirectionToSuper(effectiveVariance: TypeVariance, toSuper: Boolean) =
        when (effectiveVariance) {
            TypeVariance.OUT -> toSuper
            TypeVariance.IN -> !toSuper
            TypeVariance.INV -> throw AssertionError("Incorrect variance $effectiveVariance")
        }

    private fun approximateParametrizedType(
        type: SimpleTypeMarker,
        conf: TypeApproximatorConfiguration,
        toSuper: Boolean,
        depth: Int
    ): SimpleTypeMarker? {
        val typeConstructor = type.typeConstructor()
//        val parameters = type.typeConstructor().parameters
//        val arguments = type.arguments
        if (typeConstructor.parametersCount() != type.argumentsCount()) {
            return if (conf.errorType) {
                ErrorUtils.createErrorType("Inconsistent type: $type (parameters.size = ${typeConstructor.parametersCount()}, arguments.size = ${type.argumentsCount()})")
            } else type.defaultResult(toSuper)
        }

        val newArguments = arrayOfNulls<TypeArgumentMarker?>(type.argumentsCount())

        loop@ for (index in 0 until type.argumentsCount()) {
            val parameter = typeConstructor.getParameter(index)
            val argument = type.getArgument(index)

            if (argument.isStarProjection()) continue

            val argumentType = argument.getType()//.unwrap()
            val effectiveVariance = AbstractTypeChecker.effectiveVariance(parameter.getVariance(), argument.getVariance())
            when (effectiveVariance) {
                null -> {
                    return if (conf.errorType) {
                        ErrorUtils.createErrorType(
                            "Inconsistent type: $type ($index parameter has declared variance: ${parameter.getVariance()}, " +
                                    "but argument variance is ${argument.getVariance()})"
                        )
                    } else type.defaultResult(toSuper)
                }
                TypeVariance.OUT, TypeVariance.IN -> {
                    /**
                     * Out<Foo> <: Out<superType(Foo)>
                     * Inv<out Foo> <: Inv<out superType(Foo)>

                     * In<Foo> <: In<subType(Foo)>
                     * Inv<in Foo> <: Inv<in subType(Foo)>
                     */
                    val approximatedArgument = argumentType.let {
                        if (isApproximateDirectionToSuper(effectiveVariance, toSuper)) {
                            approximateToSuperType(it, conf, depth)
                        } else {
                            approximateToSubType(it, conf, depth)
                        }
                    } ?: continue@loop

                    if (parameter.getVariance() == TypeVariance.INV) {
                        newArguments[index] = createTypeArgument(approximatedArgument, effectiveVariance)
                    } else {
                        newArguments[index] = approximatedArgument.asTypeArgument()
                    }
                }
                TypeVariance.INV -> {
                    if (!toSuper) {
                        // Inv<Foo> cannot be approximated to subType
                        val toSubType = approximateToSubType(argumentType, conf, depth) ?: continue@loop

                        // Inv<Foo!> is supertype for Inv<Foo?>
                        if (!AbstractTypeChecker.equalTypes(
                                this,
                                argumentType,
                                toSubType
                            )
                        ) return type.defaultResult(toSuper)

                        // also Captured(out Nothing) = Nothing
                        newArguments[index] = toSubType.asTypeArgument()
                        continue@loop
                    }

                    /**
                     * Example with non-trivial both type approximations:
                     * Inv<In<C>> where C = in Int
                     * Inv<In<C>> <: Inv<out In<Int>>
                     * Inv<In<C>> <: Inv<in In<Any?>>
                     *
                     * So such case is rare and we will chose Inv<out In<Int>> for now.
                     *
                     * Note that for case Inv<C> we will chose Inv<in Int>, because it is more informative then Inv<out Any?>.
                     * May be we should do the same for deeper types, but not now.
                     */
                    if (argumentType.typeConstructor() is NewCapturedTypeConstructor) {
                        val subType = approximateToSubType(argumentType, conf, depth) ?: continue@loop
                        if (!subType.isTrivialSub()) {
                            newArguments[index] = createTypeArgument(subType, TypeVariance.IN)
                            continue@loop
                        }
                    }

                    val approximatedSuperType =
                        approximateToSuperType(argumentType, conf, depth) ?: continue@loop // null means that this type we can leave as is
                    if (approximatedSuperType.isTrivialSuper()) {
                        val approximatedSubType =
                            approximateToSubType(argumentType, conf, depth) ?: continue@loop // seems like this is never null
                        if (!approximatedSubType.isTrivialSub()) {
                            newArguments[index] = createTypeArgument(approximatedSubType, TypeVariance.IN)
                            continue@loop
                        }
                    }

                    if (AbstractTypeChecker.equalTypes(this, argumentType, approximatedSuperType)) {
                        newArguments[index] = approximatedSuperType.asTypeArgument()
                    } else {
                        newArguments[index] = createTypeArgument(approximatedSuperType, TypeVariance.OUT)
                    }
                }
            }
        }

        if (newArguments.all { it == null }) return null

        val newArgumentsList = List(type.argumentsCount()) { index -> newArguments[index] ?: type.getArgument(index) }
        return type.replaceArguments(newArgumentsList)
    }

    private fun SimpleTypeMarker.defaultResult(toSuper: Boolean) = if (toSuper) nullableAnyType() else {
        if (isMarkedNullable()) nullableNothingType() else nothingType()
    }

    // Any? or Any!
    private fun KotlinTypeMarker.isTrivialSuper() = upperBoundIfFlexible().isNullableAny()

    // Nothing or Nothing!
    private fun KotlinTypeMarker.isTrivialSub() = lowerBoundIfFlexible().isNothing()
}
//
//internal fun KotlinTypeMarker.typeDepth() =
//    when (this) {
//        is SimpleTypeMarker -> typeDepth()
//        is FlexibleType -> Math.max(lowerBound.typeDepth(), upperBound.typeDepth())
//    }
//
//internal fun SimpleTypeMarker.typeDepth(): Int {
//    if (this is TypeUtils.SpecialType) return 0
//
//    val maxInArguments = arguments.asSequence().map {
//        if (it.isStarProjection) 1 else it.type.unwrap().typeDepth()
//    }.max() ?: 0
//
//    return maxInArguments + 1
//}

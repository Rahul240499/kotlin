/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.codegen.intrinsics

import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.codegen.AsmUtil.comparisonOperandType
import org.jetbrains.kotlin.codegen.Callable
import org.jetbrains.kotlin.codegen.CallableMethod
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter

class CompareTo : IntrinsicMethod() {
    private fun genInvoke(type: Type?, v: InstructionAdapter) {
        when (type) {
            Type.INT_TYPE, Type.CHAR_TYPE -> v.invokestatic(IntrinsicMethods.INTRINSICS_CLASS_NAME, "compare", "(II)I", false)
            Type.LONG_TYPE -> v.lcmp()
            Type.FLOAT_TYPE -> v.invokestatic("java/lang/Float", "compare", "(FF)I", false)
            Type.DOUBLE_TYPE -> v.invokestatic("java/lang/Double", "compare", "(DD)I", false)
            else -> throw UnsupportedOperationException()
        }
    }

    override fun toCallable(method: CallableMethod): Callable {
        val parameterType = comparisonOperandType(
            method.dispatchReceiverType ?: method.extensionReceiverType,
            method.parameterTypes.single()
        )
        assert(AsmUtil.isPrimitive(method.returnType)) { "Return type of BinaryOp intrinsic should be of primitive type: ${method.returnType}" }

        return GeneralCompareToIntrinsicCallable(method.returnType, parameterType, parameterType, null) {
            genInvoke(parameterType, it)
        }
    }
}

class GeneralCompareToIntrinsicCallable(
    returnType: Type,
    valueParameterType: Type,
    thisType: Type? = null,
    receiverType: Type? = null,
    lambda: IntrinsicCallable.(v: InstructionAdapter) -> Unit
) : IntrinsicCallable(returnType, listOf(valueParameterType), thisType, receiverType, lambda)
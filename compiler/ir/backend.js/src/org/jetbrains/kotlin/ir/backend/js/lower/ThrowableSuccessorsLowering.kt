/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.atMostOne
import org.jetbrains.kotlin.backend.common.descriptors.WrappedPropertyDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFieldImpl
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.isThrowable
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.name.Name

class ThrowableSuccessorsLowering(context: JsIrBackendContext) : FileLoweringPass {
    private val unitType = context.irBuiltIns.unitType
    private val nothingNType = context.irBuiltIns.nothingNType
    private val nothingType = context.irBuiltIns.nothingType
    private val stringType = context.irBuiltIns.stringType

    private val propertyGetter = context.intrinsics.jsGetJSField.symbol
    private val propertySetter = context.intrinsics.jsSetJSField.symbol

    private val messageName = JsIrBuilder.buildString(stringType, "message")
    private val causeName = JsIrBuilder.buildString(stringType, "cause")
    private val nameName = JsIrBuilder.buildString(stringType, "name")

    private val throwableClass = context.symbolTable.referenceClass(
        context.getClass(JsIrBackendContext.KOTLIN_PACKAGE_FQN.child(Name.identifier("Throwable")))
    ).owner
    private val throwableConstructors = throwableClass.declarations.filterIsInstance<IrConstructor>()

    private val defaultCtor = throwableConstructors.single { it.valueParameters.size == 0 }
    private val toString =
        throwableClass.declarations.filterIsInstance<IrSimpleFunction>().single { it.name == Name.identifier("toString") }

    private val messagePropertyName = Name.identifier("message")
    private val causePropertyName = Name.identifier("cause")

    private val messageGetter =
        throwableClass.declarations.filterIsInstance<IrFunction>().atMostOne { it.name == Name.special("<get-message>") }
            ?: throwableClass.declarations.filterIsInstance<IrProperty>().atMostOne { it.name == messagePropertyName }?.getter!!
    private val causeGetter =
        throwableClass.declarations.filterIsInstance<IrFunction>().atMostOne { it.name == Name.special("<get-cause>") }
            ?: throwableClass.declarations.filterIsInstance<IrProperty>().atMostOne { it.name == causePropertyName }?.getter!!

    private val captureStackFunction = context.symbolTable.referenceSimpleFunction(context.getInternalFunctions("captureStack").single())
    private val newThrowableFunction = context.symbolTable.referenceSimpleFunction(context.getInternalFunctions("newThrowable").single())
    private val pendingSuperUsages = mutableListOf<DirectThrowableSuccessors>()

    private data class DirectThrowableSuccessors(val klass: IrClass, val message: IrField, val cause: IrField)

    override fun lower(irFile: IrFile) {

        pendingSuperUsages.clear()
        irFile.acceptChildrenVoid(ThrowableAccessorCreationVisitor())
        pendingSuperUsages.forEach { it.klass.transformChildren(ThrowableDirectSuccessorTransformer(it), it.klass) }
        irFile.transformChildrenVoid(ThrowablePropertiesUsageTransformer())
        irFile.transformChildrenVoid(ThrowableInstanceCreationLowering())
    }

    inner class ThrowableInstanceCreationLowering : IrElementTransformerVoid() {
        override fun visitCall(expression: IrCall): IrExpression {
            if (expression.symbol.owner !in throwableConstructors) return super.visitCall(expression)

            expression.transformChildrenVoid(this)

            val (messageArg, causeArg) = extractConstructorParameters(expression)

            return expression.run {
                IrCallImpl(startOffset, endOffset, type, newThrowableFunction, newThrowableFunction.descriptor).also {
                    it.putValueArgument(0, messageArg)
                    it.putValueArgument(1, causeArg)
                }
            }
        }

        private fun extractConstructorParameters(expression: IrFunctionAccessExpression): Pair<IrExpression, IrExpression> {
            val nullValue = IrConstImpl.constNull(expression.startOffset, expression.endOffset, nothingNType)
            return when {
                expression.valueArgumentsCount == 0 -> Pair(nullValue, nullValue)
                expression.valueArgumentsCount == 2 -> expression.run { Pair(getValueArgument(0)!!, getValueArgument(1)!!) }
                else -> {
                    val arg = expression.getValueArgument(0)!!
                    when {
                        arg.type.makeNotNull().isThrowable() -> Pair(nullValue, arg)
                        else -> Pair(arg, nullValue)
                    }
                }
            }
        }
    }

    inner class ThrowableAccessorCreationVisitor : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)


        override fun visitClass(declaration: IrClass) {

            if (isDirectChildOfThrowable(declaration)) {
                val messageField = createBackingField(declaration, messagePropertyName, messageGetter.returnType)
                val causeField = createBackingField(declaration, causePropertyName, causeGetter.returnType)
                val existedMessageAccessor = ownPropertyAccessor(declaration, messageGetter)
                if (existedMessageAccessor.origin == IrDeclarationOrigin.FAKE_OVERRIDE)
                    createPropertyAccessor(existedMessageAccessor, messageField)
                val existedCauseAccessor = ownPropertyAccessor(declaration, causeGetter)
                if (existedCauseAccessor.origin == IrDeclarationOrigin.FAKE_OVERRIDE)
                    createPropertyAccessor(existedCauseAccessor, causeField)

                pendingSuperUsages += DirectThrowableSuccessors(declaration, messageField, causeField)
            }
        }

        private fun createBackingField(declaration: IrClass, name: Name, type: IrType): IrField {
            val fieldDescriptor = WrappedPropertyDescriptor()
            val fieldSymbol = IrFieldSymbolImpl(fieldDescriptor)
            val fieldDeclaration = IrFieldImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                JsIrBuilder.SYNTHESIZED_DECLARATION,
                fieldSymbol,
                name,
                type,
                Visibilities.PRIVATE,
                true,
                false,
                false
            ).apply {
                parent = declaration
                fieldDescriptor.bind(this)
            }

            declaration.declarations += fieldDeclaration
            return fieldDeclaration
        }

        private fun createPropertyAccessor(fakeAccessor: IrSimpleFunction, field: IrField) {
            val name = fakeAccessor.name
            val function = JsIrBuilder.buildFunction(name).apply {
                parent = fakeAccessor.parent
                overriddenSymbols += fakeAccessor.overriddenSymbols
                returnType = fakeAccessor.returnType
                correspondingProperty = fakeAccessor.correspondingProperty
                dispatchReceiverParameter = fakeAccessor.dispatchReceiverParameter
            }

            val thisReceiver = JsIrBuilder.buildGetValue(function.dispatchReceiverParameter!!.symbol)
            val returnValue = JsIrBuilder.buildGetField(field.symbol, thisReceiver, type = field.type)
            val returnStatement = JsIrBuilder.buildReturn(function.symbol, returnValue, nothingType)
            function.body = JsIrBuilder.buildBlockBody(listOf(returnStatement))

            fakeAccessor.correspondingProperty?.getter = function
        }
    }

    private inner class ThrowableDirectSuccessorTransformer(private val successor: DirectThrowableSuccessors) :
        IrElementTransformer<IrDeclarationParent> {

        override fun visitClass(declaration: IrClass, data: IrDeclarationParent) = declaration

        override fun visitFunction(declaration: IrFunction, data: IrDeclarationParent) = super.visitFunction(declaration, declaration)

        override fun visitCall(expression: IrCall, data: IrDeclarationParent): IrElement {
            if (expression.superQualifierSymbol?.owner != throwableClass) return super.visitCall(expression, data)

            expression.transformChildren(this, data)

            val superField = when {
                expression.symbol.owner == messageGetter -> successor.message
                expression.symbol.owner == causeGetter -> successor.cause
                else -> error("Unknown accessor")
            }

            return expression.run { IrGetFieldImpl(startOffset, endOffset, superField.symbol, type, dispatchReceiver, origin) }
        }

        override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall, data: IrDeclarationParent): IrElement {
            if (expression.symbol.owner !in throwableConstructors) return super.visitDelegatingConstructorCall(expression, data)

            expression.transformChildren(this, data)

            val (messageArg, causeArg, paramStatements) = extractConstructorParameters(expression, data)

            val newDelegation = expression.run {
                IrDelegatingConstructorCallImpl(startOffset, endOffset, type, defaultCtor.symbol, defaultCtor.descriptor)
            }

            val klass = successor.klass
            val receiver = IrGetValueImpl(expression.startOffset, expression.endOffset, klass.thisReceiver!!.symbol)

            val nameArg = JsIrBuilder.buildString(stringType, klass.name.asString())

            val fillStatements = fillThrowableInstance(expression, receiver, messageArg, causeArg, nameArg)

            return expression.run {
                IrCompositeImpl(startOffset, endOffset, type, origin, paramStatements + newDelegation + fillStatements)
            }
        }

        private fun fillThrowableInstance(
            expression: IrFunctionAccessExpression,
            receiver: IrExpression,
            messageArg: IrExpression,
            causeArg: IrExpression,
            name: IrExpression
        ): List<IrStatement> {

            val setMessage = expression.run {
                IrSetFieldImpl(startOffset, endOffset, successor.message.symbol, receiver, messageArg, unitType)
            }

            val setCause = expression.run {
                IrSetFieldImpl(startOffset, endOffset, successor.cause.symbol, receiver, causeArg, unitType)
            }

            val setName = IrCallImpl(expression.startOffset, expression.endOffset, unitType, propertySetter).apply {
                putValueArgument(0, receiver)
                putValueArgument(1, nameName)
                putValueArgument(2, name)
            }

            val setStackTrace = IrCallImpl(expression.startOffset, expression.endOffset, unitType, captureStackFunction).apply {
                putValueArgument(0, receiver)
            }

            return listOf(setMessage, setCause, setName, setStackTrace)
        }

        private fun extractConstructorParameters(
            expression: IrFunctionAccessExpression,
            parent: IrDeclarationParent
        ): Triple<IrExpression, IrExpression, List<IrStatement>> {
            val nullValue = IrConstImpl.constNull(expression.startOffset, expression.endOffset, nothingNType)
            // Wrap parameters into variables to keep original evaluation order
            return when {
                expression.valueArgumentsCount == 0 -> Triple(nullValue, nullValue, emptyList())
                expression.valueArgumentsCount == 2 -> {
                    val msg = expression.getValueArgument(0)!!
                    val cus = expression.getValueArgument(1)!!
                    val irValM = JsIrBuilder.buildVar(msg.type, parent, initializer = msg)
                    val irValC = JsIrBuilder.buildVar(cus.type, parent, initializer = cus)
                    Triple(JsIrBuilder.buildGetValue(irValM.symbol), JsIrBuilder.buildGetValue(irValC.symbol), listOf(irValM, irValC))
                }
                else -> {
                    val arg = expression.getValueArgument(0)!!
                    val irVal = JsIrBuilder.buildVar(arg.type, parent, initializer = arg)
                    val argValue = JsIrBuilder.buildGetValue(irVal.symbol)
                    when {
                        arg.type.makeNotNull().isThrowable() -> {
                            val messageExpr = JsIrBuilder.buildCall(toString.symbol, stringType).apply {
                                dispatchReceiver = argValue
                            }
                            Triple(messageExpr, argValue, listOf(irVal))
                        }
                        else -> Triple(argValue, nullValue, listOf(irVal))
                    }
                }
            }
        }
    }

    private fun isDirectChildOfThrowable(irClass: IrClass) = irClass.superTypes.any { it.isThrowable() }
    private fun ownPropertyAccessor(irClass: IrClass, irBase: IrFunction) =
        irClass.declarations.filterIsInstance<IrProperty>().mapNotNull { it.getter }
            .single { it.overriddenSymbols.any { s -> s.owner == irBase } }

    inner class ThrowablePropertiesUsageTransformer : IrElementTransformerVoid() {
        override fun visitCall(expression: IrCall): IrExpression {
            val transformRequired = expression.superQualifierSymbol == null || expression.superQualifierSymbol?.owner == throwableClass

            if (!transformRequired) return super.visitCall(expression)

            expression.transformChildrenVoid(this)

            val owner = expression.symbol.owner
            return when (owner) {
                messageGetter -> {
                    IrCallImpl(expression.startOffset, expression.endOffset, expression.type, propertyGetter).apply {
                        putValueArgument(0, expression.dispatchReceiver!!)
                        putValueArgument(1, messageName)
                    }
                }
                causeGetter -> {
                    IrCallImpl(expression.startOffset, expression.endOffset, expression.type, propertyGetter).apply {
                        putValueArgument(0, expression.dispatchReceiver!!)
                        putValueArgument(1, causeName)
                    }
                }
                else -> expression
            }
        }
    }
}
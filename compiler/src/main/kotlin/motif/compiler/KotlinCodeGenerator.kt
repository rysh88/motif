/*
 * Copyright (c) 2018-2019 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package motif.compiler

import androidx.room.compiler.processing.XProcessingEnv
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.javapoet.KotlinPoetJavaPoetPreview
import com.squareup.kotlinpoet.javapoet.toKClassName
import motif.internal.None

@OptIn(KotlinPoetJavaPoetPreview::class)
object KotlinCodeGenerator {

  fun generate(scopeImpl: ScopeImpl): FileSpec {
    val typeSpec: TypeSpec = scopeImpl.spec()
    val fileSpec = FileSpec.get(scopeImpl.className.kt.packageName, typeSpec)
    return fileSpec
  }

  private fun ScopeImpl.spec(): TypeSpec {
      // Special handling for dynamic wrapper
      if (isDynamicWrapper) {
        return dynamicWrapperSpec()
      }

      return TypeSpec.classBuilder(className.kt)
          .apply {
            addAnnotation(suppressAnnotationSpec("REDUNDANT_PROJECTION", "UNCHECKED_CAST"))
            addAnnotation(scopeImplAnnotation.spec())
            addModifiers(if (internalScope) KModifier.INTERNAL else KModifier.PUBLIC)
            addSuperinterface(superClassName.kt)
            objectsField?.let { addProperty(it.spec()) }
            addProperty(dependenciesField.spec())

            // Add individual cache fields (volatile for VOLATILE_FIELDS, plain for SMART_CACHE)
            cacheFields.forEach { addProperty(it.spec(useSynchronized)) }

            // Add per-dependency lock fields (nullable, initialized conditionally)
            perDependencyLockFields?.let { lockFields ->
                lockFields.locks.values.forEach { lockFieldName ->
                    addProperty(
                        PropertySpec.builder(lockFieldName, ClassName.bestGuess("motif.MotifLock").copy(nullable = true))
                            .addModifiers(KModifier.PRIVATE)
                            .mutable(false)
                            .initializer("if (%T.usePerDependencyLock) %T() else null",
                                ClassName.bestGuess("motif.MotifRuntimeConfig"),
                                ClassName.bestGuess("motif.MotifLock"))
                            .build()
                    )
                }
            }

            primaryConstructor(constructor.spec(perDependencyLockFields))

            alternateConstructor?.let { addFunction(it.spec()) }

            accessMethodImpls
                .filter { !it.overriddenMethod.isSynthetic }
                .forEach { addFunction(it.spec()) }
            accessMethodImpls
                .filter { it.overriddenMethod.isSynthetic }
                .forEach { addProperty(it.propSpec()) }
            childMethodImpls.forEach { addFunction(it.spec()) }
            addFunction(scopeProviderMethod.spec())
            factoryProviderMethods.forEach { addFunctions(it.specs(useSynchronized, perDependencyLockFields)) }
            dependencyProviderMethods.forEach { addFunction(it.spec()) }
            dependencies?.let { addType(it.spec()) }
            objectsImpl?.let { addType(it.spec()) }
            staticDependencyClasses.forEach { addType(it.spec()) }
          }
          .build()
  }

  private fun ScopeImpl.dynamicWrapperSpec(): TypeSpec =
      TypeSpec.classBuilder(className.kt)
          .apply {
            addAnnotation(suppressAnnotationSpec("REDUNDANT_PROJECTION", "UNCHECKED_CAST"))
            addAnnotation(scopeImplAnnotation.spec())
            addModifiers(if (internalScope) KModifier.INTERNAL else KModifier.PUBLIC)
            addSuperinterface(superClassName.kt)

            // Add a property for the delegate scope implementation
            addProperty(
                PropertySpec.builder("scopeDelegate", superClassName.kt, KModifier.PRIVATE)
                    .initializer(
                        CodeBlock.builder()
                            .beginControlFlow("when (%T.cachingStrategy)", com.squareup.kotlinpoet.ClassName("motif", "MotifRuntimeConfig"))
                            .addStatement("%T.SMART_CACHE -> %T(dependencies)", com.squareup.kotlinpoet.ClassName("motif", "CachingStrategy"), getVariantClassName("_SmartCache"))
                            .addStatement("else -> %T(dependencies)", getVariantClassName("_VolatileFields"))
                            .endControlFlow()
                            .build()
                    )
                    .build()
            )

            // Add constructor that selects implementation based on MotifConfig
            primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("dependencies", dependenciesField.dependenciesClassName.kt)
                    .build()
            )

            // Delegate all accessor methods to the delegate
            accessMethodImpls
                .filter { !it.overriddenMethod.isSynthetic }
                .forEach { addFunction(it.delegateSpec()) }
            accessMethodImpls
                .filter { it.overriddenMethod.isSynthetic }
                .forEach { addProperty(it.delegatePropSpec()) }

            // Add child methods that delegate to the delegate
            childMethodImpls.forEach { addFunction(it.delegateSpec()) }

            // Add scope provider method
            addFunction(
                FunSpec.builder(scopeProviderMethod.name)
                    .returns(scopeProviderMethod.scopeClassName.kt)
                    .addStatement("return this")
                    .build()
            )

            // Add Dependencies interface so variant implementations can reference it
            dependencies?.let { addType(it.spec()) }
          }
          .build()

  private fun ScopeImpl.getVariantClassName(suffix: String): com.squareup.kotlinpoet.ClassName =
      com.squareup.kotlinpoet.ClassName(
          className.kt.packageName,
          className.kt.simpleName + suffix
      )

  private fun ScopeImplAnnotation.spec(): AnnotationSpec =
      AnnotationSpec.builder(motif.ScopeImpl::class)
          .apply {
            addMember(
                CodeBlock.builder()
                    .apply {
                      add("children = [")
                      children.forEachIndexed { i, child ->
                        val prefix = if (i == 0) "" else ", "
                        add("%L%T::class", prefix, child.kt)
                      }
                      add("]")
                    }
                    .build(),
            )
            addMember("scope = %T::class", scopeClassName.kt)
            addMember("dependencies = %T::class", dependenciesClassName.kt)
          }
          .build()

  private fun ObjectsField.spec(): PropertySpec =
      PropertySpec.builder(name, objectsClassName.kt, KModifier.PRIVATE, KModifier.FINAL)
          .initializer("%T()", objectsImplClassName.kt)
          .build()

  private fun DependenciesField.spec(): PropertySpec =
      PropertySpec.builder(name, dependenciesClassName.kt, KModifier.PRIVATE)
          .initializer(name)
          .build()

  private fun CacheField.spec(useSynchronized: Boolean): PropertySpec {
      // Both SMART_CACHE and VOLATILE_FIELDS use volatile for proper memory visibility
      val builder = if (useSynchronized) {
        // VOLATILE_FIELDS: Use None.NONE sentinel pattern with volatile
        PropertySpec.builder(name, Any::class, KModifier.PRIVATE)
            .mutable(true)
            .addAnnotation(Volatile::class)
            .initializer("%T.NONE", ClassName.bestGuess("motif.internal.None"))
      } else {
        // SMART_CACHE: Use null initialization with volatile
        PropertySpec.builder(name, Any::class.asTypeName().copy(nullable = true), KModifier.PRIVATE)
            .mutable(true)
            .addAnnotation(Volatile::class)
            .initializer("null")
      }

      return builder.build()
  }

  private fun Constructor.spec(perDependencyLockFields: PerDependencyLockFields?): FunSpec =
      FunSpec.constructorBuilder()
          .addParameter(dependenciesParameterName, dependenciesClassName.kt)
          .build()

  private fun AlternateConstructor.spec(): FunSpec =
      FunSpec.constructorBuilder()
          .addModifiers(KModifier.PUBLIC)
          .callThisConstructor(CodeBlock.of("object : %T {}", dependenciesClassName.kt))
          .build()

  private fun AccessMethodImpl.spec(): FunSpec {
      return XFunSpec.overriding(overriddenMethod.element, overriddenMethod.owner, env)
          .addStatement("return %N()", providerMethodName)
          .build()
  }

  private fun AccessMethodImpl.delegateSpec(): FunSpec =
      XFunSpec.overriding(overriddenMethod.element, overriddenMethod.owner, env)
          .addStatement("return scopeDelegate.%N()", overriddenMethod.name)
          .build()

  private fun AccessMethodImpl.propSpec(): PropertySpec {
    val propName =
        with(overriddenMethod.name) {
          when {
            startsWith("get") -> this.substring(3).decapitalize()
            startsWith("is") -> this.substring(2).decapitalize()
            else -> this
          }
        }

    return PropertySpec.builder(
            propName,
            ClassName.bestGuess(overriddenMethod.returnType.qualifiedName),
        )
        .addModifiers(KModifier.OVERRIDE)
        .initializer("%N()", providerMethodName)
        .build()
  }

  private fun AccessMethodImpl.delegatePropSpec(): PropertySpec {
    val propName =
        with(overriddenMethod.name) {
          when {
            startsWith("get") -> this.substring(3).decapitalize()
            startsWith("is") -> this.substring(2).decapitalize()
            else -> this
          }
        }
    return PropertySpec.builder(
            propName,
            ClassName.bestGuess(overriddenMethod.returnType.qualifiedName),
        )
        .addModifiers(KModifier.OVERRIDE)
        .getter(FunSpec.getterBuilder().addStatement("return scopeDelegate.%N", propName).build())
        .build()
  }

  private fun ChildMethodImpl.spec(): FunSpec {
    val childMethodParameters = parameters
    return FunSpec.builder(childMethodName)
        .apply {
          addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
          returns(childClassName.kt)
          childMethodParameters.forEach { addParameter(it.spec()) }
          if (childDependenciesImpl.useStaticClass) {
            // Use static class instantiation
            val args = listOf("this") + childMethodParameters.map { it.name }
            addStatement("return %T(%L(%L))",
                childImplClassName.kt,
                childDependenciesImpl.staticClassName!!,
                args.joinToString(", "))
          } else {
            // Use anonymous class instantiation
            addStatement("return %T(%L)", childImplClassName.kt, childDependenciesImpl.spec())
          }
        }
        .build()
  }

  private fun ChildMethodImpl.delegateSpec(): FunSpec {
    val args = parameters.map { it.name }
    return FunSpec.builder(childMethodName)
        .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
        .returns(childClassName.kt)
        .apply { this@delegateSpec.parameters.forEach { addParameter(it.spec()) } }
        .addStatement("return scopeDelegate.%N(%L)", childMethodName, args.joinToString(", "))
        .build()
  }

  private fun ChildDependenciesImpl.spec(): TypeSpec =
      TypeSpec.anonymousClassBuilder()
          .apply {
            if (isAbstractClass) {
              superclass(childDependenciesClassName.kt)
            } else {
              addSuperinterface(childDependenciesClassName.kt)
            }
            methods.forEach { addFunction(it.spec()) }
          }
          .build()

  private fun ChildDependencyMethodImpl.spec(useStaticClass: Boolean = false): FunSpec =
      FunSpec.builder(name)
          .addModifiers(KModifier.PUBLIC, KModifier.OVERRIDE)
          .returns(returnTypeName.kt)
          .addCode(returnExpression.spec(useStaticClass))
          .build()

  private fun ChildDependencyMethodImpl.ReturnExpression.spec(useStaticClass: Boolean): CodeBlock =
      when (this) {
        is ChildDependencyMethodImpl.ReturnExpression.Parameter -> spec()
        is ChildDependencyMethodImpl.ReturnExpression.Provider -> spec(useStaticClass)
      }

  private fun ChildDependencyMethodImpl.ReturnExpression.Parameter.spec(): CodeBlock =
      CodeBlock.of("return %N", parameterName)

  private fun ChildDependencyMethodImpl.ReturnExpression.Provider.spec(useStaticClass: Boolean): CodeBlock =
      if (useStaticClass) {
        CodeBlock.of("return parentScope.%N()", providerName)
      } else {
        CodeBlock.of("return this@%T.%N()", scopeImplName.kt, providerName)
      }

  private fun ChildMethodImplParameter.spec(): ParameterSpec =
      ParameterSpec.builder(name, typeName.kt).build()

  private fun ScopeProviderMethod.spec(): FunSpec =
      FunSpec.builder(name)
          .apply {
            if (isInternal) {
              addModifiers(KModifier.INTERNAL)
            }
          }
          .returns(scopeClassName.kt)
          .addStatement("return this")
          .build()

  private fun FactoryProviderMethod.specs(
      useSynchronized: Boolean,
      perDependencyLockFields: PerDependencyLockFields?
  ): List<FunSpec> {
    val primarySpec =
        FunSpec.builder(name)
            .addModifiers(KModifier.INTERNAL)
            .returns(returnTypeName.reloadedForTypeArgs(env))
            .addCode(body.spec(useSynchronized, perDependencyLockFields, name))
            .build()
    val spreadSpecs = spreadProviderMethods.map { it.spec() }
    return listOf(primarySpec) + spreadSpecs
  }

  private fun FactoryProviderMethodBody.spec(
      useSynchronized: Boolean,
      perDependencyLockFields: PerDependencyLockFields?,
      providerMethodName: String
  ): CodeBlock =
      when (this) {
        is FactoryProviderMethodBody.Cached -> spec(useSynchronized, perDependencyLockFields, providerMethodName)
        is FactoryProviderMethodBody.Uncached -> spec()
      }

  private fun FactoryProviderMethodBody.Cached.spec(
      useSynchronized: Boolean,
      perDependencyLockFields: PerDependencyLockFields?,
      providerMethodName: String
  ): CodeBlock {
    // Strategy 1: SMART_CACHE with nullable lock pattern
    // Uses null initialization with double-checked locking
    // Lock fields are nullable and initialized based on MotifRuntimeConfig.usePerDependencyLock (checked in constructor)
    if (!useSynchronized) {
        val localFieldName = "_$cacheFieldName"

        // Get the lock field name for this cache field (if per-dependency locks are enabled)
        val lockFieldName = perDependencyLockFields?.locks?.get(cacheFieldName)

        val codeBuilder = CodeBlock.builder()
            .addStatement("var %N = %N", localFieldName, cacheFieldName)
            .beginControlFlow("if (%N == null)", localFieldName)

        // Add synchronized block using nullable lock pattern: lock_foo ?: this
        if (lockFieldName != null) {
            codeBuilder.beginControlFlow("synchronized(%N ?: this)", lockFieldName)
        } else {
            codeBuilder.beginControlFlow("synchronized(this)")
        }

        return codeBuilder
            .addStatement("%N = %N", localFieldName, cacheFieldName)
            .beginControlFlow("if (%N == null)", localFieldName)
            .addStatement("%N = %L", localFieldName, instantiation.spec())
            .beginControlFlow("if (%N == null)", localFieldName)
            .addStatement("throw %T(%S)", NullPointerException::class, "Factory method cannot return null")
            .endControlFlow()
            .addStatement("%N = %N", cacheFieldName, localFieldName)
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement("return %N as %T", localFieldName, returnTypeName.reloadedForTypeArgs(env))
            .build()
    }

    // Strategy 2: VOLATILE_FIELDS - use None.NONE sentinel with nullable lock pattern
    // Lock fields are nullable and initialized based on MotifRuntimeConfig.usePerDependencyLock (checked in constructor)

    // Get the lock field name for this cache field (if per-dependency locks are enabled)
    val lockFieldName = perDependencyLockFields?.locks?.get(cacheFieldName)

    val codeBuilder = CodeBlock.builder()
        .beginControlFlow("if (%N === %T.NONE)", cacheFieldName, ClassName.bestGuess("motif.internal.None"))

    // Add synchronized block using nullable lock pattern: lock_foo ?: this
    if (lockFieldName != null) {
        codeBuilder.beginControlFlow("synchronized(%N ?: this)", lockFieldName)
    } else {
        codeBuilder.beginControlFlow("synchronized(this)")
    }

    return codeBuilder
        .beginControlFlow("if (%N === %T.NONE)", cacheFieldName, ClassName.bestGuess("motif.internal.None"))
        .addStatement("%N = %L", cacheFieldName, instantiation.spec())
        .endControlFlow()
        .endControlFlow()
        .endControlFlow()
        .add("return %N as %T", cacheFieldName, returnTypeName.reloadedForTypeArgs(env))
        .build()
  }

  private fun motif.compiler.TypeName.reloadedForTypeArgs(env: XProcessingEnv): TypeName =
      if (kt is ParameterizedTypeName) {
        kt
      } else {
        // ensures that type arguments get loaded
        KotlinTypeWorkaround.javaToKotlinType(env.requireType(j))
      }

  private fun FactoryProviderMethodBody.Uncached.spec(): CodeBlock =
      CodeBlock.of("return %L", instantiation.spec())

  private fun FactoryProviderInstantiation.spec(): CodeBlock =
      when (this) {
        is FactoryProviderInstantiation.Basic -> spec()
        is FactoryProviderInstantiation.Constructor -> spec()
        is FactoryProviderInstantiation.Binds -> spec()
      }

  private fun FactoryProviderInstantiation.Basic.spec(): CodeBlock {
    val methodName = factoryMethodName.substringBeforeLast('$')
    return if (isStatic) {
      CodeBlock.of("%T.%N%L", objectsClassName.kt, methodName, callProviders.spec())
    } else {
      CodeBlock.of("%N.%N%L", objectsFieldName, methodName, callProviders.spec())
    }
  }

  private fun FactoryProviderInstantiation.Constructor.spec(): CodeBlock =
      CodeBlock.of("%T%L", returnTypeName.kt, callProviders.spec())

  private fun FactoryProviderInstantiation.Binds.spec(): CodeBlock =
      CodeBlock.of("%N()", providerMethodName)

  private fun CallProviders.spec(): String {
    val callString = providerMethodNames.joinToString { "$it()" }
    return "($callString)"
  }

  private fun SpreadProviderMethod.spec(): FunSpec =
      FunSpec.builder(name)
          .apply {
            returns(returnTypeName.kt)
            if (isStatic) {
              addStatement("return %T.%N()", sourceTypeName.kt, spreadMethodName)
            } else {
              addStatement("return %N().%N()", sourceProviderMethodName, spreadMethodName)
            }
          }
          .build()

  private fun DependencyProviderMethod.spec(): FunSpec =
      FunSpec.builder(name)
          .addModifiers(KModifier.INTERNAL)
          .returns(returnTypeName.kt)
          .addStatement("return %N.%N()", dependenciesFieldName, dependencyMethodName)
          .build()

  private fun Dependencies.spec(): TypeSpec {
    val typeSpecBuilder =
        if (methods.any { it.internal }) {
          TypeSpec.classBuilder(className.kt).addModifiers(KModifier.ABSTRACT)
        } else {
          TypeSpec.interfaceBuilder(className.kt)
        }
    return typeSpecBuilder.apply { methods.forEach { addFunction(it.spec()) } }.build()
  }

  private fun DependencyMethod.spec(): FunSpec =
      FunSpec.builder(name)
          .apply {
            qualifier?.let { addAnnotation(it.spec()) }
            addModifiers(if (internal) KModifier.INTERNAL else KModifier.PUBLIC)
            addModifiers(KModifier.ABSTRACT)
            returns(returnTypeName.kt)
            addKdoc(javaDoc.spec())
          }
          .build()

  private fun Qualifier.spec(): AnnotationSpec {
    val className =
        annotation.mirror.type.typeElement?.className?.toKClassName()
            ?: throw IllegalStateException("No ClassName found for: ${annotation.mirror.type}")
    return AnnotationSpec.builder(className)
        .apply {
          annotation.mirror.annotationValues.forEach {
            it.value?.let { value -> addMember("%S", value) }
          }
        }
        .build()
  }

  private fun DependencyMethodJavaDoc.spec(): CodeBlock =
      CodeBlock.builder()
          .apply {
            add("\nRequested from:\n")
            requestedFrom.forEach { add(it.spec()) }
            add("\n")
          }
          .build()

  private fun JavaDocMethodLink.spec(): CodeBlock = CodeBlock.of("* [%L.%N]\n", owner, methodName)

  private fun ObjectsImpl.spec(): TypeSpec =
      TypeSpec.classBuilder(className.kt)
          .apply {
            addModifiers(KModifier.PRIVATE)
            if (isInterface) {
              addSuperinterface(superClassName.kt)
            } else {
              superclass(superClassName.kt)
            }
            abstractMethods.forEach { addFunction(it.spec()) }
          }
          .build()

  private fun ObjectsAbstractMethod.spec(): FunSpec =
      XFunSpec.overriding(overriddenMethod.element, overriddenMethod.owner, env)
          .addStatement("throw %T()", UnsupportedOperationException::class)
          .build()

  private fun suppressAnnotationSpec(vararg names: String): AnnotationSpec =
      AnnotationSpec.builder(Suppress::class.java)
          .addMember(names.joinToString(", ") { "%S" }, *names)
          .build()

  // ===== Static Dependency Class for Selective Caching =====

  private fun StaticDependencyClass.spec(): TypeSpec {
    return TypeSpec.classBuilder(className)
        .apply {
          addModifiers(KModifier.PRIVATE)
          if (isAbstractClass) {
            // Abstract class for internal dependencies
            superclass(childDependenciesClassName.kt)
          } else {
            addSuperinterface(childDependenciesClassName.kt)
          }

          // Add properties for parent scope and method parameters
          addProperty(
              PropertySpec.builder("parentScope", parentScopeClassName.kt, KModifier.PRIVATE)
                  .initializer("parentScope")
                  .build()
          )
          methodParameters.forEach { param ->
            addProperty(
                PropertySpec.builder(param.name, param.typeName.kt, KModifier.PRIVATE)
                    .initializer(param.name)
                    .build()
            )
          }

          // Add primary constructor
          primaryConstructor(constructorSpec())

          // Add dependency methods (using static class context)
          methods.forEach { addFunction(it.spec(useStaticClass = true)) }
        }
        .build()
  }

  private fun StaticDependencyClass.constructorSpec(): FunSpec {
    return FunSpec.constructorBuilder()
        .apply {
          addParameter("parentScope", parentScopeClassName.kt)
          methodParameters.forEach { param ->
            addParameter(param.name, param.typeName.kt)
          }
        }
        .build()
  }
}

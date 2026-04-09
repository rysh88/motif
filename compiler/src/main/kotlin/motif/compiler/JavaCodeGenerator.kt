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
import androidx.room.compiler.processing.compat.XConverters.toJavac
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.TypeSpec
import com.squareup.kotlinpoet.javapoet.KotlinPoetJavaPoetPreview
import com.uber.xprocessing.ext.isKotlinSource
import com.uber.xprocessing.ext.withRawTypeFix
import motif.MotifLock
import motif.MotifRuntimeConfig
import motif.internal.None
import javax.lang.model.element.Modifier
import javax.lang.model.type.DeclaredType

object JavaCodeGenerator {

  fun generate(scopeImpl: ScopeImpl): JavaFile {
    val typeSpec: TypeSpec = scopeImpl.spec()
    return JavaFile.builder(scopeImpl.className.j.packageName(), typeSpec).build()
  }

  private fun ScopeImpl.spec(): TypeSpec {
      // Special handling for runtime-selectable wrapper
      if (isRuntimeSelectableWrapper) {
        return dynamicWrapperSpec()
      }

      return TypeSpec.classBuilder(className.j)
          .apply {
            addAnnotation(scopeImplAnnotation.spec())
            addModifiers(Modifier.PUBLIC)
            addSuperinterface(superClassName.j)
            objectsField?.let { addField(it.spec()) }
            addField(dependenciesField.spec())

            // Add cache fields (both strategies use volatile for memory visibility)
            cacheFields.forEach { addField(it.spec(isBaselineStrategy)) }

            // Add per-dependency lock fields (nullable, initialized conditionally)
            perDependencyLockFields?.let { lockFields ->
                lockFields.locks.values.forEach { lockFieldName ->
                    addField(
                        FieldSpec.builder(MotifLock::class.java, lockFieldName, Modifier.PRIVATE)
                            .build()
                    )
                }
            }
            addMethod(constructor.spec(perDependencyLockFields))
            alternateConstructor?.let { addMethod(it.spec()) }
            accessMethodImpls.forEach { addMethod(it.spec()) }
            childMethodImpls.forEach { addMethod(it.spec()) }
            addMethod(scopeProviderMethod.spec())
            factoryProviderMethods.forEach {
                addMethods(it.specs(isBaselineStrategy, perDependencyLockFields))
            }
            dependencyProviderMethods.forEach { addMethod(it.spec()) }
            dependencies?.let { addType(it.spec()) }
            objectsImpl?.let { addType(it.spec()) }
            staticDependencyClasses.forEach { addType(it.spec()) }
          }
          .build()
  }

  /**
   * Generates a dynamic wrapper class for RUNTIME_SELECTABLE that delegates to either
   * _SmartCache or _Baseline implementation based on MotifRuntimeConfig.
   */
  private fun ScopeImpl.dynamicWrapperSpec(): TypeSpec =
      TypeSpec.classBuilder(className.j)
          .apply {
            addAnnotation(scopeImplAnnotation.spec())
            addModifiers(Modifier.PUBLIC)
            addSuperinterface(superClassName.j)

            // Add a field for the delegate scope implementation
            addField(
                FieldSpec.builder(superClassName.j, "scopeDelegate", Modifier.PRIVATE, Modifier.FINAL)
                    .build()
            )

            // Add constructor that selects implementation based on MotifRuntimeConfig
            addMethod(
                MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(dependenciesField.dependenciesClassName.j, "dependencies")
                    .addComment("Delegate to either _SmartCache or _Baseline variant based on MotifRuntimeConfig.cachingStrategy")
                    .beginControlFlow("switch (\$T.cachingStrategy)", MotifRuntimeConfig::class.java)
                    .addStatement("case SMART_CACHE:\nthis.scopeDelegate = new \$T(dependencies);\nbreak", getVariantClassName("_SmartCache"))
                    .addStatement("default:\nthis.scopeDelegate = new \$T(dependencies);\nbreak", getVariantClassName("_Baseline"))
                    .endControlFlow()
                    .build()
            )

            // Add no-arg constructor if Dependencies has no methods
            alternateConstructor?.let {
                addMethod(
                    MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addComment("Delegate to either _SmartCache or _Baseline variant based on MotifRuntimeConfig.cachingStrategy")
                        .beginControlFlow("switch (\$T.cachingStrategy)", MotifRuntimeConfig::class.java)
                        .addStatement("case SMART_CACHE:\nthis.scopeDelegate = new \$T();\nbreak", getVariantClassName("_SmartCache"))
                        .addStatement("default:\nthis.scopeDelegate = new \$T();\nbreak", getVariantClassName("_Baseline"))
                        .endControlFlow()
                        .build()
                )
            }

            // Delegate all accessor methods to the delegate
            accessMethodImpls.forEach { addMethod(it.delegateSpec()) }

            // Add child methods that delegate to the delegate
            childMethodImpls.forEach { addMethod(it.delegateSpec()) }

            // Add scope provider method
            addMethod(
                MethodSpec.methodBuilder(scopeProviderMethod.name)
                    .returns(scopeProviderMethod.scopeClassName.j)
                    .addStatement("return this")
                    .build()
            )

            // Add Dependencies interface so variant implementations can reference it
            dependencies?.let { addType(it.spec()) }
          }
          .build()

  private fun ScopeImpl.getVariantClassName(suffix: String): com.squareup.javapoet.ClassName =
      com.squareup.javapoet.ClassName.get(
          className.j.packageName(),
          className.j.simpleName() + suffix
      )

  private fun ScopeImplAnnotation.spec(): AnnotationSpec =
      AnnotationSpec.builder(motif.ScopeImpl::class.java)
          .apply {
            if (children.isEmpty()) {
              addMember("children", "{}")
            } else {
              children.forEach { child -> addMember("children", "\$T.class", child.j) }
            }
            addMember("scope", "\$T.class", scopeClassName.j)
            addMember("dependencies", "\$T.class", dependenciesClassName.j)
          }
          .build()

  private fun ObjectsField.spec(): FieldSpec =
      FieldSpec.builder(objectsClassName.j, name, Modifier.PRIVATE, Modifier.FINAL)
          .initializer("new \$T()", objectsImplClassName.j)
          .build()

  private fun DependenciesField.spec(): FieldSpec =
      FieldSpec.builder(dependenciesClassName.j, name, Modifier.PRIVATE, Modifier.FINAL).build()

  private fun CacheField.spec(isBaselineStrategy: Boolean): FieldSpec {
      // Both SMART_CACHE and BASELINE use volatile for proper memory visibility
      val modifiers = mutableListOf(Modifier.PRIVATE, Modifier.VOLATILE)

      return FieldSpec.builder(Object::class.java, name, *modifiers.toTypedArray())
          .apply {
              if (isBaselineStrategy) {
                  // BASELINE: Use None.NONE sentinel pattern with volatile
                  initializer("\$T.NONE", None::class.java)
              }
              // SMART_CACHE: Use null initialization with volatile (no initializer needed)
          }
          .build()
  }

  private fun Constructor.spec(perDependencyLockFields: PerDependencyLockFields?): MethodSpec =
      MethodSpec.constructorBuilder()
          .addModifiers(Modifier.PUBLIC)
          .addParameter(dependenciesClassName.j, dependenciesParameterName)
          .apply {
              // Cache config value to ensure consistent lock initialization
              if (perDependencyLockFields?.locks?.isNotEmpty() == true) {
                  addStatement(
                      "final boolean usePerDependencyLock = \$T.usePerDependencyLock",
                      MotifRuntimeConfig::class.java
                  )

                  // Initialize lock fields BEFORE assigning dependencies to prevent race conditions
                  perDependencyLockFields.locks.values.forEach { lockFieldName ->
                      addStatement(
                          "this.\$N = usePerDependencyLock ? new \$T() : null",
                          lockFieldName,
                          MotifLock::class.java
                      )
                  }
              }
          }
          .addStatement("this.\$N = \$N", dependenciesFieldName, dependenciesParameterName)
          .build()

  private fun AlternateConstructor.spec(): MethodSpec =
      MethodSpec.constructorBuilder()
          .addModifiers(Modifier.PUBLIC)
          .addStatement("this(new \$T() {})", dependenciesClassName.j)
          .build()

  private fun AccessMethodImpl.spec(): MethodSpec =
      MethodSpec.overriding(
              overriddenMethod.element.toJavac(),
              overriddenMethod.owner.toJavac() as DeclaredType,
              env.toJavac().typeUtils,
          )
          .addStatement("return \$N()", providerMethodName)
          .build()

  private fun AccessMethodImpl.delegateSpec(): MethodSpec =
      MethodSpec.overriding(
              overriddenMethod.element.toJavac(),
              overriddenMethod.owner.toJavac() as DeclaredType,
              env.toJavac().typeUtils,
          )
          .addStatement("return scopeDelegate.\$N()", overriddenMethod.name)
          .build()

  private fun ChildMethodImpl.spec(): MethodSpec =
      MethodSpec.methodBuilder(childMethodName)
          .apply {
            addAnnotation(Override::class.java)
            addModifiers(Modifier.PUBLIC)
            returns(childClassName.j)
            this@spec.parameters.forEach { addParameter(it.spec()) }
            if (childDependenciesImpl.useStaticClass) {
              // Use static class instantiation
              val args = listOf("this") + this@spec.parameters.map { it.name }
              addStatement("return new \$T(new \$L(\$L))",
                  childImplClassName.j,
                  childDependenciesImpl.staticClassName,
                  args.joinToString(", "))
            } else {
              // Use anonymous class instantiation
              addStatement("return new \$T(\$L)", childImplClassName.j, childDependenciesImpl.spec())
            }
          }
          .build()

  private fun ChildMethodImpl.delegateSpec(): MethodSpec {
    val params = parameters.map { ParameterSpec.builder(it.typeName.j, it.name, Modifier.FINAL).build() }
    val args = parameters.map { it.name }
    return MethodSpec.methodBuilder(childMethodName)
        .addAnnotation(Override::class.java)
        .addModifiers(Modifier.PUBLIC)
        .returns(childClassName.j)
        .addParameters(params)
        .addStatement("return scopeDelegate.\$N(\$L)", childMethodName, args.joinToString(", "))
        .build()
  }

  @OptIn(KotlinPoetJavaPoetPreview::class)
  private fun ChildDependenciesImpl.spec(): TypeSpec {
    val isKotlinDepInterface = env.findTypeElement(childDependenciesClassName.j).isKotlinSource(env)
    return TypeSpec.anonymousClassBuilder("")
        .apply {
          addSuperinterface(childDependenciesClassName.j)
          methods.forEach { addMethod(it.spec(env, isKotlinDepInterface)) }
        }
        .build()
  }

  private fun ChildDependencyMethodImpl.spec(
      env: XProcessingEnv,
      isKotlinDependenciesInterface: Boolean,
      useStaticClass: Boolean = false,
  ): MethodSpec =
      MethodSpec.methodBuilder(name)
          .addAnnotation(Override::class.java)
          .addModifiers(Modifier.PUBLIC)
          .returns(
              if (isKotlinDependenciesInterface) {
                returnTypeName.j.withRawTypeFix(env)
              } else {
                returnTypeName.j
              },
          )
          .addStatement(returnExpression.spec(useStaticClass))
          .build()

  private fun ChildDependencyMethodImpl.ReturnExpression.spec(useStaticClass: Boolean): CodeBlock =
      when (this) {
        is ChildDependencyMethodImpl.ReturnExpression.Parameter -> spec()
        is ChildDependencyMethodImpl.ReturnExpression.Provider -> spec(useStaticClass)
      }

  private fun ChildDependencyMethodImpl.ReturnExpression.Parameter.spec(): CodeBlock =
      CodeBlock.of("return \$N", parameterName)

  private fun ChildDependencyMethodImpl.ReturnExpression.Provider.spec(useStaticClass: Boolean): CodeBlock =
      if (useStaticClass) {
        CodeBlock.of("return parentScope.\$N()", providerName)
      } else {
        CodeBlock.of("return \$T.this.\$N()", scopeImplName.j, providerName)
      }

  private fun ChildMethodImplParameter.spec(): ParameterSpec =
      ParameterSpec.builder(typeName.j, name, Modifier.FINAL).build()

  private fun ScopeProviderMethod.spec(): MethodSpec =
      MethodSpec.methodBuilder(name).returns(scopeClassName.j).addStatement("return this").build()

  private fun FactoryProviderMethod.specs(
      isBaselineStrategy: Boolean,
      perDependencyLockFields: PerDependencyLockFields?
  ): List<MethodSpec> {
    val primarySpec =
        MethodSpec.methodBuilder(name)
            .returns(returnTypeName.j)
            .addCode(body.spec(isBaselineStrategy, perDependencyLockFields))
            .build()
    val spreadSpecs = spreadProviderMethods.map { it.spec() }
    return listOf(primarySpec) + spreadSpecs
  }

  private fun FactoryProviderMethodBody.spec(
      isBaselineStrategy: Boolean,
      perDependencyLockFields: PerDependencyLockFields?,
  ): CodeBlock =
      when (this) {
        is FactoryProviderMethodBody.Cached -> spec(isBaselineStrategy, perDependencyLockFields)
        is FactoryProviderMethodBody.Uncached -> spec()
      }

  private fun FactoryProviderMethodBody.Cached.spec(
      isBaselineStrategy: Boolean,
      perDependencyLockFields: PerDependencyLockFields?,
  ): CodeBlock {
    // SMART_CACHE: Uses null initialization with double-checked locking
    if (!isBaselineStrategy) {
        val localFieldName = "_$cacheFieldName"

        // Get the lock field name for this cache field (if per-dependency locks are enabled)
        val lockFieldName = perDependencyLockFields?.locks?.get(cacheFieldName)

        val codeBuilder = CodeBlock.builder()
            .add("Object $localFieldName = \$N;\n", cacheFieldName)
            .beginControlFlow("if (\$N == null)", localFieldName)

        // Add synchronized block using nullable lock pattern: lock_foo != null ? lock_foo : this
        if (lockFieldName != null) {
            codeBuilder.beginControlFlow(
                "synchronized (\$N != null ? \$N : this)",
                lockFieldName,
                lockFieldName
            )
        } else {
            codeBuilder.beginControlFlow("synchronized (this)")
        }

        return codeBuilder
            .add("\$N = \$N;\n", localFieldName, cacheFieldName)
            .beginControlFlow("if (\$N == null)", localFieldName)
            .add("\$N = \$L;\n", localFieldName, instantiation.spec())
            .beginControlFlow("if (\$N == null)", localFieldName)
            .add(
                "throw new \$T(\$S);\n",
                NullPointerException::class.java,
                "Factory method cannot return null",
            )
            .endControlFlow()
            .add("\$N = \$N;\n", cacheFieldName, localFieldName)
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .add("return (\$T) \$N;\n", returnTypeName.j, localFieldName)
            .build()
    }

    // BASELINE: Use None.NONE sentinel with synchronized blocks

    // Get the lock field name for this cache field (if per-dependency locks are enabled)
    val lockFieldName = perDependencyLockFields?.locks?.get(cacheFieldName)

    val codeBuilder = CodeBlock.builder()
        .beginControlFlow("if (\$N == \$T.NONE)", cacheFieldName, None::class.java)

    // Add synchronized block using nullable lock pattern: lock_foo != null ? lock_foo : this
    if (lockFieldName != null) {
        codeBuilder.beginControlFlow(
            "synchronized (\$N != null ? \$N : this)",
            lockFieldName,
            lockFieldName
        )
    } else {
        codeBuilder.beginControlFlow("synchronized (this)")
    }

    return codeBuilder
        .beginControlFlow("if (\$N == \$T.NONE)", cacheFieldName, None::class.java)
        .add("\$N = \$L;\n", cacheFieldName, instantiation.spec())
        .endControlFlow()
        .endControlFlow()
        .endControlFlow()
        .add("return (\$T) \$N;\n", returnTypeName.j, cacheFieldName)
        .build()
  }

  private fun FactoryProviderMethodBody.Uncached.spec(): CodeBlock =
      CodeBlock.of("return \$L;\n", instantiation.spec())

  private fun FactoryProviderInstantiation.spec(): CodeBlock =
      when (this) {
        is FactoryProviderInstantiation.Basic -> spec()
        is FactoryProviderInstantiation.Constructor -> spec()
        is FactoryProviderInstantiation.Binds -> spec()
      }

  private fun FactoryProviderInstantiation.Basic.spec(): CodeBlock =
      if (isStatic) {
        CodeBlock.of("\$T.\$N\$L", objectsClassName.j, factoryMethodName, callProviders.spec())
      } else {
        CodeBlock.of("\$N.\$N\$L", objectsFieldName, factoryMethodName, callProviders.spec())
      }

  private fun FactoryProviderInstantiation.Constructor.spec(): CodeBlock =
      CodeBlock.of("new \$T\$L", returnTypeName.j, callProviders.spec())

  private fun FactoryProviderInstantiation.Binds.spec(): CodeBlock =
      CodeBlock.of("\$N()", providerMethodName)

  private fun CallProviders.spec(): String {
    val callString = providerMethodNames.joinToString { "$it()" }
    return "($callString)"
  }

  private fun SpreadProviderMethod.spec(): MethodSpec =
      MethodSpec.methodBuilder(name)
          .returns(returnTypeName.j)
          .addStatement("return \$N().\$N()", sourceProviderMethodName, spreadMethodName)
          .build()

  private fun DependencyProviderMethod.spec(): MethodSpec =
      MethodSpec.methodBuilder(name)
          .returns(returnTypeName.j)
          .addStatement("return \$N.\$N()", dependenciesFieldName, dependencyMethodName)
          .build()

  private fun Dependencies.spec(): TypeSpec =
      TypeSpec.interfaceBuilder(className.j)
          .apply {
            addModifiers(Modifier.PUBLIC)
            methods.forEach { addMethod(it.spec()) }
          }
          .build()

  private fun DependencyMethod.spec(): MethodSpec =
      MethodSpec.methodBuilder(name)
          .apply {
            qualifier?.let { addAnnotation(it.spec()) }
            addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            returns(returnTypeName.j)
            addJavadoc(javaDoc.spec())
          }
          .build()

  private fun Qualifier.spec(): AnnotationSpec {
    val className =
        annotation.mirror.type.typeElement?.className
            ?: throw IllegalStateException("No ClassName found for: ${annotation.mirror.type}")
    return AnnotationSpec.builder(className)
        .apply {
          annotation.mirror.annotationValues.forEach {
            it.value?.let { value -> addMember(it.name, "\$S", value) }
          }
        }
        .build()
  }

  private fun DependencyMethodJavaDoc.spec(): CodeBlock =
      CodeBlock.builder()
          .apply {
            add("<ul>\nRequested from:\n")
            requestedFrom.forEach { add(it.spec()) }
            add("</ul>\n")
          }
          .build()

  private fun JavaDocMethodLink.spec(): CodeBlock {
    val parameterTypeString = parameterTypes.joinToString()
    return CodeBlock.of("<li>{@link \$L#\$N(\$L)}</li>\n", owner, methodName, parameterTypeString)
  }

  private fun ObjectsImpl.spec(): TypeSpec =
      TypeSpec.classBuilder(className.j)
          .apply {
            addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            if (isInterface) {
              addSuperinterface(superClassName.j)
            } else {
              superclass(superClassName.j)
            }
            abstractMethods.forEach { addMethod(it.spec()) }
          }
          .build()

  private fun ObjectsAbstractMethod.spec(): MethodSpec =
      MethodSpec.overriding(
              overriddenMethod.element.toJavac(),
              overriddenMethod.owner.toJavac() as DeclaredType,
              env.toJavac().typeUtils,
          )
          .addStatement("throw new \$T()", UnsupportedOperationException::class.java)
          .build()


  // ===== Static Dependency Classes =====

  @OptIn(KotlinPoetJavaPoetPreview::class)
  private fun StaticDependencyClass.spec(): TypeSpec {
    val isKotlinDepInterface = env.findTypeElement(childDependenciesClassName.j).isKotlinSource(env)
    return TypeSpec.classBuilder(className)
        .apply {
          addModifiers(Modifier.PRIVATE, Modifier.STATIC)
          if (isAbstractClass) {
            // Abstract class for internal dependencies
            superclass(childDependenciesClassName.j)
          } else {
            addSuperinterface(childDependenciesClassName.j)
          }

          // Add fields for parent scope and method parameters
          addField(
              FieldSpec.builder(parentScopeClassName.j, "parentScope", Modifier.PRIVATE, Modifier.FINAL)
                  .build()
          )
          methodParameters.forEach { param ->
            addField(
                FieldSpec.builder(param.typeName.j, param.name, Modifier.PRIVATE, Modifier.FINAL)
                    .build()
            )
          }

          addMethod(constructorSpec())

          // Add dependency methods (using static class context)
          methods.forEach { addMethod(it.spec(env, isKotlinDepInterface, useStaticClass = true)) }
        }
        .build()
  }

  private fun StaticDependencyClass.constructorSpec(): MethodSpec {
    return MethodSpec.constructorBuilder()
        .apply {
          addParameter(parentScopeClassName.j, "parentScope")
          addStatement("this.parentScope = parentScope")
          methodParameters.forEach { param ->
            addParameter(param.typeName.j, param.name)
            addStatement("this.\$N = \$N", param.name, param.name)
          }
        }
        .build()
  }
}

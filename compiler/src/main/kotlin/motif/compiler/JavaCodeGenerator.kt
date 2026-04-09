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
import javax.lang.model.element.Modifier
import javax.lang.model.type.DeclaredType
import motif.internal.None

object JavaCodeGenerator {

  fun generate(scopeImpl: ScopeImpl): JavaFile {
    val typeSpec: TypeSpec = scopeImpl.spec()
    return JavaFile.builder(scopeImpl.className.j.packageName(), typeSpec).build()
  }

  private fun ScopeImpl.spec(): TypeSpec {
      // Special handling for dynamic wrapper
      if (isDynamicWrapper) {
        return dynamicWrapperSpec()
      }

      return TypeSpec.classBuilder(className.j)
          .apply {
            addAnnotation(scopeImplAnnotation.spec())
            addModifiers(Modifier.PUBLIC)
            addSuperinterface(superClassName.j)
            objectsField?.let { addField(it.spec()) }
            addField(dependenciesField.spec())

            // Add individual cache fields (volatile for VOLATILE_FIELDS, plain for SMART_CACHE)
            cacheFields.forEach { addField(it.spec(useSynchronized)) }

            // Add per-dependency lock fields (nullable, initialized conditionally in constructor)
            perDependencyLockFields?.let { lockFields ->
                lockFields.locks.values.forEach { lockFieldName ->
                    addField(
                        FieldSpec.builder(com.squareup.javapoet.ClassName.get("motif", "MotifLock"), lockFieldName, Modifier.PRIVATE, Modifier.FINAL)
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
                addMethods(it.specs(useSynchronized, perDependencyLockFields))
            }
            dependencyProviderMethods.forEach { addMethod(it.spec()) }
            dependencies?.let { addType(it.spec()) }
            objectsImpl?.let { addType(it.spec()) }
            staticDependencyClasses.forEach { addType(it.spec()) }
          }
          .build()
  }

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
                    .beginControlFlow("switch (\$T.cachingStrategy)", com.squareup.javapoet.ClassName.get("motif", "MotifRuntimeConfig"))
                    .addStatement("case SMART_CACHE:\nthis.scopeDelegate = new \$T(dependencies);\nbreak", getVariantClassName("_SmartCache"))
                    .addStatement("default:\nthis.scopeDelegate = new \$T(dependencies);\nbreak", getVariantClassName("_VolatileFields"))
                    .endControlFlow()
                    .build()
            )

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

  private fun CacheField.spec(useSynchronized: Boolean): FieldSpec {
      // Both SMART_CACHE and VOLATILE_FIELDS use volatile for proper memory visibility
      val modifiers = mutableListOf(Modifier.PRIVATE, Modifier.VOLATILE)

      if (useSynchronized) {
        // VOLATILE_FIELDS: Use None.NONE sentinel pattern with volatile
        return FieldSpec.builder(Object::class.java, name, *modifiers.toTypedArray())
            .initializer("\$T.NONE", com.squareup.javapoet.ClassName.get("motif.internal", "None"))
            .build()
      } else {
        // SMART_CACHE: Use null initialization with volatile
        return FieldSpec.builder(Object::class.java, name, *modifiers.toTypedArray())
            .build()
      }
  }

  private fun Constructor.spec(perDependencyLockFields: PerDependencyLockFields?): MethodSpec =
      MethodSpec.constructorBuilder()
          .addModifiers(Modifier.PUBLIC)
          .addParameter(dependenciesClassName.j, dependenciesParameterName)
          .addStatement("this.\$N = \$N", dependenciesFieldName, dependenciesParameterName)
          .apply {
              // Initialize per-dependency lock fields conditionally
              // Check MotifRuntimeConfig.usePerDependencyLock once at construction time
              perDependencyLockFields?.locks?.values?.forEach { lockFieldName ->
                  addStatement(
                      "this.\$N = \$T.usePerDependencyLock ? new \$T() : null",
                      lockFieldName,
                      com.squareup.javapoet.ClassName.get("motif", "MotifRuntimeConfig"),
                      com.squareup.javapoet.ClassName.get("motif", "MotifLock")
                  )
              }
          }
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
              // Use static class instantiation: new PhotoGridScopeDependencies(this, parent)
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
      useSynchronized: Boolean,
      perDependencyLockFields: PerDependencyLockFields?
  ): List<MethodSpec> {
    val primarySpec =
        MethodSpec.methodBuilder(name)
            .returns(returnTypeName.j)
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

        // Generate the lock expression: lock_foo != null ? lock_foo : this
        val lockExpression = if (lockFieldName != null) {
            "\$N != null ? \$N : this"
        } else {
            "this"
        }

        val codeBuilder = CodeBlock.builder()
            .add("Object $localFieldName = \$N;\n", cacheFieldName)
            .beginControlFlow("if (\$N == null)", localFieldName)

        // Add synchronized block using nullable lock pattern
        if (lockFieldName != null) {
            codeBuilder.beginControlFlow(
                "synchronized ($lockExpression)",
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

    // Strategy 2: VOLATILE_FIELDS - use None.NONE sentinel with nullable lock pattern
    // Lock fields are nullable and initialized based on MotifRuntimeConfig.usePerDependencyLock (checked in constructor)

    // Get the lock field name for this cache field (if per-dependency locks are enabled)
    val lockFieldName = perDependencyLockFields?.locks?.get(cacheFieldName)

    // Generate the lock expression: lock_foo != null ? lock_foo : this
    val lockExpression = if (lockFieldName != null) {
        "\$N != null ? \$N : this"
    } else {
        "this"
    }

    val codeBuilder = CodeBlock.builder()
        .beginControlFlow("if (\$N == \$T.NONE)", cacheFieldName, com.squareup.javapoet.ClassName.get("motif.internal", "None"))

    // Add synchronized block using nullable lock pattern
    if (lockFieldName != null) {
        codeBuilder.beginControlFlow(
            "synchronized ($lockExpression)",
            lockFieldName,
            lockFieldName
        )
    } else {
        codeBuilder.beginControlFlow("synchronized (this)")
    }

    return codeBuilder
        .beginControlFlow("if (\$N == \$T.NONE)", cacheFieldName, com.squareup.javapoet.ClassName.get("motif.internal", "None"))
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


  // ===== Static Dependency Class for Selective Caching =====

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

          // Add constructor
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

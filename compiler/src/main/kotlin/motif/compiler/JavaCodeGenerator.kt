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
    // Generate wrapper class for RUNTIME_SELECTABLE strategy
    if (isRuntimeSelectableWrapper) {
      return wrapperSpec()
    }

    // Generate normal implementation or variant class
    return TypeSpec.classBuilder(
          // Append variant suffix for RUNTIME_SELECTABLE strategy variants
          if (variantSuffix != null) {
              com.squareup.javapoet.ClassName.get(
                  className.j.packageName(),
                  className.j.simpleName() + variantSuffix
              )
          } else {
              className.j
          }
      )
          .apply {
            addAnnotation(scopeImplAnnotation.spec())
            addModifiers(Modifier.PUBLIC)
            addSuperinterface(superClassName.j)
            objectsField?.let { addField(it.spec()) }
            addField(dependenciesField.spec())
            cacheFields.forEach { addField(it.spec(isBaselineStrategy)) }

            // Add per-dependency lock fields for strategies that support them
            // (BASELINE_WITH_LOCK_SELECTABLE and SMART_CACHE)
            perDependencyLockFields?.let { lockFields ->
                // Add config value cache to ensure consistent lock initialization
                if (lockFields.locks.isNotEmpty()) {
                    addField(
                        FieldSpec.builder(Boolean::class.javaPrimitiveType, "usePerDependencyLocking", Modifier.PRIVATE, Modifier.FINAL)
                            .initializer("%T.usePerDependencyLock", com.squareup.javapoet.ClassName.get("motif", "MotifRuntimeConfig"))
                            .build()
                    )
                }

                // Add nullable lock fields, conditionally initialized
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
            factoryProviderMethods.forEach { addMethods(it.specs(isBaselineStrategy, perDependencyLockFields)) }
            dependencyProviderMethods.forEach { addMethod(it.spec()) }
            staticDependencyClasses.forEach { addType(it.spec()) }
            dependencies?.let { addType(it.spec()) }
            objectsImpl?.let { addType(it.spec()) }
          }
          .build()
  }

  /**
   * Generates a runtime wrapper class for RUNTIME_SELECTABLE strategy.
   * The wrapper delegates to variant implementations based on MotifRuntimeConfig.cachingStrategy.
   */
  private fun ScopeImpl.wrapperSpec(): TypeSpec {
    val delegateField = FieldSpec.builder(superClassName.j, "delegate", Modifier.PRIVATE, Modifier.FINAL)
        .initializer(
            CodeBlock.builder()
                .beginControlFlow("(%T.cachingStrategy == %T.BASELINE_WITH_LOCK_SELECTABLE)",
                    com.squareup.javapoet.ClassName.get("motif", "MotifRuntimeConfig"),
                    com.squareup.javapoet.ClassName.get("motif", "CachingStrategy"))
                .add("? new %T(dependencies)\n",
                    com.squareup.javapoet.ClassName.get(
                        className.j.packageName(),
                        className.j.simpleName() + "_BaselineSelectableLock"
                    ))
                .nextControlFlow("else if (%T.cachingStrategy == %T.SMART_CACHE)",
                    com.squareup.javapoet.ClassName.get("motif", "MotifRuntimeConfig"),
                    com.squareup.javapoet.ClassName.get("motif", "CachingStrategy"))
                .add("? new %T(dependencies)\n",
                    com.squareup.javapoet.ClassName.get(
                        className.j.packageName(),
                        className.j.simpleName() + "_SmartCache"
                    ))
                .nextControlFlow("else")
                .add(": new %T(dependencies))",
                    com.squareup.javapoet.ClassName.get(
                        className.j.packageName(),
                        className.j.simpleName() + "_BaselineSelectableLock"
                    ))
                .endControlFlow()
                .build()
        )
        .build()

    return TypeSpec.classBuilder(className.j)
        .apply {
          addAnnotation(scopeImplAnnotation.spec())
          addModifiers(Modifier.PUBLIC)
          addSuperinterface(superClassName.j)

          // Add delegate field
          addField(delegateField)

          // Add dependencies field
          addField(dependenciesField.spec())

          // Add constructor
          addMethod(constructor.spec(null))

          // Add alternate constructor if present
          alternateConstructor?.let { addMethod(it.spec()) }

          // Delegate all access methods
          accessMethodImpls.forEach { accessMethod ->
            addMethod(
                MethodSpec.overriding(
                        accessMethod.overriddenMethod.element.toJavac(),
                        accessMethod.overriddenMethod.owner.toJavac() as DeclaredType,
                        accessMethod.env.toJavac().typeUtils,
                    )
                    .addStatement("return delegate.\$N()", accessMethod.overriddenMethod.name)
                    .build()
            )
          }

          // Delegate all child methods
          childMethodImpls.forEach { childMethod ->
            addMethod(
                MethodSpec.methodBuilder(childMethod.childMethodName)
                    .addAnnotation(Override::class.java)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(childMethod.childClassName.j)
                    .apply {
                      childMethod.parameters.forEach { param ->
                        addParameter(param.spec())
                      }
                    }
                    .addStatement(
                        "return delegate.\$N(\$L)",
                        childMethod.childMethodName,
                        childMethod.parameters.joinToString(", ") { it.name }
                    )
                    .build()
            )
          }

          // Add Objects nested class if present (for variants to reference)
          objectsImpl?.let { addType(it.spec()) }
        }
        .build()
  }

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

  private fun CacheField.spec(isBaselineStrategy: Boolean): FieldSpec =
      if (isBaselineStrategy) {
        // BASELINE: Use None.NONE sentinel
        FieldSpec.builder(Object::class.java, name, Modifier.PRIVATE, Modifier.VOLATILE)
            .initializer("\$T.NONE", None::class.java)
            .build()
      } else {
        // SMART_CACHE: Use null initialization
        FieldSpec.builder(Object::class.java, name, Modifier.PRIVATE, Modifier.VOLATILE).build()
      }

  private fun Constructor.spec(perDependencyLockFields: PerDependencyLockFields?): MethodSpec {
    val builder = MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(dependenciesClassName.j, dependenciesParameterName)
        .addStatement("this.\$N = \$N", dependenciesFieldName, dependenciesParameterName)

    // Initialize lock fields conditionally based on usePerDependencyLocking
    perDependencyLockFields?.locks?.values?.forEach { lockFieldName ->
        builder.addStatement("this.\$N = usePerDependencyLocking ? new \$T() : null", lockFieldName, com.squareup.javapoet.ClassName.get("motif", "MotifLock"))
    }

    return builder.build()
  }

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

  private fun ChildMethodImpl.spec(): MethodSpec =
      MethodSpec.methodBuilder(childMethodName)
          .apply {
            addAnnotation(Override::class.java)
            addModifiers(Modifier.PUBLIC)
            returns(childClassName.j)
            this@spec.parameters.forEach { addParameter(it.spec()) }
            addStatement("return new \$T(\$L)", childImplClassName.j, childDependenciesImpl.spec())
          }
          .build()

  @OptIn(KotlinPoetJavaPoetPreview::class)
  private fun ChildDependenciesImpl.spec(): CodeBlock {
    if (useStaticClass) {
      // Use static class instantiation
      val args = mutableListOf<Any>()
      val argFormats = mutableListOf<String>()

      // First argument is always "this" (parent scope reference)
      args.add("this")
      argFormats.add("\$N")

      // Add parameter arguments based on ChildDependencyMethodImpl that use parameters
      methods.forEach { method ->
        when (val expr = method.returnExpression) {
          is ChildDependencyMethodImpl.ReturnExpression.Parameter -> {
            args.add(expr.parameterName)
            argFormats.add("\$N")
          }
          else -> {} // Providers don't need args, they use parentScope field
        }
      }

      return CodeBlock.of("new \$N(${argFormats.joinToString(", ")})", staticClassName!!, *args.toTypedArray())
    } else {
      // Use anonymous class (original behavior)
      return CodeBlock.of("\$L", spec_anonymousClass())
    }
  }

  @OptIn(KotlinPoetJavaPoetPreview::class)
  private fun ChildDependenciesImpl.spec_anonymousClass(): TypeSpec {
    val isKotlinDepInterface = env.findTypeElement(childDependenciesClassName.j).isKotlinSource(env)
    return TypeSpec.anonymousClassBuilder("")
        .apply {
          addSuperinterface(childDependenciesClassName.j)
          methods.forEach { addMethod(it.spec(env, isKotlinDepInterface, useStaticClass = false)) }
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

  private fun ChildDependencyMethodImpl.ReturnExpression.spec(useStaticClass: Boolean = false): CodeBlock =
      when (this) {
        is ChildDependencyMethodImpl.ReturnExpression.Parameter -> spec()
        is ChildDependencyMethodImpl.ReturnExpression.Provider -> spec(useStaticClass)
      }

  private fun ChildDependencyMethodImpl.ReturnExpression.Parameter.spec(): CodeBlock =
      CodeBlock.of("return \$N", parameterName)

  private fun ChildDependencyMethodImpl.ReturnExpression.Provider.spec(useStaticClass: Boolean = false): CodeBlock =
      if (useStaticClass) {
        CodeBlock.of("return parentScope.\$N()", providerName)
      } else {
        CodeBlock.of("return \$T.this.\$N()", scopeImplName.j, providerName)
      }

  private fun ChildMethodImplParameter.spec(): ParameterSpec =
      ParameterSpec.builder(typeName.j, name, Modifier.FINAL).build()

  /**
   * Generates a static dependency class for SMART_CACHE strategy.
   * Example:
   * ```
   * private static class PhotoGridScopeDependencies implements PhotoGridScope.Dependencies {
   *     private final RootScopeImpl parentScope;
   *     private final ViewGroup viewGroup;
   *
   *     PhotoGridScopeDependencies(RootScopeImpl parentScope, ViewGroup viewGroup) {
   *         this.parentScope = parentScope;
   *         this.viewGroup = viewGroup;
   *     }
   *
   *     @Override
   *     public ViewGroup viewGroup() { return viewGroup; }
   *
   *     @Override
   *     public Database database() { return parentScope.database(); }
   * }
   * ```
   */
  @OptIn(KotlinPoetJavaPoetPreview::class)
  private fun StaticDependencyClass.spec(): TypeSpec {
    val isKotlinDepInterface = env.findTypeElement(childDependenciesClassName.j).isKotlinSource(env)

    return TypeSpec.classBuilder(className)
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
        .apply {
          if (isAbstractClass) {
            addModifiers(Modifier.ABSTRACT)
          }
        }
        .addSuperinterface(childDependenciesClassName.j)
        .apply {
          // Add parentScope field
          addField(
              FieldSpec.builder(parentScopeClassName.j, "parentScope", Modifier.PRIVATE, Modifier.FINAL)
                  .build()
          )

          // Add parameter fields
          methodParameters.forEach { param ->
            addField(
                FieldSpec.builder(param.typeName.j, param.name, Modifier.PRIVATE, Modifier.FINAL)
                    .build()
            )
          }

          // Add constructor
          addMethod(
              MethodSpec.constructorBuilder()
                  .addParameter(parentScopeClassName.j, "parentScope")
                  .apply {
                    methodParameters.forEach { param ->
                      addParameter(param.typeName.j, param.name)
                    }
                  }
                  .addStatement("this.parentScope = parentScope")
                  .apply {
                    methodParameters.forEach { param ->
                      addStatement("this.\$N = \$N", param.name, param.name)
                    }
                  }
                  .build()
          )

          // Add dependency methods
          methods.forEach { method ->
            addMethod(method.spec(env, isKotlinDepInterface, useStaticClass = true))
          }
        }
        .build()
  }


  private fun ScopeProviderMethod.spec(): MethodSpec =
      MethodSpec.methodBuilder(name).returns(scopeClassName.j).addStatement("return this").build()

  private fun FactoryProviderMethod.specs(
      isBaselineStrategy: Boolean,
      perDependencyLockFields: PerDependencyLockFields?
  ): List<MethodSpec> {
    val primarySpec =
        MethodSpec.methodBuilder(name)
            .returns(returnTypeName.j)
            .addStatement(body.spec(isBaselineStrategy, perDependencyLockFields))
            .build()
    val spreadSpecs = spreadProviderMethods.map { it.spec() }
    return listOf(primarySpec) + spreadSpecs
  }

  private fun FactoryProviderMethodBody.spec(
      isBaselineStrategy: Boolean,
      perDependencyLockFields: PerDependencyLockFields?
  ): CodeBlock =
      when (this) {
        is FactoryProviderMethodBody.Cached -> spec(isBaselineStrategy, perDependencyLockFields)
        is FactoryProviderMethodBody.Uncached -> spec()
      }

  private fun FactoryProviderMethodBody.Cached.spec(
      isBaselineStrategy: Boolean,
      perDependencyLockFields: PerDependencyLockFields?
  ): CodeBlock {
    // SMART_CACHE strategy: Use null initialization
    if (!isBaselineStrategy) {
      val localFieldName = "_$cacheFieldName"
      // Get the lock field name for this cache field (if per-dependency locks are enabled)
      val lockFieldName = perDependencyLockFields?.locks?.get(cacheFieldName)

      val builder = CodeBlock.builder()
          // Using a local variable reduces atomic read overhead
          .add("Object $localFieldName = \$N;\n", cacheFieldName)
          .beginControlFlow("if (\$N == null)", localFieldName)

      // Add synchronized block using nullable lock pattern: lock_foo != null ? lock_foo : this
      if (lockFieldName != null) {
          builder.beginControlFlow("synchronized(\$N != null ? \$N : this)", lockFieldName, lockFieldName)
      } else {
          builder.beginControlFlow("synchronized (this)")
      }

      return builder
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
          .add("return (\$T) \$N", returnTypeName.j, localFieldName)
          .build()
    }
    // BASELINE strategy: Use None.NONE sentinel
    // Get the lock field name for this cache field (if per-dependency locks are enabled)
    val lockFieldName = perDependencyLockFields?.locks?.get(cacheFieldName)

    val builder = CodeBlock.builder()
        .beginControlFlow("if (\$N == \$T.NONE)", cacheFieldName, None::class.java)

    // Add synchronized block using nullable lock pattern: lock_foo != null ? lock_foo : this
    if (lockFieldName != null) {
        builder.beginControlFlow("synchronized(\$N != null ? \$N : this)", lockFieldName, lockFieldName)
    } else {
        builder.beginControlFlow("synchronized (this)")
    }

    return builder
        .beginControlFlow("if (\$N == \$T.NONE)", cacheFieldName, None::class.java)
        .add("\$N = \$L;", cacheFieldName, instantiation.spec())
        .endControlFlow()
        .endControlFlow()
        .endControlFlow()
        .add("return (\$T) \$N", returnTypeName.j, cacheFieldName)
        .build()
  }

  private fun FactoryProviderMethodBody.Uncached.spec(): CodeBlock =
      CodeBlock.of("return \$L", instantiation.spec())

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
            addModifiers(Modifier.STATIC)  // Package-private (no access modifier) so variants can access it
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
}

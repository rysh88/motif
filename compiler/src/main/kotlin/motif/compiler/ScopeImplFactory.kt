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
import motif.ast.IrClass
import motif.ast.IrType
import motif.ast.compiler.CompilerAnnotation
import motif.ast.compiler.CompilerClass
import motif.ast.compiler.CompilerMethod
import motif.ast.compiler.CompilerType
import motif.core.ResolvedGraph
import motif.core.ScopeEdge
import motif.internal.Constants
import motif.models.AccessMethodSink
import motif.models.BasicFactoryMethod
import motif.models.BindsFactoryMethod
import motif.models.ChildMethod
import motif.models.ConstructorFactoryMethod
import motif.models.FactoryMethod
import motif.models.FactoryMethodSink
import motif.models.Scope
import motif.models.Sink
import motif.models.Spread
import motif.models.Type

class ScopeImplFactory
private constructor(
    private val env: XProcessingEnv,
    private val graph: ResolvedGraph,
) {

  private val scopeImplClassNames = mutableMapOf<Scope, ClassName>()
  private val dependenciesClassNames = mutableMapOf<Scope, ClassName>()
  private val objectsClassNames = mutableMapOf<Scope, ClassName?>()
  private val objectsImplClassNames = mutableMapOf<Scope, ClassName>()
  private val typeNames = mutableMapOf<CompilerType, TypeName>()

  private val dependencyMethods = mutableMapOf<Scope, List<DependencyMethodData>>()

  private fun create(): List<ScopeImpl> =
      graph.scopes
          .filter { scope -> env.findTypeElement(scope.implClassName.j.toString()) == null }
          .flatMap { scope -> Factory(scope).create() }

  private inner class Factory(private val scope: Scope) {

    private val methodNameScope = NameScope(blacklist = scope.clazz.methods.map { it.name })
    private val fieldNameScope =
        NameScope(blacklist = listOf(OBJECTS_FIELD_NAME, DEPENDENCIES_FIELD_NAME))

    private val providerMethodNames = mutableMapOf<Type, String>()
    private val cacheFieldNames = mutableMapOf<Type, String>()

    // Determine caching strategy early for use in helper methods
    private val scopeAnnotation = scope.clazz.annotations.find { it.className == motif.Scope::class.java.name }!!
    private val cachingStrategy = resolveCachingStrategy(scopeAnnotation)
    private val useSelectiveCaching = cachingStrategy == motif.CachingStrategy.SMART_CACHE

    // Memoization for shouldCache() to avoid recomputation and handle recursion
    private val shouldCacheCache = mutableMapOf<Type, Boolean>()
    private val shouldCacheComputing = mutableSetOf<Type>()

    // Memoization for usage count to avoid recomputation
    private val usageCountCache by lazy {
      val counts = mutableMapOf<Type, Int>()

      // Count all factory method parameter usages (local usage within this scope)
      scope.factoryMethods.forEach { factoryMethod ->
        factoryMethod.parameters.forEach { param ->
          counts[param.type] = (counts[param.type] ?: 0) + 1
        }
      }

      counts
    }

    // Memoization for dependent lookup: Type -> Set of FactoryMethods that depend on it
    private val dependentsCache by lazy {
      val dependents = mutableMapOf<Type, MutableSet<FactoryMethod>>()

      scope.factoryMethods.forEach { factoryMethod ->
        factoryMethod.parameters.forEach { param ->
          dependents.getOrPut(param.type) { mutableSetOf() }.add(factoryMethod)
        }
      }

      dependents
    }

    // Track static class names by their structural signature to enable deduplication
    private val staticClassNameBySignature = mutableMapOf<String, String>()
    // Track all used static class names to prevent collisions
    private val usedStaticClassNames = mutableSetOf<String>()

    fun create(): List<ScopeImpl> {
      // For RUNTIME_SELECTABLE, generate both implementations
      // BASELINE = without selective caching
      // SMART_CACHE = with selective caching optimization
      if (cachingStrategy == motif.CachingStrategy.RUNTIME_SELECTABLE) {
        return listOf(
          createForStrategy(motif.CachingStrategy.BASELINE, "_Baseline", forceNoSelectiveCaching = true),
          createForStrategy(motif.CachingStrategy.SMART_CACHE, "_SmartCache", forceNoSelectiveCaching = false),
          createDynamicWrapper()
        )
      }

      // For specific strategies, generate single implementation
      return listOf(createForStrategy(cachingStrategy, "", forceNoSelectiveCaching = false))
    }

    private fun createDynamicWrapper(): ScopeImpl {
      val isInternal = (scope.clazz as? CompilerClass)?.isInternal() ?: false

      // The dynamic wrapper needs the Dependencies interface so variant implementations can reference it
      // It also needs to delegate all interface methods to the selected variant implementation
      return ScopeImpl(
          isBaselineStrategy = false,
          className = scope.implClassName,
          superClassName = scope.typeName,
          internalScope = isInternal,
          scopeImplAnnotation = scopeImplAnnotation(),
          objectsField = null, // Variants have their own Objects implementation
          dependenciesField = dependenciesField(),
          perDependencyLockFields = null,
          cacheFields = emptyList(),
          constructor = constructor(),
          alternateConstructor = alternateConstructor(),
          accessMethodImpls = accessMethodImpls(), // Wrapper needs to delegate interface methods
          childMethodImpls = childMethodImpls(scope.implClassName, false), // Wrapper needs to delegate child methods
          scopeProviderMethod = scopeProviderMethod(),
          factoryProviderMethods = emptyList(),
          dependencyProviderMethods = emptyList(),
          objectsImpl = null, // Variants have their own Objects implementation
          dependencies = dependencies(),
          staticDependencyClasses = emptyList(),
          isRuntimeSelectableWrapper = true,
      )
    }

    private fun createForStrategy(
        strategy: motif.CachingStrategy,
        suffix: String,
        forceNoSelectiveCaching: Boolean
    ): ScopeImpl {
      val isInternal = (scope.clazz as? CompilerClass)?.isInternal() ?: false

      // SMART_CACHE uses per-dependency locks, BASELINE uses synchronized(this)
      val isBaselineStrategy = strategy == motif.CachingStrategy.BASELINE

      // Selective caching only if not forced off (DYNAMIC_MODE forces it off)
      val useSelectiveCaching = !forceNoSelectiveCaching && strategy == motif.CachingStrategy.SMART_CACHE

      // Create class name with suffix for DYNAMIC_MODE variants
      val implClassName = if (suffix.isNotEmpty()) {
        val originalName = scope.implClassName
        ClassName.get(originalName.j.packageName(), originalName.j.simpleName() + suffix)
      } else {
        scope.implClassName
      }

      // Use static classes for SMART_CACHE, anonymous classes for BASELINE
      val shouldUseStaticClasses = useSelectiveCaching

      return ScopeImpl(
          isBaselineStrategy,
          implClassName,
          scope.typeName,
          isInternal,
          scopeImplAnnotation(),
          objectsField(implClassName),
          dependenciesField(),
          // Generate per-dependency locks for both SMART_CACHE and BASELINE
          perDependencyLockFields(useSelectiveCaching, isBaselineStrategy),
          cacheFields(useSelectiveCaching),
          constructor(),
          alternateConstructor(),
          accessMethodImpls(),
          childMethodImpls(implClassName, shouldUseStaticClasses),
          scopeProviderMethod(),
          factoryProviderMethods(useSelectiveCaching),
          dependencyProviderMethods(),
          objectsImpl(implClassName),
          dependencies(),
          if (shouldUseStaticClasses) staticDependencyClasses(implClassName) else emptyList(),
      )
    }

    private fun scopeImplAnnotation(): ScopeImplAnnotation {
      val childClassNames = graph.getChildEdges(scope).map { childEdge -> childEdge.child.typeName }
      return ScopeImplAnnotation(childClassNames, scope.typeName, scope.dependenciesClassName)
    }

    private fun objectsField(implClassName: ClassName): ObjectsField? {
      val objectsClassName = scope.objectsClassName ?: return null
      val objectsImplClassName = implClassName.nestedClass("Objects")
      return ObjectsField(objectsClassName, objectsImplClassName, OBJECTS_FIELD_NAME)
    }

    private fun dependenciesField(): DependenciesField =
        DependenciesField(scope.dependenciesClassName, DEPENDENCIES_FIELD_NAME)

    /**
     * Determines if a factory method should skip caching based on @DoNotCache annotation
     * and the current caching strategy.
     *
     * @param factoryMethod The factory method to check
     * @param useSelectiveCaching True if using SMART_CACHE (selective caching), false for BASELINE
     * @return true if caching should be skipped, false otherwise
     */
    private fun shouldSkipCaching(factoryMethod: FactoryMethod, useSelectiveCaching: Boolean): Boolean {
        if (!factoryMethod.hasDoNotCache) {
            return false
        }

        // If onlyForSmartCacheMode = true, only skip caching in SMART_CACHE mode
        if (factoryMethod.doNotCacheOnlyForSmartCache) {
            return useSelectiveCaching
        }

        // Otherwise (@DoNotCache or @DoNotCache(onlyForSmartCacheMode = false)), skip in all modes
        return true
    }

    private fun cacheFields(useSelectiveCaching: Boolean): List<CacheField> {
        val cachedMethods = scope.factoryMethods.filter {
            !shouldSkipCaching(it, useSelectiveCaching)
        }

        return if (useSelectiveCaching) {
            // For selective caching: skip cache for internal-only, single-use dependencies
            cachedMethods.filter { shouldCache(it) }.map { factoryMethod ->
                CacheField(getCacheFieldName(factoryMethod.returnType.type))
            }
        } else {
            cachedMethods.map { factoryMethod ->
                CacheField(getCacheFieldName(factoryMethod.returnType.type))
            }
        }
    }

    /**
     * Creates per-dependency lock fields for both SMART_CACHE and BASELINE strategies.
     * Lock fields are nullable and initialized conditionally in the constructor based on
     * MotifRuntimeConfig.usePerDependencyLock (checked once at construction time).
     */
    private fun perDependencyLockFields(useSelectiveCaching: Boolean, isBaselineStrategy: Boolean): PerDependencyLockFields? {
        // Get all cached dependencies (different filtering based on strategy)
        val cachedMethods = if (useSelectiveCaching) {
            // SMART_CACHE: Only methods that pass shouldCache() check
            scope.factoryMethods.filter {
                !shouldSkipCaching(it, useSelectiveCaching) && shouldCache(it)
            }
        } else {
            // BASELINE: All methods that aren't explicitly skipped
            scope.factoryMethods.filter {
                !shouldSkipCaching(it, useSelectiveCaching)
            }
        }

        // Create map from cache field name to lock field name
        val locks = cachedMethods.associate { factoryMethod ->
            val cacheFieldName = getCacheFieldName(factoryMethod.returnType.type)
            val lockFieldName = "lock_$cacheFieldName"
            cacheFieldName to lockFieldName
        }

        // Return null if no locks are needed (no cached dependencies)
        // This prevents generating empty lock field declarations
        return if (locks.isEmpty()) null else PerDependencyLockFields(locks)
    }

    /**
     * Determines whether a factory method's result should be cached.
     *
     * This is the core of SMART_CACHE selective caching optimization. It analyzes
     * dependency usage patterns to decide if caching is beneficial:
     *
     * - Always cache if dependency has public accessor method (can be called externally)
     * - Always cache if dependency is used multiple times within the scope
     * - Cache if dependency has @Expose annotation (may be used by child scopes)
     * - Skip caching for internal-only, single-use dependencies
     * - Skip caching for passthrough methods (simple type casts)
     *
     * Uses memoization to avoid recomputation and handle recursive dependencies.
     */
    private fun shouldCache(factoryMethod: FactoryMethod): Boolean {
        val returnType = factoryMethod.returnType.type

        // Check memoization cache first
        shouldCacheCache[returnType]?.let { return it }

        // Detect cycles (shouldn't happen with valid DI graphs, but be defensive)
        if (returnType in shouldCacheComputing) {
            // Conservative: cache dependencies involved in cycles to break the cycle
            // Unless explicitly marked with @DoNotCache for all modes (not just SmartCache)
            return !factoryMethod.hasDoNotCache || factoryMethod.doNotCacheOnlyForSmartCache
        }

        // Mark as computing to detect cycles
        shouldCacheComputing.add(returnType)

        try {
            val result = computeShouldCache(factoryMethod)
            shouldCacheCache[returnType] = result
            return result
        } finally {
            shouldCacheComputing.remove(returnType)
        }
    }

    /**
     * Internal implementation of shouldCache logic (extracted for memoization wrapper).
     */
    private fun computeShouldCache(factoryMethod: FactoryMethod): Boolean {
        val returnType = factoryMethod.returnType.type

        // Rule 1: If method has @DoNotCache annotation, never cache
        if (factoryMethod.hasDoNotCache) {
            return false
        }

        // Rule 2: Skip cache if return type has @DoNotCache annotation
        if (hasDoNotCacheAnnotation(returnType)) {
            return false
        }

        // Rule 3: Skip cache for abstract passthrough methods
        // These are abstract methods that just cast/forward a parameter to a different type
        // with no construction cost. Only applies to abstract methods - concrete methods
        // are kept as-is since the user wrote them explicitly.
        // Example: abstract Context context(AppCompatActivity activity)
        if (isPassthroughMethod(factoryMethod)) {
            return false
        }

        // Rule 4: Check if this dependency has public accessor method
        // If yes, it can be called from outside, so always cache
        val hasAccessor = scope.accessMethods.any { it.returnType == returnType }
        if (hasAccessor) {
            return true
        }

        // Count how many times this dependency is used internally (by other factory methods)
        val usageCount = countInternalUsage(returnType)

        // Rule 5: Dead code - not used at all, never cache
        if (usageCount == 0) {
            return false
        }

        // Rule 6: Used multiple times internally
        if (usageCount > 1) {
            return true
        }

        // Rule 7: Has @Expose annotation
        // Even if usageCount == 1, exposed methods can be called from outside the scope
        // (by parent scopes, child scopes, or external code), so cache them
        if (factoryMethod.isExposed) {
            return true
        }

        // Rule 8: Used by at least one non-cached dependent
        // (If a non-cached method depends on this, it will be created multiple times)
        // Optimization: If usageCount = 1 and the only dependent is cached, we can skip caching
        // because the dependency will only be created once when the cached dependent is first created.
        return !isDependentCreatedOnce(returnType)
    }

    /**
     * Checks if a factory method is a passthrough method that should not be cached.
     * A passthrough method is an abstract method with a single parameter that just
     * casts/forwards the parameter to a different type with no construction cost.
     *
     * Only applies to abstract methods (from @Objects abstract class or @Binds).
     * Concrete methods are excluded because the user explicitly wrote them.
     *
     * Example: abstract Context context(AppCompatActivity activity)
     * - Method is abstract
     * - Has 1 parameter: AppCompatActivity
     * - Returns Context (which AppCompatActivity is assignable to)
     * - No transformation or construction happens - just a type cast
     */
    private fun isPassthroughMethod(factoryMethod: FactoryMethod): Boolean {
        // Only check abstract methods - concrete methods are kept as-is
        if (!factoryMethod.method.isAbstract()) {
            return false
        }

        // Must have exactly 1 parameter
        if (factoryMethod.parameters.size != 1) {
            return false
        }

        val paramType = factoryMethod.parameters.single().type
        val returnType = factoryMethod.returnType.type.type as? CompilerType ?: return false
        val paramCompilerType = paramType.type as? CompilerType ?: return false

        // Check if return type is assignable from parameter type (i.e., it's a type cast)
        val isAssignable = try {
            val returnXType = env.findType(returnType.qualifiedName)
            val paramXType = env.findType(paramCompilerType.qualifiedName)

            if (returnXType != null && paramXType != null) {
                returnXType.isAssignableFrom(paramXType)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }

        return isAssignable
    }

    /**
     * Checks if a concrete factory method is a simple type cast wrapper.
     * This is used for code generation optimization - we can bypass the wrapper method
     * and directly return the parameter.
     *
     * Only checks BasicFactoryMethod (concrete methods in @Objects class).
     * Example: Listener listener(ListenerImpl impl) { return impl; }
     */
    private fun isConcreteWrapperMethod(factoryMethod: FactoryMethod): Boolean {
        // Only check concrete methods
        if (factoryMethod !is BasicFactoryMethod) {
            return false
        }

        // Must have exactly 1 parameter
        if (factoryMethod.parameters.size != 1) {
            return false
        }

        val paramType = factoryMethod.parameters.single().type.type as? CompilerType
        val returnType = factoryMethod.returnType.type.type as? CompilerType

        if (paramType == null || returnType == null) {
            return false
        }

        // Check if return type is assignable from parameter type
        return try {
            val paramXType = env.findType(paramType.qualifiedName)
            val returnXType = env.findType(returnType.qualifiedName)

            if (paramXType != null && returnXType != null) {
                returnXType.isAssignableFrom(paramXType)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if the single dependent of this type will only be created once.
     *
     * This is used to determine if we need to cache this dependency. If the dependent
     * is only created once, then this dependency is also only created once, so we don't
     * need to cache it.
     *
     * Note: This function is only called when usageCount == 1, so there is exactly one dependent.
     * Uses shouldCache() recursively to determine if the dependent is actually cached.
     * This function is only called during selective caching.
     *
     * Returns true if the dependent is created only once (either cached, or used only once).
     * Returns false if the dependent will be created multiple times (needs caching).
     *
     * Uses memoized dependentsCache for O(1) lookup.
     */
    private fun isDependentCreatedOnce(type: Type): Boolean {
        // Since this is only called when usageCount == 1, there's exactly one dependent
        val dependent = dependentsCache[type]?.singleOrNull() ?: return true

        // If the dependent is cached, it's only created once
        if (shouldCache(dependent)) {
            return true
        }

        // If the dependent is not cached, check how many times it's used
        val dependentUsageCount = usageCountCache[dependent.returnType.type] ?: 0

        // Return true if the dependent is only used once (created once)
        // Return false if the dependent is used multiple times (created multiple times)
        return dependentUsageCount <= 1
    }

    /**
     * Checks if a type or any of its parent classes has the @DoNotCache annotation.
     * This check is transitive - it walks up the class hierarchy checking each class.
     */
    private fun hasDoNotCacheAnnotation(type: Type): Boolean {
        val compilerType = type.type as? CompilerType ?: return false

        try {
            val xType = env.findType(compilerType.qualifiedName) ?: return false
            val typeElement = xType.typeElement ?: return false

            // Walk up the class hierarchy checking for @DoNotCache annotation
            var currentElement = typeElement
            val visited = mutableSetOf<String>()

            while (true) {
                val qname = currentElement.qualifiedName

                // Prevent infinite loops
                if (qname in visited) break
                visited.add(qname)

                // Check if current class has @DoNotCache annotation
                if (currentElement.hasAnnotation(motif.DoNotCache::class)) {
                    return true
                }

                // Move to superclass
                val superType = currentElement.superClass
                if (superType == null || superType.typeElement == null) {
                    break
                }

                currentElement = superType.typeElement!!

                // Stop at Object/Any
                val superQName = currentElement.qualifiedName
                if (superQName == "java.lang.Object" || superQName == "kotlin.Any") {
                    break
                }
            }

            return false
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Counts how many times a dependency type is used locally within this scope.
     * Only counts usage as parameters in factory methods.
     *
     * Note: Child scope dependencies are handled separately via accessor methods,
     * which are detected by the hasAccessor check in shouldCache().
     *
     * Uses memoized cache for efficient lookup.
     */
    private fun countInternalUsage(type: Type): Int {
        return usageCountCache[type] ?: 0
    }

    private fun constructor(): Constructor =
        Constructor(scope.dependenciesClassName, "dependencies", DEPENDENCIES_FIELD_NAME)

    private fun alternateConstructor(): AlternateConstructor? {
      if (getDependencyMethodData(scope).isNotEmpty() ||
          !scope.dependencies?.methods.isNullOrEmpty()) {
        return null
      }
      return AlternateConstructor(scope.dependenciesClassName)
    }

    private fun accessMethodImpls(): List<AccessMethodImpl> {
        return scope.accessMethods.map { accessMethod ->
          val providerMethodName = getProviderMethodName(accessMethod.returnType)
          val compilerMethod = accessMethod.method as CompilerMethod

          AccessMethodImpl(
              env,
              compilerMethod,
              providerMethodName,
          )
        }
    }

    private fun childMethodImpls(implClassName: ClassName, useStaticClasses: Boolean): List<ChildMethodImpl> =
        graph.getChildEdges(scope).map { childMethodImpl(it, implClassName, useStaticClasses) }

    private fun childMethodImpl(
        childEdge: ScopeEdge,
        implClassName: ClassName,
        useStaticClasses: Boolean
    ): ChildMethodImpl =
        ChildMethodImpl(
            childEdge.child.typeName,
            childEdge.child.implClassName,
            childEdge.method.method.name,
            childEdge.method.parameters.map(this::childMethodImplParameter),
            childDependenciesImpl(childEdge, implClassName, useStaticClasses),
        )

    private fun childMethodImplParameter(
        childMethodParameter: ChildMethod.Parameter,
    ): ChildMethodImplParameter =
        ChildMethodImplParameter(
            childMethodParameter.parameter.type.typeName,
            childMethodParameter.parameter.name,
        )

    private fun childDependenciesImpl(
        childEdge: ScopeEdge,
        implClassName: ClassName,
        useStaticClasses: Boolean
    ): ChildDependenciesImpl {
      // Validate that no two parameters have the same type (Motif doesn't support this without qualifiers)
      val duplicateTypes = childEdge.method.parameters
          .groupBy { it.type }
          .filter { it.value.size > 1 }

      if (duplicateTypes.isNotEmpty()) {
          val duplicateTypesList = duplicateTypes.keys.joinToString(", ") { it.simpleName }
          throw IllegalStateException(
              """
              Child method has multiple parameters of the same type, which is not supported.
              Scope: ${scope.typeName}
              Child scope: ${childEdge.child.typeName}
              Method: ${childEdge.method.method.name}
              Duplicate types: $duplicateTypesList

              To fix this, use @Named or other qualifiers to distinguish parameters of the same type.
              """.trimIndent()
          )
      }

      val parameters: Map<Type, ChildMethod.Parameter> =
          childEdge.method.parameters.associateBy { parameter -> parameter.type }
      val dependencyMethodImpls =
          getDependencyMethodData(childEdge.child).map { methodData ->
            childDependencyMethodImpl(parameters, methodData, implClassName)
          }
      val isAbstractClass = dependencyMethodImpls.any { it.isInternal }

      // For static classes, create a signature for this dependency implementation to enable deduplication
      val (useStaticClass, staticClassName) = if (useStaticClasses) {
        // Create a signature for this dependency implementation to enable deduplication
        val signature = createStaticClassSignature(
            childEdge.child.dependenciesClassName,
            childEdge.method.parameters.map(this::childMethodImplParameter),
            dependencyMethodImpls
        )

        // Reuse existing class name if we've seen this exact signature before
        val className = staticClassNameBySignature.getOrPut(signature) {
          // Generate a unique class name based on child scope name
          val baseName = "${childEdge.child.clazz.type.simpleName}Dependencies"
          var uniqueName = baseName
          var counter = 2

          // If this name is already used by a different signature, add a suffix
          while (uniqueName in usedStaticClassNames) {
            uniqueName = "${baseName}_${counter}"
            counter++
          }

          // Mark this name as used
          usedStaticClassNames.add(uniqueName)
          uniqueName
        }
        Pair(true, className)
      } else {
        Pair(false, null)
      }

      return ChildDependenciesImpl(
          childEdge.child.dependenciesClassName,
          dependencyMethodImpls,
          isAbstractClass,
          env,
          useStaticClass,
          staticClassName,
          implClassName,
      )
    }

    private fun childDependencyMethodImpl(
        parameters: Map<Type, ChildMethod.Parameter>,
        methodData: DependencyMethodData,
        implClassName: ClassName,
    ): ChildDependencyMethodImpl {
      val parameter = parameters[methodData.returnType]
      val returnExpression =
          if (parameter == null) {
            ChildDependencyMethodImpl.ReturnExpression.Provider(
                implClassName,
                getProviderMethodName(methodData.returnType),
            )
          } else {
            ChildDependencyMethodImpl.ReturnExpression.Parameter(parameter.parameter.name)
          }
      val isInternal = (methodData.returnType.type as? CompilerType)?.isInternal() ?: false
      return ChildDependencyMethodImpl(
          methodData.name,
          methodData.returnTypeName,
          returnExpression,
          isInternal,
      )
    }

    private fun scopeProviderMethod(): ScopeProviderMethod {
      val name = getProviderMethodName(Type(scope.clazz.type, null))
      val isInternal = (scope.clazz.type as? CompilerType)?.isInternal() ?: false
      return ScopeProviderMethod(name, scope.typeName, isInternal)
    }

    private fun factoryProviderMethods(useSelectiveCaching: Boolean): List<FactoryProviderMethod> =
        scope.factoryMethods.map { factoryMethod ->
          val returnType = factoryMethod.returnType.type
          val spreadProviderMethods =
              factoryMethod.spread?.let { spreadProviderMethods(it) } ?: emptyList()
          FactoryProviderMethod(
              getProviderMethodName(returnType),
              returnType.type.typeName,
              factoryProviderMethodBody(factoryMethod, useSelectiveCaching),
              spreadProviderMethods,
              env,
          )
        }

    private fun factoryProviderMethodBody(factoryMethod: FactoryMethod, useSelectiveCaching: Boolean): FactoryProviderMethodBody {
      // Thin wrapper optimization for SMART_CACHE:
      // If a concrete method is a simple type cast wrapper, bypass it and return the parameter directly
      // instead of calling the wrapper method. This avoids unnecessary method calls.
      if (useSelectiveCaching && isConcreteWrapperMethod(factoryMethod)) {
        val bindsInstantiation = FactoryProviderInstantiation.Binds(
            getProviderMethodName((factoryMethod as BasicFactoryMethod).parameters.single().type),
        )
        return FactoryProviderMethodBody.Uncached(bindsInstantiation)
      }

      val instantiation =
          when (factoryMethod) {
            is BasicFactoryMethod -> basicInstantiation(factoryMethod)
            is ConstructorFactoryMethod -> constructorInstantiation(factoryMethod)
            is BindsFactoryMethod -> bindsInstantiation(factoryMethod)
          }

      // Check if we should skip caching based on @DoNotCache annotation and strategy
      val shouldBeCached = !shouldSkipCaching(factoryMethod, useSelectiveCaching) &&
          if (useSelectiveCaching) {
            // For selective caching: also check if this dependency should be cached based on usage
            shouldCache(factoryMethod)
          } else {
            // For non-selective strategies: cache everything that doesn't have @DoNotCache
            true
          }

      return if (shouldBeCached) {
        FactoryProviderMethodBody.Cached(
            getCacheFieldName(factoryMethod.returnType.type),
            factoryMethod.returnType.type.type.typeName,
            instantiation,
            env,
        )
      } else {
        FactoryProviderMethodBody.Uncached(instantiation)
      }
    }

    private fun spreadProviderMethods(spread: Spread): List<SpreadProviderMethod> =
        spread.methods.map { method ->
          SpreadProviderMethod(
              getProviderMethodName(method.returnType),
              method.method.isStatic(),
              method.returnType.type.typeName,
              method.sourceType.type.typeName,
              getProviderMethodName(method.sourceType),
              method.name,
          )
        }

    private fun basicInstantiation(
        factoryMethod: BasicFactoryMethod,
    ): FactoryProviderInstantiation.Basic =
        FactoryProviderInstantiation.Basic(
            OBJECTS_FIELD_NAME,
            factoryMethod.objects.clazz.typeName,
            factoryMethod.isStatic,
            factoryMethod.name,
            callProviders(factoryMethod),
        )

    private fun constructorInstantiation(
        factoryMethod: ConstructorFactoryMethod,
    ): FactoryProviderInstantiation.Constructor =
        FactoryProviderInstantiation.Constructor(
            factoryMethod.returnType.type.type.typeName,
            callProviders(factoryMethod),
        )

    private fun bindsInstantiation(
        factoryMethod: BindsFactoryMethod,
    ): FactoryProviderInstantiation.Binds =
        FactoryProviderInstantiation.Binds(
            getProviderMethodName(factoryMethod.parameters.single().type),
        )

    private fun callProviders(factoryMethod: FactoryMethod): CallProviders {
      val names =
          factoryMethod.parameters.map { parameter -> getProviderMethodName(parameter.type) }
      return CallProviders(names)
    }

    private fun dependencyProviderMethods(): List<DependencyProviderMethod> =
        getDependencyMethodData(scope).map { methodData ->
          DependencyProviderMethod(
              getProviderMethodName(methodData.returnType),
              methodData.returnTypeName,
              DEPENDENCIES_FIELD_NAME,
              methodData.name,
              env,
          )
        }

    private fun objectsImpl(implClassName: ClassName): ObjectsImpl? {
      val objects = scope.objects ?: return null
      val objectsClassName = scope.objectsClassName ?: return null
      val abstractMethods =
          objects.factoryMethods
              .filter { it.method.isAbstract() }
              .map { ObjectsAbstractMethod(env, it.method as CompilerMethod) }
      return ObjectsImpl(
          implClassName.nestedClass("Objects"),
          objectsClassName,
          objects.clazz.kind == IrClass.Kind.INTERFACE,
          abstractMethods,
      )
    }

    private fun dependencies(): Dependencies? {
      if (scope.dependencies != null) {
        return null
      }
      val methods =
          getDependencyMethodData(scope).map { methodData ->
            val qualifier =
                methodData.returnType.qualifier?.let { annotation ->
                  Qualifier(annotation as CompilerAnnotation)
                }
            val isInternal = (methodData.returnType.type as? CompilerType)?.isInternal() ?: false
            DependencyMethod(
                methodData.name,
                methodData.returnTypeName,
                qualifier,
                javaDoc(methodData),
                isInternal,
            )
          }
      return Dependencies(scope.dependenciesClassName, methods)
    }

    /**
     * Creates a signature for a static dependency class to enable deduplication.
     * Classes with the same signature will share the same implementation.
     */
    private fun createStaticClassSignature(
        childDependenciesClassName: ClassName,
        methodParameters: List<ChildMethodImplParameter>,
        dependencyMethods: List<ChildDependencyMethodImpl>
    ): String {
      // Signature includes:
      // 1. The interface/class being implemented
      // 2. The method parameter types (in order)
      // 3. The dependency methods and how they're satisfied (parameter vs provider)
      val parts = mutableListOf<String>()

      parts.add(childDependenciesClassName.toString())
      parts.add(methodParameters.joinToString(",") { it.typeName.toString() })

      dependencyMethods.forEach { method ->
        val source = when (val expr = method.returnExpression) {
          is ChildDependencyMethodImpl.ReturnExpression.Parameter -> "param:${expr.parameterName}"
          is ChildDependencyMethodImpl.ReturnExpression.Provider -> "provider:${expr.providerName}"
        }
        parts.add("${method.name}:${method.returnTypeName}=$source")
      }

      return parts.joinToString("|")
    }

    private fun staticDependencyClasses(implClassName: ClassName): List<StaticDependencyClass> {
      val uniqueClasses = mutableMapOf<String, StaticDependencyClass>()
      val usedNames = mutableSetOf<String>()

      graph.getChildEdges(scope).forEach { childEdge ->
        val parameters: Map<Type, ChildMethod.Parameter> =
            childEdge.method.parameters.associateBy { parameter -> parameter.type }
        val dependencyMethodImpls =
            getDependencyMethodData(childEdge.child).map { methodData ->
              childDependencyMethodImpl(parameters, methodData, implClassName)
            }
        val isAbstractClass = dependencyMethodImpls.any { it.isInternal }
        val methodParameters = childEdge.method.parameters.map(this::childMethodImplParameter)

        // Create signature and check if we've seen it before
        val signature = createStaticClassSignature(
            childEdge.child.dependenciesClassName,
            methodParameters,
            dependencyMethodImpls
        )

        // Only add if we haven't seen this signature yet
        if (signature !in uniqueClasses) {
          // Generate class name or use existing one from the map
          val className = staticClassNameBySignature.getOrPut(signature) {
            val baseName = "${childEdge.child.clazz.type.simpleName}Dependencies"
            var uniqueName = baseName
            var counter = 2

            // If this name is already used by a different signature, add a suffix
            while (uniqueName in usedNames) {
              uniqueName = "${baseName}_${counter}"
              counter++
            }

            usedNames.add(uniqueName)
            uniqueName
          }

          uniqueClasses[signature] = StaticDependencyClass(
              className,
              childEdge.child.dependenciesClassName,
              implClassName,
              isAbstractClass,
              dependencyMethodImpls,
              methodParameters,
              env,
          )
        }
      }

      return uniqueClasses.values.toList()
    }

    private fun javaDoc(methodData: DependencyMethodData): DependencyMethodJavaDoc {
      val requestedFrom =
          methodData.sinks.map { sink ->
            val (ownerType, callerMethod) =
                when (sink) {
                  is FactoryMethodSink -> Pair(sink.parameter.owner.type, sink.parameter.method)
                  is AccessMethodSink -> Pair(sink.scope.clazz.type, sink.accessMethod.method)
                }

            val owner = removeGenerics(ownerType.qualifiedName)
            val methodName =
                if (callerMethod.isConstructor) {
                  ownerType.simpleName.substringBefore('<')
                } else {
                  callerMethod.name
                }
            val paramList = callerMethod.parameters.map { removeGenerics(it.type.qualifiedName) }

            JavaDocMethodLink(owner, methodName, paramList)
          }
      return DependencyMethodJavaDoc(requestedFrom)
    }

    private fun removeGenerics(name: String): String = name.takeWhile { it != '<' }

    private fun getProviderMethodName(type: Type): String {
      return providerMethodNames.computeIfAbsent(type) { methodNameScope.name(type) }
    }

    private fun getCacheFieldName(type: Type): String {
        val fieldName = cacheFieldNames.computeIfAbsent(type) { fieldNameScope.name(type) }
        if (fieldName.isEmpty()) {
            throw IllegalStateException(
                """
                Generated empty cache field name for type: ${type.qualifiedName}
                Scope: ${scope.typeName}
                This indicates a bug in the name generation logic.
                """.trimIndent()
            )
        }
        return fieldName
    }

    /**
     * Resolves caching strategy from @Scope annotation.
     */
    private fun resolveCachingStrategy(scopeAnnotation: motif.ast.IrAnnotation): motif.CachingStrategy {
        val strategyValue = scopeAnnotation.annotationValueMap[SCOPE_ANNOTATION_FIELD_CACHING_STRATEGY]
            ?: return motif.CachingStrategy.BASELINE

        return when (strategyValue.toString()) {
            "SMART_CACHE" -> motif.CachingStrategy.SMART_CACHE
            "RUNTIME_SELECTABLE" -> motif.CachingStrategy.RUNTIME_SELECTABLE
            "DYNAMIC_MODE" -> motif.CachingStrategy.RUNTIME_SELECTABLE  // backward compatibility
            "OPTIMIZED_MULTI_LOCK" -> motif.CachingStrategy.SMART_CACHE  // backward compatibility
            "VOLATILE_FIELDS_SELECTIVE" -> motif.CachingStrategy.SMART_CACHE  // backward compatibility
            "BASELINE" -> motif.CachingStrategy.BASELINE
            else -> motif.CachingStrategy.BASELINE
        }
    }
  }

  private class DependencyMethodData(
      val name: String,
      val returnTypeName: TypeName,
      val returnType: Type,
      val sinks: List<Sink>,
  )

  private fun getDependencyMethodData(scope: Scope): List<DependencyMethodData> =
      dependencyMethods.computeIfAbsent(scope) { createDependencyMethods(scope) }

  private fun createDependencyMethods(scope: Scope): List<DependencyMethodData> {
    val nameScope = NameScope()
    fun getName(type: Type): String {
      val dependencies = scope.dependencies ?: return nameScope.name(type)
      val method =
          dependencies.methodByType[type]
              ?: throw IllegalStateException("Could not find Dependencies method for type: $type")
      return method.method.name
    }
    return graph.getUnsatisfied(scope).toSortedMap().entries.map { (type, sinks) ->
      DependencyMethodData(getName(type), type.type.typeName, type, sinks)
    }
  }

  private val IrType.typeName: TypeName
    get() = typeNames.computeIfAbsent(this as CompilerType) { type -> TypeName.get(type.mirror) }

  private val IrClass.typeName: ClassName
    get() = type.typeName.className

  private val Scope.typeName: ClassName
    get() = clazz.typeName

  private val Scope.implClassName: ClassName
    get() =
        scopeImplClassNames.computeIfAbsent(this) { scope ->
          val scopeClassName = scope.clazz.typeName
          val prefix = scopeClassName.kt.simpleNames.joinToString("")
          ClassName.get(scopeClassName.kt.packageName, "$prefix${Constants.SCOPE_IMPL_SUFFIX}")
        }

  private val Scope.dependenciesClassName: ClassName
    get() =
        dependenciesClassNames.computeIfAbsent(this) {
          dependencies?.clazz?.typeName ?: implClassName.nestedClass("Dependencies")
        }

  private val Scope.objectsClassName: ClassName?
    get() =
        objectsClassNames.computeIfAbsent(this) { scope ->
          val objects = scope.objects ?: return@computeIfAbsent null
          objects.clazz.typeName
        }

  private val Scope.objectsImplClassName: ClassName
    get() =
        objectsImplClassNames.computeIfAbsent(this) { scope ->
          scope.implClassName.nestedClass("Objects")
        }

  companion object {

    private const val OBJECTS_FIELD_NAME = "objects"
    private const val DEPENDENCIES_FIELD_NAME = "dependencies"
    private const val SCOPE_ANNOTATION_FIELD_CACHING_STRATEGY = "cachingStrategy"

    fun create(env: XProcessingEnv, graph: ResolvedGraph): List<ScopeImpl> =
        ScopeImplFactory(env, graph).create()
  }
}

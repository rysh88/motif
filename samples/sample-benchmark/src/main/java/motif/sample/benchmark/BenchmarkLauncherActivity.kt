package motif.sample.benchmark

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.LinearLayout
import android.view.ViewGroup
import android.view.Gravity
import motif.ScopeFactory
import motif.MotifRuntimeConfig
import motif.CachingStrategy
import motif.sample.benchmark.java.BenchmarkJavaScope

/**
 * Benchmark launcher for testing Java code generator
 * with VOLATILE_FIELDS and SMART_CACHE strategies.
 */
class BenchmarkLauncherActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(32, 32, 32, 32)
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
    }

    val titleText = TextView(this).apply {
      text = "Motif Java Benchmark\n1000 Dependencies"
      textSize = 24f
      gravity = Gravity.CENTER
      setPadding(0, 0, 0, 40)
    }

    val javaVolatileButton = Button(this).apply {
      text = "VOLATILE_FIELDS"
      setOnClickListener {
        runBenchmark(CachingStrategy.VOLATILE_FIELDS)
      }
    }

    val javaSmartCacheButton = Button(this).apply {
      text = "SMART_CACHE"
      setOnClickListener {
        runBenchmark(CachingStrategy.SMART_CACHE)
      }
    }

    layout.addView(titleText)
    layout.addView(javaVolatileButton)
    layout.addView(javaSmartCacheButton)

    setContentView(layout)
  }

  private fun runBenchmark(strategy: CachingStrategy) {
    MotifRuntimeConfig.cachingStrategy = strategy

    val timings = mutableListOf<Long>()

    for (iteration in 1..100) {
      val startTime = System.currentTimeMillis()

      // Create 100 scope instances to make the benchmark take ~1 second
      for (scopeInstance in 1..100) {
        val scope: BenchmarkJavaScope = ScopeFactory.create(
          BenchmarkJavaScope::class.java,
          object : BenchmarkJavaScope.Dependencies {}
        )

        // Access all 1000 dependencies
        for (i in 1..1000) {
          val methodName = "javaDependency${i.toString().padStart(3, '0')}"
          try {
            val method = scope.javaClass.getMethod(methodName)
            method.invoke(scope)
          } catch (e: NoSuchMethodException) {
            // Method doesn't exist (e.g., @DoNotCache dependency), skip it
          }
        }
      }

      val elapsed = System.currentTimeMillis() - startTime
      timings.add(elapsed)
    }

    showResults(strategy, timings)
  }

  private fun calculatePercentile(sortedValues: List<Long>, percentile: Int): Long {
    val index = (percentile / 100.0 * (sortedValues.size - 1)).toInt()
    return sortedValues[index]
  }

  private fun showResults(strategy: CachingStrategy, timings: List<Long>) {
    val sortedTimings = timings.sorted()
    val p50 = calculatePercentile(sortedTimings, 50)
    val p75 = calculatePercentile(sortedTimings, 75)
    val p90 = calculatePercentile(sortedTimings, 90)
    val min = sortedTimings.first()
    val max = sortedTimings.last()
    val avg = sortedTimings.average().toLong()

    val resultLayout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(32, 32, 32, 32)
    }

    val resultText = TextView(this).apply {
      text = """
        Java Code Generator
        Strategy: ${strategy.name}

        Ran 100 iterations
        100 scopes x 1000 dependencies per iteration

        Min:  ${min}ms
        p50:  ${p50}ms
        p75:  ${p75}ms
        p90:  ${p90}ms
        Max:  ${max}ms
        Avg:  ${avg}ms

        Click back to test another configuration
      """.trimIndent()
      textSize = 16f
      gravity = Gravity.CENTER
    }

    val backButton = Button(this).apply {
      text = "Back"
      setOnClickListener {
        recreate()
      }
    }

    resultLayout.addView(resultText)
    resultLayout.addView(backButton)

    setContentView(resultLayout)

    // Report fully drawn for benchmark measurements
    reportFullyDrawn()
  }
}

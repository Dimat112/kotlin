/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.toBooleanLenient
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.perfstat.PerfStat
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties
import kotlin.system.measureNanoTime

private val USE_NI = System.getProperty("fir.bench.oldfe.ni", "true") == "true"

class NonFirResolveModularizedTotalKotlinTest : AbstractModularizedTest() {
    private var totalTime = 0L
    private var files = 0
    private var lines = 0
    private var measure = FirResolveBench.Measure()

    private var perfBenchListener: PerfBenchListener? = null

    private val perfHelper = if (USE_PERF_STAT) PerfStat().also {
        it.init(PERF_LIB_PATH)

        USE_PERF_STAT_CONFIG?.let { cfg ->
            val args = cfg.split(',')
            for (arg in args) {
                val (name, valueStr) = arg.split('=')
                val field = it::class.memberProperties.find { it.name == name } ?: error("PerfStat flag not found: $name")
                @Suppress("UNCHECKED_CAST")
                (field as KMutableProperty1<PerfStat, Boolean>).set(it, valueStr.toBooleanLenient()!!)
            }
        }
    } else null

    private val times = mutableListOf<Long>()

    private fun runAnalysis(moduleData: ModuleData, environment: KotlinCoreEnvironment) {
        val project = environment.project
        perfBenchListener?.before()
        val vmBefore = vmStateSnapshot()
        val time = measureNanoTime {
            try {
                KotlinToJVMBytecodeCompiler.analyze(environment)
            } catch (e: Throwable) {
                var exception: Throwable? = e
                while (exception != null && exception != exception.cause) {
                    exception.printStackTrace()
                    exception = exception.cause
                }
                throw e
            }
        }
        val vmAfter = vmStateSnapshot()
        perfBenchListener?.after("Analyze")

        val psiFiles = environment.getSourceFiles()
        files += psiFiles.size
        lines += psiFiles.sumBy { StringUtil.countNewLines(it.text) }
        totalTime += time
        measure.time += time
        measure.vmCounters += vmAfter - vmBefore
        measure.files += psiFiles.size

        println("Time is ${time * 1e-6} ms")
    }

    private fun writeMessageToLog(message: String) {
        PrintStream(FileOutputStream(reportDir().resolve("report-$reportDateStr.log"), true)).use { stream ->
            stream.println(message)
        }
    }

    private fun dumpTime(message: String, time: Long) {
        writeMessageToLog("$message: ${time * 1e-6} ms")
    }

    override fun processModule(moduleData: ModuleData): ProcessorAction {
        val disposable = Disposer.newDisposable()


        val configuration = createDefaultConfiguration(moduleData)


        configuration.languageVersionSettings =
            LanguageVersionSettingsImpl(
                configuration.languageVersionSettings.languageVersion,
                configuration.languageVersionSettings.apiVersion,
                specificFeatures = mapOf(
                    LanguageFeature.NewInference to if (USE_NI) LanguageFeature.State.ENABLED else LanguageFeature.State.DISABLED
                )
            )

        System.getProperty("fir.bench.oldfe.jvm_target")?.let {
            configuration.put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.fromString(it) ?: error("Unknown JvmTarget"))
        }
        configuration.put(MESSAGE_COLLECTOR_KEY, object : MessageCollector {
            override fun clear() {

            }

            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (location != null)
                    print(location.toString())
                print(":")
                print(severity)
                print(":")
                println(message)
            }

            override fun hasErrors(): Boolean {
                return false
            }

        })
        val environment = KotlinCoreEnvironment.createForTests(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)

        runAnalysis(moduleData, environment)

        Disposer.dispose(disposable)
        return ProcessorAction.NEXT
    }

    private fun reportPerfStat(perfBenchListener: PerfBenchListener, statistics: FirResolveBench.TotalStatistics, pass: Int) {

        perfBenchListener.buildReport(System.out)
        PrintStream(
            FileOutputStream(
                reportDir().resolve("perf-$reportDateStr.log"),
                true
            )
        ).use { stream ->
            stream.println("====== Pass $pass ======")
            statistics.reportTimings(stream)
            perfBenchListener.buildReport(stream)

            stream.println()
            stream.println()
        }

    }

    override fun afterPass(pass: Int) {
        perfBenchListener?.let {
            reportPerfStat(
                it,
                FirResolveBench.TotalStatistics(
                    0, 0, 0, 0, 0, 0, fileCount = files, totalLines = lines, emptyMap(),
                    timePerTransformer = mapOf("Analyze" to measure)
                ),
                pass
            )
        }
    }

    override fun beforePass(pass: Int) {
        measure = FirResolveBench.Measure()
        if (perfHelper != null) perfBenchListener = PerfBenchListener(perfHelper)
        files = 0
        lines = 0
        totalTime = 0
    }

    fun testTotalKotlin() {

        isolate(perfHelper)

        perfHelper?.open()

        writeMessageToLog("use_ni: $USE_NI")

        for (i in 0 until PASSES) {
            runTestOnce(i)
            times += totalTime
            dumpTime("Pass $i", totalTime)
            totalTime = 0L
        }

        val bestTime = times.minOrNull()!!
        val bestPass = times.indexOf(bestTime)
        dumpTime("Best pass: $bestPass", bestTime)

        perfHelper?.close()
    }
}
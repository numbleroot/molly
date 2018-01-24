package edu.berkeley.cs.boom.molly

import java.io.File
import scala.io.Source
import pl.project13.scala.rainbow._
import com.typesafe.scalalogging.LazyLogging
import edu.berkeley.cs.boom.molly.DedalusRewrites._
import edu.berkeley.cs.boom.molly.DedalusParser._
import edu.berkeley.cs.boom.molly.report.HTMLWriter
import com.codahale.metrics.{ Slf4jReporter, MetricRegistry }
import java.util.concurrent.TimeUnit
import scalaz.syntax.id._
import scalaz.EphemeralStream
import edu.berkeley.cs.boom.molly.derivations.{ SAT4JSolver, Z3Solver }

case class Config(
  eot: Int = 3,
  eff: Int = 2,
  crashes: Int = 0,
  nodes: Seq[String] = Seq(),
  inputPrograms: Seq[File] = Seq(),
  strategy: String = "sat",
  //solver: String = "sat4j",
  solver: String = "z3",
  useSymmetry: Boolean = false,
  generateProvenanceDiagrams: Boolean = false,
  disableDotRendering: Boolean = false,
  findAllCounterexamples: Boolean = false,
  negativeSupport: Boolean = true,
  maxRuns: Int = Int.MaxValue)

object SyncFTChecker extends LazyLogging {

  val parser = new scopt.OptionParser[Config]("syncftChecker") {
    head("syncftchecker", "0.1")
    opt[Int]('t', "EOT") text "end of time (default 3)" action { (x, c) => c.copy(eot = x) }
    opt[Int]('f', "EFF") text "end of finite failures (default 2)" action { (x, c) => c.copy(eff = x) }
    opt[Int]('c', "crashes") text "crash failures (default 0)" action { (x, c) => c.copy(crashes = x) }
    opt[String]('N', "nodes") text "a comma-separated list of nodes (required)" required () action { (x, c) => c.copy(nodes = x.split(',')) }
    opt[String]("solver") text "the solver to use ('z3' or 'sat4j' or 'ilp')" action { (x, c) => c.copy(solver = x) } validate { x => if (x != "z3" && x != "sat4j" && x != "ilp") failure("solver should be 'z3' or 'sat4j' or 'ilp'") else success }
    opt[String]("strategy") text "the search strategy ('sat', 'random' or 'pcausal')" action { (x, c) => c.copy(strategy = x) } validate { x => if (x != "sat" && x != "random" && x != "pcausal") failure("strategy should be 'sat', 'random', or 'pcausal'") else success }
    opt[Unit]("use-symmetry") text "use symmetry to skip equivalent failure scenarios" action { (x, c) => c.copy(useSymmetry = true) }
    opt[Unit]("prov-diagrams") text "generate provenance diagrams for each execution" action { (x, c) => c.copy(generateProvenanceDiagrams = true) }
    opt[Unit]("disable-dot-rendering") text "disable automatic rendering of `dot` diagrams" action { (x, c) => c.copy(disableDotRendering = true) }
    opt[Unit]("find-all-counterexamples") text "continue after finding the first counterexample" action { (x, c) => c.copy(findAllCounterexamples = true) }
    opt[Unit]("negative-support") text "Negative support. Slow, but necessary for completeness" action { (x, c) => c.copy(negativeSupport = true) }
    arg[File]("<file>...") unbounded () minOccurs 1 text "Dedalus files" action { (x, c) => c.copy(inputPrograms = c.inputPrograms :+ x) }
  }

  /**
   * Like `takeWhile`, but also takes the first element NOT satisfying the predicate.
   */
  def takeUpTo[T](stream: EphemeralStream[T], pred: T => Boolean): EphemeralStream[T] = {
    if (stream.isEmpty) {
      stream
    } else {
      val head = stream.head()
      if (pred(head)) {
        EphemeralStream(head)
      } else {
        head ##:: takeUpTo(stream.tail(), pred)
      }
    }
  }

  def check(config: Config, metrics: MetricRegistry): EphemeralStream[Run] = {

    val combinedInput = config.inputPrograms.flatMap(Source.fromFile(_).getLines()).mkString("\n")
    val includeSearchPath = config.inputPrograms(0).getParentFile
    val program = combinedInput |> parseProgramAndIncludes(includeSearchPath) |> referenceClockRules |> splitAggregateRules |> addProvenanceRules
    val failureSpec = FailureSpec(config.eot, config.eff, config.crashes, config.nodes.toList)

    val solver = config.solver match {
      case "z3" => Z3Solver
      case "sat4j" => SAT4JSolver
      //case "ilp" => ILPSolver
      case s => throw new IllegalArgumentException(s"unknown solver $s")
    }

    val verifier = new Verifier(failureSpec, program, solver, causalOnly = (config.strategy == "pcausal"), useSymmetry = config.useSymmetry, negativeSupport = config.negativeSupport)(metrics)

    logger.info(s"Gross estimate: ${failureSpec.grossEstimate} runs")

    val results = config.strategy match {
      case "sat" => verifier.verify
      case "pcausal" => verifier.verify
      case "random" => verifier.random
      case s => throw new IllegalArgumentException(s"unknown strategy $s")
    }

    if (config.findAllCounterexamples) {
      results
    } else {
      takeUpTo(results, _.status == RunStatus("failure"))
    }
  }

  def main(args: Array[String]) {

    val metrics = new MetricRegistry
    val metricsReporter = Slf4jReporter.forRegistry(metrics)
      .outputTo(logger.underlying)
      .convertRatesTo(TimeUnit.SECONDS)
      .convertDurationsTo(TimeUnit.MILLISECONDS)
      .build()

    parser.parse(args, Config()) map { config =>

      println(config)

      // Kind of a hack to tee an ephemeral stream
      var firstCounterExample: FailureSpec = null
      val results = check(config, metrics).map {
        case r =>
          if (firstCounterExample == null && r.status == RunStatus("failure"))
            firstCounterExample = r.failureSpec
          r
      }

      val curTimestamp: Long = System.currentTimeMillis / 1000
      val inputProgs = config.inputPrograms.addString(new StringBuilder(), "_").toString().replace('.', '_')
      val nodesString = config.nodes.mkString("_")
      val outputDir = s"output/${curTimestamp}_${inputProgs}_${config.eot}_${config.eff}_${config.crashes}_${nodesString}"

      // Write specified analysis output to
      // directory specific to this specification.
      HTMLWriter.write(new File(outputDir), Nil, results, config.generateProvenanceDiagrams, config.disableDotRendering)

      println("-" * 80)
      metricsReporter.report()
      println("-" * 80)

      firstCounterExample match {
        case null => println("No counterexamples found".green)
        case fs: FailureSpec => println(s"Found counterexamples; first is:\n${fs.crashes ++ fs.omissions}".red)
      }
    } getOrElse {
      // Error messages
    }
  }
}

package edu.berkeley.cs.boom.molly.derivations

import org.sat4j.minisat.SolverFactory
import edu.berkeley.cs.boom.molly.FailureSpec
import scala.collection.mutable
import org.sat4j.specs.IVecInt
import org.sat4j.core.VecInt
import com.typesafe.scalalogging.slf4j.Logging
import org.sat4j.tools.ModelIterator
import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions

object SATSolver extends Logging {
  sealed trait SATVariable
  case class CrashFailure(node: String, time: Int) extends SATVariable
  case class NeverCrashed(node: String) extends SATVariable
  case class MessageLoss(from: String, to: String, time: Int) extends SATVariable {
    require (from != to, "Can't lose messages sent to self")
  }

  /**
   * @param failureSpec a description of failures.
   * @param goals a list of goals whose derivations we'll attempt to falsify
   * @param messages a list of messages sent during the program's execution
   * @param seed a set of message failures and crashes that we already know have occurred,
   *             e.g. from previous runs.
   * @return all solutions to the SAT problem, formulated as failure specifications
   */
  def solve(failureSpec: FailureSpec, goals: List[GoalNode], messages: Seq[Message], seed: Set[SATVariable] = Set.empty):
    Set[FailureSpec] = {
    val firstMessageSendTimes =
      messages.groupBy(_.from).mapValues(_.minBy(_.sendTime).sendTime)
    val models = goals.flatMap { goal => solve(failureSpec, goal, firstMessageSendTimes, seed) }.toSet
    logger.info(s"SAT problem has ${models.size} solutions:\n${models.map(_.toString()).mkString("\n")}")
    def isSubset[T](set: Set[T], superset: Set[T]): Boolean = set.forall(e => superset.contains(e))
    val minimalModels = models.filterNot { m => models.exists(m2 => m != m2 && isSubset(m2, m) )}
    logger.info(s"Minimal models are: \n${minimalModels.map(_.toString()).mkString("\n")}")
    minimalModels.flatMap { vars =>
      val crashes = vars.collect { case cf: CrashFailure => cf }
      val omissions = vars.collect { case ml: MessageLoss => ml }
      if (crashes.isEmpty && omissions.isEmpty) {
        None
      } else {
        Some(failureSpec.copy (crashes = crashes, omissions = omissions))
      }
    }
  }

  private def solve(failureSpec: FailureSpec, goal: GoalNode,
                    firstMessageSendTimes: Map[String, Int], seed: Set[SATVariable]):
    Traversable[Set[SATVariable]] = {
    val solver = SolverFactory.newLight()

    val idToSatVariable = mutable.HashMap[Int, SATVariable]()
    val satVariableToId = mutable.HashMap[SATVariable, Int]()

    implicit def satVarToInt(satVar: SATVariable): Int = {
      val id = satVariableToId.getOrElseUpdate(satVar, solver.nextFreeVarId(true))
      idToSatVariable(id) = satVar
      id
    }
    implicit def satVarsToVecInt(clause: Iterable[SATVariable]): IVecInt =
      new VecInt(clause.map(satVarToInt).toArray)

    val distinctGoalDerivations = goal.enumerateDistinctDerivations
    // Crash failures:
    // Only nodes that sent messages (or that are assumed to have crashed as part of the seed)
    // will be candidates for crashing:
    val importantNodes: Set[String] =
      distinctGoalDerivations.flatMap(_.importantClocks).filter(_._3 < failureSpec.eff).map(_._1).toSet ++
      seed.collect { case cf: CrashFailure => cf.node }
    if (importantNodes.isEmpty) {
      logger.debug(s"Goal ${goal.tuple} has no important nodes; skipping SAT solver")
      return Set.empty
    } else {
      logger.debug(s"Goal ${goal.tuple} has important nodes $importantNodes")
    }
    // Add constraints to ensure that each node crashes at a single time, or never crashes:
    for (node <- importantNodes) {
      // There's no point in considering crashes before the first time that a node sends a message,
      // since all such scenarios will be equivalent to crashing when sending the first message:
      val firstSendTime = firstMessageSendTimes.getOrElse(node, 1)
      // Create one variable for every time at which the node could crash
      val crashVars = (firstSendTime to failureSpec.eff - 1).map(t => CrashFailure(node, t))
      // Include any crashes specified in the seed, since they might be excluded by the
      // "no crashes before the first message was sent" constraint:
      val seedCrashes = seed.collect { case c: CrashFailure => c }
      // An extra variable for scenarios where the node didn't crash:
      val neverCrashed = NeverCrashed(node)
      // Each node crashes at a single time, or never crashes:
      solver.addExactly((crashVars ++ seedCrashes).toSet ++ Seq(neverCrashed), 1)
    }
    // If there are at most C crashes, then at least (N - C) nodes never crash:
    solver.addAtLeast(failureSpec.nodes.map(NeverCrashed), failureSpec.nodes.size - failureSpec.maxCrashes)

    // Message losses:
    for (
      derivation <- distinctGoalDerivations;
      importantClocks = derivation.importantClocks;
      // Only allow failures before the EFF:
      failures = importantClocks.filter(_._3 < failureSpec.eff)
      if !failures.isEmpty
    ) {
      logger.debug(s"Goal ${goal.tuple} has possible failures $failures")
      // The message could be missing due to a message loss or due to its
      // sender having crashed at an earlier timestamp:
      val messageLosses = failures.map(MessageLoss.tupled)
      val crashes = messageLosses.flatMap { loss =>
        val firstSendTime = firstMessageSendTimes.getOrElse(loss.from, 1)
        val crashTimes = firstSendTime to loss.time
        crashTimes.map ( t => CrashFailure(loss.from, t))
      }
      solver.addClause(messageLosses ++ crashes)
    }

    // Assume any message losses that have already occurred:
    val assumptions = seed

    val modelIterator = new ModelIterator(solver)
    val models = ArrayBuffer[Set[SATVariable]]()
    while (modelIterator.isSatisfiable(assumptions)) {
      val newModel = modelIterator.model().filter(_ > 0).map(idToSatVariable).toSet
      // Exclude models where no failures or crashes occurred:
      if (!newModel.filter(!_.isInstanceOf[NeverCrashed]).isEmpty) {
        models += newModel
      }
    }
    models
  }
}

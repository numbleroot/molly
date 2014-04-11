package edu.berkeley.cs.boom.molly.ast

import org.kiama.attribution.Attributable
import org.kiama.util.Positioned


trait Node extends Attributable with Positioned

sealed trait Atom extends Node
sealed trait Expression extends Atom
sealed trait Constant extends Expression

case class Expr(left: Constant, op: String, right: Expression) extends Expression {
  def variables: Set[Identifier] = {
    val rightVariables = right match {
      case i: Identifier => Set(i)
      case e: Expr => e.variables
      case _ => Set.empty
    }
    left match {
      case i: Identifier => Set(i) ++ rightVariables
      case _ => Set.empty
    }
  }
}

case class StringLiteral(str: String) extends Constant
case class IntLiteral(int: Int) extends Constant
case class Identifier(name: String) extends Constant

case class Aggregate(aggName: String, aggColumn: String) extends Atom

case class Program(
  rules: List[Rule],
  facts: List[Predicate],
  includes: List[Include],
  tables: Set[Table] = Set()) extends Node

case class Table(name: String, types: List[String])

sealed trait Clause extends Node
case class Include(file: String) extends Clause
case class Rule(head: Predicate, body: List[Either[Predicate, Expr]]) extends Clause {
  def bodyPredicates: List[Predicate] = body.collect { case Left(pred) => pred }
  def bodyQuals: List[Expr] = body.collect { case Right(expr) => expr }
  def variablesWithIndexes: List[(String, (String, Int))] = {
    (List(head) ++ bodyPredicates).flatMap(_.variablesWithIndexes)
  }
  def variables: Set[String] = {
    variablesWithIndexes.map(_._1).toSet
  }
  /** Variables that are bound in the body (i.e. appear more than once) */
  def boundVariables: Set[String] = {
    val allVars =
      bodyPredicates.flatMap(_.variables.toSeq) ++ bodyQuals.flatMap(_.variables).map(_.name).toSeq
    allVars.groupBy(identity).mapValues(_.size).filter(_._2 >= 2).keys.toSet
  }
  // Match the Ruby solver's convention that a predicate's location column always appears
  // as the first column of its first body predicate
  val locationSpecifier = bodyPredicates(0).cols(0)
}
case class Predicate(tableName: String,
                     cols: List[Atom],
                     notin: Boolean,
                     time: Option[Time]) extends Clause {

  def variables: Set[String] = {
    variablesWithIndexes.map(_._1).toSet
  }

  /**
   * Returns a list of (variableName, (tableName, colNumber)) tuples.
   */
  def variablesWithIndexes: List[(String, (String, Int))] = {
    cols.zipWithIndex.collect {
      case (Identifier(i), index) if i != "_" => (i, (tableName, index))
    }
  }

  def variablesInAggregates: Set[String] = {
    cols.collect { case Aggregate(_, aggCol) => aggCol}.toSet
  }

  def variablesInExpressions: Set[String] = {
    cols.collect { case e: Expr => e }.flatMap(_.variables).map(_.name).toSet
  }
}

sealed trait Time extends Node
case class Next() extends Time
case class Async() extends Time
case class Tick(number: Int) extends Time
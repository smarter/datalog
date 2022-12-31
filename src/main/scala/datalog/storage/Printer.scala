package datalog.storage

import datalog.execution.ast.*
import datalog.execution.JoinIndexes

import scala.collection.{immutable, mutable}

// Keep pretty print stuff separate bc long and ugly, mb put it in a macro
class Printer[S <: StorageManager](val s: S) {
  var known = 0

  def factToString(r: s.EDB): String = {
    r.map(s => s.mkString("(", ", ", ")")).mkString("[", ", ", "]")
  }
  def ruleToString(r: s.IDB): String = {
    r.map(s => if (s.isEmpty) "<empty>" else s.head.toString + s.drop(1).mkString(" :- ", ",", ""))
      .mkString("[", "; ", "]")
  }
  def edbToString(db: s.FactDatabase): String = {
    immutable.ListMap(db.toSeq.sortBy(_._1):_*)
      .map((k, v) => (s.ns(k), factToString(v)))
      .mkString("[\n  ", ",\n  ", "]")
  }
  def idbToString(db: s.RuleDatabase): String = {
    immutable.ListMap(db.toSeq.sortBy(_._1):_*)
      .map((k, v) => (s.ns(k), ruleToString(v)))
      .mkString("[\n  ", ",\n  ", "]")
  }
  def naivePlanToString(keys: s.Table[JoinIndexes]): String = {
    "Union( " +
      keys.map(k =>
        if (k.edb)
          "SCAN(" + k.deps.map(n => s.ns(n)).mkString("[", ", ", "]") + ")"
        else
          "Project" + k.projIndexes.map((typ, v) => f"$typ$v").mkString("[", " ", "]") + "( " +
            "JOIN" +
            k.varIndexes.map(v => v.mkString("$", "==$", "")).mkString("[", ",", "]") +
            k.constIndexes.map((k, v) => k + "==" + v).mkString("{", "&&", "}") +
            k.deps.map(n => if(s.idbs.contains(n)) s.ns(n) + s"($n)" else "edbs-" + s.ns(n) + s"($n)").mkString("(", "*", ")") +
            " )"
      ).mkString("", ", ", "") +
      " )"
  }

  def snPlanToString(keys: s.Table[JoinIndexes]): String = {
    "UNION( " +
      keys.map(k =>
        if (k.edb)
          "SCAN(" + k.deps.map(n => s.ns(n)).mkString("[", ", ", "]") + ")"
        else
          var idx = -1
          "UNION(" +
            k.deps.map(d => {
              var found = false
              "PROJECT" + k.projIndexes.map((typ, v) => f"$typ$v").mkString("[", " ", "]") + "( " +
                "JOIN" +
                k.varIndexes.map(v => v.mkString("$", "==$", "")).mkString("[", ",", "]") +
                k.constIndexes.map((k, v) => k + "==" + v).mkString("{", "&&", "}") +
                k.deps.zipWithIndex.map((n, i) => {
                  if (n == d && !found && i > idx)
                    found = true
                    idx = i
                    "delta[known][" + s.ns(n) + s"($n)" + "]"
                  else
                    if(s.idbs.contains(n)) "derived[known][" + s.ns(n) + s"($n)" + "]" else "edbs[" + s.ns(n) + s"($n)" + "]"
                }).mkString("(", "*", ")") +
                " )"
            }).mkString("[ ", ", ", " ]") + " )"
      ).mkString("[ ", ", ", " ]") +
      " )"
  }

  override def toString() = {
    def printHelperRelation(i: Int, db: s.FactDatabase): String = {
      val name = if (i == known) "known" else "new"
      "\n" + name + ": " + edbToString(db)
    }
    "+++++\n" +
      "EDB:" + edbToString(s.edbs) +
      "\nIDB:" + idbToString(s.idbs) +
      "\nDERIVED:" + s.derivedDB.map(printHelperRelation).mkString("[", ", ", "]") +
      "\nDELTA:" + s.deltaDB.map(printHelperRelation).mkString("[", ", ", "]") +
      "\n+++++"
  }

  def printAST(node: ASTNode): String = {
    node match {
      case ProgramNode(allRules) => "PROGRAM\n" + allRules.map((rId, rules) => s"  ${s.ns(rId)} => ${printAST(rules)}").mkString("", "\n", "")
      case AllRulesNode(rules) => s"${rules.map(printAST).mkString("[", "\n\t", "  ]")}"
      case RuleNode(head, body, joinIdx) =>
        s"\n\t${printAST(head)} :- ${body.map(printAST).mkString("(", ", ", ")")}" +
          s" => idx=${joinIdx.getOrElse("").toString}\n"
      case n: AtomNode => n match {
        case NegAtom(expr) => s"!${printAST(expr)}"
        case LogicAtom(relation, terms) => s"${s.ns(relation)}${terms.map(printAST).mkString("(", ", ", ")")}"
      }
      case n: TermNode => n match {
        case VarTerm(value) => s"${value.toString}"
        case ConstTerm(value) => s"$value"
      }
    }
  }
}

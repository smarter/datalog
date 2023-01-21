package datalog.storage

import datalog.execution.ast.*
import datalog.execution.JoinIndexes
import datalog.execution.ir.*

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
      case AllRulesNode(rules, rId, edb) => s"${if (edb) "{EDB}"+factToString(s.edbs(rId))+"{IDB}" else ""}${rules.map(printAST).mkString("[", "\n\t", "  ]")}"
      case RuleNode(head, body, joinIdx) =>
        s"\n\t${printAST(head)} :- ${body.map(printAST).mkString("(", ", ", ")")}" +
          s" => idx=${if (joinIdx.isEmpty) "NONE" else joinIdx.get.toStringWithNS(s.ns)}\n"
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

  def printIR(node: IROp, ident: Int = 0)(using ctx: InterpreterContext): String = {
    val i = "  "*ident
    i + (node match {
      case ProgramOp(body) => s"PROGRAM:\n${printIR(body, ident+1)}"
      case SwapOp() => "SWAP"
      case DoWhileOp(body, cond) => s"DO {\n${printIR(body, ident+1)}}\n${i}WHILE {${printIR(cond, ident)}}\n"
      case SequenceOp(ops) => s"SEQ:${ops.zipWithIndex.map((o, idx) => s"$idx" + printIR(o, ident+1)).mkString("[\n", ",\n", "]")}"
      case CompareOp(db) => s"DIFF:$db"
      case ClearOp() => s"CLEAR"
      case ScanEDBOp(srcRel) => s"READ(edbs[${ctx.storageManager.ns(srcRel)}])"
      case ScanOp(srcRel, db, knowledge) =>
//        val name = if (knowledge == known) "known" else "new"
        s"READ[$db.$knowledge](${ctx.storageManager.ns(srcRel)})"
      case JoinOp(subOps, keys) => s"JOIN${keys.varToString()}${keys.constToString()}${subOps.map(s => printIR(s, ident+1)).mkString("(\n", ",\n", ")")}"
      case ProjectOp(subOp, keys) => s"PROJECT${keys.projToString()}(\n${printIR(subOp, ident+1)})"
      case InsertOp(rId, db, knowledge, subOp, clear) =>
//        val name = if (knowledge == known) "known" else "new"
        s"INSERT INTO $db.$knowledge.${ctx.storageManager.ns(rId)}\n${printIR(subOp, ident+1)}"
      case UnionOp(ops) => s"UNION${ops.map(o => printIR(o, ident+1)).mkString("(\n", ",\n", ")")}"
      case DiffOp(lhs, rhs) => s"DIFF ${printIR(lhs)} - ${printIR(rhs)}"
      case DebugNode(prefix, op) => s"DEBUG: $prefix"
      case DebugPeek(prefix, msg, op) => s"DEBUG PEEK: $prefix into: ${printIR(op)}"
    })
  }
}

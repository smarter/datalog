package datalog.execution

import datalog.dsl.{Atom, Constant, Term, Variable}
import datalog.storage.{SimpleStorageManager, StorageManager}
import datalog.tools.Debug.debug

import scala.collection.mutable

class SemiNaiveExecutionEngine(override val storageManager: StorageManager) extends NaiveExecutionEngine(storageManager) {
  import storageManager.EDB

  def evalRuleSN(rId: Int, newDbId: Int, knownDbId: Int): EDB = {
    storageManager.SPJU(rId, storageManager.getOperatorKeys(rId), knownDbId)
  }

  def evalSN(rId: Int, relations: Seq[Int], newDbId: Int, knownDbId: Int): Unit = {
    debug("evalSN for ", () => storageManager.ns(rId))
    relations.foreach(r => {
      debug("\t=>iterating@", () => storageManager.ns(r))
      val prev = storageManager.getDerivedDB(r, knownDbId)
      debug("\tderived[known][" + storageManager.ns(r) + "] =", () => storageManager.printer.factToString(prev))
      val res = evalRuleSN(r, newDbId, knownDbId)
      debug("\tevalRuleSN=", () => storageManager.printer.factToString(res))
      val diff = storageManager.getDiff(res, prev)
      storageManager.resetDerived(r, newDbId, diff, prev) // set derived[new] to derived[new]+delta[new]
      storageManager.resetDelta(r, newDbId, diff)
      debug("\tdiff, i.e. delta[new][" + storageManager.ns(r) + "] =", () => storageManager.printer.factToString(storageManager.deltaDB(newDbId)(r)))
      debug("\tall, i.e. derived[new][" + storageManager.ns(r) + "] =", () => storageManager.printer.factToString(storageManager.derivedDB(newDbId)(r)))
    })
  }

  override def solve(rId: Int): Set[Seq[Term]] = {
    if (storageManager.edbs.contains(rId)) { // if just an edb predicate then return
      return storageManager.getEDBResult(rId)
    }
    // TODO: if a IDB predicate without vars, then solve all and test contains result?
    //    if (relations.isEmpty)
    //      return Set()
    val relations = precedenceGraph.topSort().filter(r => !storageManager.edbs.contains(r))
    debug("solving relation: " + storageManager.ns(rId) + " order of relations=", relations.toString)
    var knownDbId = storageManager.initEvaluation()
    var newDbId = storageManager.initEvaluation()
    var count = 0

    debug("initial state @ -1", storageManager.printer.toString)
    val startRId = relations.head
    relations.foreach(rel => {
      eval(rel, relations, newDbId, knownDbId) // this fills derived[new]
      storageManager.resetDelta(rel, newDbId, storageManager.getDerivedDB(rel, newDbId)) // copy delta[new] = derived[new]
    })

//    relations.foreach(rel => storageManager.resetDelta(rel, newDbId, storageManager.getDerivedDB(rel, newDbId))) // copy delta[new] = derived[new]

    var setDiff = true
    while(setDiff) {
      val t = knownDbId
      knownDbId = newDbId
      newDbId = t // swap new and known DBs
      storageManager.clearDB(true, newDbId)
      storageManager.printer.known = knownDbId // TODO: get rid of

      debug("initial state @ " + count, storageManager.printer.toString)
      count += 1
      evalSN(rId, relations, newDbId, knownDbId)
      setDiff = storageManager.deltaDB(newDbId).exists((k, v) => v.nonEmpty)
//      debug("state after evalSN " + count, storageManager.printer.toString)
    }
    storageManager.getIDBResult(rId, newDbId)
  }
}
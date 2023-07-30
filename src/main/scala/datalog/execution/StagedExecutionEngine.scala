package datalog.execution

import datalog.dsl.{Atom, Constant, Term, Variable}
import datalog.execution
import datalog.execution.ast.*
import datalog.execution.ast.transform.{ASTTransformerContext, CopyEliminationPass, Transformer}
import datalog.execution.ir.*
import datalog.storage.{DB, EDB, KNOWLEDGE, StorageManager}
import datalog.tools.Debug.debug

import scala.collection.mutable
import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, blocking}
import scala.util.{Failure, Success}
import scala.quoted.*

class StagedExecutionEngine(val storageManager: StorageManager, val defaultJITOptions: JITOptions = JITOptions()) extends ExecutionEngine {
  val precedenceGraph = new PrecedenceGraph(using storageManager.ns)
  val prebuiltOpKeys: mutable.Map[Int, mutable.ArrayBuffer[JoinIndexes]] = mutable.Map[Int, mutable.ArrayBuffer[JoinIndexes]]() // TODO: currently unused, mb remove from EE
  val ast: ProgramNode = ProgramNode()
  private val tCtx = ASTTransformerContext(using precedenceGraph)(using storageManager)
  given JITOptions = defaultJITOptions
  val transforms: Seq[Transformer] = Seq(CopyEliminationPass(using tCtx))
  val compiler: StagedCompiler = StagedCompiler(storageManager)
  compiler.clearDottyThread()
  var stragglers: mutable.WeakHashMap[Int, Future[CompiledFn[?]]] = mutable.WeakHashMap.empty // should be ok since we are only removing by ref and then iterating on values only?

  def createIR(ast: ASTNode)(using InterpreterContext): IROp[Any] = IRTreeGenerator().generateTopLevelProgram(ast, naive=false, stratified=defaultJITOptions.stratified)

  def initRelation(rId: Int, name: String): Unit = {
    storageManager.ns(rId) = name
    storageManager.initRelation(rId, name)
  }

  def get(rId: Int): Set[Seq[Term]] = {
    if (storageManager.knownDbId == -1)
      throw new Exception("Solve() has not yet been called")
    if (precedenceGraph.idbs.contains(rId))
      storageManager.getKnownIDBResult(rId)
    else
      storageManager.getEDBResult(rId)
  }

  def get(name: String): Set[Seq[Term]] = {
    get(storageManager.ns(name))
  }

  def insertIDB(rId: Int, ruleSeq: Seq[Atom]): Unit = {
    precedenceGraph.addNode(ruleSeq)
//    println(s"${storageManager.printer.ruleToString(ruleSeq)}")

    var rule = ruleSeq.toArray
    var k = JoinIndexes(rule, None)
    storageManager.allRulesAllIndexes.getOrElseUpdate(rId, mutable.Map[String, JoinIndexes]())

    if (rule.length <= heuristics.max_length_cache)
      val allK = JoinIndexes.allOrders(rule)
      storageManager.allRulesAllIndexes.getOrElseUpdate(rId, mutable.Map[String, JoinIndexes]()) ++= allK

    if (defaultJITOptions.sortOrder._1 == 1) // sort before inserting, just in case EDBs are defined
      val (sortedBody, newHash) = JoinIndexes.presortSelect(
        a =>
          if (storageManager.edbContains(a.rId))
            (true, storageManager.getEDBResult(a.rId).size)
          else
            (true, Int.MaxValue),
        k,
        storageManager
      )
      rule = rule.head +: sortedBody.map(_._1)
      k = JoinIndexes(rule, Some(k.cxns))
    else if (defaultJITOptions.sortOrder._1 == 5) // mimic "bad luck" program definition, so ingest rules in a bad order and then don't update them.
      val (sortedBody, newHash) = JoinIndexes.presortSelectWorst(
        a =>
          if (storageManager.edbContains(a.rId))
            (true, storageManager.getEDBResult(a.rId).size)
          else
            (true, Int.MaxValue),
        k,
        storageManager
      )
      rule = rule.head +: sortedBody.map(_._1)
      k = JoinIndexes(rule, Some(k.cxns))

    //    println(s"${storageManager.printer.ruleToString(rule)}")

    val allRules = ast.rules.getOrElseUpdate(rId, AllRulesNode(mutable.ArrayBuffer.empty, rId)).asInstanceOf[AllRulesNode]
    allRules.rules.append(
      RuleNode(
        LogicAtom(
          rule.head.rId,
          rule.head.terms.map {
            case x: Variable => VarTerm(x)
            case x: Constant => ConstTerm(x)
          },
          rule.head.negated
        ),
        rule.drop(1).map(b =>
          LogicAtom(b.rId, b.terms.map {
            case x: Variable => VarTerm(x)
            case x: Constant => ConstTerm(x)
          }, b.negated)
        ),
        rule,
        k
      ))
  }

  def insertEDB(rule: Atom): Unit = {
    storageManager.insertEDB(rule)
    val allRules = ast.rules.getOrElseUpdate(rule.rId, AllRulesNode(mutable.ArrayBuffer.empty, rule.rId)).asInstanceOf[AllRulesNode]
    allRules.edb = true
  }

  // NOTE: this method is just for testing to see how much overhead tree processing has, not used irl.
  def generateProgramTree(rId: Int): (IROp[Any], InterpreterContext) = {
    // verify setup
    storageManager.verifyEDBs(precedenceGraph.idbs)
    if (storageManager.edbContains(rId) && !precedenceGraph.idbs.contains(rId)) { // if just an edb predicate then return
      debug("Returning EDB without any IDB rule: ", () => storageManager.ns(rId))
      throw new Exception("NOTE: using generateProgramTree which is only for benchmarking")
    }
    if (!precedenceGraph.idbs.contains(rId)) {
      throw new Exception("Solving for rule without body")
    }
    val transformedAST = transforms.foldLeft(ast: ASTNode)((t, pass) => pass.transform(t)(using storageManager))

    var toSolve = rId
    if (tCtx.aliases.contains(rId))
      toSolve = tCtx.aliases.getOrElse(rId, rId)
      if (storageManager.edbContains(toSolve) && !precedenceGraph.idbs.contains(toSolve)) { // if just an edb predicate then return
        throw new Exception("NOTE: using generateProgramTree which is only for benchmarking")
      }
    given irCtx: InterpreterContext = InterpreterContext(storageManager, precedenceGraph, toSolve)
    val irTree = createIR(transformedAST)
    (irTree, irCtx)
  }

  // Separate these out for easier benchmarking

  def preCompile(irTree: IROp[Any]): CompiledFn[Any] = {
    compiler.getCompiled(irTree)
  }
  def solvePreCompiled(compiled: CompiledFn[Any], ctx: InterpreterContext): Set[Seq[Term]] = {
    compiled(storageManager)
    storageManager.getNewIDBResult(ctx.toSolve)
  }

  def solveBytecodeGenerated(irTree: IROp[Any], ctx: InterpreterContext): Set[Seq[Term]] = {
    debug("", () => "bytecode generated mode")
    val compiled = compiler.getBytecodeGenerated(irTree)
    compiled(storageManager)
    storageManager.getNewIDBResult(ctx.toSolve)
  }

  def solveCompiled(irTree: IROp[Any], ctx: InterpreterContext): Set[Seq[Term]] = {
    debug("", () => "compile-only mode")
    val compiled = compiler.getCompiled(irTree)
    compiled(storageManager)
    storageManager.getNewIDBResult(ctx.toSolve)
  }

  def solveInterpreted[T](irTree: IROp[Any], ctx: InterpreterContext):  Set[Seq[Term]] = {
    debug("", () => "interpret-only mode")
    irTree.run(storageManager)
    storageManager.getNewIDBResult(ctx.toSolve)
  }

  def solveJIT(irTree: IROp[Any], ctx: InterpreterContext)(using jitOptions: JITOptions): Set[Seq[Term]] = {
    debug("", () => s"JIT with options $jitOptions")
    jit(irTree)
    storageManager.getNewIDBResult(ctx.toSolve)
  }

  inline def checkResult[T](value: Future[StorageManager => T], op: IROp[T], default: () => T)(using jitOptions: JITOptions): T = {
    value.value match {
      case Some(Success(run)) =>
        debug(s"Compilation succeeded: ${op.code}", () => "")
        stragglers.remove(op.compiledFn.hashCode()) // TODO: might not work, but jsut end up waiting for completed future
        run(storageManager)
      case Some(Failure(e)) =>
        stragglers.remove(op.compiledFn.hashCode())
        throw Exception(s"Error compiling ${op.code} with: ${e.getCause}")
      case None =>
        if (jitOptions.block)
          debug(s"${op.code} compilation not ready yet, so blocking", () => "")
          val res = Await.result(op.compiledFn, Duration.Inf)(storageManager)
          stragglers.remove(op.compiledFn.hashCode())
          res
        else
          debug(s"${op.code} compilation not ready yet, so defaulting", () => "")
          default()
    }
  }

  inline def startCompileThread[T](op: IROp[T])(using jitOptions: JITOptions): Unit =
    debug(s"starting online compilation for code ${op.code}", () => "")
//    if (jitOptions.block)
//      given staging.Compiler = dotty
//      op.blockingCompiledFn = compiler.getCompiled(op)
//    else
    given scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    op.compiledFn = if (jitOptions.useBytecodeGenerator)
        Future {
        compiler.getBytecodeGenerated(op)
      }
    else
      Future {
         compiler.getCompiled(op)
      }

    stragglers.addOne(op.compiledFn.hashCode(), op.compiledFn)

  def jit[T](irTree: IROp[T])(using jitOptions: JITOptions): T = {
//    debug("", () => s"IN STAGED JIT IR, code=${irTree.code}, gran=${jitOptions.granularity}")
    irTree match {
      case op: ProgramOp =>
        op.run_continuation(storageManager, Seq(sm => jit(op.children.head)))

      case op: DoWhileOp =>
       op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o)))

      case op: SequenceOp if irTree.code == OpCode.EVAL_SN =>
        // TODO: inspect delta known and recompile conditionally only if the deltas have changed enough
        op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o)))

      case op: SequenceOp =>
        op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o)))

      case op: UpdateDiscoveredOp =>
        op.run(storageManager)

      case op: SwapAndClearOp =>
        op.run(storageManager)

      case op: InsertOp =>
        op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o.asInstanceOf[IROp[EDB]])))

      case op: DebugNode =>
        op.run(storageManager)

      case op: UnionSPJOp if jitOptions.granularity == op.code =>
        val shortC = if (jitOptions.sortOrder._1 != 0 && op.children.size < 3 && jitOptions.sortOrder._2 != 0) { // don't recompile query plans with <2 joins
//          println("skip <3")
          Some(op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o))))
        } else if (jitOptions.sortOrder._1 != 0 && jitOptions.sortOrder._1 != 5 && jitOptions.sortOrder._2 == 2) { // sort child relations and see if change is above threshold
          val sortFn =
            jitOptions.sortOrder._1 match
              case 3 =>
                (a: Atom)
                =>
                if (storageManager.edbContains(a.rId))
                  (true, storageManager.getEDBResult(a.rId).size)
                else
                  (true, Int.MaxValue)
              case 1 =>
                (a: Atom)
                => (true, storageManager.getKnownDerivedDB(a.rId).length)
              case 4 =>
                (a: Atom)
                => (storageManager.allRulesAllIndexes.contains(a.rId), storageManager.getKnownDerivedDB(a.rId).length)
              case _ => throw new Exception(s"Unknown sort order ${jitOptions.sortOrder}")
          val (nb, _) = JoinIndexes.presortSelect(sortFn, op.k, storageManager)
          val oldBody = op.k.atoms.drop(1).map(_.hash)
          val newBodyIdx = nb.map(h => oldBody.indexOf(h._1.hash))

          def levenshtein(s1: String, s2: String): Int = { // from wikipedia
            val memorizedCosts = mutable.Map[(Int, Int), Int]()
            def lev: ((Int, Int)) => Int = {
              case (k1, k2) =>
                memorizedCosts.getOrElseUpdate((k1, k2), (k1, k2) match {
                  case (i, 0) => i
                  case (0, j) => j
                  case (i, j) =>
                    Seq(1 + lev((i - 1, j)),
                      1 + lev((i, j - 1)),
                      lev((i - 1, j - 1))
                        + (if (s1(i - 1) != s2(j - 1)) 1 else 0)).min
                })
            }
            lev((s1.length, s2.length))
          }

          val distance = levenshtein(newBodyIdx.mkString("", "", ""), Array.range(0, newBodyIdx.length).mkString("", "", ""))

//          println(s"newBodyIdxs: ${newBodyIdx.mkString("[", ", ", "]")} distance: $distance")
          if (distance < 3)
            Some(op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o))))
          else
            None // TODO: fix this
        } else {
          None
        }
        if (shortC.isDefined)
          shortC.get
        else if (jitOptions.block) { // not AOT therefore not async, so compile + block
          startCompileThread(op)
          checkResult(op.compiledFn, op, () => op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o))))
        } else {
          given scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

          op.compiledFnIndexed = Future {
            //            given staging.Compiler = dedicatedDotty; // dedicatedDotty //staging.Compiler.make(getClass.getClassLoader) // TODO: new dotty per thread, maybe concat
            compiler.getCompiledIndexed(op)
          }
          //        Thread.sleep(1000)
          storageManager.union(op.children.zipWithIndex.map((c, i) =>
            op.compiledFnIndexed.value match {
              case Some(Success(run)) =>
                debug(s"Compilation succeeded: ${op.code}", () => "")
                //              stragglers.remove(op.compiledFn.hashCode()) // TODO: might not work, but jsut end up waiting for completed future
                run(storageManager, i)
              case Some(Failure(e)) =>
                //              stragglers.remove(op.compiledFn.hashCode())
                throw Exception(s"Error compiling ${op.code} with: ${e.getCause}")
              case None =>
                if (jitOptions.block) // TODO: get rid of this since can't block + AOT anymore
                  debug(s"${op.code} compilation not ready yet, so blocking", () => "")
                  val res = Await.result(op.compiledFnIndexed, Duration.Inf)(storageManager, i)
                  //                stragglers.remove(op.compiledFn.hashCode())
                  res
                else
                  debug(s"${op.code} subsection compilation not ready yet, so defaulting", () => "")
                  c.run(storageManager)
            }
          ))
        }

      case op: ProjectJoinFilterOp if jitOptions.granularity == op.code => // check if aot compile is ready
        startCompileThread(op)
        checkResult(op.compiledFn, op, () => op.run_continuation(storageManager, op.childrenSO.map(o => (sm: StorageManager) => jit(o))))

      case op: UnionOp if jitOptions.granularity == op.code =>
        //          println(s"TV: ${jitOptions.thresholdVal}; TN: ${jitOptions.thresholdNum}::${op.children.sliding(2).map {
        //              case Seq(x, y, _*) =>
        //                val l = x.run(storageManager).length
        //                val r = y.run(storageManager).length
        //                if (l != 0  && r != 0) l.toFloat / r else -1
        //              case _ => -1
        //          }.filter(f => f < 0).mkString("(", ", ", ")")}")
        //          val recompile = op.children.sliding(2).map{
        //            case Seq(x, y, _*) =>
        //              val l = x.run(storageManager).length
        //              val r = y.run(storageManager).length
        //              l != 0 && r != 0 && l.toFloat / r > jitOptions.thresholdVal
        //            case _ => false
        //          }.count(b => b) > jitOptions.thresholdNum
        //                  if (recompile)
        //                    startCompileThread(op, newDotty)
        //                checkResult(op.compiledFn, op, () => op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o))))

        //        if (!jitOptions.aot && op.compiledFn != null && storageManager.deltaDB(storageManager.knownDbId).values.map(_.length).exists(_ > jitOptions.thresholdNum)) {
        //          op.compiledFn(storageManager)
        //        } else if (jitOptions.aot && op.compiledFnIndexed != null && storageManager.deltaDB(storageManager.knownDbId).values.map(_.length).exists(_ > jitOptions.thresholdNum)) {
        //          op.compiledFnIndexed(storageManager)
        if (jitOptions.block) {
          // HACK: this should only happen with useBytecodeGenerator enabled
          op.blockingCompiledFn = if (jitOptions.useBytecodeGenerator)
            compiler.getBytecodeGenerated(op)
          else
            compiler.getCompiled(op)

          op.blockingCompiledFn(storageManager)
        } else {
          given scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

          op.compiledFnIndexed = Future {
            compiler.getCompiledIndexed(op)
          }
          //                Thread.sleep(1000)
          storageManager.union(op.children.zipWithIndex.map((c, i) =>
            op.compiledFnIndexed.value match {
              case Some(Success(run)) =>
                debug(s"Compilation succeeded: ${op.code}", () => "")
                //              stragglers.remove(op.compiledFn.hashCode()) // TODO: might not work, but jsut end up waiting for completed future
                run(storageManager, i)
              case Some(Failure(e)) =>
                //              stragglers.remove(op.compiledFn.hashCode())
                throw Exception(s"Error compiling ${op.code} with: ${e.getCause}")
              case None =>
                if (jitOptions.block)
                  debug(s"${op.code} compilation not ready yet, so blocking", () => "")
                  val res = Await.result(op.compiledFnIndexed, Duration.Inf)(storageManager, i)
                  //                stragglers.remove(op.compiledFn.hashCode())
                  res
                else
                  debug(s"${op.code} subsection compilation not ready yet, so defaulting", () => "")
                  c.run(storageManager)
            }
          ))
        }

      case op: ScanOp =>
        op.run(storageManager)

      case op: ScanEDBOp =>
        op.run(storageManager)

      case op: ComplementOp =>
        op.run(storageManager)

      case op: ProjectJoinFilterOp =>
        op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o)))

      case op: UnionOp =>
        op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o)))

      case op: UnionSPJOp =>
        op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o)))

      case op: DiffOp =>
        op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o)))

      case op: DebugPeek =>
        op.run_continuation(storageManager, op.children.map(o => (sm: StorageManager) => jit(o)))

      case _ => throw new Exception(s"Error: JIT-ing unknown operator ${irTree.code}")
    }
  }

  def waitForStragglers(): Unit = {
    debug("", () => s"cleaning up ${stragglers.values.size} stragglers")
    stragglers.values.foreach(t =>
      try {
        Await.result(t, Duration.Inf)
      } catch {
        case e => throw new Exception(s"Exception cleaning up compiler: ${e.getCause}")
      }
    )
    stragglers.clear()
  }

  override def solve(rId: Int): Set[Seq[Term]] = {
    given JITOptions = defaultJITOptions
//    println(s"jit opts==${defaultJITOptions}")
    debug("", () => s"solve $rId with options $defaultJITOptions")
    // verify setup
    storageManager.verifyEDBs(precedenceGraph.idbs)
    if (storageManager.edbContains(rId) && !precedenceGraph.idbs.contains(rId)) { // if just an edb predicate then return
      debug("Returning EDB without any IDB rule: ", () => storageManager.ns(rId))
      return storageManager.getEDBResult(rId)
    }
    if (!precedenceGraph.idbs.contains(rId)) {
      throw new Exception("Solving for rule without body")
    }

    // generate and transform tree
    val transformedAST = transforms.foldLeft(ast: ASTNode)((t, pass) => pass.transform(t)(using storageManager))
    var toSolve = rId
    if (tCtx.aliases.contains(rId)) {
      toSolve = tCtx.aliases.getOrElse(rId, rId)
      debug("aliased:", () => s"${storageManager.ns(rId)} => ${storageManager.ns(toSolve)}")
      if (storageManager.edbContains(toSolve) && !precedenceGraph.idbs.contains(toSolve)) { // if just an edb predicate then return
        debug("Returning EDB as IDB aliased to EDB: ", () => storageManager.ns(toSolve))
        return storageManager.getEDBResult(toSolve)
      }
    }

    given irCtx: InterpreterContext = InterpreterContext(storageManager, precedenceGraph, toSolve)
    debug("AST: ", () => storageManager.printer.printAST(ast))
    debug("TRANSFORMED: ", () => storageManager.printer.printAST(transformedAST))
    debug("PG: ", () => precedenceGraph.toString())

    val irTree = createIR(transformedAST)

    debug("IRTree: ", () => storageManager.printer.printIR(irTree))
    if (defaultJITOptions.granularity == OpCode.OTHER) // i.e. never compile
      solveInterpreted(irTree, irCtx)
//    else if (defaultJITOptions.useBytecodeGenerator) {
//      // TODO: generalize bytecode generation to relax these constraints?
//      assert(
//        defaultJITOptions.granularity == OpCode.PROGRAM && defaultJITOptions.aot && defaultJITOptions.block,
//        s"unsupported configuration: $defaultJITOptions"
//      )
//      solveBytecodeGenerated(irTree, irCtx)
//    }
    else if (defaultJITOptions.granularity == OpCode.PROGRAM && defaultJITOptions.aot && defaultJITOptions.block) // i.e. compile asap and block
      solveCompiled(irTree, irCtx)
    else
      solveJIT(irTree, irCtx)
  }
}
class NaiveStagedExecutionEngine(storageManager: StorageManager, defaultJITOptions: JITOptions = JITOptions()) extends StagedExecutionEngine(storageManager, defaultJITOptions) {
  override def createIR(ast: ASTNode)(using InterpreterContext): IROp[Any] = IRTreeGenerator().generateTopLevelProgram(ast, naive=true, stratified=defaultJITOptions.stratified)
}

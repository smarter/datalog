package datalog.benchmarks.examples

import datalog.dsl.{Constant, Program, __}
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.nio.file.Paths
@Fork(1) // # of jvms that it will use
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
class ranpo_benchmark() extends ExampleBenchmarkGenerator (
  "ranpo",
  Set(),
  Set("CI")
) with ranpo {
 override def toSolve: String = super.toSolve
 @Setup
 def s(): Unit = setup() // can't add annotations to super, so just call

 @TearDown
 def f(): Unit = finish()

 // relational, naive
 @Benchmark def naive_relational(blackhole: Blackhole): Unit = {
  val p = "NaiveRelational"
  if(!programs.contains(p))
    throw new Exception(f"skip test $p for current env")
  blackhole.consume(run(programs(p), result))
 }
 // relational, seminaive
 @Benchmark def seminaive_relational(blackhole: Blackhole): Unit = {
  val p = "SemiNaiveRelational"
  if(!programs.contains(p))
    throw new Exception(f"skip test $p for current env")
  blackhole.consume(run(programs(p), result))
 }

 // collections, naive
 @Benchmark def naive_collections(blackhole: Blackhole): Unit = {
  val p = "NaiveCollections"
  if(!programs.contains(p))
    throw new Exception(f"skip test $p for current env")
  blackhole.consume(run(programs(p), result))
 }
 // relational, seminaive
 @Benchmark def seminaive_collections(blackhole: Blackhole): Unit = {
  val p = "SemiNaiveCollections"
  if(!programs.contains(p))
    throw new Exception(f"skip test $p for current env")
  blackhole.consume(run(programs(p), result))
 }

 // staged, naive
 @Benchmark def naive_staged(blackhole: Blackhole): Unit = {
  val p = "NaiveStagedCollections"
  if(!programs.contains(p))
    throw new Exception(f"skip test $p for current env")
  blackhole.consume(run(programs(p), result))
 }

 // staged, seminaive
 @Benchmark def seminaive_staged(blackhole: Blackhole): Unit = {
  val p = "SemiNaiveStagedCollections"
  if(!programs.contains(p))
    throw new Exception(f"skip test $p for current env")
  blackhole.consume(run(programs(p), result))
 }
}
trait ranpo {

  def pretest(program: Program): Unit = {
    val Check = program.namedRelation[Constant]("Check")

    val In = program.namedRelation[Constant]("In")

    val A = program.relation[Constant]("A")

    val i, a, b, c, d, e, f = program.variable()
    
    A(1,i) :- ( Check(a, b, c, d, e, f), In(a, b, c, d, e, f, i) )
    A(2,i) :- ( Check(a, b, c, __, e, __), In(a, b, c, __, e, __, i) )
    A(3,i) :- ( Check(a, __, c, d, e, f), In(a, __, c, d, e, f, i) )
    A(4,i) :- ( Check(a, b, c, d, __, __), In(a, b, c, d, __, __, i) )
    A(5,i) :- ( Check(a, b, __, d, e, f), In(a, b, __, d, e, f, i) )
    A(6,i) :- ( Check(a, b, __, __, e, f), In(a, b, __, __, e, f, i) )
    A(7, i) :- ( Check(__,__, c, d, e, f), In(__, __, c, d, e, f, i) )
    A(8, i) :- ( Check(__, b, __, d, __, f), In(__, b, __, d, __, f, i) )
    A(9, i) :- ( Check(a, b, __, d, __, f), In(a, b, __, d, __, f, i) )
  }
}